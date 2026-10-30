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

/** Actual current-stage installation bytes and native receipts, deliberately short of final delivery. */
fun stagedPackageCheck(input: Path, output: Path, tools: AndroidTools): Map<String, Any?> {
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "Staging CI requires a clean source tree" }
    val context = readJson(input.resolve("context.json"))
    val run = context["run"].obj()
    val plan = stagedArtifactPlan(context, source, context.str("repository"))
    val delivery = releaseContract(root.resolve("app/build.gradle.kts").readText(), source)
    val version = delivery.str("version")
    val code = delivery["android_version_code"] as Int
    val trustedMain = run["head_branch"] == "main" && run["event"] in setOf("push", "workflow_dispatch") && run["head_sha"] == source
    val requested = output.toAbsolutePath().normalize()
    val owned = requireNotNull(requested.parent).toRealPath().resolve(requested.fileName)
    require(!owned.exists(NOFOLLOW_LINKS) && !owned.startsWith(input.toRealPath()) && !input.toRealPath().startsWith(owned)) { "Use fresh staged output separate from original inputs" }
    Files.createDirectory(owned)
    val payloads = Files.createDirectory(owned.resolve("payloads"))
    val transports = Files.createDirectory(owned.resolve("transport"))
    val unpacked = plan.associate { artifact ->
        val archive = input.resolve("archives/${artifact.id}.zip")
        require(Files.isRegularFile(archive, NOFOLLOW_LINKS) && archive.toRealPath().parent == input.resolve("archives").toRealPath()) { "Artifact archive escapes the owned input set" }
        val destination = payloads.resolve(artifact.role)
        val transport = inspectArtifactArchive(artifact.catalog, archive, artifact.id, artifact.runId, artifact.run.str("head_sha"), destination)
        transports.resolve("${artifact.role}.json").writeText(json(transport))
        artifact.role to destination
    }
    fun path(role: String, name: String) = unpacked.getValue(role).resolve(name)
    fun receipt(role: String, name: String, pass: Boolean = true): Map<String, Any?> = readJson(path(role, name)).also {
        require(it["source_commit"] == source && it["source_dirty"] == false && (!pass || it["result"] == "PASS")) { "Receipt is not clean successful same-source evidence: $role" }
    }
    val policy = receipt("policy", "source-policy.json")
    require(policy["baseline_commit"] == "423b8af13632896b55780783b54b5f720ec4c9dd" && policy["untracked_files"].array().isEmpty()) { "Source boundary receipt changed" }
    val phase = receipt("phase", "migration-phase.json", false)
    require(phase["milestone"] == "M3" && phase["version"] == version && phase["android_version_code"] == code && phase["retained_architecture_checks"].obj()["result"] == "PASS" && phase["shared_module_targets"].obj()["domain"].array() == portableDomainTargets && phase["shared_module_targets"].obj()["common"].array() == portableCommonTargets) { "Expected the reviewed M3 common/domain/retained architecture/version checks" }
    val shared = receipt("shared", "shared-core.json", false)
    require(shared["schema_version"] == SHARED_SCHEMA && shared["shared_library_artifacts_verified"] == true && shared["version"] == version && shared["android_version_code"] == code && shared["targets"].array() == listOf("android")) { "Shared artifact scope changed" }
    require(shared["modules"].obj().keys == sharedArtifactModules.toSet() && shared["verification_scope"] == SHARED_SCOPE) { "Unexpected shared module inventory" }
    shared["modules"].obj().forEach { (name, value) ->
        val module = value.obj()
        require(module["artifact"] == "$name.aar") { "Shared AAR path differs from its owned module" }
        require(sha256(path("shared", "shared-core/${module.str("artifact")}")) == module["aar_sha256"]) { "Actual shared AAR bytes changed" }
    }
    val productJvm = shared["product_jvm"].obj()
    require(productJvm.keys == setOf("network", "shared-ui"))
    productJvm.forEach { (name, value) ->
        val evidence = value.obj()
        require(evidence["artifact"] == "$name-jvm.jar")
        checkedJvmInventory(path("shared", "shared-core/$name-jvm.jar"), evidence)
        val sources = if (name == "network") 2 else 0
        val tests = sharedHostTestEvidence(filesBelow(path("shared", "shared-core/tests/$name-jvm"), ".xml").map(Path::readBytes), sources)
        require(tests == evidence["host_tests"])
        if (name == "network") {
            verifyNetworkTests(tests)
            val android = sharedHostTestEvidence(filesBelow(path("shared", "shared-core/tests/network"), ".xml").map(Path::readBytes), sources)
            verifyNetworkTests(android)
            require(android == shared["modules"].obj().getValue("network").obj()["host_tests"])
        }
    }
    val domain = shared["domain_jvm"].obj()
    require(domain["artifact"] == "domain-jvm.jar") { "Missing actual portable domain JAR" }
    verifyDomainTests(domain["host_tests"].obj(), jvm = true)
    verifyDomainTests(shared["modules"].obj().getValue("domain").obj()["host_tests"].obj())
    for ((name, expected) in listOf(
        "domain" to shared["modules"].obj().getValue("domain").obj()["host_tests"].obj(),
        "domain-jvm" to domain["host_tests"].obj(),
    )) {
        val actual = sharedHostTestEvidence(filesBelow(path("shared", "shared-core/tests/$name"), ".xml").map(Path::readBytes), 2)
        require(actual == expected) { "Actual domain test XML differs from shared producer: $name" }
    }
    val domainJar = path("shared", "shared-core/domain-jvm.jar")
    checkedDomainJvmArtifact(domainJar, domain)
    val domainProbe = domainJvmLinkProbe(domainJar)
    require(listOf("result", "title_cases", "archive_methods").all { domainProbe[it] == domain["link_probe"].obj()[it] }) { "Packaged domain API differs from the shared producer" }
    receipt("domain-native", "domain-native.json")
    val nativeDomain = checkedDomainNativeArtifact(unpacked.getValue("domain-native"))
    receipt("common-native", "common-native.json")
    val nativeCommon = checkedNativeLibraryArtifact(unpacked.getValue("common-native"), commonNativeSpec)
    val common = shared["common_jvm"].obj()
    require(common["artifact"] == "common-jvm.jar")
    val commonJar = path("shared", "shared-core/common-jvm.jar")
    checkedCommonJvmArtifact(commonJar, common)
    val commonTests = sharedHostTestEvidence(filesBelow(path("shared", "shared-core/tests/common-jvm"), ".xml").map(Path::readBytes), 2)
    verifyCommonJvmTests(commonTests)
    require(commonTests == common["host_tests"])
    val commonProbe = commonJvmLinkProbe(commonJar)
    val commonConsumers = listOf(
        listOf("linux-host", "common-jvm.json", "Linux", "amd64"),
        listOf("macos-host", "common-jvm.json", "Mac", "aarch64"),
        listOf("linux-runtime", "reports/desktop-linux-arm64/common-jvm.json", "Linux", "aarch64"),
        listOf("intel-consumer", "common-jvm.json", "Mac", "x86_64"),
        listOf("windows-core", "common-jvm.json", "Windows", "aarch64"),
    ).associate { (role, name, os, architecture) ->
        val proof = receipt(role, name)
        verifyCommonJvmReceipt(proof, source, shared, os, architecture)
        role to proof
    }
    for (role in listOf("linux-host", "macos-host")) {
        val host = receipt(role, "desktop-native.json")
        require(host["target"] == if (role == "linux-host") "x86_64-unknown-linux-gnu" else "aarch64-apple-darwin") { "Host JNI target differs" }
        require(host["jvm_architecture"] == if (role == "linux-host") "amd64" else "aarch64") { "Host JNI JVM architecture differs" }
        require(host["checks"].obj()["ownership_checks"] == 4170 && host["checks"].obj()["archive_cases"] == 13) { "Incomplete host JNI regression" }
    }
    val linux = receipt("linux-runtime", "reports/desktop-linux-arm64/linux-arm64.json")
    val linuxChecks = linux["checks"].obj()
    require(linux["target"] == "aarch64-unknown-linux-gnu" && linuxChecks["version"] == version) { "Linux identity changed" }
    require(linuxChecks["native_files"].array().size == 30 && linuxChecks["installed_notices"].array().size == expectedDesktopNativeNoticeCount() && linuxChecks["launches"].array().size == 2) { "Incomplete reviewed Linux runtime inventory" }
    require(
        linuxChecks["launches"].array().all {
            val proof = it.obj()
            proof["rust_hash_results"] == 100 && (proof["compose_frames"] as Number).toInt() > 0 && proof["bundled_runtime"] == "PASS" && proof["render_api"] == "SOFTWARE_COMPAT"
        },
    ) { "Both original Linux windows must pass" }
    val image = path("linux-runtime", "app/desktop/build/compose/binaries/main/app/EhViewer")
    linuxChecks["native_files"].array().forEach { value ->
        val entry = value.obj()
        val file = image.resolve(stageZipName(entry.str("path")))
        require(sha256(file) == entry["sha256"]) { "Actual Linux native file changed" }
        file.inputStream().use { verifyLinuxArm64Elf(it.readNBytes(32)) }
    }
    linuxChecks["installed_notices"].array().forEach { value ->
        val entry = value.obj()
        require(sha256(image.resolve("lib/" + stageZipName(entry.str("path")))) == entry["sha256"]) { "Linux notice changed" }
    }
    val ios = receipt("ios", "startup.json", false)
    verifyNetworkTests(ios["network_tests"].obj())
    val iosSession = ios["session_backend_tests"].obj()
    require(iosSession["result"] == "PASS" && iosSession["passed"] == 3 && iosSession["ignored"] == 0 && iosSession["execution"] == "REAL_IOS_KEYCHAIN_ON_OWNED_SIMULATOR")
    require(sha256(path("ios", "session-tests.kexe")) == iosSession["test_executable_sha256"] && sha256(path("ios", "session-entitlements.plist")) == iosSession["simulator_identity_sha256"])
    require(ios["version"] == version && ios["build"] == code.toString() && ios["architecture"] == "arm64" && ios["rust_c_abi"] == "PASS" && ios["compose_ready"] == "PASS") { "Incomplete iOS startup evidence" }
    require(listOf("cold_start", "background_foreground", "explicit_termination").all { ios[it] == "PASS" } && ios["rust_completed_results_by_launch"].array() == listOf(2000, 2000) && ios["rust_live_results_after_probe"] == 0) { "Incomplete original iOS lifecycle/ownership evidence" }
    require(JsonSlurper().parseText(json(iosUnifiedLifecycle(path("ios", "lifecycle.log").readText(), (ios["first_pid"] as Number).toLong(), (ios["second_pid"] as Number).toLong()))).obj() == ios["unified_log_lifecycle"]) { "Actual per-PID iOS lifecycle differs" }
    val query = readJson(path("ios", "lifecycle-query.json"))
    val phases = path("ios", "phases.log").readText().lineSequence().filter(String::isNotBlank).associate { line ->
        val fields = line.split(' ', limit = 2)
        fields[1] to Instant.parse(fields[0])
    }
    require(query["timeout_seconds"] == 30 && Instant.parse(query.str("start")) <= phases.getValue("first-cold-start") && Instant.parse(query.str("end")) >= phases.getValue("second-render")) { "Original bounded iOS log query differs" }
    for (name in listOf("first", "second")) {
        val pid = ios["${name}_pid"]
        val stderr = path("ios", "$name-cold-start.stderr.log").readText()
        for (marker in listOf("cold-start", "compose-ready", "rust-ffi-ready completed=2000 live=0")) require(stderr.lineSequence().any { "EhViewer[$pid:" in it && "EhViewerM1:$marker" in it }) { "Independent per-PID iOS stderr differs" }
    }
    val headers = listOf("aarch64-apple-ios", "aarch64-apple-ios-sim").map { path("apple-rust", "$it/include/ehviewer_capi.h") }
    headers.forEach { verifyGalleryCapiHeader(it.readText()) }
    require(sha256(headers.first()) == sha256(headers.last())) { "Device and Simulator C ABI declarations differ" }

    val candidate = Files.createDirectory(owned.resolve("candidate"))
    val assets = mutableListOf<Map<String, Any?>>()
    fun retain(id: String, file: Path, sourceName: String = file.fileName.toString()) {
        val target = releaseTargets.single { it.id == id }
        val name = target.fileName(version)
        require(file.isRegularFile(NOFOLLOW_LINKS) && assets.none { it["target"] == id }) { "Missing/duplicated staged package" }
        Files.copy(file, candidate.resolve(name))
        require(sha256(candidate.resolve(name)) == sha256(file)) { "Staging changed package bytes" }
        assets += mapOf("target" to id, "file" to name, "source_file" to sourceName, "sha256" to sha256(file), "bytes" to Files.size(file), "source_commit" to source, "release_version" to version, "package_version" to if (target.platform == "android") "$version-SNAPSHOT" else version, "final_delivery_acceptance" to "NOT_VERIFIED")
    }
    val snapshot = receipt("snapshot", "snapshot-manifest.json", false)
    require(snapshot["version"] == "$version-SNAPSHOT" && snapshot["versionCode"] == code && snapshot["production_signature_required"] == trustedMain) { "Snapshot candidate version/signature policy differs" }
    val attachments = snapshot["attachments"].array().map(Any?::obj)
    require(attachments.size == 4 && attachments.map { it["abi"] }.toSet() == abis.keys + "universal") { "Expected all four APK candidates" }
    val apks = Files.createDirectory(owned.resolve("apk-check"))
    val metadata = mapOf(
        "artifactType" to mapOf("type" to "APK"),
        "applicationId" to "moe.tarsin.ehviewer",
        "elements" to attachments.map { entry ->
            val abi = entry.str("abi")
            val name = "EhViewer-$version-$abi.apk"
            require(entry["name"] == name) { "Unexpected APK name" }
            val apk = path("apk-$abi", name)
            require(sha256(apk) == entry["sha256"]) { "APK differs from its complete snapshot manifest" }
            Files.copy(apk, apks.resolve(name))
            mapOf("outputFile" to name, "versionCode" to code, "versionName" to "$version-SNAPSHOT", "filters" to if (abi == "universal") emptyList<Any>() else listOf(mapOf("filterType" to "ABI", "value" to abi)))
        },
    )
    apks.resolve("output-metadata.json").writeText(json(metadata))
    val verifiedApks = verifyApks(apks, tools, release = trustedMain, code = code)
    require(trustedMain || verifiedApks.none { it["certificate"] == listOf(CERTIFICATE) }) { "Feature candidates must use an isolated test certificate" }
    if ("android-reports" in unpacked) {
        require(receipt("android-reports", "reports/snapshot.json", false) == snapshot) { "Snapshot completion report changed" }
        val prior = JsonSlurper().parse(path("android-reports", "reports/release-apks.json").toFile()).array().map(Any?::obj).associateBy { it.str("abi") }
        verifiedApks.forEach { actual ->
            val old = prior.getValue(actual.str("abi"))
            val normalized = JsonSlurper().parseText(json(actual)).obj()
            require(listOf("sha256", "versionCode", "versionName", "certificate", "native").all { normalized[it] == old[it] }) { "Independent APK checks differ from the producer" }
        }
    }
    val androidIds = mapOf("arm64-v8a" to "android-arm64", "armeabi-v7a" to "android-armv7", "universal" to "android-universal", "x86_64" to "android-x64")
    verifiedApks.forEach { retain(androidIds.getValue(it.str("abi")), apks.resolve(it.str("file"))) }

    val producerFile = path("intel-producer", "reports/desktop-intel/intel-dmg.json")
    val producer = receipt("intel-producer", "reports/desktop-intel/intel-dmg.json")
    val consumer = receipt("intel-consumer", "intel-dmg.json")
    val dmg = path("intel-producer", "app/desktop/build/compose/binaries/main/dmg/EhViewer-$version.dmg")
    verifyIntelProducer(producer, source, sha256(dmg))
    require(consumer["dmg_sha256"] == sha256(dmg) && consumer["producer_report_sha256"] == sha256(producerFile) && consumer["physical_host"] == "x86_64" && consumer["target"] == "x86_64-apple-darwin" && consumer["execution_mode"] == "native-intel" && consumer["physical_intel_hardware"] == "PASS" && consumer["rosetta_probe"] in setOf("", "0") && consumer["arm_hardware_probe"] in setOf("", "0") && consumer.str("cpu_brand").contains("Intel")) { "Native Intel did not consume the actual ARM-produced DMG" }
    fun intelInventory(value: Map<String, Any?>): Map<String, Any?> = value["checks"].obj().filterKeys { it !in setOf("launches", "native_files", "installed_notices") } + listOf("native_files", "installed_notices").associateWith { key -> value["checks"].obj()[key].array().map(Any?::obj).associateBy { it.str("path") } }
    require(intelInventory(producer) == intelInventory(consumer)) { "Actual Intel producer/consumer native inventories differ" }
    for (proof in listOf(producer, consumer)) {
        val checks = proof["checks"].obj()
        require(proof["jdk"] == "25.0.5" && checks["native_files"].array().size == 32 && checks["installed_notices"].array().size == expectedDesktopNativeNoticeCount()) { "Incomplete reviewed Intel runtime inventory" }
        require(
            checks["version"] == version && checks["launches"].array().size == 2 && checks["launches"].array().all {
                val launch = it.obj()
                launch["rust_hash_results"] == 100 && launch["bundled_runtime"] == "PASS" && (launch["compose_frames"] as Number).toInt() > 0
            },
        ) { "Incomplete actual Intel package launches" }
    }
    FileChannel.open(dmg).use { channel ->
        val signature = ByteBuffer.allocate(4)
        require(channel.size() > 512 && channel.read(signature, channel.size() - 512) == 4 && signature.array().decodeToString() == "koly") { "Expected actual UDIF DMG bytes" }
    }
    retain("macos-x64", dmg)

    val packagedFile = path("windows-package", "windows-arm64-package.json")
    val packaged = receipt("windows-package", "windows-arm64-package.json", false)
    val runtimeFile = path("windows-runtime", "windows-arm64-runtime.json")
    val runtime = receipt("windows-runtime", "windows-arm64-runtime.json")
    val launch = receipt("windows-launch", "windows-arm64-zip-launch.json", false)
    val core = receipt("windows-core", "windows-native-core.json")
    val jvmFile = path("windows-jvm", "windows-jvm-ffi.json")
    val jvm = receipt("windows-jvm", "windows-jvm-ffi.json")
    require(jvm["target"] == "aarch64-pc-windows-msvc" && jvm["caller"].obj()["rust_hash_results"] == "2000" && jvm["caller"].obj()["rust_active_results"] == "0" && jvm["caller"].obj()["library_closed"] == "PASS") { "Incomplete original Windows JVM ownership evidence" }
    require(jvm["caller"].obj()["path_transport"] == "utf8-base64" && listOf("double_free", "stale_handle", "undersized_copy").all { jvm["caller"].obj()[it] == "PASS" }) { "Original standalone Windows ownership errors differ" }
    require(sha256(path("windows-jvm", "native-libraries/ehviewer_capi.dll")) == jvm["rust_capi_sha256"]) { "Standalone Windows C API bytes changed" }
    require((core["passed"] as Number).toInt() >= MINIMUM_NATIVE_SESSION_PASSES && core["filtered"] == 0 && sha256(path("windows-runtime", "native-core-proof.json")) == sha256(path("windows-core", "windows-native-core.json")) && sha256(path("windows-runtime", "jvm-ffi-proof.json")) == sha256(jvmFile)) { "Original Windows Core/JVM proof chain changed" }
    val archive = path("windows-package", releaseTargets.single { it.id == "windows-arm64" }.fileName(version))
    require(packaged["version"] == version && packaged["android_version_code"] == code && packaged["target"] == "aarch64-pc-windows-msvc" && packaged["packager_source_dirty"] == false && packaged["runtime_proof_sha256"] == sha256(runtimeFile)) { "Windows ZIP producer provenance differs" }
    require(runtime["version"] == version && runtime["target"] == "aarch64-pc-windows-msvc") { "Windows runtime identity differs" }
    val manifest = packaged.filterKeys { it !in setOf("archive", "archive_evidence", "native_files_verified") }
    val archiveProof = verifyStageZip(archive, manifest)
    require(JsonSlurper().parseText(json(archiveProof)).obj() == packaged["archive_evidence"] && launch["archive_sha256"] == sha256(archive) && launch["package_report_sha256"] == sha256(packagedFile) && launch["runtime_report_sha256"] == sha256(runtimeFile) && launch["zip_extracted_launch"] == "PASS" && launch["version"] == version) { "Windows ZIP/native startup hashes differ" }
    val native = runtime["native_files"].array().map(Any?::obj).associate { stageZipName(it.str("path").replace('\\', '/')) to it.str("sha256") }
    require(native["app/resources/ehviewer_capi.dll"] == jvm["rust_capi_sha256"]) { "Packaged Windows Rust differs from the actual JVM caller" }
    require(native.size == runtime["native_files"].array().size && setOf("EhViewer.exe", "runtime/bin/server/jvm.dll", "app/skiko-windows-arm64.dll", "app/resources/ehviewer_capi.dll").all { it in native }) { "Missing original Windows entry/runtime/Skiko/Rust" }
    val recordedFiles = manifest["files"].array().map(Any?::obj).associateBy { it.str("path") }
    native.forEach { (name, expected) -> require(recordedFiles.getValue("EhViewer/$name")["sha256"] == expected) { "Windows native bytes differ from the measured runtime" } }
    runtime["installed_notices"].obj().forEach { (name, expected) -> require(recordedFiles.getValue("EhViewer/app/resources/licenses/" + stageZipName(name.replace('\\', '/')))["sha256"] == expected) { "Windows notice differs from the measured runtime" } }
    ZipFile(archive.toFile()).use { zip ->
        val actualNative = zip.entries().asSequence().filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in setOf("dll", "exe") }.map { it.name.removePrefix("EhViewer/") }.toSet()
        require(actualNative == native.keys && native.size == packaged["native_files_verified"]) { "Incomplete actual Windows native set" }
        native.forEach { (name, _) -> zip.getInputStream(zip.getEntry("EhViewer/$name")).use { verifyWindowsArm64Pe(it.readNBytes(1 shl 20)) } }
    }
    require(launch["launches"].array().size == 2 && launch["extracted_file_count"] == manifest["files"].array().size && launch["native_files_verified"] == native.size) { "Incomplete extracted Windows package proof" }
    fun windowsWindow(proof: Map<String, Any?>) {
        require(proof["application_version"] == version && proof["architecture"] == "aarch64" && proof["java_version"] == "25.0.5.0.2" && proof["windows_code_page"] == "65001" && proof["native_encoding"] == "UTF-8" && proof["render_api"] == "SOFTWARE_COMPAT" && proof["rust_hash_results"] == "2000" && proof["rust_active_results"] == "0" && proof["rust_ownership_errors"] == "PASS" && proof["rust_library_closed"] == "PASS" && proof["window_showing"] == "true" && proof.str("compose_frames").toInt() > 0) { "Incomplete original Windows window/ownership check" }
    }
    require(runtime["launches"].array().size == 2) { "Both original Windows image launches remain mandatory" }
    runtime["launches"].array().forEach { windowsWindow(it.obj()) }
    launch["launches"].array().forEachIndexed { index, value ->
        val proof = value.obj()
        val properties = Properties().apply { path("windows-launch", "launch-${index + 1}/launcher.properties").inputStream().use(::load) }
        require(properties.stringPropertyNames().associateWith { properties.getProperty(it) } == proof) { "Actual Windows launch properties differ" }
        windowsWindow(proof)
        require(proof.str("java_home").replace('\\', '/').endsWith("/ZIP 安装目录/EhViewer/runtime") && proof.str("rust_library").replace('\\', '/').endsWith("/ZIP 安装目录/EhViewer/app/resources/ehviewer_capi.dll")) { "Expected the extracted bundled JVM/Rust paths" }
    }
    retain("windows-arm64", archive)
    require(assets.size == 6 && candidate.toFile().listFiles()!!.map { it.name }.toSet() == assets.map { it.str("file") }.toSet()) { "Expected exactly six current-stage candidate files" }
    val result = mapOf(
        "schema_version" to 6, "verification_scope" to "M3_ACTUAL_STAGED_PACKAGE_PREFLIGHT", "source_commit" to source, "source_dirty" to dirty, "producer_source_clean" to true,
        "version" to version, "android_version_code" to code, "run_id" to run["id"], "run_head_sha" to run["head_sha"], "android_artifact_run_id" to context["android_run"].obj()["id"],
        "upstream_jobs" to stagedUpstreamJobs.sorted(), "artifact_inputs" to plan.map { mapOf("role" to it.role, "artifact_id" to it.id, "run_id" to it.runId, "run_head_sha" to it.run["head_sha"], "digest" to it.metadata["digest"]) },
        "candidate_assets" to assets, "staged_package_bytes_verified" to true, "candidate_android_version_name" to "$version-SNAPSHOT", "android_production_certificate_required" to trustedMain, "android_apk_checks" to verifiedApks,
        "native_execution_in_this_job" to "NOT_EXECUTED", "same_source_native_receipts_verified" to true,
        "domain_jvm_link_probe" to domainProbe, "domain_jvm_link_probe_executed_here" to true,
        "common_jvm_link_probe" to commonProbe, "common_jvm_link_probe_executed_here" to true, "common_jvm_consumers" to commonConsumers,
        "domain_native" to nativeDomain, "domain_native_receipt_verified" to true, "domain_ios_targets" to domainIosTargets,
        "common_native" to nativeCommon, "common_native_receipt_verified" to true, "common_ios_targets" to domainIosTargets,
        "ios_product_domain_integration" to "NOT_INTEGRATED",
        "remaining_final_targets" to releaseTargets.filter { target -> assets.none { it["target"] == target.id } }.map { mapOf("target" to it.id, "file" to it.fileName(version), "final_package" to "NOT_INTEGRATED") },
        "linux_arm64_transport_permissions" to "NOT_PRESERVED; ORIGINAL_RUNNER_EXECUTION_VERIFIED_SEPARATELY", "ios_unsigned_ipa" to "NOT_INTEGRATED", "development_signed_device" to "NOT_VERIFIED",
        "minimum_systems" to "NOT_VERIFIED", "hardware_acceleration" to "NOT_VERIFIED", "complete_license_sbom" to "NOT_VERIFIED", "product_p0_verified" to false, "all_eleven_assets_verified" to false, "publish_enabled" to false,
    )
    owned.resolve("staged-package.json").writeText(json(result))
    return result
}

fun stagedPackageDryRun(repository: String, runId: Long, androidRunId: Long, output: Path, tools: AndroidTools): Map<String, Any?> {
    require(!output.exists(NOFOLLOW_LINKS)) { "Use fresh staged dry-run evidence" }
    Files.createDirectory(output)
    val input = fetchStagedInputs(repository, runId, androidRunId, output.resolve("input"))
    return stagedPackageCheck(input, output.resolve("checked"), tools)
}
