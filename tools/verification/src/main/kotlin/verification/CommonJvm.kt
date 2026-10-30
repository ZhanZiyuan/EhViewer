package verification

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.concurrent.CancellationException
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import okio.FileSystem
import okio.Path as OkioPath
import okio.Path.Companion.toPath

val portableCommonTargets = listOf("android", "jvm") + domainIosTargets
val commonJvmCases = listOf(
    "actual_jar_and_system_filesystem",
    "unicode_exact_bytes_and_truncation",
    "real_directory_metadata_and_listing",
    "copy_atomic_move_and_deletion",
    "missing_source_and_directory_sink",
    "read_failure_closes_stream",
    "write_failure_closes_stream",
    "cancellation_propagates_and_closes_streams",
)
private val commonJvmApiClasses = listOf(
    "com.ehviewer.core.files.FileSystem_jvmKt",
    "com.ehviewer.core.files.Paths_jvmKt",
    "com.ehviewer.core.files.PathsKt",
)

fun checkedCommonJvmArtifact(jar: Path, evidence: Map<String, Any?>) {
    val classes = checkedJvmInventory(jar, evidence)
    require(commonJvmApiClasses.all { it.replace('.', '/') + ".class" in classes }) { "Common JAR omits an actual filesystem entry point" }
}

fun verifyCommonJvmTests(tests: Map<String, Any?>) {
    require(
        tests["status"] == "PASS" && tests["executed"] == true && tests["source_files"] == 2 && tests["tests"] == 8 &&
            tests["suites"].obj() == mapOf("GalleryTitlesTest[desktop]" to 3, "FilesystemTest[desktop]" to 5),
    ) { "All original title and real filesystem/cancellation JVM tests must execute" }
}

/** Execute the actual packaged Kotlin entry points; never substitute another filesystem implementation. */
fun commonJvmLinkProbe(jar: Path): Map<String, Any?> = URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getSystemClassLoader()).use { loader ->
    val types = commonJvmApiClasses.map(loader::loadClass)
    types.forEach { type -> require(Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath() == jar.toRealPath()) { "Common API did not load from the actual JAR" } }
    val getter = types[0].getMethod("getSystemFileSystem")
    require(getter.invoke(null) === FileSystem.SYSTEM) { "Common API does not expose the real system filesystem" }
    val read = types[1].getMethod("read", OkioPath::class.java, Function1::class.java)
    val write = types[1].getMethod("write", OkioPath::class.java, Function1::class.java)
    fun invoke(method: Method, vararg arguments: Any?): Any? = try {
        method.invoke(null, *arguments)
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
    fun pathCall(name: String, file: OkioPath): Any? = invoke(types[2].getMethod(name, OkioPath::class.java), file)
    fun readFile(file: OkioPath, action: (Source) -> Any?): Any? = invoke(read, file, action)
    fun writeFile(file: OkioPath, action: (Sink) -> Any?): Any? = invoke(write, file, action)
    fun bytes(file: OkioPath) = readFile(file) { it.readByteArray() } as ByteArray
    fun expectIoFailure(action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        require(failure is java.io.IOException) { "The actual filesystem must expose its real I/O failure" }
    }
    fun expectClosed(action: () -> Unit) {
        require(runCatching(action).exceptionOrNull() is IllegalStateException) { "The actual callback stream was left open" }
    }
    temporary { temporary ->
        val directory = temporary.resolve("JVM 资料 Cafe\u0301 📚").toString().toPath()
        pathCall("mkdirs", directory)
        require(pathCall("isDirectory", directory) == true && pathCall("exists", directory) == true)
        val file = directory.resolve("内容 📚.bin")
        val payload = ByteArray(196_733) { (it % 251).toByte() }
        writeFile(file) { it.write(payload) }
        require(bytes(file).contentEquals(payload))
        writeFile(file) { it.writeString("short 📚") }
        require(bytes(file).contentEquals("short 📚".encodeToByteArray()))
        require(pathCall("isFile", file) == true && (pathCall("metadataOrNull", file) as okio.FileMetadata).size == "short 📚".encodeToByteArray().size.toLong())
        require((pathCall("list", directory) as List<*>).toSet() == setOf(file))
        require(invoke(types[2].getMethod("find", OkioPath::class.java, String::class.java), directory, file.name) == file)
        val copy = directory.resolve("copy.bin")
        invoke(types[2].getMethod("sendTo", OkioPath::class.java, OkioPath::class.java), file, copy)
        require(bytes(copy).contentEquals(bytes(file)))
        val moved = directory.resolve("moved.bin")
        invoke(types[2].getMethod("moveTo", OkioPath::class.java, OkioPath::class.java), copy, moved)
        require(pathCall("exists", copy) == false && bytes(moved).contentEquals(bytes(file)))
        expectIoFailure { readFile(directory.resolve("missing.bin")) { it.readByteArray() } }
        expectIoFailure { writeFile(directory) { it.writeString("invalid") } }
        lateinit var failedSource: Source
        val readFailure = IllegalStateException("owned read callback fixture")
        require(
            runCatching {
                readFile(file) {
                    failedSource = it
                    throw readFailure
                }
            }.exceptionOrNull() === readFailure,
        )
        expectClosed { failedSource.readByte() }
        lateinit var failedSink: Sink
        val writeFailure = IllegalStateException("owned write callback fixture")
        require(
            runCatching {
                writeFile(file) {
                    failedSink = it
                    throw writeFailure
                }
            }.exceptionOrNull() === writeFailure,
        )
        expectClosed { failedSink.writeByte(1) }
        val cancelled = CancellationException("owned cancellation fixture")
        require(
            runCatching {
                readFile(file) {
                    failedSource = it
                    throw cancelled
                }
            }.exceptionOrNull() === cancelled,
        )
        expectClosed { failedSource.readByte() }
        require(
            runCatching {
                writeFile(file) {
                    failedSink = it
                    throw cancelled
                }
            }.exceptionOrNull() === cancelled,
        )
        expectClosed { failedSink.writeByte(1) }
        pathCall("deleteContent", directory)
        require((pathCall("list", directory) as List<*>).isEmpty())
        pathCall("delete", directory)
        require(pathCall("exists", directory) == false)
    }
    mapOf(
        "result" to "PASS", "verification_scope" to "ACTUAL_COMMON_JVM_FILESYSTEM_PROBE", "jar_sha256" to sha256(jar),
        "cases" to commonJvmCases, "case_count" to commonJvmCases.size, "actual_packaged_api_loaded" to true,
        "java_version" to System.getProperty("java.version"), "jvm_architecture" to System.getProperty("os.arch"),
        "os" to System.getProperty("os.name"), "path_transport" to "IN_PROCESS_UTF16", "owned_temporary_cleanup" to "PASS",
        "product_p0_verified" to false, "all_eleven_assets_verified" to false, "publish_enabled" to false,
    )
}

fun verifyCommonJvmProducerIdentity(shared: Map<String, Any?>, source: String) {
    require(
        shared["source_commit"] == source && shared["source_dirty"] == false && shared["schema_version"] == SHARED_SCHEMA &&
            shared["verification_scope"] == SHARED_SCOPE && shared["shared_library_artifacts_verified"] == true &&
            shared["portable_common_targets"].array() == portableCommonTargets,
    ) { "Common JVM input must be the actual clean same-source shared producer" }
}

/** Consume the real CI JAR on Linux/macOS/Windows, independent of building the Android project there. */
fun commonJvmArtifact(input: Path, output: Path): Map<String, Any?> {
    require(!output.exists() && Files.isDirectory(output.parent)) { "Use a fresh receipt in an existing owned evidence directory" }
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(!dirty) { "Actual common artifact consumption requires clean source" }
    val shared = readJson(input.resolve("shared-core.json"))
    verifyCommonJvmProducerIdentity(shared, source)
    val evidence = shared["common_jvm"].obj()
    require(evidence["artifact"] == "common-jvm.jar")
    val jar = input.resolve("shared-core/common-jvm.jar")
    checkedCommonJvmArtifact(jar, evidence)
    val tests = sharedHostTestEvidence(filesBelow(input.resolve("shared-core/tests/common-jvm"), ".xml").map(Path::readBytes), 2)
    verifyCommonJvmTests(tests)
    require(tests == evidence["host_tests"]) { "Actual common JVM XML differs from the producer" }
    val probe = commonJvmLinkProbe(jar)
    return mapOf(
        "schema_version" to 1, "source_commit" to source, "source_dirty" to false, "result" to "PASS",
        "verification_scope" to "ACTUAL_COMMON_JVM_ARTIFACT_CONSUMPTION", "jar_sha256" to sha256(jar),
        "complete_compiler_inventory_verified" to true, "original_jvm_tests" to tests, "probe" to probe,
        "native_common_targets" to domainIosTargets, "native_common_execution_here" to "NOT_EXECUTED", "product_p0_verified" to false, "all_eleven_assets_verified" to false, "publish_enabled" to false,
    ).also { Files.writeString(output, json(it), CREATE_NEW) }
}

fun verifyCommonJvmReceipt(proof: Map<String, Any?>, source: String, shared: Map<String, Any?>, os: String, architecture: String) {
    val jvm = shared["common_jvm"].obj()
    require(
        proof["schema_version"] == 1 && proof["result"] == "PASS" && proof["source_commit"] == source && proof["source_dirty"] == false &&
            proof["verification_scope"] == "ACTUAL_COMMON_JVM_ARTIFACT_CONSUMPTION" && proof["jar_sha256"] == jvm["jar_sha256"] &&
            proof["complete_compiler_inventory_verified"] == true && proof["original_jvm_tests"] == jvm["host_tests"] &&
            proof["native_common_targets"].array() == domainIosTargets && proof["native_common_execution_here"] == "NOT_EXECUTED",
    ) { "Missing actual same-source common JVM consumer" }
    val probe = proof["probe"].obj()
    require(
        probe["result"] == "PASS" && probe["verification_scope"] == "ACTUAL_COMMON_JVM_FILESYSTEM_PROBE" &&
            probe["jar_sha256"] == jvm["jar_sha256"] && probe["cases"].array() == commonJvmCases && probe["case_count"] == commonJvmCases.size &&
            probe["actual_packaged_api_loaded"] == true && probe["owned_temporary_cleanup"] == "PASS" &&
            probe["path_transport"] == "IN_PROCESS_UTF16" && probe.str("os").startsWith(os) && probe["jvm_architecture"] == architecture &&
            listOf("product_p0_verified", "all_eleven_assets_verified", "publish_enabled").all { proof[it] == false && probe[it] == false },
    ) { "Actual common filesystem runtime/cases/cleanup differ" }
}
