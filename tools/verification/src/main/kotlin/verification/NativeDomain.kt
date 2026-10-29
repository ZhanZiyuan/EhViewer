package verification

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

val domainIosTargets = listOf("iosArm64", "iosSimulatorArm64")
val portableDomainTargets = listOf("android", "jvm") + domainIosTargets
val domainNativeDependencies = setOf("stdlib", "org.jetbrains.kotlinx:kotlinx-serialization-cbor", "org.jetbrains.kotlinx:kotlinx-serialization-core")
val domainModelTypes = listOf(
    "BaseGalleryInfo", "GalleryInfo", "GalleryDetail", "GalleryPreview", "V1GalleryPreview", "V2GalleryPreview",
    "GalleryPreviewList", "GalleryComment", "GalleryCommentList", "GalleryTag", "GalleryTagGroup",
    "PowerStatus", "VoteStatus", "TagNamespace", "TagNamespaceSerializer",
)

fun verifyDomainNativeDependencies(value: String?) {
    val dependencies = value?.split(' ') ?: emptyList()
    require(dependencies.size == domainNativeDependencies.size && dependencies.toSet() == domainNativeDependencies) {
        "Native domain dependencies differ from the reviewed existing serialization runtime"
    }
}

fun verifyDomainTests(tests: Map<String, Any?>, native: Boolean = false, jvm: Boolean = false) {
    require(!native || !jvm)
    val prefix = if (native) "iosSimulatorArm64Test." else ""
    val suites = if (jvm) {
        mapOf("GalleryDomainTitlesTest[desktop]" to 3, "GalleryModelWireTest[desktop]" to 5)
    } else {
        mapOf(
            "${prefix}com.ehviewer.core.domain.GalleryDomainTitlesTest" to 3,
            "${prefix}com.ehviewer.core.model.GalleryModelWireTest" to 5,
        )
    }
    require(
        tests["status"] == "PASS" && tests["executed"] == true && tests["source_files"] == 2 && tests["tests"] == 8 &&
            tests["suites"].obj() == suites,
    ) { "Every original title and model wire test must execute" }
}

fun domainKotlinVersion(): String = Regex("(?m)^kotlin = \"([^\"]+)\"$")
    .findAll(root.resolve("gradle/libs.versions.toml").readText()).single().groupValues[1]

private fun nativeDomainDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

/** Check actual compiler KLIB bytes, independently of a target declaration or file extension. */
fun domainNativeKlibEvidence(bytes: ByteArray, target: String, compiler: String): Map<String, Any?> {
    require(target in setOf("ios_arm64", "ios_simulator_arm64"))
    val entries = coreArchive(bytes)
    require(entries.keys.all { it.startsWith("default/") }) { "Unexpected KLIB root" }
    val manifest = Properties().apply {
        load(ByteArrayInputStream(requireNotNull(entries["default/manifest"]) { "Missing actual Native manifest" }))
    }.let { p -> p.stringPropertyNames().sorted().associateWith(p::getProperty) }
    require(
        manifest["builtins_platform"] == "NATIVE" && manifest["native_targets"] == target &&
            manifest["compiler_version"] == compiler && manifest["short_name"] == "domain" &&
            manifest["unique_name"] == "EhViewer.core:domain",
    ) { "Native domain compiler, target or module identity differs" }
    verifyDomainNativeDependencies(manifest["depends"])
    val required = listOf(
        "default/linkdata/module",
        "default/linkdata/package_com.ehviewer.core.domain/0_domain.knm",
        "default/linkdata/package_com.ehviewer.core.domain.reader/0_reader.knm",
        "default/ir/files.knf", "default/ir/irDeclarations.knd", "default/ir/bodies.knb",
        "default/ir/strings.knt", "default/ir/signatures.knt", "default/ir/types.knt",
    ) + (0..8).map { "default/linkdata/package_com.ehviewer.core.model/${it}_model.knm" }
    require(required.all { entries[it]?.isNotEmpty() == true }) { "Empty/missing real production metadata or IR; NO-SOURCE is not acceptance" }
    return mapOf(
        "result" to "PASS",
        "target" to target,
        "compiler_version" to compiler,
        "manifest" to manifest,
        "compiler_files" to entries.mapValues { nativeDomainDigest(it.value) },
        "compiler_file_count" to entries.size,
    )
}

/** The Native test must be an actual thin ARM64 iOS Simulator executable. */
fun verifyDomainSimulatorMachO(bytes: ByteArray): Map<String, Any?> {
    require(bytes.size >= 32)
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(b.getInt(0) == 0xfeedfacf.toInt() && b.getInt(4) == 0x0100000c && b.getInt(12) == 2) { "Expected a thin ARM64 Mach-O executable" }
    val count = b.getInt(16)
    val commandBytes = b.getInt(20)
    require(count in 1..1024 && commandBytes in 24..(bytes.size - 32)) { "Invalid Mach-O load commands" }
    var offset = 32
    var platform: Map<String, Any?>? = null
    fun version(value: Int) = "${value ushr 16}.${(value ushr 8) and 255}.${value and 255}"
    repeat(count) {
        require(offset <= 32 + commandBytes - 8)
        val size = b.getInt(offset + 4)
        require(size >= 8 && size % 8 == 0 && size <= 32 + commandBytes - offset)
        if (b.getInt(offset) == 0x32) {
            require(platform == null && size >= 24 && b.getInt(offset + 8) == 7) { "Expected exactly one iOS Simulator build platform" }
            platform = mapOf(
                "architecture" to "arm64",
                "platform" to "IOSSIMULATOR",
                "declared_minimum_ios" to version(b.getInt(offset + 12)),
                "sdk" to version(b.getInt(offset + 16)),
            )
        }
        offset += size
    }
    require(offset == 32 + commandBytes)
    return requireNotNull(platform) { "Missing actual iOS Simulator build platform" }
}

fun verifyDomainNativeApiDump(text: String) {
    require(
        listOf(
            "public final fun selectGalleryTitle(title: kotlin/String?, japaneseTitle: kotlin/String?, preferJapanese: kotlin/Boolean): kotlin/String",
            "public abstract interface com/ehviewer/core/domain/reader/ArchiveCatalog",
            "public abstract fun extension(index: kotlin/Int): kotlin/String",
            "public abstract fun providePassword(password: kotlin/String): kotlin/Boolean",
            "public abstract val pageCount: kotlin/Int",
            "public abstract val needsPassword: kotlin/Boolean",
        ).all(text::contains),
    ) { "Actual Native KLIB lost the original production title/archive API" }
    require(
        domainModelTypes.all { type ->
            Regex("(?m)^    public [^\\n]+ com/ehviewer/core/model/$type :").containsMatchIn(text)
        },
    ) { "Actual Native KLIB lost a production gallery model API" }
    for (type in listOf("BaseGalleryInfo", "GalleryDetail", "GalleryPreviewList", "GalleryComment", "GalleryCommentList", "GalleryTag", "GalleryTagGroup", "V1GalleryPreview", "V2GalleryPreview")) {
        require(text.contains("public final fun serializer(): kotlinx/serialization/KSerializer<com/ehviewer/core/model/$type>")) {
            "Actual Native KLIB lost a generated production serializer"
        }
    }
}

private fun packageDomainKlib(directory: Path, archive: Path, target: String, compiler: String): Map<String, Any?> {
    val paths = Files.walk(directory).use { it.sorted().toList() }
    require(paths.none(Files::isSymbolicLink)) { "Compiler output must not contain symbolic links" }
    val files = paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
    require(files.isNotEmpty()) { "Missing actual compiler KLIB output" }
    ZipOutputStream(Files.newOutputStream(archive, CREATE_NEW)).use { zip ->
        files.forEach { file ->
            zip.putNextEntry(ZipEntry(directory.relativize(file).joinToString("/")).apply { time = 0 })
            Files.copy(file, zip)
            zip.closeEntry()
        }
    }
    val evidence = domainNativeKlibEvidence(archive.readBytes(), target, compiler)
    require(evidence["compiler_files"] == files.associate { directory.relativize(it).joinToString("/") to sha256(it) }) { "Packaged KLIB differs from complete compiler output" }
    return evidence + mapOf("artifact" to archive.fileName.toString(), "sha256" to sha256(archive))
}

/** Force real shared Native compilation/tests on a new owned device; never use a user's Simulator. */
fun iosDomainNative(output: Path): Map<String, Any?> {
    require(System.getProperty("os.name").startsWith("Mac") && command("uname", "-m").trim() == "arm64")
    require(!output.exists()) { "Use fresh Native domain evidence" }
    output.createDirectories()
    val source = command("git", "rev-parse", "HEAD").trim()
    val originalStatus = command("git", "status", "--porcelain", "--untracked-files=all")
    val dirty = originalStatus.isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires clean source" }
    val compiler = domainKotlinVersion()
    val nativeHome = Path.of(System.getenv("KONAN_DATA_DIR") ?: Path.of(System.getProperty("user.home"), ".konan").toString())
        .resolve("kotlin-native-prebuilt-macos-aarch64-$compiler")
    val klib = nativeHome.resolve("bin/klib")
    require(Files.isExecutable(klib)) { "Missing the actual pinned Kotlin/Native inspection tool" }
    val runtime = latestIosRuntime(groovy.json.JsonSlurper().parseText(command("xcrun", "simctl", "list", "-j", "runtimes")).obj()["runtimes"].array())
    val type = runtimeIphone(runtime)
    val device = command("xcrun", "simctl", "create", "EhViewer-M3-Domain-${UUID.randomUUID()}", type.str("identifier"), runtime.str("identifier")).trim()
    require(Regex("[A-Fa-f0-9-]{36}").matches(device))
    output.resolve("environment.json").writeText(json(mapOf("source_commit" to source, "source_dirty" to dirty, "owned_simulator" to device, "runtime" to runtime, "device_type" to type)))
    val result = try {
        command("xcrun", "simctl", "boot", device)
        command("xcrun", "simctl", "bootstatus", device, "-b", timeout = Duration.ofMinutes(5))
        temporary { staged ->
            val started = Instant.now()
            try {
                val log = command(
                    root.resolve("gradlew"), ":core:domain:compileKotlinIosArm64", ":core:domain:iosSimulatorArm64Test",
                    "-Pehviewer.domain.testSimulator=$device", "-Pehviewer.domain.testDirectory=$staged",
                    "--rerun-tasks", "--console=plain", "--no-daemon", "--max-workers=1", "-Dorg.gradle.parallel=false",
                    "-Dorg.gradle.jvmargs=-Xmx2g", "-Pkotlin.native.jvmArgs=-Xmx3g",
                    timeout = Duration.ofMinutes(10),
                )
                output.resolve("gradle-tests.log").writeText(log)
            } catch (failure: CommandFailure) {
                output.resolve("gradle-tests.log").writeText(failure.output)
                throw failure
            }
            val domain = root.resolve("core/domain/build")
            val compiled = domain.resolve("bin/iosSimulatorArm64/debugTest/test.kexe")
            val executed = staged.resolve("test.kexe")
            require(Files.getLastModifiedTime(compiled).toInstant() >= started.minusSeconds(1)) { "Stale Native executable cannot satisfy a NO-SOURCE compile" }
            require(Files.isExecutable(executed) && sha256(compiled) == sha256(executed)) { "Executed Native test is not the actual freshly linked binary" }
            val machine = executed.inputStream().use { verifyDomainSimulatorMachO(it.readNBytes(1024 * 1024)) }
            Files.copy(executed, output.resolve("test.kexe"))
            val xml = filesBelow(domain.resolve("test-results/iosSimulatorArm64Test"), ".xml")
            require(xml.size == 2 && xml.all { Files.getLastModifiedTime(it).toInstant() >= started.minusSeconds(1) }) { "Missing/stale actual Native JUnit XML" }
            val tests = sharedHostTestEvidence(xml.map(Path::readBytes), filesBelow(root.resolve("core/domain/src/commonTest"), ".kt").size)
            verifyDomainTests(tests, native = true)
            xml.forEach { Files.copy(it, output.resolve(it.fileName)) }
            val libraries = linkedMapOf<String, Any?>()
            for ((target, triple) in listOf("iosArm64" to "ios_arm64", "iosSimulatorArm64" to "ios_simulator_arm64")) {
                val archive = output.resolve("domain-$target.klib")
                val directory = domain.resolve("classes/kotlin/$target/main/klib/domain")
                require(Files.getLastModifiedTime(directory.resolve("default/manifest")).toInstant() >= started.minusSeconds(1)) { "Stale Native KLIB cannot satisfy a NO-SOURCE compile" }
                val evidence = packageDomainKlib(directory, archive, triple, compiler)
                val dump = command(klib, "dump-metadata", archive)
                verifyDomainNativeApiDump(dump)
                val apiFile = output.resolve("api-$target.txt").also { it.writeText(dump) }
                libraries[target] = evidence + mapOf("api_dump" to apiFile.fileName.toString(), "api_sha256" to sha256(apiFile))
            }
            require(command("git", "rev-parse", "HEAD").trim() == source && command("git", "status", "--porcelain", "--untracked-files=all") == originalStatus) { "Source changed during Native execution" }
            mapOf(
                "schema_version" to 2, "verification_scope" to "ACTUAL_IOS_DOMAIN_COMPILE_AND_TEST",
                "source_commit" to source, "source_dirty" to dirty, "result" to "PASS",
                "domain_targets" to portableDomainTargets, "libraries" to libraries, "host_tests" to tests,
                "test_executable" to "test.kexe", "test_executable_sha256" to sha256(executed),
                "compiler_test_executable_sha256" to sha256(compiled), "test_machine" to machine,
                "test_execution" to "BOOTED_OWNED_SIMULATOR", "test_task_timeout_seconds" to 300,
                "compiled_outputs_fresh" to true,
                "runtime" to runtime.str("name"), "device_type" to type.str("name"),
                "klib_tool_sha256" to sha256(klib), "device_execution" to "NOT_VERIFIED",
                "ios_product_domain_integration" to "NOT_INTEGRATED", "unsigned_ipa" to "NOT_INTEGRATED",
                "minimum_systems" to "NOT_VERIFIED", "product_p0_verified" to false,
                "all_eleven_assets_verified" to false, "publish_enabled" to false,
            )
        }
    } catch (failure: Throwable) {
        output.resolve("failure.txt").writeText(failure.toString())
        throw failure
    } finally {
        try {
            command("xcrun", "simctl", "shutdown", device)
        } finally {
            command("xcrun", "simctl", "delete", device)
        }
    }
    return (result + ("owned_simulator_cleanup" to "PASS")).also { output.resolve("domain-native.json").writeText(json(it)) }
}

/** Inspect the separately executed Native evidence using the actual uploaded bytes on any host. */
fun checkedDomainNativeArtifact(directory: Path): Map<String, Any?> {
    val proof = readJson(directory.resolve("domain-native.json"))
    require(
        proof["schema_version"] == 2 && proof["result"] == "PASS" && proof["verification_scope"] == "ACTUAL_IOS_DOMAIN_COMPILE_AND_TEST" &&
            proof["domain_targets"].array() == portableDomainTargets && proof["compiled_outputs_fresh"] == true &&
            proof["test_execution"] == "BOOTED_OWNED_SIMULATOR" && proof["test_task_timeout_seconds"] == 300 &&
            proof["owned_simulator_cleanup"] == "PASS",
    ) { "Missing actual compiled/tested/cleaned Native domain evidence" }
    require(
        proof["device_execution"] == "NOT_VERIFIED" && proof["ios_product_domain_integration"] == "NOT_INTEGRATED" &&
            proof["unsigned_ipa"] == "NOT_INTEGRATED" && proof["minimum_systems"] == "NOT_VERIFIED" &&
            listOf("product_p0_verified", "all_eleven_assets_verified", "publish_enabled").all { proof[it] == false },
    ) { "Native library tests must not certify device/product delivery" }
    val environment = readJson(directory.resolve("environment.json"))
    require(
        environment["source_commit"] == proof["source_commit"] && environment["source_dirty"] == proof["source_dirty"] &&
            Regex("[A-Fa-f0-9-]{36}").matches(environment.str("owned_simulator")) &&
            environment["runtime"].obj()["name"] == proof["runtime"] && environment["runtime"].obj()["isAvailable"] == true &&
            environment["device_type"].obj()["name"] == proof["device_type"] && environment["device_type"].obj()["productFamily"] == "iPhone",
    ) { "Native test environment differs from its owned Simulator receipt" }
    val libraries = proof["libraries"].obj()
    require(libraries.keys == domainIosTargets.toSet())
    for ((target, triple) in listOf("iosArm64" to "ios_arm64", "iosSimulatorArm64" to "ios_simulator_arm64")) {
        val row = libraries.getValue(target).obj()
        require(row["artifact"] == "domain-$target.klib" && row["api_dump"] == "api-$target.txt")
        val archive = directory.resolve(row.str("artifact"))
        require(sha256(archive) == row["sha256"]) { "Actual Native KLIB bytes changed" }
        val evidence = domainNativeKlibEvidence(archive.readBytes(), triple, domainKotlinVersion())
        require(evidence.all { (key, value) -> row[key] == value }) { "Native compiler file/manifest inventory changed" }
        val api = directory.resolve(row.str("api_dump"))
        require(sha256(api) == row["api_sha256"])
        verifyDomainNativeApiDump(api.readText())
    }
    require(proof["test_executable"] == "test.kexe")
    val executable = directory.resolve("test.kexe")
    require(
        sha256(executable) == proof["test_executable_sha256"] &&
            proof["compiler_test_executable_sha256"] == proof["test_executable_sha256"],
    ) { "Actual Native execution/compiler bytes differ" }
    val machine = executable.inputStream().use { verifyDomainSimulatorMachO(it.readNBytes(1024 * 1024)) }
    require(machine == proof["test_machine"])
    val xml = filesBelow(directory, ".xml")
    require(xml.size == 2)
    val tests = sharedHostTestEvidence(xml.map(Path::readBytes), 2)
    verifyDomainTests(tests, native = true)
    require(tests == proof["host_tests"]) { "Actual Native test XML differs from its receipt" }
    return mapOf(
        "verification_scope" to "IOS_DOMAIN_ARTIFACT_BYTES_AND_TEST_RECEIPT",
        "source_commit" to proof["source_commit"], "source_dirty" to proof["source_dirty"],
        "result" to "PASS",
        "targets" to domainIosTargets,
        "host_tests" to tests,
        "test_executable_sha256" to sha256(executable),
        "test_machine" to machine,
        "original_native_execution" to "BOOTED_OWNED_SIMULATOR",
        "native_execution_here" to "NOT_EXECUTED",
        "ios_product_domain_integration" to "NOT_INTEGRATED",
        "product_p0_verified" to false, "all_eleven_assets_verified" to false, "publish_enabled" to false,
    )
}
