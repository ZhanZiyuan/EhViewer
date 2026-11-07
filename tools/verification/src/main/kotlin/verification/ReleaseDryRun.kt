package verification

import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile
import kotlin.io.path.appendText
import kotlin.io.path.exists

/** An intentionally public test identity, never an alternative production certificate. */
const val PUBLIC_RELEASE_TEST_CERTIFICATE = "760b7ad9c5287d89f74602ecd934e7faee06d00db8f8d094d075ca64178d2822"

fun requirePublicReleaseDryRun(environment: Map<String, String>, localComponentTest: Boolean = false) {
    if (environment["GITHUB_ACTIONS"] != "true") {
        require(localComponentTest && environment["RELEASE_PUBLISH"] != "true") { "Explicit local component-test mode is required outside Actions" }
        return
    }
    require(!localComponentTest) { "Local component-test mode is forbidden in Actions" }
    require(environment["GITHUB_REPOSITORY"] == "ZhanZiyuan/EhViewer" && environment["GITHUB_EVENT_NAME"] == "workflow_dispatch") { "Public test signing is restricted to this repository's manual Release dry-run" }
    val ref = environment["GITHUB_REF"]
    require(ref in setOf("refs/heads/main", "refs/heads/feature/kmp")) { "Unexpected release dry-run branch" }
    require(environment["GITHUB_WORKFLOW_REF"] == "ZhanZiyuan/EhViewer/.github/workflows/releases.yml@$ref") { "Unexpected release workflow identity" }
    require(environment["RELEASE_PUBLISH"] == "false" && environment["PUBLIC_TEST_DRY_RUN"] == "true") { "Public test signing cannot authorize publication" }
}

internal fun publicSigningFixture(): Pair<Map<String, Any?>, ByteArray> {
    val resource = object {}.javaClass.getResourceAsStream("/verification/public-release-dry-run-signing.json") ?: error("Missing public test-signing fixture")
    val fixture = resource.use { JsonSlurper().parse(it).obj() }
    val bytes = Base64.getDecoder().decode(fixture.str("keystore_base64"))
    fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    require(digest(bytes) == fixture["keystore_sha256"] && fixture["certificate_sha256"] == PUBLIC_RELEASE_TEST_CERTIFICATE && PUBLIC_RELEASE_TEST_CERTIFICATE != CERTIFICATE)
    val store = KeyStore.getInstance("JKS").apply { bytes.inputStream().use { load(it, fixture.str("password").toCharArray()) } }
    require(store.aliases().toList() == listOf(fixture.str("alias")) && store.isKeyEntry(fixture.str("alias"))) { "Unexpected public test-keystore identity" }
    require(digest(store.getCertificate(fixture.str("alias")).encoded) == PUBLIC_RELEASE_TEST_CERTIFICATE) { "Public test certificate changed" }
    return fixture to bytes
}

/** Write only the generated public fixture and a fresh, private diagnostic key under an owned root. */
fun initializePublicReleaseSigning(output: Path, localComponentTest: Boolean = false): Map<String, Any?> {
    requirePublicReleaseDryRun(System.getenv(), localComponentTest)
    require(!output.exists(NOFOLLOW_LINKS)) { "Use a fresh owned dry-run signing directory" }
    val (fixture, bytes) = publicSigningFixture()
    privateDirectory(output)
    val store = output.resolve("public-test-only.jks").toAbsolutePath()
    Files.createFile(store, PosixFilePermissions.asFileAttribute(permissions600))
    Files.write(store, bytes)
    val diagnostics = diagnosticsKey(output.resolve("diagnostics"))
    val variables = mapOf(
        "EHVIEWER_KEYSTORE" to store.toString(),
        "EHVIEWER_STORE_PASSWORD" to fixture.str("password"),
        "EHVIEWER_KEY_ALIAS" to fixture.str("alias"),
        "EHVIEWER_KEY_PASSWORD" to fixture.str("password"),
        "EHVIEWER_DRY_RUN_DIAGNOSTICS_PUBLIC_KEY" to diagnostics.str("public_key"),
        "EHVIEWER_DRY_RUN_DIAGNOSTICS_PRIVATE_KEY" to diagnostics.str("private_key"),
    )
    if (System.getenv("GITHUB_ACTIONS") == "true") {
        val target = Path.of(requireNotNull(System.getenv("GITHUB_ENV")))
        variables.forEach { (key, value) ->
            require('\n' !in value && '\r' !in value) { "Invalid Actions environment value" }
            target.appendText("$key=$value\n")
        }
    }
    return mapOf(
        "mode" to "PUBLIC_TEST_RELEASE_DRY_RUN",
        "production_signing_material_used" to false,
        "local_component_test" to localComponentTest,
        "certificate_sha256" to PUBLIC_RELEASE_TEST_CERTIFICATE,
        "keystore" to store.toString(),
        "diagnostics_public_key" to diagnostics["public_key"],
        "diagnostics_private_key_retained_only_in_owned_directory" to true,
        "publish_enabled" to false,
    )
}

fun requirePublicReleaseCertificate(proofs: List<Map<String, Any?>>) {
    require(proofs.isNotEmpty() && proofs.all { it["certificate"] == listOf(PUBLIC_RELEASE_TEST_CERTIFICATE) }) { "Dry-run APKs must use exactly the public test identity" }
}

/** Authenticate the actual encrypted bundle, then compare every retained plaintext byte. */
fun verifyDryRunDiagnostics(encrypted: Path, privateKey: Path, original: Path, localComponentTest: Boolean = false): Map<String, Any?> = temporary { temp ->
    requirePublicReleaseDryRun(System.getenv(), localComponentTest)
    val decrypted = temp.resolve("diagnostics.zip")
    decryptDiagnostics(encrypted, privateKey, decrypted)
    val names = setOf("mapping.txt", "native-debug-symbols.zip", "manifest.json")
    val digests = ZipFile(decrypted.toFile()).use { zip ->
        val entries = zip.entries().toList()
        require(entries.size == names.size && entries.map { it.name }.toSet() == names && entries.none { it.isDirectory }) { "Unexpected decrypted diagnostic entries" }
        entries.associate { entry ->
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == sha256(original.resolve(entry.name))) { "Decrypted diagnostic differs: ${entry.name}" }
            entry.name to actual
        }
    }
    mapOf("result" to "PASS", "encrypted_sha256" to sha256(encrypted), "decrypted_original_sha256" to digests, "plaintext_uploaded" to false)
}
