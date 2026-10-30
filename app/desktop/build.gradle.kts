import groovy.json.JsonSlurper
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.composeCompilerReportGenerator) apply false
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.spotless)
}

/** M1 desktop runtime probes. Third-party sources and all outputs stay in the build tree. */
abstract class BuildDesktopNative : DefaultTask() {
    @get:Input abstract val target: Property<String>

    @get:Input abstract val webpCommit: Property<String>

    @get:Input abstract val webpSha256: Property<String>

    @get:Internal abstract val repository: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory abstract val resources: DirectoryProperty

    @get:OutputDirectory abstract val evidence: DirectoryProperty

    @TaskAction
    fun build() {
        val mac = target.get().endsWith("-apple-darwin")
        val arm = target.get().startsWith("aarch64-")
        val hostArm = System.getProperty("os.arch") in setOf("aarch64", "arm64")
        check(
            if (mac) {
                System.getProperty("os.name").startsWith("Mac") && arm == hostArm
            } else {
                target.get().endsWith("-unknown-linux-gnu") && System.getProperty("os.name") == "Linux" && arm == hostArm
            },
        ) { "The native Rust target must match this Desktop JDK and operating system" }
        val repo = repository.get().asFile
        val logs = evidence.get().asFile.also { it.mkdirs() }
        fun run(name: String, directory: File, vararg arguments: String) {
            val log = logs.resolve("$name.log")
            val process = ProcessBuilder(arguments.toList()).directory(directory)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor()
                error("Desktop native command timed out: $name")
            }
            check(process.exitValue() == 0) { "$name failed: ${log.readText()}" }
        }
        val archive = temporaryDir.resolve("webp.tar.gz")
        if (!archive.isFile) {
            val url = URI("https://codeload.github.com/webmproject/libwebp/tar.gz/${webpCommit.get()}").toURL()
            val connection = url.openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 120_000
            }
            connection.getInputStream().use { input -> archive.outputStream().use { output -> input.copyTo(output) } }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == webpSha256.get()) { "Pinned WebP source checksum mismatch" }
        logs.resolve("source.txt").writeText("webp_commit=${webpCommit.get()}\nwebp_archive_sha256=$digest\n")
        run("extract", temporaryDir, "cmake", "-E", "tar", "xzf", archive.absolutePath)
        val webpBuild = temporaryDir.resolve("webp-build")
        run(
            "configure", repo, "cmake", "-S", temporaryDir.resolve("libwebp-${webpCommit.get()}").absolutePath,
            "-B", webpBuild.absolutePath, "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
            *if (mac) arrayOf("-DCMAKE_OSX_ARCHITECTURES=${if (arm) "arm64" else "x86_64"}", "-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0") else emptyArray(),
            "-DBUILD_SHARED_LIBS=OFF", "-DWEBP_BUILD_ANIM_UTILS=OFF", "-DWEBP_BUILD_CWEBP=OFF",
            "-DWEBP_BUILD_DWEBP=OFF", "-DWEBP_BUILD_VWEBP=OFF", "-DWEBP_BUILD_WEBPINFO=OFF",
            "-DWEBP_BUILD_WEBPMUX=OFF", "-DWEBP_BUILD_EXTRAS=OFF",
        )
        run("webp", repo, "cmake", "--build", webpBuild.absolutePath, "--target", "webp", "webpdemux", "--parallel", "4")
        run(
            "rust", repo, "cargo", "rustc", "--manifest-path", repo.resolve("native/rust/Cargo.toml").absolutePath,
            "--locked", "--lib", "--features", "jvm", "--crate-type", "cdylib", "--target", target.get(),
            "--", "-L", "native=${webpBuild.absolutePath}", "-C",
            if (mac) "link-arg=-Wl,-install_name,@rpath/libehviewer_rust.dylib" else "link-arg=-Wl,-soname,libehviewer_rust.so",
            *if (mac) emptyArray() else arrayOf("-C", "link-arg=-Wl,-z,defs"),
        )
        val platform = "${if (mac) "macos" else "linux"}-${if (arm) "arm64" else "x64"}"
        val library = "libehviewer_rust.${if (mac) "dylib" else "so"}"
        val destination = resources.get().asFile.resolve("$platform/$library")
        destination.parentFile.mkdirs()
        repo.resolve("native/rust/target/${target.get()}/debug/$library").copyTo(destination, overwrite = true)
        // Keep notices in the installed app, independently of the DMG's GPL license prompt.
        val notices = resources.get().asFile.resolve("common/licenses")
        check(!notices.exists() || notices.deleteRecursively()) { "Cannot replace generated notices" }
        check(notices.mkdirs()) { "Cannot create generated notices directory" }
        repo.resolve("app/desktop/licenses").copyRecursively(notices, overwrite = true)
        val webpNotices = notices.resolve("libwebp-1.6.0").also { check(it.mkdirs()) }
        for (name in listOf("COPYING", "PATENTS", "AUTHORS")) {
            temporaryDir.resolve("libwebp-${webpCommit.get()}/$name").copyTo(webpNotices.resolve(name))
        }
        webpNotices.resolve("origin.toml").writeText(
            "component = \"libwebp\"\nversion = \"1.6.0\"\nlicense = \"BSD-3-Clause\"\n" +
                "repository = \"https://github.com/webmproject/libwebp\"\n" +
                "commit = \"${webpCommit.get()}\"\narchive_sha256 = \"${webpSha256.get()}\"\n",
        )
        val projectLicense = notices.resolve("EhViewer/LICENSE.txt")
        check(projectLicense.parentFile.mkdirs())
        repo.resolve("LICENSE").copyTo(projectLicense)
    }
}

/** Build the native desktop browser for the actual distribution CPU; retain Cargo's output receipt. */
abstract class BuildNativeBrowser : DefaultTask() {
    @get:Input abstract val target: Property<String>

    @get:Internal abstract val repository: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile abstract val executable: RegularFileProperty

    @get:OutputDirectory abstract val evidence: DirectoryProperty

    @TaskAction
    fun build() {
        val repo = repository.get().asFile
        val logs = evidence.get().asFile.also { it.mkdirs() }
        val cargoOutput = logs.resolve("cargo.jsonl")
        val cargoErrors = logs.resolve("cargo.stderr.log")
        val destination = executable.get().asFile
        val windows = target.get().endsWith("pc-windows-msvc")
        val cargoTarget = temporaryDir.resolve("cargo-target")
        val process = ProcessBuilder(
            "cargo", "build", "--manifest-path", repo.resolve("native/browser/Cargo.toml").absolutePath,
            "--locked", "--release", "--target", target.get(), "--target-dir", cargoTarget.absolutePath,
            "--message-format=json-render-diagnostics",
        ).directory(repo).redirectOutput(cargoOutput).redirectError(cargoErrors).start()
        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor()
            error("Native browser build timed out")
        }
        check(process.exitValue() == 0) { "Native browser build failed: ${cargoErrors.readText()}" }
        val actual = cargoTarget.resolve("${target.get()}/release/ehviewer_browser${if (windows) ".exe" else ""}").canonicalFile
        val artifacts = cargoOutput.readLines().filter { it.startsWith('{') }.map { JsonSlurper().parseText(it) as Map<*, *> }
            .filter { it["reason"] == "compiler-artifact" && (it["target"] as Map<*, *>)["name"] == "ehviewer_browser" }
        check(artifacts.any { (it["executable"] as? String)?.let(::File)?.canonicalFile == actual } && actual.isFile) { "Cargo did not produce the requested native browser" }
        destination.parentFile.mkdirs()
        actual.copyTo(destination, overwrite = true)
        if (!windows) check(destination.setExecutable(true, false))
        logs.resolve("binary.sha256").writeText(MessageDigest.getInstance("SHA-256").digest(destination.readBytes()).joinToString("") { "%02x".format(it) } + "\n")
    }
}

/** Select the published Intel entries; the original checksummed cache stays untouched. */
abstract class SelectIntelSkiko : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: ConfigurableFileCollection

    @get:Input abstract val sourceSha256: Property<String>

    @get:OutputFile abstract val destination: RegularFileProperty

    @TaskAction
    fun select() {
        val original = source.singleFile
        val digest = MessageDigest.getInstance("SHA-256").apply {
            original.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    update(buffer, 0, count)
                }
            }
        }.digest().joinToString("") { "%02x".format(it) }
        check(digest == sourceSha256.get()) { "Unexpected upstream Skiko runtime archive" }
        val output = destination.get().asFile.also { it.parentFile.mkdirs() }
        ZipFile(original).use { zip ->
            val entries = zip.entries().asSequence().toList()
            check(entries.any { it.name == "libskiko-macos-x64.dylib" })
            val rejected = setOf("libskiko-macos-arm64.dylib", "libskiko-macos-arm64.dylib.sha256")
            check(entries.map { it.name }.containsAll(rejected))
            ZipOutputStream(output.outputStream()).use { result ->
                for (entry in entries.filter { it.name !in rejected }) {
                    result.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(result) }
                    result.closeEntry()
                }
            }
        }
    }
}

val skikoSource = configurations.create("intelSkikoSource") { isTransitive = false }
val selectIntelSkiko = tasks.register<SelectIntelSkiko>("selectIntelSkiko") {
    source.from(skikoSource)
    sourceSha256.set("aacb31a5d2a70197abe18f9e81698eef15c83435791d4c487a2ffe139913762e")
    destination.set(layout.buildDirectory.file("native-dependencies/skiko-awt-runtime-macos-x64-${libs.versions.skiko.get()}.jar"))
}

val repositoryRoot = layout.projectDirectory.dir("../..")
val intelMac = System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") in setOf("x86_64", "amd64")
val windowsArm = System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "aarch64"
val prepareDesktopNative = tasks.register<BuildDesktopNative>("prepareDesktopNative") {
    val machine = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "aarch64" else "x86_64"
    target.set("$machine-${if (System.getProperty("os.name") == "Linux") "unknown-linux-gnu" else "apple-darwin"}")
    webpCommit.set("4fa21912338357f89e4fd51cf2368325b59e9bd9") // Same source as the existing Android build.
    webpSha256.set("923f3382a47a2af185c3240c954cf004428b237bd7317413a95146d01eb4b94b")
    repository.set(repositoryRoot)
    sources.from(
        repositoryRoot.file("rust-toolchain.toml"),
        fileTree(repositoryRoot.dir("native/rust")) { exclude("target/**") },
        repositoryRoot.file("native/third-party/CMakeLists.txt"),
        repositoryRoot.file("LICENSE"),
        fileTree(repositoryRoot.dir("app/desktop/licenses")),
    )
    resources.set(layout.buildDirectory.dir("native-resources"))
    evidence.set(layout.buildDirectory.dir("native-evidence"))
}

val browserMachine = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "aarch64" else "x86_64"
val browserOperatingSystem = when {
    System.getProperty("os.name").startsWith("Windows") -> "windows"
    System.getProperty("os.name").startsWith("Mac") -> "macos"
    else -> "linux"
}
val browserResourcePlatform = "$browserOperatingSystem-${if (browserMachine == "aarch64") "arm64" else "x64"}"
val prepareNativeBrowser = tasks.register<BuildNativeBrowser>("prepareNativeBrowser") {
    target.set(
        "$browserMachine-${when (browserOperatingSystem) {
            "windows" -> "pc-windows-msvc"
            "macos" -> "apple-darwin"
            else -> "unknown-linux-gnu"
        }}",
    )
    repository.set(repositoryRoot)
    sources.from(repositoryRoot.file("rust-toolchain.toml"), fileTree(repositoryRoot.dir("native/browser")) { exclude("target/**") })
    executable.set(layout.buildDirectory.file("native-browser-bin/$browserResourcePlatform/ehviewer_browser${if (browserOperatingSystem == "windows") ".exe" else ""}"))
    evidence.set(layout.buildDirectory.dir("native-browser-evidence"))
}
val desktopProductResources = layout.buildDirectory.dir("desktop-product-resources")
val prepareDesktopProductResources = tasks.register<Sync>("prepareDesktopProductResources") {
    into(desktopProductResources)
    if (windowsArm) from(layout.buildDirectory.dir("windows-desktop-resources")) else from(prepareDesktopNative.flatMap { it.resources })
    from(prepareNativeBrowser.flatMap { it.executable }) { into(browserResourcePlatform) }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareDesktopProductResources) }

/** jpackage copies additional resources as data and drops their executable bit. */
class PreserveBrowserExecutable(private val executable: File) : Action<Task>, java.io.Serializable {
    override fun execute(task: Task) {
        check(executable.isFile && executable.setExecutable(true, false) && executable.canExecute()) {
            "The packaged native browser must be executable"
        }
    }
}
if (browserOperatingSystem != "windows") {
    val packagedBrowser = layout.buildDirectory.file(
        "compose/binaries/main/app/EhViewer${if (browserOperatingSystem == "macos") ".app/Contents/app" else "/lib/app"}/resources/ehviewer_browser",
    ).get().asFile
    tasks.matching { it.name == "createDistributable" }.configureEach {
        doLast(PreserveBrowserExecutable(packagedBrowser))
    }
}

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":app:shared"))
    implementation(libs.serialization.json)
    implementation(libs.compose.material3)
    implementation(compose.desktop.currentOs) {
        if (intelMac) exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-macos-x64")
    }
    add(skikoSource.name, libs.skiko.macos.x64)
    if (intelMac) runtimeOnly(files(selectIntelSkiko.flatMap { it.destination }))
}

val androidBuild = repositoryRoot.file("app/build.gradle.kts")
val releaseVersion = providers.fileContents(androidBuild).asText.map {
    Regex("val releaseVersion = \"([^\"]+)\"").find(it)?.groupValues?.get(1) ?: error("Missing authoritative version")
}

// Keep every process/launcher boundary ASCII-safe; decode the real UTF-8 paths inside the caller.
val ffiLibrary = providers.gradleProperty("ffiLibraryBase64").orElse(
    providers.gradleProperty("ffiLibrary").map { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) },
)
val ffiOutput = providers.gradleProperty("ffiOutputBase64").orElse(
    providers.gradleProperty("ffiOutput").map { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) },
)
tasks.register<JavaExec>("rustCapiProbe") {
    group = "verification"
    description = "Call the real Rust C ABI from a native desktop JVM"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.hippo.ehviewer.desktop.RustCapiProbeKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf("--library-base64", ffiLibrary.get(), "--output-base64", ffiOutput.get())
        },
    )
}

abstract class BrowserRegressionArguments : CommandLineArgumentProvider {
    @get:Input abstract val library: Property<String>

    @get:Input abstract val executable: Property<String>

    @get:Input abstract val evidence: Property<String>

    @get:Input abstract val cApi: Property<Boolean>
    override fun asArguments(): Iterable<String> = listOf(library.get(), executable.get(), evidence.get()) + if (cApi.get()) listOf("--c-api") else emptyList()
}
tasks.register<JavaExec>("browserHandoffRegression") {
    group = "verification"
    description = "Transfer actual native browser cookies through the production JNI/C ABI"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.hippo.ehviewer.desktop.BrowserBridgeProbeKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    argumentProviders.add(
        objects.newInstance<BrowserRegressionArguments>().apply {
            library.set(providers.gradleProperty("nativeBrowserLibrary"))
            executable.set(providers.gradleProperty("nativeBrowserExecutable"))
            evidence.set(providers.gradleProperty("nativeBrowserEvidence"))
            cApi.set(providers.gradleProperty("nativeBrowserUseCapi").map(String::toBooleanStrict).orElse(false))
        },
    )
}

val regressionLibrary = providers.gradleProperty("galleryRegressionLibrary")
tasks.register<JavaExec>("galleryProductRegression") {
    group = "verification"
    description = "Decode real Rust documents and platform image pixels using production shared code"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.hippo.ehviewer.desktop.GalleryRegressionMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    inputs.file(regressionLibrary)
    argumentProviders.add(CommandLineArgumentProvider { listOf(regressionLibrary.get()) })
}

compose.desktop {
    application {
        mainClass = "com.hippo.ehviewer.desktop.DesktopMainKt"
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "EhViewer"
            packageVersion = releaseVersion.get()
            description = "EhViewer gallery browser"
            vendor = "EhViewer"
            modules("java.desktop", "java.logging", "java.management", "jdk.unsupported")
            appResourcesRootDir.set(desktopProductResources)
            licenseFile.set(repositoryRoot.file("LICENSE"))
            macOS {
                bundleID = "moe.tarsin.ehviewer.m1"
                minimumSystemVersion = "11.0"
            }
        }
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.ktlint.get().version)
    }
    kotlinGradle { ktlint(libs.ktlint.get().version) }
}
