package verification

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Copy only regular files/directories into a fresh installation; never follow image links. */
fun copyDesktopImage(source: Path, destination: Path) {
    require(source.isDirectory() && !Files.isSymbolicLink(source) && !destination.exists(NOFOLLOW_LINKS)) { "Expected an image and a fresh destination" }
    val original = source.toRealPath()
    val target = destination.toAbsolutePath().normalize()
    require(!target.startsWith(original) && !original.startsWith(target)) { "Installation overlaps its source" }
    val entries = Files.walk(original).use { it.toList() }
    require(entries.all { !Files.isSymbolicLink(it) && (it.isDirectory(NOFOLLOW_LINKS) || it.isRegularFile(NOFOLLOW_LINKS)) }) { "Image contains links or special files" }
    for (entry in entries) {
        val copied = target.resolve(original.relativize(entry))
        if (entry.isDirectory()) copied.createDirectories() else Files.copy(entry, copied)
    }
}

/** A real Windows ARM64 jpackage image and two bundled-JVM/Compose/C ABI launches; no ZIP claim. */
fun desktopWindowsArm64(jvmReport: Path, coreReport: Path, output: Path): Map<String, Any?> {
    require(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "aarch64") { "Use a native Windows ARM64 JVM and runner" }
    require(System.getenv("GITHUB_ACTIONS") != "true" || System.getenv("RUNNER_ARCH") == "ARM64") { "Expected the Windows ARM64 runner" }
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires a clean source tree" }
    val ffi = readJson(jvmReport)
    val caller = ffi["caller"].obj()
    require(ffi["result"] == "PASS" && ffi["source_commit"] == source && ffi["source_dirty"] == dirty && ffi["target"] == "aarch64-pc-windows-msvc") { "A JVM FFI proof from this exact source is required" }
    require(caller["rust_hash_results"] == "2000" && caller["rust_active_results"] == "0" && caller["library_closed"] == "PASS" && caller["path_transport"] == "utf8-base64") { "Incomplete real JVM ownership proof" }
    require(sha256(coreReport) == sha256(jvmReport.parent.resolve("native-core-proof.json"))) { "Native proof differs from the JVM caller's tested input" }
    val core = readJson(coreReport)
    require(core["result"] == "PASS" && core["source_commit"] == source && core["source_dirty"] == dirty && (core["passed"] as Number).toInt() >= MINIMUM_NATIVE_SESSION_PASSES && core["filtered"] == 0) { "Complete native Core/C ABI/session tests are required" }
    val work = root.resolve("app/desktop/build/windows-desktop-runtime")
    val resources = root.resolve("app/desktop/build/windows-desktop-resources")
    require(!work.exists() && !resources.exists() && !output.exists()) { "Use fresh build/resources/evidence directories" }
    work.createDirectories()
    output.createDirectories()
    Files.copy(jvmReport, output.resolve("jvm-ffi-proof.json"))
    Files.copy(coreReport, output.resolve("native-core-proof.json"))
    fun capture(name: String, vararg args: Any, timeout: Duration = Duration.ofMinutes(15), environment: Map<String, String> = emptyMap()): String = try {
        command(*args, timeout = timeout, environment = environment).also { output.resolve(name).writeText(it) }
    } catch (failure: CommandFailure) {
        output.resolve(name).writeText(failure.output)
        throw failure
    }
    fun pe(path: Path) = path.inputStream().use { verifyWindowsArm64Pe(it.readNBytes(1 shl 20)) }
    val jvmWork = root.resolve("app/desktop/build/windows-jvm-ffi")
    require(sha256(jvmWork.resolve("jdk.zip")) == ffi.str("jdk_archive_sha256")) { "Verified vendor JDK archive changed" }
    val jdk = Path.of(caller.str("java_home")).toRealPath()
    require(jdk.startsWith(jvmWork.resolve("jdk").toRealPath()) && sha256(jdk.resolve("bin/java.exe")) == ffi.str("java_sha256")) { "JDK differs from the actual FFI caller" }
    require(caller["java_version"] == "25.0.5.0.2" && jdk.resolve("release").readText().contains("OS_ARCH=\"aarch64\"")) { "Unexpected native vendor JVM" }
    for (name in listOf("bin/java.exe", "bin/jlink.exe", "bin/jpackage.exe", "bin/server/jvm.dll")) pe(jdk.resolve(name))

    val nativeBundle = resources.resolve("windows-arm64").createDirectories()
    val library = Path.of(caller.str("library")).toRealPath()
    require(library.startsWith(jvmWork.toRealPath()) && sha256(library) == ffi.str("rust_capi_sha256")) { "C ABI library differs from its real caller" }
    val expectedLibraries = linkedMapOf("ehviewer_capi.dll" to sha256(library))
    pe(library)
    Files.copy(library, nativeBundle.resolve("ehviewer_capi.dll"))
    for (entry in core["dlls"].array()) {
        val item = entry.obj()
        val name = item.str("file")
        require(Path.of(name).fileName.toString() == name && name.endsWith(".dll")) { "Invalid native dependency name" }
        val dll = library.parent.resolve(name)
        require(sha256(dll) == item.str("sha256")) { "Tested dependency changed: $name" }
        pe(dll)
        require(expectedLibraries.put(name, sha256(dll)) == null) { "Duplicate native dependency" }
        Files.copy(dll, nativeBundle.resolve(name))
    }
    require(expectedLibraries.size == 11) { "Missing tested C ABI/dependency DLLs" }
    val notices = resources.resolve("common/licenses").createDirectories()
    copyDesktopImage(root.resolve("app/desktop/licenses"), notices.resolve("upstream"))
    val ownLicense = notices.resolve("EhViewer/LICENSE.txt")
    ownLicense.parent.createDirectories()
    Files.copy(root.resolve("LICENSE"), ownLicense)
    for (entry in core["installed_copyrights"].array()) {
        val item = entry.obj()
        val relative = Path.of(item.str("path"))
        require(!relative.isAbsolute && relative.normalize() == relative && !relative.startsWith("..")) { "Invalid copyright path" }
        val original = coreReport.parent.resolve("licenses").resolve(relative)
        require(sha256(original) == item.str("sha256")) { "Tested dependency copyright changed" }
        val destination = notices.resolve("vcpkg").resolve(relative)
        destination.parent.createDirectories()
        Files.copy(original, destination)
    }
    val noticeHashes = Files.walk(notices).use { paths ->
        paths.filter { it.isRegularFile() }.toList().associate { notices.relativize(it).toString() to sha256(it) }
    }
    require(noticeHashes.size == 6 + filesBelow(root.resolve("app/desktop/licenses"), "").size) { "Expected every project, upstream and native copyright notice" }
    val pathKey = System.getenv().keys.single { it.equals("PATH", ignoreCase = true) }
    // Do not inherit the Core build's temporary vcpkg DLL path.
    val buildEnvironment = mapOf("JAVA_HOME" to jdk.toString(), pathKey to "${jdk.resolve("bin")};${System.getenv(pathKey)}")
    capture("browser-clippy.txt", "cargo", "clippy", "--manifest-path", root.resolve("native/browser/Cargo.toml"), "--locked", "--all-targets", "--target", WINDOWS_TARGET, "--", "-D", "warnings", environment = buildEnvironment)
    capture(
        "package-app-image.txt", jdk.resolve("bin/java.exe"), "-Dorg.gradle.appname=gradlew", "-jar", root.resolve("gradle/wrapper/gradle-wrapper.jar"),
        "-p", root.resolve("app/desktop"), "createDistributable", "spotlessCheck", "--console=plain", "--no-daemon", "--max-workers=1",
        "-Dorg.gradle.parallel=false", "-Dorg.gradle.jvmargs=-Xmx2g", "-Dorg.gradle.java.home=$jdk", environment = buildEnvironment,
    )
    val originalImage = root.resolve("app/desktop/build/compose/binaries/main/app/EhViewer")
    val image = output.resolve("image/EhViewer")
    image.parent.createDirectories()
    copyDesktopImage(originalImage, image)
    val originalLauncher = image.resolve("EhViewer.exe")
    val originalLauncherSha256 = sha256(originalLauncher)
    // Windows copies can retain DOS read-only. Only this fresh owned image may be edited.
    val dos = checkNotNull(Files.getFileAttributeView(originalLauncher, java.nio.file.attribute.DosFileAttributeView::class.java))
    val readOnlyBefore = dos.readAttributes().isReadOnly
    output.resolve("launcher-file-before.json").writeText(json(mapOf("read_only" to readOnlyBefore, "writable" to Files.isWritable(originalLauncher))))
    dos.setReadOnly(false)
    val readOnlyAfter = dos.readAttributes().isReadOnly
    output.resolve("launcher-file-after.json").writeText(json(mapOf("read_only" to readOnlyAfter, "writable" to Files.isWritable(originalLauncher))))
    require(!readOnlyAfter && Files.isWritable(originalLauncher)) { "The owned launcher copy is not writable for SDK resource editing" }
    val mt = windowsManifestTool()
    val originalManifest = output.resolve("launcher-original.manifest.xml")
    capture("manifest-extract.txt", mt, "-nologo", "-inputresource:$originalLauncher;#1", "-out:$originalManifest")
    val utf8Manifest = output.resolve("launcher-utf8.manifest.xml")
    Files.write(utf8Manifest, windowsUtf8Manifest(Files.readAllBytes(originalManifest)))
    capture("manifest-validate.txt", mt, "-nologo", "-manifest", utf8Manifest, "-validate_manifest")
    capture("manifest-embed.txt", mt, "-nologo", "-manifest", utf8Manifest, "-outputresource:$originalLauncher;#1")
    val embeddedManifest = output.resolve("launcher-embedded.manifest.xml")
    capture("manifest-read-back.txt", mt, "-nologo", "-inputresource:$originalLauncher;#1", "-out:$embeddedManifest")
    verifyWindowsUtf8Manifest(Files.readAllBytes(originalManifest), Files.readAllBytes(embeddedManifest))
    pe(originalLauncher)
    val installed = work.resolve("Installed Runtime 资料/EhViewer")
    installed.parent.createDirectories()
    copyDesktopImage(image, installed)
    val app = installed.resolve("app")
    val runtime = installed.resolve("runtime")
    val launcher = installed.resolve("EhViewer.exe")
    val rust = app.resolve("resources/ehviewer_capi.dll")
    val jvm = runtime.resolve("bin/server/jvm.dll")
    require(runtime.resolve("lib/modules").isRegularFile()) { "Missing bundled runtime modules" }
    val jmod = jdk.resolve("jmods/java.base.jmod")
    val jvmInput = "lib/server/jvm.dll"
    val jvmInputSha256 = jmodEntrySha256(jmod, jvmInput)
    require(sha256(jvm) == jvmInputSha256) { "Bundled JVM differs from the verified vendor JMOD input" }
    for ((name, digest) in expectedLibraries) require(sha256(app.resolve("resources").resolve(name)) == digest) { "Packaged native dependency mismatch: $name" }
    for ((name, digest) in noticeHashes) require(sha256(app.resolve("resources/licenses").resolve(name)) == digest) { "Packaged notice mismatch: $name" }
    require(sha256(runtime.resolve("legal/java.base/LICENSE")) == sha256(jdk.resolve("legal/java.base/LICENSE"))) { "Missing bundled JVM license" }
    val packagedFiles = Files.walk(installed).use { paths -> paths.filter { it.isRegularFile() }.toList() }
    require(packagedFiles.none { it.extension.lowercase() in setOf("so", "dylib", "jnilib") || Regex("\\.so\\.[0-9.]+$").containsMatchIn(it.name) }) { "Foreign native library in the Windows image" }
    val natives = packagedFiles.filter { it.extension.lowercase() in setOf("dll", "exe") }.map { path ->
        pe(path)
        mapOf("path" to installed.relativize(path).toString(), "sha256" to sha256(path))
    }
    for (path in listOf(launcher, rust, jvm)) pe(path)
    val skiko = app.listDirectoryEntries("skiko-windows-arm64.dll").single()
    require(sha256(skiko) == "f72ac08cb6163da84817a102203ce9103015cee42e4dbb80e2b337b3e3bbadbf") { "Unexpected pinned Skiko ARM64 DLL" }
    var jarNatives = 0
    for (jar in app.listDirectoryEntries("*.jar")) {
        ZipFile(jar.toFile()).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.') in setOf("dll", "so", "dylib", "jnilib") }.forEach { entry ->
                require(entry.name.endsWith(".dll")) { "Foreign native in ${jar.name}!/${entry.name}" }
                zip.getInputStream(entry).use { verifyWindowsArm64Pe(it.readNBytes(1 shl 20)) }
                jarNatives++
            }
        }
    }
    val version = Regex("val releaseVersion = \"([^\"]+)\"").find(root.resolve("app/build.gradle.kts").readText())!!.groupValues[1]
    require(app.resolve("EhViewer.cfg").readText().lineSequence().any { it == "java-options=-Djpackage.app-version=$version" }) { "Image version mismatch" }
    val renderApi = "SOFTWARE_COMPAT"
    val launches = windowsPackagedLaunches(installed, output, version)
    val result = mapOf(
        "source_commit" to source, "source_dirty" to dirty, "result" to "PASS", "target" to "aarch64-pc-windows-msvc",
        "version" to version, "os" to System.getProperty("os.name"), "os_version" to System.getProperty("os.version"),
        "jdk_archive_sha256" to ffi.str("jdk_archive_sha256"), "jvm_ffi_report_sha256" to sha256(jvmReport),
        "native_files" to natives, "installed_notices" to noticeHashes, "jar_native_libraries" to jarNatives,
        "rust_sha256" to sha256(rust), "skiko_sha256" to sha256(skiko), "launcher_sha256" to sha256(launcher),
        "runtime_jvm_sha256" to sha256(jvm), "launches" to launches, "requested_render_api" to renderApi,
        "jvm_input" to mapOf("archive" to "java.base.jmod", "archive_sha256" to sha256(jmod), "entry" to jvmInput, "entry_sha256" to jvmInputSha256),
        "launcher_manifest" to mapOf("original_launcher_sha256" to originalLauncherSha256, "embedded_manifest_sha256" to sha256(embeddedManifest), "sdk_tool" to mt.toString(), "sdk_tool_sha256" to sha256(mt), "settings_preserved" to "PASS", "read_only_before" to readOnlyBefore, "read_only_after" to readOnlyAfter),
        "hardware_acceleration" to "NOT_VERIFIED", "windows_zip" to "NOT_VERIFIED", "desktop_p0" to "NOT_VERIFIED",
        "minimum_os" to "NOT_VERIFIED", "complete_dependency_license_audit" to "NOT_VERIFIED",
    )
    output.resolve("windows-arm64-runtime.json").writeText(json(result))
    return result
}
