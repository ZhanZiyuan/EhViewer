import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.spotless)
}

/** M1 Intel packaging probe. Third-party sources and all outputs stay in the build tree. */
abstract class BuildIntelNative : DefaultTask() {
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
        check(System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") in setOf("x86_64", "amd64")) { "Use the real macOS Intel JDK for this packaging spike" }
        val repo = repository.get().asFile
        val logs = evidence.get().asFile.also { it.mkdirs() }
        fun run(name: String, directory: File, vararg arguments: String) {
            val log = logs.resolve("$name.log")
            val process = ProcessBuilder(arguments.toList()).directory(directory)
                .redirectErrorStream(true).redirectOutput(log).start()
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor()
                error("Intel native command timed out: $name")
            }
            check(process.exitValue() == 0) { "$name failed: ${log.readText()}" }
        }
        val archive = temporaryDir.resolve("webp.tar.gz")
        val url = URI("https://codeload.github.com/webmproject/libwebp/tar.gz/${webpCommit.get()}").toURL()
        val connection = url.openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        connection.getInputStream().use { input -> archive.outputStream().use { output -> input.copyTo(output) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == webpSha256.get()) { "Pinned WebP source checksum mismatch" }
        logs.resolve("source.txt").writeText("webp_commit=${webpCommit.get()}\nwebp_archive_sha256=$digest\n")
        run("extract", temporaryDir, "cmake", "-E", "tar", "xzf", archive.absolutePath)
        val webpBuild = temporaryDir.resolve("webp-build")
        run(
            "configure", repo, "cmake", "-S", temporaryDir.resolve("libwebp-${webpCommit.get()}").absolutePath,
            "-B", webpBuild.absolutePath, "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_OSX_ARCHITECTURES=x86_64",
            "-DBUILD_SHARED_LIBS=OFF", "-DWEBP_BUILD_ANIM_UTILS=OFF", "-DWEBP_BUILD_CWEBP=OFF",
            "-DWEBP_BUILD_DWEBP=OFF", "-DWEBP_BUILD_VWEBP=OFF", "-DWEBP_BUILD_WEBPINFO=OFF",
            "-DWEBP_BUILD_WEBPMUX=OFF", "-DWEBP_BUILD_EXTRAS=OFF",
        )
        run("webp", repo, "cmake", "--build", webpBuild.absolutePath, "--target", "webp", "webpdemux", "--parallel", "4")
        run(
            "rust", repo, "cargo", "rustc", "--manifest-path", repo.resolve("native/rust/Cargo.toml").absolutePath,
            "--locked", "--lib", "--features", "jvm", "--crate-type", "cdylib", "--target", "x86_64-apple-darwin",
            "--", "-L", "native=${webpBuild.absolutePath}", "-C", "link-arg=-Wl,-install_name,@rpath/libehviewer_rust.dylib",
        )
        val destination = resources.get().asFile.resolve("macos-x64/libehviewer_rust.dylib")
        destination.parentFile.mkdirs()
        repo.resolve("native/rust/target/x86_64-apple-darwin/debug/libehviewer_rust.dylib").copyTo(destination, overwrite = true)
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

val prepareIntelNative = tasks.register<BuildIntelNative>("prepareIntelNative") {
    webpCommit.set("4fa21912338357f89e4fd51cf2368325b59e9bd9") // Same source as the existing Android build.
    webpSha256.set("923f3382a47a2af185c3240c954cf004428b237bd7317413a95146d01eb4b94b")
    repository.set(rootProject.layout.projectDirectory)
    sources.from(
        rootProject.file("rust-toolchain.toml"),
        rootProject.fileTree("native/rust") { exclude("target/**") },
        rootProject.file("native/third-party/CMakeLists.txt"),
    )
    resources.set(layout.buildDirectory.dir("native-resources"))
    evidence.set(layout.buildDirectory.dir("native-evidence"))
}

kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
dependencies {
    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-macos-x64")
    }
    add(skikoSource.name, libs.skiko.macos.x64)
    runtimeOnly(files(selectIntelSkiko.flatMap { it.destination }))
}

val androidBuild = rootProject.layout.projectDirectory.file("app/build.gradle.kts")
val releaseVersion = providers.fileContents(androidBuild).asText.map {
    Regex("val releaseVersion = \"([^\"]+)\"").find(it)?.groupValues?.get(1) ?: error("Missing authoritative version")
}

compose.desktop {
    application {
        mainClass = "com.hippo.ehviewer.desktop.DesktopMainKt"
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "EhViewer"
            packageVersion = releaseVersion.get()
            description = "EhViewer M1 desktop runtime spike"
            vendor = "EhViewer"
            modules("java.desktop", "java.logging", "java.management", "jdk.unsupported")
            appResourcesRootDir.set(prepareIntelNative.flatMap { it.resources })
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
            macOS { bundleID = "moe.tarsin.ehviewer.m1" }
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
