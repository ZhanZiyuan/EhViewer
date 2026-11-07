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

fun verifyStagedDomainTests(xml: List<ByteArray>, expected: Map<String, Any?>, jvm: Boolean, repository: Path = root) {
    verifyDomainTests(expected, jvm = jvm)
    val sources = filesBelow(repository.resolve("core/domain/src/commonTest"), ".kt").size
    val actual = sharedHostTestEvidence(xml, sources)
    require(actual == expected) { "Actual domain test XML differs from shared producer: ${if (jvm) "domain-jvm" else "domain"}" }
}

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
    require(phase["milestone"] == "M5" && phase["version"] == version && phase["android_version_code"] == code && phase["retained_architecture_checks"].obj()["result"] == "PASS" && phase["shared_module_targets"].obj()["domain"].array() == portableDomainTargets && phase["shared_module_targets"].obj()["common"].array() == portableCommonTargets) { "Expected the reviewed M5 common/domain/retained architecture/version checks" }
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
    }
    verifyProductTestReceipts(shared, path("shared", "shared-core"))
    val domain = shared["domain_jvm"].obj()
    require(domain["artifact"] == "domain-jvm.jar") { "Missing actual portable domain JAR" }
    for ((name, expected) in listOf(
        "domain" to shared["modules"].obj().getValue("domain").obj()["host_tests"].obj(),
        "domain-jvm" to domain["host_tests"].obj(),
    )) {
        verifyStagedDomainTests(filesBelow(path("shared", "shared-core/tests/$name"), ".xml").map(Path::readBytes), expected, jvm = name == "domain-jvm")
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
    val dataJvm = shared["data_jvm"].obj()
    require(dataJvm["artifact"] == "data-jvm.jar")
    checkedDataJvmArtifact(path("shared", "shared-core/data-jvm.jar"), dataJvm)
    val dataJvmSources = listOf("commonTest", "nonAndroidTest", "desktopTest").sumOf { filesBelow(root.resolve("core/data/src/$it"), ".kt").size }
    val dataJvmTests = sharedHostTestEvidence(filesBelow(path("shared", "shared-core/tests/data-jvm"), ".xml").map(Path::readBytes), dataJvmSources)
    verifyDataTests(dataJvmTests)
    require(dataJvmTests == dataJvm["host_tests"]) { "Actual database JVM XML differs from its producer receipt" }
    val commonProbe = commonJvmLinkProbe(commonJar)
    val commonConsumers = listOf(
        listOf("linux-host", "common-jvm.json", "Linux", "amd64"),
        listOf("macos-host", "common-jvm.json", "Mac", "aarch64"),
        listOf("linux-runtime", "reports/desktop-linux-arm64/common-jvm.json", "Linux", "aarch64"),
        listOf("intel-consumer", "common-jvm.json", "Mac", "x86_64"),
        listOf("windows-core", "common-jvm.json", "Windows", "aarch64"),
        listOf("windows-x64-core", "common-jvm.json", "Windows", "amd64"),
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
        require(host["gallery_product_checks"] == 5 && host["account_product_checks"] == 4 && host["history_product_checks"] == 3 && host["archive_product_checks"] == 9) { "Missing actual host gallery/account/favorite/history/archive/directory runtime checks" }
        val browser = host["native_browser"].obj()
        verifyDesktopBrowserReceipt(browser, setOf(host.str("jvm_architecture")), "JNI", host.str("library_sha256"), browser.str("browser_executable_sha256"))
        require(readJson(path(role, "native-browser.json")) == browser) { "Browser receipt differs from host summary" }
    }
    val linux = receipt("linux-runtime", "reports/desktop-linux-arm64/linux-arm64.json")
    val linuxChecks = linux["checks"].obj()
    require(linux["target"] == "aarch64-unknown-linux-gnu" && linuxChecks["version"] == version) { "Linux identity changed" }
    require(linuxChecks["native_files"].array().size == 31 && linuxChecks["installed_notices"].array().size == expectedDesktopNativeNoticeCount() && linuxChecks["launches"].array().size == 2) { "Incomplete reviewed Linux runtime inventory" }
    require(
        linuxChecks["launches"].array().all {
            val proof = it.obj()
            proof["gallery_product_checks"] == 5 && proof["account_product_checks"] == 4 && proof["history_product_checks"] == 3 && proof["archive_product_checks"] == 9 && proof["rust_hash_results"] == 100 && (proof["compose_frames"] as Number).toInt() > 0 && proof["bundled_runtime"] == "PASS" && proof["render_api"] == "SOFTWARE_COMPAT"
        },
    ) { "Both original Linux windows must pass" }
    val image = path("linux-runtime", "app/desktop/build/compose/binaries/main/app/EhViewer")
    for ((index, launch) in linuxChecks["launches"].array().withIndex()) {
        val browser = desktopBrowserReceipt(path("linux-runtime", "reports/desktop-linux-arm64/launch-${index + 1}"), image.resolve("lib/app/resources/libehviewer_rust.so"), image.resolve("lib/app/resources/ehviewer_browser"), setOf("aarch64"), "JNI")
        require(launch.obj()["native_browser"] == browser)
    }
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
    val iosReader = ios["reader_tests"].obj()
    verifyReaderTests(iosReader)
    require(iosReader["execution"] == "BOOTED_OWNED_SIMULATOR" && sha256(path("ios", "reader-tests.kexe")) == iosReader["test_executable_sha256"])
    val readerXml = filesBelow(path("ios", "."), ".xml").filter { it.fileName.toString().startsWith("reader-") }
    val readerEvidence = sharedHostTestEvidence(readerXml.map(Path::readBytes), productTestSources(root, "shared-ui", "ios").size)
    require(readerEvidence == iosReader - setOf("execution", "test_executable_sha256")) { "Actual Native reader XML differs from its producer receipt" }
    val iosData = ios["data_tests"].obj()
    verifyDataTests(iosData, native = true)
    require(iosData["execution"] == "BOOTED_OWNED_SIMULATOR" && sha256(path("ios", "data-tests.kexe")) == iosData["test_executable_sha256"])
    val dataXml = filesBelow(path("ios", "."), ".xml").filter { it.fileName.toString().startsWith("data-") }
    val dataSources = listOf("commonTest", "nonAndroidTest", "iosTest").sumOf { filesBelow(root.resolve("core/data/src/$it"), ".kt").size }
    require(sharedHostTestEvidence(dataXml.map(Path::readBytes), dataSources) == iosData - setOf("execution", "test_executable_sha256")) { "Actual Native database XML differs from its producer receipt" }
    require(ios["gallery_product_checks_by_launch"].array() == listOf(5, 5)) { "Missing actual iOS detail/page/static and animated image checks" }
    require(ios["account_product_checks_by_launch"].array() == listOf(4, 4)) { "Missing actual iOS account/favorite decoder checks" }
    require(ios["history_product_checks_by_launch"].array() == listOf(3, 3)) { "Missing actual iOS native database runtime checks" }
    require(ios["archive_product_checks_by_launch"].array() == listOf(9, 9)) { "Missing actual iOS private archive/directory runtime checks" }
    require(ios["language_product_checks_by_launch"].array() == listOf(4, 4)) { "Missing actual packaged iOS language resources" }
    require(ios["archive_share_checks_by_launch"].array() == listOf(4, 4)) { "Missing actual UIKit archive sharing failure/cancellation/retry checks" }
    require(ios["browser_cookie_checks_by_launch"].array() == listOf(4, 4)) { "Missing actual WebKit cookie transfer checks" }
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
        for (marker in listOf("cold-start", "compose-ready", "rust-ffi-ready completed=2000 live=0", "browser-cookie-ready checks=4")) require(stderr.lineSequence().any { "EhViewer[$pid:" in it && "EhViewerM1:$marker" in it }) { "Independent per-PID iOS stderr differs" }
    }
    val headers = listOf("aarch64-apple-ios", "aarch64-apple-ios-sim").map { path("apple-rust", "$it/include/ehviewer_capi.h") }
    headers.forEach { verifyGalleryCapiHeader(it.readText()) }
    require(sha256(headers.first()) == sha256(headers.last())) { "Device and Simulator C ABI declarations differ" }

    val candidate = Files.createDirectory(owned.resolve("candidate"))
    val assets = mutableListOf<Map<String, Any?>>()
    fun retain(id: String, file: Path, sourceName: String = file.fileName.toString(), expectedSha256: String? = null) {
        val target = releaseTargets.single { it.id == id }
        val name = target.fileName(version)
        require(file.isRegularFile(NOFOLLOW_LINKS) && assets.none { it["target"] == id }) { "Missing/duplicated staged package" }
        val sourceDigest = expectedSha256?.also { require(Regex("[0-9a-f]{64}").matches(it)) } ?: sha256(file)
        Files.copy(file, candidate.resolve(name))
        val retainedDigest = sha256(candidate.resolve(name))
        require(retainedDigest == sourceDigest) { "Staging changed package bytes" }
        assets += mapOf("target" to id, "file" to name, "source_file" to sourceName, "sha256" to retainedDigest, "bytes" to Files.size(file), "source_commit" to source, "release_version" to version, "package_version" to if (target.platform == "android") "$version-SNAPSHOT" else version, "final_delivery_acceptance" to "NOT_VERIFIED")
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

    val nativePackages = verifyNativeCandidatePackages(source, version, code, ::path) { id, file, digest ->
        retain(id, file, expectedSha256 = digest)
    }
    require(assets.size == 11 && candidate.toFile().listFiles()!!.map { it.name }.toSet() == assets.map { it.str("file") }.toSet()) { "Expected exactly eleven current-stage candidate files" }
    val result = mapOf(
        "schema_version" to 10, "verification_scope" to "M5_M6_ACTUAL_STAGED_PACKAGE_PREFLIGHT", "source_commit" to source, "source_dirty" to dirty, "producer_source_clean" to true,
        "version" to version, "android_version_code" to code, "run_id" to run["id"], "run_head_sha" to run["head_sha"], "android_artifact_run_id" to context["android_run"].obj()["id"],
        "upstream_jobs" to stagedUpstreamJobs.sorted(), "artifact_inputs" to plan.map { mapOf("role" to it.role, "artifact_id" to it.id, "run_id" to it.runId, "run_head_sha" to it.run["head_sha"], "digest" to it.metadata["digest"]) },
        "candidate_assets" to assets, "staged_package_bytes_verified" to true, "candidate_android_version_name" to "$version-SNAPSHOT", "android_production_certificate_required" to trustedMain, "android_apk_checks" to verifiedApks,
        "native_execution_in_this_job" to "NOT_EXECUTED", "same_source_native_receipts_verified" to true,
        "domain_jvm_link_probe" to domainProbe, "domain_jvm_link_probe_executed_here" to true,
        "common_jvm_link_probe" to commonProbe, "common_jvm_link_probe_executed_here" to true, "common_jvm_consumers" to commonConsumers,
        "domain_native" to nativeDomain, "domain_native_receipt_verified" to true, "domain_ios_targets" to domainIosTargets,
        "common_native" to nativeCommon, "common_native_receipt_verified" to true, "common_ios_targets" to domainIosTargets,
        "ios_product_domain_integration" to "GALLERY_LIBRARY_READER_DOWNLOADS",
        "remaining_final_targets" to releaseTargets.filter { target -> assets.none { it["target"] == target.id } }.map { mapOf("target" to it.id, "file" to it.fileName(version), "final_package" to "NOT_INTEGRATED") },
        "linux_arm64_transport_permissions" to "NOT_PRESERVED; ORIGINAL_RUNNER_EXECUTION_VERIFIED_SEPARATELY", "ios_unsigned_ipa" to nativePackages["ios_unsigned_ipa"], "macos_arm64_dmg" to nativePackages["macos_arm64_dmg"], "development_signed_device" to "NOT_VERIFIED",
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
