package verification

import groovy.json.JsonSlurper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Reject renamed, fat, ARM, truncated or non-executable objects in the Intel distribution. */
fun verifyIntelMachO(header: ByteArray) {
    require(header.size >= 32) { "Truncated Mach-O" }
    val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    require(data.getInt(0) == 0xfeedfacf.toInt() && data.getInt(4) == 0x1000007 && data.getInt(12) in setOf(2, 6)) { "Expected thin x86_64 Mach-O executable/library" }
}

/** Check the retained upstream texts in the installed app, after jpackage has copied resources. */
private fun desktopNativeNotices(contents: Path): List<Map<String, String>> {
    val notices = contents.resolve("app/resources/licenses")
    val expected = linkedMapOf(
        "EhViewer/LICENSE.txt" to sha256(root.resolve("LICENSE")),
        "skiko-0.144.6/LICENSE.txt" to "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4",
        "skiko-0.144.6/NOTICE.txt" to "35dd077c24768b53625101cfcc0ad853ae7e2abc08bd2a111a0267760d1406d8",
        "libwebp-1.6.0/COPYING" to "5aec868f669e384a22372a4e8a1a6cd7d44c64cd451f960ca69cc170d1e13acf",
        "libwebp-1.6.0/PATENTS" to "cc3273e0694ea5896145e0677699b53471b03ea43021ddc50e7923fbb9f5023c",
        "libwebp-1.6.0/AUTHORS" to "f251be89fd442bd9d701360730aea163cc8e2076ff03528f59267dc2c4f8b60a",
    )
    return expected.map { (name, digest) ->
        val file = notices.resolve(name)
        require(file.isRegularFile()) { "Missing installed Native notice: $name" }
        require(sha256(file) == digest) { "Installed Native notice differs from pinned upstream text: $name" }
        mapOf("path" to contents.relativize(file).toString(), "sha256" to digest)
    }
}

/** Bind the consumer to the successful ARM receipt and the exact product bytes. */
fun verifyIntelProducer(proof: Map<String, Any?>, sourceCommit: String, dmgSha256: String) {
    require(proof.str("result") == "PASS" && proof.str("source_commit") == sourceCommit && proof["source_dirty"] == false) { "Expected a successful clean producer at this exact source SHA" }
    require(proof.str("physical_host") == "arm64" && proof.str("rosetta_probe") == "1" && proof.str("target") == "x86_64-apple-darwin") { "The DMG must have been built and verified on ARM/Rosetta" }
    require(proof.str("dmg_sha256") == dmgSha256) { "Consumer DMG differs from the ARM producer's tested bytes" }
}

/** A producer report selects native Intel consumption of the very same ARM-built DMG. */
fun desktopIntelDmg(dmgInput: Path, output: Path, producerReport: Path? = null): Map<String, Any?> {
    val dmg = if (Files.isDirectory(dmgInput)) dmgInput.listDirectoryEntries("*.dmg").single() else dmgInput
    require(System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") in setOf("x86_64", "amd64")) { "Run with the real Intel JDK" }
    val nativeIntel = producerReport != null
    val armHardware = command("sysctl", "-in", "hw.optional.arm64").trim()
    val translated = command("arch", "-x86_64", "/usr/sbin/sysctl", "-in", "sysctl.proc_translated").trim()
    val cpuBrand = command("sysctl", "-n", "machdep.cpu.brand_string").trim()
    if (nativeIntel) {
        // Intel kernels may omit ARM/Rosetta OIDs. -i ignores an unknown OID, not other failures.
        require(armHardware in setOf("", "0") && translated in setOf("", "0") && cpuBrand.contains("Intel", ignoreCase = true)) { "Native Intel verification requires Intel hardware without ARM/Rosetta translation" }
    } else {
        require(armHardware == "1") { "This packaging spike must run on physical Apple ARM64" }
        require(translated == "1") { "Rosetta is unavailable" }
    }
    require(!output.exists()) { "Expected fresh report directory" }
    val sourceCommit = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires a clean source tree" }
    val producer = producerReport?.let { path ->
        readJson(path).also { verifyIntelProducer(it, sourceCommit, sha256(dmg)) }
    }
    FileChannel.open(dmg).use { channel ->
        require(channel.size() > 512) { "Truncated DMG" }
        val signature = ByteBuffer.allocate(4)
        check(channel.read(signature, channel.size() - 512) == 4 && signature.array().decodeToString() == "koly") { "Expected real UDIF DMG" }
    }
    output.createDirectories()
    fun capture(name: String, vararg args: Any, timeout: Duration = Duration.ofMinutes(5), input: String? = null): String = try {
        command(*args, timeout = timeout, input = input).also { output.resolve(name).writeText(it) }
    } catch (failure: CommandFailure) {
        output.resolve(name).writeText(failure.output)
        throw failure
    }
    capture("imageinfo.txt", "hdiutil", "imageinfo", dmg)
    val checks = temporary { scratch ->
        val mount = scratch.resolve("mount").createDirectories()
        val installed = scratch.resolve("installed").createDirectories()
        var attached = false
        try {
            // jpackage embeds this repository's GPLv3 text; accept it for this owned test install.
            capture("attach.txt", "hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", mount, dmg, input = "Y\n")
            attached = true
            val source = mount.listDirectoryEntries("*.app").single()
            val app = installed.resolve(source.name)
            capture("install.txt", "ditto", source, app)
            capture("detach.txt", "hdiutil", "detach", mount)
            attached = false
            val plist = JsonSlurper().parseText(command("plutil", "-convert", "json", "-o", "-", app.resolve("Contents/Info.plist"))).obj()
            val version = Regex("val releaseVersion = \"([^\"]+)\"").find(root.resolve("app/build.gradle.kts").readText())!!.groupValues[1]
            require(plist.str("CFBundleShortVersionString") == version && plist.str("CFBundleVersion") == version)
            val contents = app.resolve("Contents")
            val notices = desktopNativeNotices(contents)
            val launcher = contents.resolve("MacOS").resolve(plist.str("CFBundleExecutable"))
            launcher.inputStream().use { verifyIntelMachO(it.readNBytes(32)) }
            val rust = contents.resolve("app/resources/libehviewer_rust.dylib")
            rust.inputStream().use { verifyIntelMachO(it.readNBytes(32)) }
            val bundledHome = contents.resolve("runtime/Contents/Home")
            val jvm = bundledHome.resolve("lib/server/libjvm.dylib")
            jvm.inputStream().use { verifyIntelMachO(it.readNBytes(32)) }
            require(bundledHome.resolve("lib/modules").isRegularFile()) { "Missing jlink runtime modules" }
            val nativeFiles = mutableListOf<Map<String, Any?>>()
            Files.walk(app).use { paths ->
                paths.filter { it.isRegularFile() }.forEach { path ->
                    require(path.toRealPath().startsWith(app.toRealPath())) { "Bundle symlink escapes the installed app" }
                    val header = path.inputStream().use { it.readNBytes(32) }
                    if (header.size >= 4 && ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == 0xfeedfacf.toInt()) {
                        verifyIntelMachO(header)
                        val identities = capture("native-identity-${nativeFiles.size}.txt", "otool", "-D", path)
                            .lineSequence().drop(1).map(String::trim).filter { it.isNotEmpty() }.toSet()
                        require(identities.all { it.startsWith('@') || it == "./${path.name}" || it == path.name }) { "Native install name contains an external path: ${path.name}" }
                        val dependencies = capture("native-dependencies-${nativeFiles.size}.txt", "otool", "-L", path)
                            .lineSequence().drop(1).map { it.trim().substringBefore(" (compatibility") }.filter { it.isNotEmpty() && it !in identities }.toList()
                        require(dependencies.all { it.startsWith('@') || it.startsWith("/usr/lib/") || it.startsWith("/System/Library/") }) { "Native dependency escapes bundle/system: ${path.name}" }
                        nativeFiles += mapOf("path" to app.relativize(path).toString(), "sha256" to sha256(path), "install_names" to identities.toList(), "dependencies" to dependencies)
                    } else {
                        require(path.extension !in setOf("dylib", "jnilib", "so", "dll")) { "Invalid/foreign native library: ${path.name}" }
                    }
                }
            }
            val skiko = contents.resolve("app").listDirectoryEntries("libskiko*.dylib").single()
            skiko.inputStream().use { verifyIntelMachO(it.readNBytes(32)) }
            var jarNatives = 0
            contents.resolve("app").listDirectoryEntries("*.jar").forEach { jar ->
                ZipFile(jar.toFile()).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.') in setOf("dylib", "jnilib", "so", "dll") }.forEach { entry ->
                        require(entry.name.substringAfterLast('.') in setOf("dylib", "jnilib")) { "Foreign native in ${jar.name}!/${entry.name}" }
                        try {
                            zip.getInputStream(entry).use { verifyIntelMachO(it.readNBytes(32)) }
                        } catch (failure: IllegalArgumentException) {
                            throw IllegalArgumentException("Wrong architecture in ${jar.name}!/${entry.name}", failure)
                        }
                        jarNatives++
                    }
                }
            }
            val launchEvidence = (1..2).map { attempt ->
                val proofDirectory = output.resolve("launch-$attempt")
                capture("launch-$attempt.log", launcher, "--m1-smoke", proofDirectory, timeout = Duration.ofMinutes(2))
                val proof = Properties().apply { proofDirectory.resolve("launcher.properties").inputStream().use(::load) }
                require(proof.getProperty("architecture") in setOf("x86_64", "amd64"))
                require(Path.of(proof.getProperty("java_home")).toRealPath() == bundledHome.toRealPath()) { "Launcher used an external JVM" }
                require(proof.getProperty("java_version") == System.getProperty("java.version")) { "Unexpected bundled JVM version" }
                require(proof.getProperty("rust_hash_results").toInt() == 100 && proof.getProperty("compose_frames").toInt() > 0 && proof.getProperty("window_showing") == "true")
                mapOf("rust_hash_results" to proof.getProperty("rust_hash_results").toInt(), "compose_frames" to proof.getProperty("compose_frames").toInt(), "bundled_runtime" to "PASS")
            }
            val dependencyLog = capture("rust-dependencies.txt", "otool", "-L", rust)
            require(!dependencyLog.contains("/opt/homebrew") && !dependencyLog.contains("/Users/runner") && !dependencyLog.contains(root.toString())) { "Native library depends on a build-machine path" }
            val regression = desktopArchiveRegression(output, Path.of(System.getProperty("java.home"), "bin/java"), rust)
            mapOf(
                "version" to version, "native_files" to nativeFiles, "skiko_sha256" to sha256(skiko), "jar_native_libraries" to jarNatives,
                "installed_notices" to notices, "complete_dependency_license_audit" to "NOT_VERIFIED",
                "declared_minimum_os" to plist.str("LSMinimumSystemVersion"), "minimum_os_validation" to "NOT_VERIFIED",
                "launches" to launchEvidence, "jni_regression_external_test_jdk" to regression,
                "runtime_jvm_sha256" to sha256(jvm), "rust_sha256" to sha256(rust), "launcher_sha256" to sha256(launcher),
            )
        } finally {
            if (attached) capture("cleanup-detach.txt", "hdiutil", "detach", mount)
        }
    }
    val result = mapOf(
        "source_commit" to sourceCommit, "source_dirty" to dirty,
        "result" to "PASS", "physical_host" to if (nativeIntel) "x86_64" else "arm64", "target" to "x86_64-apple-darwin", "rosetta_probe" to translated,
        "cpu_brand" to cpuBrand, "arm_hardware_probe" to armHardware, "execution_mode" to if (nativeIntel) "native-intel" else "rosetta",
        "jdk" to System.getProperty("java.version"), "dmg_sha256" to sha256(dmg), "checks" to checks,
        "macos" to command("sw_vers", "-productVersion").trim(), "xcode" to command("xcodebuild", "-version").trim(),
        "rust" to (producer?.str("rust") ?: command("rustc", "--version").trim()), "cmake" to (producer?.str("cmake") ?: command("cmake", "--version").lineSequence().first()),
        "build_tools_origin" to if (nativeIntel) "validated-arm-producer-report" else "local-arm-build-host",
        "producer_report_sha256" to producerReport?.let(::sha256), "artifact_build_host" to "arm64",
        "physical_intel_hardware" to if (nativeIntel) "PASS" else "NOT_VERIFIED", "signing_notarization" to "NOT_VERIFIED", "desktop_p0" to "NOT_VERIFIED",
    )
    output.resolve("intel-dmg.json").writeText(json(result))
    return result
}
