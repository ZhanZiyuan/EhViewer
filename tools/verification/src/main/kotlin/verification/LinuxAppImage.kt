package verification

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Native execution receipts must bind the exact CPU, clean source, version and actual bytes. */
fun verifyLinuxAppImageProducer(proof: Map<String, Any?>, source: String, version: String, architecture: String, digest: String) {
    val runtime = appImageRuntimes.getValue(architecture)
    val target = "$architecture-unknown-linux-gnu"
    require(proof["result"] == "PASS" && proof["source_commit"] == source && proof["source_dirty"] == false && proof["version"] == version && proof["target"] == target) { "Expected a clean successful same-source Linux package producer" }
    require(proof["file"] == "EhViewer-$version-linux-$architecture.AppImage" && proof["sha256"] == digest) { "AppImage differs from tested producer bytes/name" }
    require(proof["execution_mode"] == "NATIVE_APPIMAGE_EXTRACT_AND_RUN_AND_FUSE" && proof["normal_fuse_mount"] == "PASS" && proof["publish_enabled"] == false) { "Both actual AppImage entry modes must pass" }
    val sourceRecord = proof["package_source"].obj()
    require(sourceRecord["source_commit"] == source && sourceRecord["source_dirty"] == false && sourceRecord["version"] == version && sourceRecord["target"] == target && sourceRecord["format"] == "APPIMAGE_TYPE_2" && sourceRecord["runtime_version"] == "20251108" && sourceRecord["runtime_sha256"] == runtime.sha256) { "Embedded package identity differs" }
}

/** Compare actual filesystem content, modes and internal links across SquashFS transport. */
fun linuxImageInventory(directory: Path): Map<String, Map<String, Any?>> {
    val base = directory.toRealPath()
    return Files.walk(base).use { paths ->
        paths.filter { it != base }.sorted().map { path ->
            require(path.toRealPath().startsWith(base)) { "Image link escapes its owned root" }
            val mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS))
            val kind = when {
                Files.isSymbolicLink(path) -> "symlink"
                Files.isDirectory(path, NOFOLLOW_LINKS) -> "directory"
                Files.isRegularFile(path, NOFOLLOW_LINKS) -> "file"
                else -> error("Special files are not application resources")
            }
            val record = mutableMapOf<String, Any?>("kind" to kind, "mode" to mode)
            if (kind == "symlink") {
                val target = Files.readSymbolicLink(path)
                require(!target.isAbsolute) { "Expected a relative internal application link" }
                record["target"] = target.toString()
            }
            if (path.isRegularFile()) {
                record["bytes"] = Files.size(path)
                record["sha256"] = sha256(path)
            }
            base.relativize(path).joinToString("/") to record.toMap()
        }.toList().toMap()
    }
}

/** Build a genuine type-2 image and execute its own runtime/entry, without an authored shell launcher. */
fun linuxAppImage(imageInput: Path, runtimeReport: Path, outputInput: Path): Map<String, Any?> {
    val image = imageInput.toRealPath()
    val requested = outputInput.toAbsolutePath().normalize()
    val output = requested.parent.toRealPath().resolve(requested.fileName)
    require(!output.startsWith(image) && !image.startsWith(output)) { "Keep fresh package output separate from its image input" }
    val architecture = command("uname", "-m").trim()
    val preset = appImageRuntimes.getValue(architecture)
    val jvmArchitectures = if (architecture == "aarch64") setOf("aarch64") else setOf("x86_64", "amd64")
    require(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in jvmArchitectures) { "AppImage packaging must use its native CPU/JDK" }
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires clean package source" }
    val delivery = releaseContract(root.resolve("app/build.gradle.kts").readText(), source)
    val version = delivery.str("version")
    val imageProof = readJson(runtimeReport)
    val checks = imageProof["checks"].obj()
    require(imageProof["result"] == "PASS" && imageProof["source_commit"] == source && imageProof["source_dirty"] == dirty && imageProof["target"] == "$architecture-unknown-linux-gnu") { "Expected a successful same-source native image receipt" }
    require(imageProof["jvm_version"] == System.getProperty("java.version") && checks["version"] == version && checks["launches"].array().size == 2) { "Expected both retained bundled-runtime launches" }
    require(image.isDirectory() && !output.exists(NOFOLLOW_LINKS)) { "Expected an actual image and fresh package output" }
    output.createDirectories()
    fun capture(name: String, vararg args: Any, timeout: Duration = Duration.ofMinutes(5), environment: Map<String, String> = emptyMap()): String = try {
        command(*args, timeout = timeout, environment = environment).also { output.resolve(name).writeText(it) }
    } catch (failure: CommandFailure) {
        output.resolve(name).writeText(failure.output)
        throw failure
    }
    fun fetch(name: String, url: String, size: Long?, digest: String): Path {
        val partial = output.resolve("$name.partial")
        capture("$name-download.log", "curl", "--fail", "--location", "--connect-timeout", "30", "--max-time", "120", "--retry", "2", "--output", partial, url, timeout = Duration.ofMinutes(7))
        require((size == null || Files.size(partial) == size) && sha256(partial) == digest) { "Downloaded upstream bytes differ: $name" }
        return Files.move(partial, output.resolve(name))
    }
    val runtime = fetch("runtime-$architecture", preset.url, preset.size, preset.sha256)
    Files.setPosixFilePermissions(runtime, PosixFilePermissions.fromString("rwxr-xr-x"))
    runtime.inputStream().use { verifyAppImageHeader(it.readNBytes(64), preset) }
    require(capture("runtime-offset.log", runtime, "--appimage-offset").trim().toLong() == preset.size) { "Unexpected runtime filesystem offset" }
    val runtimeLicense = fetch(
        "AppImage-runtime-LICENSE",
        "https://raw.githubusercontent.com/AppImage/type2-runtime/dd6cebedcbddde9c82f89b011e8e1d40b6e43868/LICENSE",
        null,
        "aa154fc9070614bbe7921f89db11efd1dba7a1f3a41685958110e2230f9c0ca1",
    )
    val candidate = output.resolve("EhViewer-$version-linux-$architecture.AppImage")
    val packageSource = mapOf(
        "source_commit" to source,
        "source_dirty" to dirty,
        "version" to version,
        "android_version_code" to delivery["android_version_code"],
        "target" to "$architecture-unknown-linux-gnu",
        "format" to "APPIMAGE_TYPE_2",
        "runtime_version" to "20251108",
        "runtime_sha256" to preset.sha256,
    )
    val evidence = temporary { scratch ->
        val appDir = scratch.resolve("EhViewer.AppDir")
        capture("copy-image.log", "cp", "-a", image, appDir)
        val icon = appDir.resolve("lib/EhViewer.png")
        require(icon.isRegularFile()) { "Missing actual source-derived platform icon" }
        val decoded = requireNotNull(ImageIO.read(icon.toFile()))
        require(decoded.width == 512 && decoded.height == 512) { "Expected the shared 512px desktop icon" }
        Files.copy(icon, appDir.resolve("EhViewer.png"))
        Files.createSymbolicLink(appDir.resolve(".DirIcon"), Path.of("EhViewer.png"))
        Files.createSymbolicLink(appDir.resolve("AppRun"), Path.of("bin/EhViewer"))
        appDir.resolve("EhViewer.desktop").writeText("[Desktop Entry]\nType=Application\nName=EhViewer\nComment=EhViewer gallery browser\nExec=AppRun\nIcon=EhViewer\nTerminal=false\nCategories=Graphics;Network;\nX-AppImage-Version=$version\n")
        val metadata = appDir.resolve("usr/share/doc/EhViewer").createDirectories()
        metadata.resolve("package-source.json").writeText(json(packageSource))
        Files.copy(runtimeLicense, metadata.resolve("AppImage-runtime-LICENSE"))
        val expected = linuxImageInventory(appDir)
        val nativeFiles = checks["native_files"].array().map(Any?::obj)
        require(nativeFiles.all { expected.getValue(it.str("path"))["sha256"] == it["sha256"] }) { "Copied Native bytes differ from the executed image" }
        val epoch = command("git", "log", "-1", "--format=%ct").trim().toLong()
        val squash = scratch.resolve("filesystem.squashfs")
        capture("mksquashfs.log", "mksquashfs", appDir, squash, "-noappend", "-all-root", "-no-xattrs", "-comp", "zstd", "-processors", "2", "-no-progress", "-mkfs-time", epoch, "-all-time", epoch)
        Files.newOutputStream(candidate).use { destination ->
            runtime.inputStream().use { it.copyTo(destination) }
            squash.inputStream().use { it.copyTo(destination) }
        }
        Files.setPosixFilePermissions(candidate, PosixFilePermissions.fromString("rwxr-xr-x"))
        val format = inspectLinuxAppImage(candidate, architecture)
        require(format["filesystem_created_epoch"] == epoch)
        require(capture("appimage-offset.log", candidate, "--appimage-offset").trim().toLong() == preset.size)
        val extraction = scratch.resolve("extracted").createDirectories()
        capture("appimage-extraction.log", "env", "-C", extraction, candidate.toAbsolutePath(), "--appimage-extract")
        val installed = extraction.resolve("squashfs-root")
        val actual = linuxImageInventory(installed)
        require(actual == expected) { "Actual extracted AppImage files/modes/links differ from package input" }
        require(readJson(installed.resolve("usr/share/doc/EhViewer/package-source.json")) == packageSource)
        require(Files.isExecutable(installed.resolve("AppRun")) && Files.readSymbolicLink(installed.resolve("AppRun")) == Path.of("bin/EhViewer"))
        output.resolve("payload-inventory.json").writeText(json(actual))
        val runtimeTemporary = scratch.resolve("runtime-tmp").createDirectories()
        val browserDigest = nativeFiles.single { it.str("path").endsWith("/resources/ehviewer_browser") }.str("sha256")
        val launches = (1..2).map { attempt ->
            val mode = if (attempt == 1) "EXTRACT_AND_RUN" else "FUSE"
            val modeArguments = if (attempt == 1) arrayOf("--appimage-extract-and-run") else emptyArray()
            val report = output.resolve("launch-$attempt").toAbsolutePath()
            capture("appimage-launch-$attempt.log", candidate.toAbsolutePath(), *modeArguments, "--m1-smoke", report, timeout = Duration.ofMinutes(2), environment = mapOf("TMPDIR" to runtimeTemporary.toString()))
            val proof = Properties().apply { report.resolve("launcher.properties").inputStream().use(::load) }
            val javaHome = Path.of(proof.getProperty("java_home")).toAbsolutePath().normalize()
            require(javaHome.startsWith(runtimeTemporary.toRealPath()) && javaHome.endsWith(Path.of("lib/runtime"))) { "AppImage used an external JVM" }
            require(proof.getProperty("architecture") in jvmArchitectures && proof.getProperty("java_version") == imageProof["jvm_version"] && proof.getProperty("application_version") == version)
            require(proof.getProperty("runtime_jvm_sha256") == checks["runtime_jvm_sha256"]) { "Executed AppImage JVM differs from the original tested runtime" }
            val fileStore = proof.getProperty("runtime_file_store")
            require(!fileStore.isNullOrBlank()) { "Missing actual runtime filesystem type" }
            var mountCleanupMillis: Long? = null
            if (mode == "FUSE") {
                val mount = javaHome.parent.parent
                require(fileStore.startsWith("fuse") && mount.fileName.toString().startsWith(".mount_")) { "Default AppImage entry did not execute from its actual FUSE mount" }
                // The runtime may finish its own asynchronous unmount after the child has exited.
                // Do not race that owner by walking/deleting the still-changing mount directory.
                val started = System.nanoTime()
                val deadline = started + Duration.ofSeconds(10).toNanos()
                while (mount.exists(NOFOLLOW_LINKS) && System.nanoTime() < deadline) Thread.sleep(25)
                require(!mount.exists(NOFOLLOW_LINKS)) { "The owned AppImage FUSE mount did not clean up within ten seconds" }
                mountCleanupMillis = Duration.ofNanos(System.nanoTime() - started).toMillis()
            }
            for ((key, count) in mapOf("gallery" to 5, "account" to 4, "history" to 3, "archive" to 9, "language" to 4)) require(proof.getProperty("${key}_product_checks") == count.toString())
            require(proof.getProperty("rust_hash_results") == "100" && proof.getProperty("window_showing") == "true" && proof.getProperty("compose_frames").toInt() > 0)
            val renderApi = proof.getProperty("render_api")
            require(renderApi in setOf("OPENGL", "SOFTWARE_FAST", "SOFTWARE_COMPAT") && (checks["requested_render_api"] == null || renderApi == checks["requested_render_api"])) { "AppImage rendering differs from the requested backend" }
            val browser = readJson(report.resolve("native-browser.json"))
            verifyDesktopBrowserReceipt(browser, jvmArchitectures, "JNI", checks.str("rust_sha256"), browserDigest)
            mapOf("mode" to mode, "runtime_file_store" to fileStore, "owned_fuse_mount_removed" to if (mode == "FUSE") true else null, "mount_cleanup_millis" to mountCleanupMillis, "render_api" to renderApi, "bundled_runtime" to "PASS", "runtime_jvm_sha256" to proof.getProperty("runtime_jvm_sha256"), "architecture" to proof.getProperty("architecture"), "compose_frames" to proof.getProperty("compose_frames").toInt(), "gallery_product_checks" to 5, "account_product_checks" to 4, "history_product_checks" to 3, "archive_product_checks" to 9, "language_product_checks" to 4, "rust_hash_results" to 100, "native_browser" to browser)
        }
        mapOf("format" to format, "payload_entries" to actual.size, "launches" to launches)
    }
    val result = mapOf(
        "result" to "PASS", "source_commit" to source, "source_dirty" to dirty, "version" to version,
        "target" to "$architecture-unknown-linux-gnu", "file" to candidate.fileName.toString(), "sha256" to sha256(candidate), "bytes" to Files.size(candidate),
        "package_source" to packageSource, "image_report_sha256" to sha256(runtimeReport), "image_receipt" to imageProof,
        "evidence" to evidence, "execution_mode" to "NATIVE_APPIMAGE_EXTRACT_AND_RUN_AND_FUSE", "normal_fuse_mount" to "PASS",
        "minimum_systems" to "NOT_VERIFIED", "complete_license_sbom" to "NOT_VERIFIED", "desktop_p0" to "NOT_VERIFIED", "publish_enabled" to false,
    )
    output.resolve("appimage.json").writeText(json(result))
    return result
}
