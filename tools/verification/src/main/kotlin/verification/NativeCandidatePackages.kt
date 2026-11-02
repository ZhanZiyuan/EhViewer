package verification

import groovy.json.JsonSlurper
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Instant
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Shared byte/CPU/provenance checks for the seven actual non-Android installation packages. */
fun verifyNativeCandidatePackages(
    source: String,
    version: String,
    code: Int,
    path: (String, String) -> Path,
    retain: (String, Path, String) -> Unit,
): Map<String, Any?> {
    fun receipt(role: String, name: String, pass: Boolean = true): Map<String, Any?> = readJson(path(role, name)).also {
        require(it["source_commit"] == source && it["source_dirty"] == false && (!pass || it["result"] == "PASS")) { "Receipt is not clean successful same-source evidence: $role" }
    }
    val producerFile = path("intel-producer", "reports/desktop-intel/intel-dmg.json")
    val producer = receipt("intel-producer", "reports/desktop-intel/intel-dmg.json")
    val consumer = receipt("intel-consumer", "intel-dmg.json")
    val dmg = path("intel-producer", "app/desktop/build/compose/binaries/main/dmg/EhViewer-$version.dmg")
    val intelDigest = sha256(dmg)
    verifyIntelProducer(producer, source, intelDigest)
    require(consumer["dmg_sha256"] == intelDigest && consumer["producer_report_sha256"] == sha256(producerFile) && consumer["physical_host"] == "x86_64" && consumer["target"] == "x86_64-apple-darwin" && consumer["execution_mode"] == "native-intel" && consumer["physical_intel_hardware"] == "PASS" && consumer["rosetta_probe"] in setOf("", "0") && consumer["arm_hardware_probe"] in setOf("", "0") && consumer.str("cpu_brand").contains("Intel")) { "Native Intel did not consume the actual ARM-produced DMG" }
    fun intelInventory(value: Map<String, Any?>): Map<String, Any?> = value["checks"].obj().filterKeys { it !in setOf("launches", "native_files", "installed_notices") } + listOf("native_files", "installed_notices").associateWith { key -> value["checks"].obj()[key].array().map(Any?::obj).associateBy { it.str("path") } }
    require(intelInventory(producer) == intelInventory(consumer)) { "Actual Intel producer/consumer native inventories differ" }
    for (proof in listOf(producer, consumer)) {
        val checks = proof["checks"].obj()
        require(proof["jdk"] == "25.0.5" && checks["native_files"].array().size == 33 && checks["installed_notices"].array().size == expectedDesktopNativeNoticeCount()) { "Incomplete reviewed Intel runtime inventory" }
        val nativeFiles = checks["native_files"].array().map(Any?::obj)
        val browserDigest = nativeFiles.single { it.str("path").endsWith("/resources/ehviewer_browser") }.str("sha256")
        for (launch in checks["launches"].array()) verifyDesktopBrowserReceipt(launch.obj()["native_browser"].obj(), setOf("x86_64", "amd64"), "JNI", checks.str("rust_sha256"), browserDigest)
        require(
            checks["version"] == version && checks["launches"].array().size == 2 && checks["launches"].array().all {
                val launch = it.obj()
                launch["gallery_product_checks"] == 5 && launch["account_product_checks"] == 4 && launch["history_product_checks"] == 3 && launch["archive_product_checks"] == 9 && launch["language_product_checks"] == 4 && launch["rust_hash_results"] == 100 && launch["bundled_runtime"] == "PASS" && (launch["compose_frames"] as Number).toInt() > 0
            },
        ) { "Incomplete actual Intel package launches" }
    }
    FileChannel.open(dmg).use { channel ->
        val signature = ByteBuffer.allocate(4)
        require(channel.size() > 512 && channel.read(signature, channel.size() - 512) == 4 && signature.array().decodeToString() == "koly") { "Expected actual UDIF DMG bytes" }
    }
    retain("macos-x64", dmg, intelDigest)

    val armDmg = path("macos-host", "arm64-dmg/EhViewer-$version-macos-arm64.dmg")
    val armProducer = receipt("macos-host", "arm64-dmg/arm64-dmg.json")
    val armDigest = sha256(armDmg)
    verifyArm64Producer(armProducer, source, armDigest)
    val armChecks = armProducer["checks"].obj()
    require(armProducer["jdk"] == "25.0.5" && armChecks["version"] == version && armChecks["native_files"].array().size == 33 && armChecks["jar_native_libraries"] == 1 && armChecks["installed_notices"].array().size == expectedDesktopNativeNoticeCount()) { "Incomplete reviewed ARM64 installed runtime inventory" }
    val armNativeFiles = armChecks["native_files"].array().map(Any?::obj)
    require(armNativeFiles.map { it.str("path") }.toSet().size == armNativeFiles.size)
    val armBrowserDigest = armNativeFiles.single { it.str("path").endsWith("/resources/ehviewer_browser") }.str("sha256")
    val armLaunches = armChecks["launches"].array().map(Any?::obj)
    require(armLaunches.size == 2) { "Expected two actual installed ARM64 launcher executions" }
    for (launch in armLaunches) {
        verifyDesktopBrowserReceipt(launch["native_browser"].obj(), setOf("arm64", "aarch64"), "JNI", armChecks.str("rust_sha256"), armBrowserDigest)
        require(launch["gallery_product_checks"] == 5 && launch["account_product_checks"] == 4 && launch["history_product_checks"] == 3 && launch["archive_product_checks"] == 9 && launch["language_product_checks"] == 4 && launch["rust_hash_results"] == 100 && launch["bundled_runtime"] == "PASS" && (launch["compose_frames"] as Number).toInt() > 0) { "Incomplete actual ARM64 package business/Compose regressions" }
    }
    val armRegression = armChecks["jni_regression_external_test_jdk"].obj()
    require((armRegression["ownership_checks"] as Number).toInt() >= 4170 && armRegression["archive_cases"] == 13 && armRegression["fixture_baseline"] == "8fdfb6c5550fab506759a5587b1c8ee4050f507e") { "Incomplete actual ARM64 installed JNI/archive regression" }
    FileChannel.open(armDmg).use { channel ->
        val signature = ByteBuffer.allocate(4)
        require(channel.size() > 512 && channel.read(signature, channel.size() - 512) == 4 && signature.array().decodeToString() == "koly") { "Expected actual ARM64 UDIF DMG bytes" }
    }
    retain("macos-arm64", armDmg, armDigest)

    for ((id, role, prefix, imageReport) in listOf(
        listOf("linux-x64", "linux-host", "appimage", "linux-image/linux-x64.json"),
        listOf("linux-arm64", "linux-runtime", "reports/desktop-linux-arm64/appimage", "reports/desktop-linux-arm64/linux-arm64.json"),
    )) {
        val architecture = if (id == "linux-arm64") "aarch64" else "x86_64"
        val producer = receipt(role, "$prefix/appimage.json")
        val packageFile = path(role, "$prefix/EhViewer-$version-linux-$architecture.AppImage")
        val digest = sha256(packageFile)
        verifyLinuxAppImageProducer(producer, source, version, architecture, digest)
        require((producer["bytes"] as Number).toLong() == Files.size(packageFile))
        require(producer["package_source"].obj()["android_version_code"] == code)
        val originalImage = receipt(role, imageReport)
        require(producer["image_receipt"] == originalImage && producer["image_report_sha256"] == sha256(path(role, imageReport))) { "AppImage did not retain its original same-source native image checks" }
        val checks = originalImage["checks"].obj()
        require(originalImage["target"] == "$architecture-unknown-linux-gnu" && originalImage["jvm_version"] == "25.0.5" && checks["version"] == version && checks["installed_notices"].array().size == expectedDesktopNativeNoticeCount())
        val regression = checks["jni_regression_external_test_jdk"].obj()
        require((regression["ownership_checks"] as Number).toInt() >= 4170 && regression["archive_cases"] == 13) { "Missing retained Linux JNI/archive regression" }
        val nativeFiles = checks["native_files"].array().map(Any?::obj)
        val browserDigest = nativeFiles.single { it.str("path").endsWith("/resources/ehviewer_browser") }.str("sha256")
        val evidence = producer["evidence"].obj()
        val portableFormat = JsonSlurper().parseText(json(inspectLinuxAppImage(packageFile, architecture))).obj()
        require(portableFormat == evidence["format"] && (evidence["payload_entries"] as Number).toInt() > nativeFiles.size) { "Actual AppImage format differs from the producer's extracted payload" }
        val launches = evidence["launches"].array().map(Any?::obj)
        val jvmArchitectures = if (id == "linux-arm64") setOf("aarch64") else setOf("x86_64", "amd64")
        require(launches.size == 2 && launches.map { it["mode"] }.toSet() == setOf("EXTRACT_AND_RUN", "FUSE")) { "Missing actual AppImage entry mode" }
        for (launch in launches) {
            require(launch["architecture"] in jvmArchitectures && launch["runtime_jvm_sha256"] == checks["runtime_jvm_sha256"] && launch["bundled_runtime"] == "PASS")
            require(launch["render_api"] == "SOFTWARE_COMPAT")
            require(launch["gallery_product_checks"] == 5 && launch["account_product_checks"] == 4 && launch["history_product_checks"] == 3 && launch["archive_product_checks"] == 9 && launch["language_product_checks"] == 4 && launch["rust_hash_results"] == 100 && (launch["compose_frames"] as Number).toInt() > 0)
            if (launch["mode"] == "FUSE") require(launch.str("runtime_file_store").startsWith("fuse") && launch["owned_fuse_mount_removed"] == true && (launch["mount_cleanup_millis"] as Number).toLong() in 0L..10000L) { "Default AppImage entry did not use and release FUSE" }
            verifyDesktopBrowserReceipt(launch["native_browser"].obj(), jvmArchitectures, "JNI", checks.str("rust_sha256"), browserDigest)
        }
        retain(id, packageFile, digest)
    }

    for (platform in WindowsPlatform.entries) {
        val rolePrefix = if (platform == WindowsPlatform.ARM64) "windows" else "windows-x64"
        val packagedFile = path("$rolePrefix-package", "windows-${platform.assetArchitecture}-package.json")
        val packaged = receipt("$rolePrefix-package", "windows-${platform.assetArchitecture}-package.json", false)
        val runtimeFile = path("$rolePrefix-runtime", "windows-${platform.assetArchitecture}-runtime.json")
        val runtime = receipt("$rolePrefix-runtime", "windows-${platform.assetArchitecture}-runtime.json")
        val launch = receipt("$rolePrefix-launch", "windows-${platform.assetArchitecture}-zip-launch.json", false)
        val core = receipt("$rolePrefix-core", "windows-native-core.json")
        val jvmFile = path("$rolePrefix-jvm", "windows-jvm-ffi.json")
        val jvm = receipt("$rolePrefix-jvm", "windows-jvm-ffi.json")
        require(packaged["verification_scope"] == platform.stageScope && launch["verification_scope"] == platform.launchScope && launch["target"] == platform.target && core["target"] == platform.target) { "Windows proof CPU/scope differs" }
        val windowsData = jvm["data_tests"].obj()
        verifyDataTests(windowsData)
        val windowsDataSources = listOf("commonTest", "nonAndroidTest", "desktopTest").sumOf { filesBelow(root.resolve("core/data/src/$it"), ".kt").size }
        require(sharedHostTestEvidence(filesBelow(path("$rolePrefix-jvm", "data-tests"), ".xml").map(Path::readBytes), windowsDataSources) == windowsData) { "Actual Windows native database XML differs from its producer receipt" }
        require(jvm["target"] == platform.target && jvm["caller"].obj()["rust_hash_results"] == "2000" && jvm["caller"].obj()["rust_active_results"] == "0" && jvm["caller"].obj()["library_closed"] == "PASS") { "Incomplete original Windows JVM ownership evidence" }
        require(jvm["caller"].obj()["path_transport"] == "utf8-base64" && listOf("double_free", "stale_handle", "undersized_copy").all { jvm["caller"].obj()[it] == "PASS" }) { "Original standalone Windows ownership errors differ" }
        require(sha256(path("$rolePrefix-jvm", "native-libraries/ehviewer_capi.dll")) == jvm["rust_capi_sha256"]) { "Standalone Windows C API bytes changed" }
        require((core["passed"] as Number).toInt() >= MINIMUM_NATIVE_SESSION_PASSES && core["filtered"] == 0 && sha256(path("$rolePrefix-runtime", "native-core-proof.json")) == sha256(path("$rolePrefix-core", "windows-native-core.json")) && sha256(path("$rolePrefix-runtime", "jvm-ffi-proof.json")) == sha256(jvmFile)) { "Original Windows Core/JVM proof chain changed" }
        val archive = path("$rolePrefix-package", releaseTargets.single { it.id == platform.releaseTarget }.fileName(version))
        require(packaged["version"] == version && packaged["android_version_code"] == code && packaged["target"] == platform.target && packaged["packager_source_dirty"] == false && packaged["runtime_proof_sha256"] == sha256(runtimeFile)) { "Windows ZIP producer provenance differs" }
        require(runtime["version"] == version && runtime["target"] == platform.target) { "Windows runtime identity differs" }
        val manifest = packaged.filterKeys { it !in setOf("archive", "archive_evidence", "native_files_verified") }
        val archiveProof = verifyStageZip(archive, manifest)
        require(JsonSlurper().parseText(json(archiveProof)).obj() == packaged["archive_evidence"] && launch["archive_sha256"] == archiveProof["sha256"] && launch["package_report_sha256"] == sha256(packagedFile) && launch["runtime_report_sha256"] == sha256(runtimeFile) && launch["zip_extracted_launch"] == "PASS" && launch["version"] == version) { "Windows ZIP/native startup hashes differ" }
        require(jvm["jdk_archive_sha256"] == platform.jdkSha256 && runtime["jdk_archive_sha256"] == platform.jdkSha256 && runtime["skiko_sha256"] == platform.skikoSha256) { "Windows vendor runtime differs from its reviewed CPU-specific pin" }
        val native = runtime["native_files"].array().map(Any?::obj).associate { stageZipName(it.str("path").replace('\\', '/')) to it.str("sha256") }
        require(native["app/resources/ehviewer_capi.dll"] == jvm["rust_capi_sha256"]) { "Packaged Windows Rust differs from the actual JVM caller" }
        require(native.size == runtime["native_files"].array().size && setOf("EhViewer.exe", "runtime/bin/server/jvm.dll", "app/skiko-windows-${platform.nativeArchitecture}.dll", "app/resources/ehviewer_capi.dll", "app/resources/ehviewer_browser.exe").all { it in native }) { "Missing original Windows entry/runtime/Skiko/Rust/browser" }
        val recordedFiles = manifest["files"].array().map(Any?::obj).associateBy { it.str("path") }
        native.forEach { (name, expected) -> require(recordedFiles.getValue("EhViewer/$name")["sha256"] == expected) { "Windows native bytes differ from the measured runtime" } }
        runtime["installed_notices"].obj().forEach { (name, expected) -> require(recordedFiles.getValue("EhViewer/app/resources/licenses/" + stageZipName(name.replace('\\', '/')))["sha256"] == expected) { "Windows notice differs from the measured runtime" } }
        ZipFile(archive.toFile()).use { zip ->
            val actualNative = zip.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("dll", "exe") }.map { it.name.removePrefix("EhViewer/") }.toSet()
            require(actualNative == native.keys && native.size == packaged["native_files_verified"]) { "Incomplete actual Windows native set" }
            native.forEach { (name, _) -> zip.getInputStream(zip.getEntry("EhViewer/$name")).use { verifyWindowsPe(it.readNBytes(1 shl 20), platform) } }
        }
        require(launch["launches"].array().size == 2 && launch["extracted_file_count"] == manifest["files"].array().size && launch["native_files_verified"] == native.size) { "Incomplete extracted Windows package proof" }
        fun windowsWindow(proof: Map<String, Any?>) {
            require(proof["gallery_product_checks"] == "5") { "Missing actual Windows detail/page/static and animated image checks" }
            require(proof["account_product_checks"] == "4") { "Missing actual Windows account/favorite decoder checks" }
            require(proof["history_product_checks"] == "3") { "Missing actual Windows native Room history checks" }
            require(proof["language_product_checks"] == "4") { "Missing actual packaged language resources" }
            require(proof["archive_product_checks"] == "9") { "Missing actual Windows private archive/directory runtime checks" }
            require(proof["application_version"] == version && proof["architecture"] in platform.jvmArchitectures && proof["java_version"] == "25.0.5.0.2" && proof["windows_code_page"] == "65001" && proof["native_encoding"] == "UTF-8" && proof["render_api"] == "SOFTWARE_COMPAT" && proof["rust_hash_results"] == "2000" && proof["rust_active_results"] == "0" && proof["rust_ownership_errors"] == "PASS" && proof["rust_library_closed"] == "PASS" && proof["window_showing"] == "true" && proof.str("compose_frames").toInt() > 0) { "Incomplete original Windows window/ownership check" }
        }
        require(runtime["launches"].array().size == 2) { "Both original Windows image launches remain mandatory" }
        runtime["launches"].array().forEach { windowsWindow(it.obj()) }
        launch["launches"].array().forEachIndexed { index, value ->
            val proof = value.obj()
            val properties = Properties().apply { path("$rolePrefix-launch", "launch-${index + 1}/launcher.properties").inputStream().use(::load) }
            require(properties.stringPropertyNames().associateWith { properties.getProperty(it) } == proof) { "Actual Windows launch properties differ" }
            val browser = readJson(path("$rolePrefix-launch", "launch-${index + 1}/native-browser.json"))
            verifyDesktopBrowserReceipt(browser, platform.jvmArchitectures, "C_ABI", native.getValue("app/resources/ehviewer_capi.dll"), native.getValue("app/resources/ehviewer_browser.exe"))
            windowsWindow(proof)
            require(proof.str("java_home").replace('\\', '/').endsWith("/ZIP 安装目录/EhViewer/runtime") && proof.str("rust_library").replace('\\', '/').endsWith("/ZIP 安装目录/EhViewer/app/resources/ehviewer_capi.dll")) { "Expected the extracted bundled JVM/Rust paths" }
        }
        retain(platform.releaseTarget, archive, archiveProof.str("sha256"))
    }
    val ipaFile = path("ios", "unsigned-ipa/" + releaseTargets.single { it.id == "ios-arm64" }.fileName(version))
    val ipaProducer = receipt("ios", "unsigned-ipa/ipa.json")
    val ipa = verifyUnsignedIosIpa(ipaFile, source, version, code)
    require(JsonSlurper().parseText(json(ipa)).obj() == ipaProducer - "archive_sha256") { "Actual IPA differs from the clean same-source producer checks" }
    retain("ios-arm64", ipaFile, ipaProducer.str("archive_sha256"))
    return mapOf("ios_unsigned_ipa" to ipaProducer, "macos_arm64_dmg" to armProducer)
}
