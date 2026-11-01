import com.ehviewer.CompileAppleAppIcons
import com.ehviewer.GeneratePlatformIcons
import com.ehviewer.MergeAppleIconInfo
import groovy.json.JsonSlurper
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.spotless)
    id("ehviewer.platform.icons")
}

/** The same immutable libarchive/XZ versions already selected by Android; no maintained C sources. */
abstract class PrepareAppleArchiveSources : DefaultTask() {
    @get:Input abstract val libarchiveCommit: Property<String>

    @get:Input abstract val libarchiveSha256: Property<String>

    @get:Input abstract val xzCommit: Property<String>

    @get:Input abstract val xzSha256: Property<String>

    @get:InputFile abstract val androidDependencyPins: RegularFileProperty

    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction fun prepare() {
        val pins = androidDependencyPins.get().asFile.readText()
        check("GIT_TAG ${libarchiveCommit.get()}" in pins && "GIT_TAG ${xzCommit.get()}" in pins) { "Apple archive versions must match the reviewed Android pins" }
        val output = destination.get().asFile
        for ((name, repository, commit, expected) in listOf(
            listOf("libarchive", "libarchive/libarchive", libarchiveCommit.get(), libarchiveSha256.get()),
            listOf("xz", "tukaani-project/xz", xzCommit.get(), xzSha256.get()),
        )) {
            val archive = temporaryDir.resolve("$name-$commit.tar.gz")
            if (!archive.isFile) {
                val connection = URI("https://codeload.github.com/$repository/tar.gz/$commit").toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }
                connection.getInputStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
            }
            check(MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) } == expected) { "Pinned $name source checksum mismatch" }
            val extract = temporaryDir.resolve("extract-$name").also { it.mkdirs() }
            val log = temporaryDir.resolve("extract-$name.log")
            val process = ProcessBuilder("cmake", "-E", "tar", "xzf", archive.absolutePath).directory(extract).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor()
                error("Pinned $name extraction timed out")
            }
            check(process.exitValue() == 0) { "Pinned $name extraction failed: ${log.readText()}" }
            val target = output.resolve(name)
            check(!target.exists() || target.deleteRecursively())
            extract.resolve("$name-$commit").copyRecursively(target)
            target.resolve("ehviewer-origin.toml").writeText("repository = \"https://github.com/$repository\"\ncommit = \"$commit\"\narchive_sha256 = \"$expected\"\n")
        }
    }
}

/** Build private static archives for one actual Apple SDK/CPU; never link host or private SDK codecs. */
abstract class BuildAppleArchives : DefaultTask() {
    @get:Input abstract val sdkRoot: Property<String>

    @get:Input abstract val simulator: Property<Boolean>

    @get:Input abstract val minimumIos: Property<String>

    @get:InputDirectory abstract val upstreamSources: DirectoryProperty

    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction fun build() {
        val sources = upstreamSources.get().asFile
        val output = destination.get().asFile.also { it.mkdirs() }
        fun run(name: String, vararg arguments: String) {
            val log = output.resolve("$name.log")
            val process = ProcessBuilder(arguments.toList()).directory(temporaryDir).redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor()
                error("Apple archive dependency timed out: $name")
            }
            check(process.exitValue() == 0) { "Apple archive dependency failed: $name\n${log.readText()}" }
        }
        val xzBuild = temporaryDir.resolve("xz-build")
        val prefix = temporaryDir.resolve("xz-prefix")
        val common = arrayOf(
            "-DCMAKE_SYSTEM_NAME=iOS", "-DCMAKE_OSX_ARCHITECTURES=arm64", "-DCMAKE_OSX_SYSROOT=${sdkRoot.get()}",
            "-DCMAKE_OSX_DEPLOYMENT_TARGET=${minimumIos.get()}", "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF",
            "-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY", "-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY", "-DBUILD_TESTING=OFF",
        )
        run(
            "xz-configure", "cmake", "-S", sources.resolve("xz").absolutePath, "-B", xzBuild.absolutePath, *common,
            "-DCMAKE_INSTALL_PREFIX=${prefix.absolutePath}", "-DXZ_NLS=OFF", "-DXZ_DOC=OFF", "-DXZ_TOOL_XZ=OFF", "-DXZ_TOOL_XZDEC=OFF", "-DXZ_TOOL_LZMADEC=OFF", "-DXZ_TOOL_LZMAINFO=OFF",
        )
        run("xz-build", "cmake", "--build", xzBuild.absolutePath, "--target", "liblzma", "--parallel", "4")
        run("xz-install", "cmake", "--install", xzBuild.absolutePath)
        val archiveBuild = temporaryDir.resolve("archive-build")
        run(
            "archive-configure", "cmake", "-S", sources.resolve("libarchive").absolutePath, "-B", archiveBuild.absolutePath, *common,
            "-DENABLE_OPENSSL=OFF", "-DENABLE_NETTLE=OFF", "-DENABLE_MBEDTLS=OFF", "-DENABLE_LIBB2=OFF", "-DENABLE_LZ4=OFF", "-DENABLE_LZO=OFF", "-DENABLE_ZSTD=OFF", "-DENABLE_BZip2=OFF", "-DENABLE_LIBXML2=OFF", "-DENABLE_EXPAT=OFF",
            "-DENABLE_TAR=OFF", "-DENABLE_CPIO=OFF", "-DENABLE_CAT=OFF", "-DENABLE_UNZIP=OFF", "-DENABLE_TEST=OFF", "-DENABLE_INSTALL=OFF", "-DENABLE_ACL=OFF", "-DENABLE_XATTR=OFF",
            "-DLIBLZMA_LIBRARY=${prefix.resolve("lib/liblzma.a").absolutePath}", "-DLIBLZMA_INCLUDE_DIR=${prefix.resolve("include").absolutePath}",
        )
        run("archive-build", "cmake", "--build", archiveBuild.absolutePath, "--target", "archive_static", "--parallel", "4")
        for ((source, name) in listOf(prefix.resolve("lib/liblzma.a") to "liblzma.a", archiveBuild.resolve("libarchive/libarchive.a") to "libarchive.a")) {
            check(source.isFile && source.length() > 0)
            source.copyTo(output.resolve(name), overwrite = true)
            run("$name-architecture", "xcrun", "lipo", "-archs", output.resolve(name).absolutePath)
            check(output.resolve("$name-architecture.log").readText().trim() == "arm64") { "Expected an ARM64 Apple static library" }
        }
        val notices = output.resolve("licenses")
        for ((component, names) in listOf("libarchive" to listOf("COPYING"), "xz" to listOf("COPYING", "COPYING.0BSD"))) {
            for (name in names + "ehviewer-origin.toml") {
                val source = sources.resolve("$component/$name")
                check(source.isFile) { "Missing original $component notice: $name" }
                source.copyTo(notices.resolve("$component/$name").also { it.parentFile.mkdirs() }, overwrite = true)
            }
        }
        output.resolve("target.txt").writeText("architecture=arm64\nsimulator=${simulator.get()}\nsdk=${sdkRoot.get()}\nminimum_build_target=${minimumIos.get()}\n")
    }
}

abstract class BuildAppleRust : DefaultTask() {
    @get:Input abstract val rustTarget: Property<String>

    @get:Input abstract val minimumIos: Property<String>

    @get:Internal abstract val repository: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile abstract val header: RegularFileProperty

    @get:OutputFile abstract val archive: RegularFileProperty

    @get:OutputFile abstract val messages: RegularFileProperty

    @get:OutputFile abstract val diagnostics: RegularFileProperty

    @TaskAction
    fun build() {
        val repo = repository.get().asFile
        val report = messages.get().asFile.also { it.parentFile.mkdirs() }
        val errors = diagnostics.get().asFile.also { it.parentFile.mkdirs() }
        val process = ProcessBuilder(
            "cargo", "build", "--manifest-path", repo.resolve("native/rust/Cargo.toml").path,
            "--locked", "-p", "ehviewer_capi", "--release", "--target", rustTarget.get(),
            "--target-dir", repo.resolve("native/rust/target").path, "--message-format=json-render-diagnostics",
        ).directory(repo).apply {
            environment()["IPHONEOS_DEPLOYMENT_TARGET"] = minimumIos.get()
        }.redirectError(errors).redirectOutput(report).start()
        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor()
            logger.error(errors.readText())
            error("Rust Apple build timed out: ${rustTarget.get()}")
        }
        if (process.exitValue() != 0) {
            logger.error(errors.readText())
            error("Rust Apple build failed: ${rustTarget.get()}; see ${report.name}")
        }
        val events = report.readLines().filter { it.startsWith('{') }.map { JsonSlurper().parseText(it) as Map<*, *> }
            .filter { (it["package_id"] as? String)?.substringAfterLast('#')?.substringBefore('@') == "ehviewer_capi" }
        val produced = events.filter { it["reason"] == "compiler-artifact" }
            .flatMap { it["filenames"] as List<*> }.map { File(it as String).canonicalFile }
        check(archive.get().asFile.canonicalFile in produced && archive.get().asFile.length() > 0) { "Cargo did not produce the expected target archive" }
        val generated = events.filter { it["reason"] == "build-script-executed" }
            .map { File(it["out_dir"] as String, "ehviewer_capi.h") }.single()
        check(generated.isFile && generated.readText().contains("ehv_hash_sha1")) { "Generated C ABI header missing" }
        header.get().asFile.also { it.parentFile.mkdirs() }.writeBytes(generated.readBytes())
    }
}

val rustRoot = rootProject.layout.projectDirectory.dir("native/rust")
val repositoryRoot = rootProject.layout.projectDirectory
val appleArchiveSources = tasks.register<PrepareAppleArchiveSources>("prepareAppleArchiveSources") {
    libarchiveCommit.set("ded82291ab41d5e355831b96b0e1ff49e24d8939")
    libarchiveSha256.set("042f0efe7147063ff9ba10f1a38ed080e949bcbd04bdbf3592b8846dd11b1da2")
    xzCommit.set("4b73f2ec19a99ef465282fbce633e8deb33691b3")
    xzSha256.set("de536250ae287bd6cdac94e72bab680a1e2219df7e07e27c376670f9cd15dc87")
    androidDependencyPins.set(repositoryRoot.file("native/third-party/CMakeLists.txt"))
    destination.set(layout.buildDirectory.dir("apple-archive-sources"))
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    targets.withType<KotlinNativeTarget>().configureEach {
        val rustTriple = when (name) {
            "iosArm64" -> "aarch64-apple-ios"
            "iosSimulatorArm64" -> "aarch64-apple-ios-sim"
            else -> error("Unsupported Rust Apple target: $name")
        }
        val rustHeader = layout.buildDirectory.file("rust/$rustTriple/include/ehviewer_capi.h")
        val rustArchive = rustRoot.file("target/$rustTriple/release/libehviewer_capi.a")
        val thirdParty = tasks.register<BuildAppleArchives>("buildArchive${name.replaceFirstChar(Char::uppercaseChar)}") {
            simulator.set(rustTriple.endsWith("-sim"))
            val sdk = if (rustTriple.endsWith("-sim")) "iphonesimulator" else "iphoneos"
            sdkRoot.set(providers.exec { commandLine("xcrun", "--sdk", sdk, "--show-sdk-path") }.standardOutput.asText.map(String::trim))
            minimumIos.set("15.0")
            upstreamSources.set(appleArchiveSources.flatMap { it.destination })
            destination.set(layout.buildDirectory.dir("rust/$rustTriple/dependencies"))
        }
        val buildRust = tasks.register<BuildAppleRust>("buildRust${name.replaceFirstChar(Char::uppercaseChar)}") {
            rustTarget.set(rustTriple)
            minimumIos.set("15.0")
            repository.set(repositoryRoot)
            sources.from(
                rustRoot.file("Cargo.toml"),
                rustRoot.file("Cargo.lock"),
                repositoryRoot.file("rust-toolchain.toml"),
                fileTree(rustRoot.dir("core")) { exclude("target/**") },
                fileTree(rustRoot.dir("capi")) { exclude("target/**") },
                fileTree(rustRoot.dir("session")) { exclude("target/**") },
            )
            header.set(rustHeader)
            archive.set(rustArchive)
            messages.set(layout.buildDirectory.file("rust/$rustTriple/cargo.jsonl"))
            diagnostics.set(layout.buildDirectory.file("rust/$rustTriple/cargo.log"))
        }
        compilations.getByName("main").cinterops.create("ehviewerCapi") {
            definitionFile.set(file("src/nativeInterop/cinterop/ehviewer.def"))
            includeDirs(rustHeader.get().asFile.parentFile)
            extraOpts("-libraryPath", rustArchive.asFile.parent, "-libraryPath", thirdParty.get().destination.get().asFile.absolutePath)
            tasks.named(interopProcessingTaskName) {
                dependsOn(buildRust, thirdParty)
                inputs.file(buildRust.flatMap { it.archive }).withPathSensitivity(PathSensitivity.NONE)
                inputs.dir(thirdParty.flatMap { it.destination }).withPathSensitivity(PathSensitivity.RELATIVE)
            }
        }
        binaries.executable {
            baseName = "EhViewer"
            entryPoint = "com.hippo.ehviewer.ios.main"
            if (rustTriple == "aarch64-apple-ios-sim") {
                linkerOpts("-sectcreate", "__TEXT", "__entitlements", file("Simulator.entitlements").absolutePath)
                linkTaskProvider.configure { inputs.file(file("Simulator.entitlements")) }
            }
        }
    }
    sourceSets.commonMain.dependencies {
        implementation(projects.core.network)
        implementation(projects.core.data)
        implementation(projects.app.shared)
        implementation(libs.compose.multiplatform.ui)
        implementation(libs.compose.multiplatform.foundation)
        implementation(libs.compose.multiplatform.runtime)
        implementation(libs.compose.material3)
    }
}

val androidVersionSource = providers.fileContents(rootProject.layout.projectDirectory.file("app/build.gradle.kts")).asText
val simulatorBundle = layout.buildDirectory.dir("simulator/EhViewer.app")
val platformIcons = tasks.named<GeneratePlatformIcons>("generatePlatformIcons")
val simulatorIcons = tasks.register<CompileAppleAppIcons>("compileSimulatorIcons") {
    catalog.set(platformIcons.flatMap { it.destination.dir("AppIcons.xcassets") })
    sdk.set("iphonesimulator")
    destination.set(layout.buildDirectory.dir("compiled-icons/simulator"))
}
tasks.register<CompileAppleAppIcons>("compileDeviceIcons") {
    catalog.set(platformIcons.flatMap { it.destination.dir("AppIcons.xcassets") })
    sdk.set("iphoneos")
    destination.set(layout.buildDirectory.dir("compiled-icons/device"))
}
val stageSimulatorApp = tasks.register<Sync>("stageSimulatorApp") {
    dependsOn("linkDebugExecutableIosSimulatorArm64")
    into(simulatorBundle)
    from(tasks.named("iosSimulatorArm64AggregateResources")) { into("compose-resources") }
    from(simulatorIcons.flatMap { it.destination }) { include("Assets.car", "*.png") }
    from(layout.buildDirectory.file("bin/iosSimulatorArm64/debugExecutable/EhViewer.kexe")) {
        rename { "EhViewer" }
        filePermissions { unix("rwxr-xr-x") }
    }
    from("Info.plist") {
        expand(
            "appVersion" to Regex("val releaseVersion = \"([^\"]+)\"").find(androidVersionSource.get())!!.groupValues[1],
            "buildVersion" to Regex("versionCode = (\\d+)").find(androidVersionSource.get())!!.groupValues[1],
        )
    }
    from(rootProject.layout.projectDirectory.dir("app/desktop/licenses/session")) { into("licenses/session") }
    from(layout.buildDirectory.dir("rust/aarch64-apple-ios-sim/dependencies/licenses")) { into("licenses/archive") }
    from(rootProject.layout.projectDirectory.file("LICENSE")) { into("licenses/EhViewer") }
    doLast(
        MergeAppleIconInfo(
            simulatorIcons.get().destination.file("PartialInfo.plist").get().asFile,
            simulatorBundle.get().asFile,
            layout.buildDirectory.dir("icon-evidence/simulator").get().asFile,
        ),
    )
}

tasks.register<Exec>("packageSimulatorApp") {
    dependsOn(stageSimulatorApp)
    commandLine("codesign", "--force", "--sign", "-", simulatorBundle.get().asFile.absolutePath)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.ktlint.get().version)
    }
    kotlinGradle { ktlint(libs.ktlint.get().version) }
}
