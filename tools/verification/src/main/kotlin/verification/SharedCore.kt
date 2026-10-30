package verification

import java.io.ByteArrayInputStream
import java.net.URLClassLoader
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
val sharedArtifactModules = listOf("common", "domain", "data", "i18n", "ui", "network", "shared-ui")
const val SHARED_SCHEMA = 5
const val SHARED_SCOPE = "ANDROID_CORE_AND_PORTABLE_COMMON_DOMAIN_NETWORK_UI_JVM"
private fun coreDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun coreArchive(bytes: ByteArray): Map<String, ByteArray> {
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

/** Match the complete Java 17 class set to the actual JVM compiler output. */
fun sharedJvmEvidence(jar: Path, compiled: Path): Map<String, Any?> {
    val classes = filesBelow(compiled, ".class").sorted().associate { file ->
        val bytes = file.readBytes()
        require(bytes.size <= CORE_ENTRY_LIMIT)
        coreClass(bytes)
        compiled.relativize(file).joinToString("/") to coreDigest(bytes)
    }
    require(classes.isNotEmpty() && Files.size(jar) <= 64 * 1024 * 1024) { "Missing actual JVM library/compiler output" }
    val packaged = coreArchive(jar.readBytes()).filterKeys { it.endsWith(".class") }.mapValues { (_, bytes) ->
        coreClass(bytes)
        coreDigest(bytes)
    }
    require(packaged == classes) { "JVM JAR class set or bytes differ from actual compiler output" }
    return mapOf("jar_sha256" to sha256(jar), "class_major" to 61, "compiled_class_files" to classes, "compiled_class_count" to classes.size, "result" to "PASS")
}

/** Recheck all packaged JVM bytes and model identities from the independently retained compiler inventory. */
fun checkedJvmInventory(jar: Path, evidence: Map<String, Any?>): Map<String, String> {
    require(evidence["result"] == "PASS" && evidence["class_major"] == 61 && sha256(jar) == evidence["jar_sha256"])
    val classes = coreArchive(jar.readBytes()).filterKeys { it.endsWith(".class") }.mapValues { (_, bytes) ->
        coreClass(bytes)
        coreDigest(bytes)
    }
    require(classes == evidence["compiled_class_files"] && classes.size == evidence["compiled_class_count"]) { "Actual domain JAR differs from complete compiler inventory" }
    return classes
}

fun checkedDomainJvmArtifact(jar: Path, evidence: Map<String, Any?>) {
    val classes = checkedJvmInventory(jar, evidence)
    require(domainModelTypes.all { "com/ehviewer/core/model/$it.class" in classes }) { "Actual domain JAR omits a gallery model" }
    require(listOf("com/ehviewer/core/domain/GalleryTitlesKt.class", "com/ehviewer/core/domain/reader/ArchiveCatalog.class").all(classes::containsKey))
}

/** Load the real production JAR and exercise its public API, preserving the Android reader port. */
fun domainJvmLinkProbe(jar: Path): Map<String, Any?> = URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getSystemClassLoader()).use { loader ->
    val titles = loader.loadClass("com.ehviewer.core.domain.GalleryTitlesKt")
    val catalog = loader.loadClass("com.ehviewer.core.domain.reader.ArchiveCatalog")
    for (type in listOf(titles, catalog)) require(Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath() == jar.toRealPath()) { "Domain API was not loaded from the actual packaged JAR" }
    val select = titles.getMethod("selectGalleryTitle", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
    val cases = listOf(
        listOf("English", "日本語 📚", false, "English"),
        listOf("English", "日本語 📚", true, "日本語 📚"),
        listOf(null, "日本語", false, "日本語"),
        listOf("English", "", true, "English"),
        listOf(null, null, false, ""),
        listOf("\t", "日本語", false, "\t"),
        listOf("Cafe\u0301", null, false, "Cafe\u0301"),
    )
    cases.forEach { row -> require(select.invoke(null, row[0], row[1], row[2]) == row[3]) { "Packaged domain title behavior changed" } }
    val methods = catalog.declaredMethods.map { method -> method.name + "(" + method.parameterTypes.joinToString(",") { it.name } + "):" + method.returnType.name }.toSet()
    require(catalog.isInterface && methods == setOf("getPageCount():int", "getNeedsPassword():boolean", "extension(int):java.lang.String", "providePassword(java.lang.String):boolean")) { "Existing archive catalog API changed" }
    mapOf("result" to "PASS", "title_cases" to cases.size, "archive_methods" to methods.sorted(), "jvm_version" to System.getProperty("java.version"), "jvm_architecture" to System.getProperty("os.arch"))
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

/** Recheck both host receipts against the same-source inventory, rather than a past slice's file count. */
fun verifyProductTestReceipts(shared: Map<String, Any?>, artifacts: Path, repository: Path = root) {
    val products = shared["product_jvm"].obj()
    require(products.keys == setOf("network", "shared-ui"))
    for ((name, value) in products) {
        val module = if (name == "network") "core/network" else "app/shared"
        val sources = filesBelow(repository.resolve("$module/src/commonTest"), ".kt").size
        val jvm = sharedHostTestEvidence(filesBelow(artifacts.resolve("tests/$name-jvm"), ".xml").map(Path::readBytes), sources)
        val android = sharedHostTestEvidence(filesBelow(artifacts.resolve("tests/$name"), ".xml").map(Path::readBytes), sources)
        require(jvm == value.obj()["host_tests"] && android == shared["modules"].obj().getValue(name).obj()["host_tests"]) { "Actual product test receipts differ from producer evidence or same-source tests" }
        for (tests in listOf(jvm, android)) {
            if (name == "network") {
                verifyNetworkTests(tests)
            } else {
                val suites = tests["suites"].obj().mapKeys { it.key.substringAfterLast('.').substringBefore('[') }
                require((suites["GalleryImageLimitsTest"] as? Number)?.toInt()?.let { it >= 2 } == true) { "Shared image allocation limits were not tested" }
            }
        }
    }
}

fun verifySharedCore(output: Path): Map<String, Any?> {
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires a clean source tree" }
    val architecture = verifyArchitecture()
    require(architecture["legacy_shared_core_android_only"] == true && architecture["portable_domain_targets"] == portableDomainTargets)
    require(architecture["portable_common_targets"] == portableCommonTargets && architecture["legacy_shared_modules"] == listOf("data", "i18n", "ui"))
    val delivery = releaseContract(root.resolve("app/build.gradle.kts").readText(), source)
    require(!output.exists()) { "Refusing to overwrite shared evidence" }
    Files.createDirectories(output)
    val modules = sharedArtifactModules.associateWith { module ->
        val sharedUi = module == "shared-ui"
        val directory = root.resolve(if (sharedUi) "app/shared" else "core/$module")
        val aar = filesBelow(directory.resolve("build/outputs/aar"), ".aar").single()
        val evidence = sharedAarEvidence(aar, directory.resolve("build/classes/kotlin/android/main"), if (sharedUi) "com.ehviewer.app.shared" else "com.ehviewer.core.$module", module == "i18n")
        val testSources = filesBelow(directory.resolve("src"), ".kt").filter { directory.resolve("src").relativize(it).getName(0).toString().endsWith("Test") }
        require(testSources.all { directory.resolve("src").relativize(it).getName(0).toString() in setOf("commonTest", "jvmTest", "androidHostTest") || module == "common" && directory.resolve("src").relativize(it).getName(0).toString() == "nonAndroidTest" }) { "A new shared test source set needs explicit stage integration" }
        val testFiles = filesBelow(directory.resolve("build/test-results/testAndroidHostTest"), ".xml")
        val androidSources = testSources.filter { directory.resolve("src").relativize(it).getName(0).toString() != "nonAndroidTest" }
        val tests = sharedHostTestEvidence(testFiles.map { it.readBytes() }, androidSources.size)
        if (module == "domain") verifyDomainTests(tests)
        if (module == "network") verifyNetworkTests(tests)
        Files.copy(aar, output.resolve("$module.aar"))
        testFiles.forEach { file ->
            val target = output.resolve("tests/$module/${file.fileName}")
            Files.createDirectories(target.parent)
            Files.copy(file, target)
        }
        evidence + mapOf("artifact" to "$module.aar", "host_tests" to tests)
    }
    val domain = root.resolve("core/domain")
    val jar = domain.resolve("build/libs/domain-jvm.jar")
    val jvm = sharedJvmEvidence(jar, domain.resolve("build/classes/kotlin/desktop/main"))
    val jvmTests = filesBelow(domain.resolve("build/test-results/desktopTest"), ".xml")
    val jvmEvidence = sharedHostTestEvidence(jvmTests.map { it.readBytes() }, filesBelow(domain.resolve("src/commonTest"), ".kt").size)
    verifyDomainTests(jvmEvidence, jvm = true)
    checkedDomainJvmArtifact(jar, jvm)
    val probe = domainJvmLinkProbe(jar)
    Files.copy(jar, output.resolve("domain-jvm.jar"))
    jvmTests.forEach { file ->
        val target = output.resolve("tests/domain-jvm/${file.fileName}")
        Files.createDirectories(target.parent)
        Files.copy(file, target)
    }
    val common = root.resolve("core/common")
    val commonJar = common.resolve("build/libs/common-jvm.jar")
    val commonJvm = sharedJvmEvidence(commonJar, common.resolve("build/classes/kotlin/desktop/main"))
    checkedCommonJvmArtifact(commonJar, commonJvm)
    val commonXml = filesBelow(common.resolve("build/test-results/desktopTest"), ".xml")
    val commonTests = sharedHostTestEvidence(commonXml.map(Path::readBytes), filesBelow(common.resolve("src/commonTest"), ".kt").size + filesBelow(common.resolve("src/nonAndroidTest"), ".kt").size)
    verifyCommonJvmTests(commonTests)
    val commonProbe = commonJvmLinkProbe(commonJar)
    Files.copy(commonJar, output.resolve("common-jvm.jar"))
    commonXml.forEach { file ->
        val target = output.resolve("tests/common-jvm/${file.fileName}")
        Files.createDirectories(target.parent)
        Files.copy(file, target)
    }
    val productJvm = mapOf("network" to "core/network", "shared-ui" to "app/shared").map { (name, path) ->
        val directory = root.resolve(path)
        val artifact = filesBelow(directory.resolve("build/libs"), "-jvm.jar").single()
        val evidence = sharedJvmEvidence(artifact, directory.resolve("build/classes/kotlin/desktop/main"))
        val sources = filesBelow(directory.resolve("src/commonTest"), ".kt")
        val xml = filesBelow(directory.resolve("build/test-results/desktopTest"), ".xml")
        val tests = sharedHostTestEvidence(xml.map(Path::readBytes), sources.size)
        if (name == "network") verifyNetworkTests(tests)
        Files.copy(artifact, output.resolve("$name-jvm.jar"))
        xml.forEach { file ->
            val target = output.resolve("tests/$name-jvm/${file.fileName}")
            Files.createDirectories(target.parent)
            Files.copy(file, target)
        }
        name to (evidence + mapOf("artifact" to "$name-jvm.jar", "host_tests" to tests))
    }.toMap()
    return mapOf(
        "schema_version" to SHARED_SCHEMA, "verification_scope" to SHARED_SCOPE, "source_commit" to source, "source_dirty" to dirty,
        "version" to delivery["version"], "android_version_code" to delivery["android_version_code"], "targets" to listOf("android"),
        "modules" to modules, "retained_architecture_checks" to architecture, "shared_library_artifacts_verified" to true,
        "domain_jvm" to jvm + mapOf("artifact" to "domain-jvm.jar", "host_tests" to jvmEvidence, "link_probe" to probe),
        "common_jvm" to commonJvm + mapOf("artifact" to "common-jvm.jar", "host_tests" to commonTests, "link_probe" to commonProbe),
        "product_jvm" to productJvm,
        "portable_common_targets" to portableCommonTargets,
        "legacy_shared_modules" to listOf("data", "i18n", "ui"),
        "portable_domain_targets" to listOf("android", "jvm"), "domain_ios_targets" to domainIosTargets,
        "domain_ios_evidence" to "SEPARATE_REQUIRED_CI_IOS_JOB", "ios_product_domain_integration" to "GALLERY_SEARCH",
        "desktop_product_domain_integration" to "GALLERY_SEARCH",
        "legacy_desktop_ios_shared_targets" to "NOT_INTEGRATED", "final_delivery_assets_verified" to false, "product_p0_verified" to false, "publish_enabled" to false,
    ).also { verifyProductTestReceipts(it, output) }
}
