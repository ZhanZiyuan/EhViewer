package verification

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText

private const val CORE_ENTRY_LIMIT = 8 * 1024 * 1024
private fun coreDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun coreArchive(bytes: ByteArray): Map<String, ByteArray> {
    require(bytes.size <= 64 * 1024 * 1024) { "Shared archive exceeds the inspection limit" }
    val entries = linkedMapOf<String, ByteArray>()
    var total = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && ':' !in name && name.removeSuffix("/").split('/').all { it.isNotEmpty() && it !in setOf(".", "..") }) { "Noncanonical shared archive entry" }
            require(name !in entries) { "Duplicate shared archive entry" }
            val content = zip.readNBytes(CORE_ENTRY_LIMIT + 1)
            total += content.size
            require(content.size <= CORE_ENTRY_LIMIT && total <= 64 * 1024 * 1024) { "Shared archive expands beyond the inspection limit" }
            entries[name] = content
        }
    }
    return entries
}

private fun coreXml(bytes: ByteArray, tag: String) = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
    isXIncludeAware = false
    isExpandEntityReferences = false
    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
}.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement.also {
    require(it.tagName == tag) { "Unexpected shared evidence XML root" }
}

private fun coreClass(bytes: ByteArray) {
    require(bytes.size >= 8 && ByteBuffer.wrap(bytes).getInt(0) == 0xcafebabe.toInt() && ByteBuffer.wrap(bytes).getShort(6).toInt() == 61) { "Expected a real Java 17 shared class" }
}

/** Inspect the actual AAR and bind every packaged class to the actual compiler output. */
fun sharedAarEvidence(aar: Path, compiled: Path, namespace: String, resourcesRequired: Boolean): Map<String, Any?> {
    val classes = filesBelow(compiled, ".class").sorted().associate { file ->
        require(Files.size(file) <= CORE_ENTRY_LIMIT) { "Shared class exceeds the inspection limit" }
        val bytes = file.readBytes()
        coreClass(bytes)
        compiled.relativize(file).joinToString("/") to coreDigest(bytes)
    }
    require(classes.isNotEmpty()) { "Missing actual shared compiler output" }
    require(Files.size(aar) <= 64 * 1024 * 1024) { "Shared AAR exceeds the inspection limit" }
    val archive = coreArchive(aar.readBytes())
    val manifest = coreXml(archive["AndroidManifest.xml"] ?: error("Missing AAR manifest"), "manifest")
    require(manifest.getAttribute("package") == namespace) { "Wrong shared library namespace" }
    val jar = archive["classes.jar"] ?: error("Missing real shared classes.jar")
    val packaged = coreArchive(jar).filterKeys { it.endsWith(".class") }.mapValues { (_, bytes) ->
        coreClass(bytes)
        coreDigest(bytes)
    }
    require(packaged == classes) { "AAR class set or bytes differ from actual compiler output" }
    val resources = archive.filterKeys { it.startsWith("res/") && it.endsWith(".xml") }.mapValues { (name, bytes) ->
        if (name.startsWith("res/values")) coreXml(bytes, "resources")
        coreDigest(bytes)
    }
    require(!resourcesRequired || "res/values/values.xml" in resources) { "Missing packaged shared localization resources" }
    return mapOf(
        "aar_sha256" to sha256(aar), "classes_jar_sha256" to coreDigest(jar), "namespace" to namespace,
        "android_class_major" to 61, "compiled_class_files" to classes, "resources" to resources,
        "compiled_class_count" to classes.size, "resource_xml_count" to resources.size, "result" to "PASS",
    )
}

/** NO_TEST_SOURCES is recorded distinctly; zero, failed, skipped or inconsistent tests cannot pass. */
fun sharedHostTestEvidence(xml: List<ByteArray>, sourceFiles: Int): Map<String, Any?> {
    require(sourceFiles >= 0)
    if (sourceFiles == 0) {
        require(xml.isEmpty()) { "Unexpected test receipt without test sources" }
        return mapOf("status" to "NO_TEST_SOURCES", "executed" to false, "source_files" to 0, "tests" to 0)
    }
    require(xml.isNotEmpty()) { "Missing actual shared host test receipts" }
    val suites = xml.map { bytes ->
        require(bytes.size <= CORE_ENTRY_LIMIT)
        val suite = coreXml(bytes, "testsuite")
        val tests = suite.getAttribute("tests").toIntOrNull()
        require(tests != null && tests > 0 && suite.getElementsByTagName("testcase").length == tests) { "Empty or inconsistent shared test receipt" }
        listOf("failures", "errors", "skipped").forEach { key ->
            require(suite.getAttribute(key).toIntOrNull() == 0) { "Shared tests failed, errored or skipped" }
        }
        listOf("failure", "error", "skipped", "testsuite").forEach { tag ->
            require(suite.getElementsByTagName(tag).length == 0) { "Unexpected unsuccessful or nested shared test result" }
        }
        suite.getAttribute("name") to tests
    }
    require(suites.map { it.first }.toSet().size == suites.size && suites.all { it.first.isNotBlank() }) { "Missing or duplicate shared test suite identity" }
    return mapOf("status" to "PASS", "executed" to true, "source_files" to sourceFiles, "tests" to suites.sumOf { it.second }, "suites" to suites.toMap())
}

fun verifySharedCore(output: Path): Map<String, Any?> {
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires a clean source tree" }
    val architecture = verifyArchitecture()
    require(architecture["shared_core_android_only"] == true)
    val delivery = releaseContract(root.resolve("app/build.gradle.kts").readText(), source)
    require(!output.exists()) { "Refusing to overwrite shared evidence" }
    Files.createDirectories(output)
    val modules = listOf("common", "data", "i18n", "ui").associateWith { module ->
        val directory = root.resolve("core/$module")
        val aar = filesBelow(directory.resolve("build/outputs/aar"), ".aar").single()
        val evidence = sharedAarEvidence(aar, directory.resolve("build/classes/kotlin/android/main"), "com.ehviewer.core.$module", module == "i18n")
        val testSources = filesBelow(directory.resolve("src"), ".kt").filter { directory.resolve("src").relativize(it).getName(0).toString().endsWith("Test") }
        require(testSources.all { directory.resolve("src").relativize(it).getName(0).toString() in setOf("commonTest", "jvmTest", "androidHostTest") }) { "A new shared test source set needs explicit stage integration" }
        val testFiles = filesBelow(directory.resolve("build/test-results/testAndroidHostTest"), ".xml")
        val tests = sharedHostTestEvidence(testFiles.map { it.readBytes() }, testSources.size)
        Files.copy(aar, output.resolve("$module.aar"))
        testFiles.forEach { file ->
            val target = output.resolve("tests/$module/${file.fileName}")
            Files.createDirectories(target.parent)
            Files.copy(file, target)
        }
        evidence + mapOf("artifact" to "$module.aar", "host_tests" to tests)
    }
    return mapOf(
        "schema_version" to 1, "verification_scope" to "CURRENT_ANDROID_SHARED_CORE", "source_commit" to source, "source_dirty" to dirty,
        "version" to delivery["version"], "android_version_code" to delivery["android_version_code"], "targets" to listOf("android"),
        "modules" to modules, "retained_architecture_checks" to architecture, "shared_library_artifacts_verified" to true,
        "desktop_ios_shared_targets" to "NOT_INTEGRATED", "final_delivery_assets_verified" to false, "product_p0_verified" to false, "publish_enabled" to false,
    )
}
