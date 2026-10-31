package verification

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlin.io.path.exists

/** GitHub transport evidence only; it cannot establish product package or native execution acceptance. */
fun inspectArtifactArchive(catalog: Map<String, Any?>, archive: Path, artifactId: Long, runId: Long, headSha: String, output: Path): Map<String, Any?> {
    require(artifactId > 0 && runId > 0 && Regex("[0-9a-f]{40}").matches(headSha) && headSha != "0".repeat(40)) { "Expected real artifact/run identities and a full nonzero head SHA" }
    val artifacts = catalog["artifacts"].array().map(Any?::obj)
    require((catalog["total_count"] as Number).toLong() == artifacts.size.toLong()) { "Artifact catalog is incomplete; fetch every page before inspection" }
    val ids = artifacts.map { (it["id"] as Number).toLong() }
    require(ids.all { it > 0 } && ids.size == ids.toSet().size && artifacts.map { it.str("name") }.toSet().size == artifacts.size) { "Duplicate or invalid artifact identity/name" }
    val artifact = artifacts.single { (it["id"] as Number).toLong() == artifactId }
    val run = artifact["workflow_run"].obj()
    require((run["id"] as Number).toLong() == runId && run["head_sha"] == headSha && artifact["expired"] == false) { "Artifact belongs to a different run/head or has expired" }
    val name = artifact.str("name")
    require(name.isNotBlank() && name.length <= 256 && name.none { it.code < 32 || it.code == 127 || it in "/\\:" } && name !in setOf(".", "..")) { "Unsafe artifact name" }
    val expectedSha = artifact.str("digest")
    require(Regex("sha256:[0-9a-f]{64}").matches(expectedSha)) { "An official SHA-256 artifact digest is required" }
    val bytes = (artifact["size_in_bytes"] as Number).toLong()
    require(bytes in 1..(1024L * 1024 * 1024) && Files.size(archive) == bytes && sha256(archive) == expectedSha.removePrefix("sha256:")) { "Archive differs from the official artifact size/digest" }
    val requested = output.toAbsolutePath().normalize()
    val destination = requireNotNull(requested.parent).toRealPath().resolve(requested.fileName)
    require(!destination.exists(NOFOLLOW_LINKS) && !archive.toRealPath().startsWith(destination)) { "Use a fresh owned extraction directory separate from the input" }
    val inventory = ZipFile(archive.toFile()).use { zip ->
        val entries = zip.entries().asSequence().toList()
        require(entries.any { !it.isDirectory } && entries.size <= 50_000) { "Empty or oversized artifact entry set" }
        val names = entries.map { stageZipName(if (it.isDirectory) it.name.removeSuffix("/") else it.name) }
        require(names.map { it.lowercase(Locale.ROOT) }.toSet().size == names.size) { "Duplicate or case-colliding artifact paths" }
        val files = entries.filterNot { it.isDirectory }.map { it.name.lowercase(Locale.ROOT) }.toSet()
        names.forEach { name ->
            var parent = name.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent.lowercase(Locale.ROOT) !in files) { "Artifact file collides with a parent directory" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        require(entries.all { it.size in 0..(512L * 1024 * 1024) } && entries.sumOf { it.size } <= 2L * 1024 * 1024 * 1024) { "Artifact expansion exceeds its inspected bounds" }
        // Validate every path and declared bound before creating any output. Never interpret archive link attributes.
        Files.createDirectory(destination)
        entries.filter { it.isDirectory }.forEach { Files.createDirectories(destination.resolve(it.name.removeSuffix("/"))) }
        entries.filterNot { it.isDirectory }.map { entry ->
            val file = destination.resolve(entry.name).normalize()
            require(file.startsWith(destination)) { "Artifact entry escapes its owned directory" }
            Files.createDirectories(file.parent)
            val digest = MessageDigest.getInstance("SHA-256")
            val crc = CRC32()
            var count = 0L
            Files.newOutputStream(file, CREATE_NEW).use { target ->
                zip.getInputStream(entry).use { source ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= entry.size) { "Artifact entry exceeds its declared size" }
                        digest.update(buffer, 0, read)
                        crc.update(buffer, 0, read)
                        target.write(buffer, 0, read)
                    }
                }
            }
            require(count == entry.size && crc.value == entry.crc) { "Artifact entry size/CRC mismatch" }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            require(Files.size(file) == count && sha256(file) == actualSha) { "Extracted artifact bytes changed" }
            mapOf("path" to entry.name, "bytes" to count, "sha256" to actualSha)
        }.sortedBy { it.getValue("path").toString() }
    }
    require(sha256(archive) == expectedSha.removePrefix("sha256:")) { "Inspection changed its source archive" }
    return mapOf(
        "schema_version" to 1, "verification_scope" to "GITHUB_ARTIFACT_TRANSPORT_ONLY", "artifact_id" to artifactId, "artifact_name" to name,
        "run_id" to runId, "run_head_sha" to headSha, "archive_sha256" to expectedSha.removePrefix("sha256:"), "archive_bytes" to bytes,
        "payload_file_count" to inventory.size, "files" to inventory, "result" to "PASS",
        "executable_permissions" to "NOT_PRESERVED_BY_THIS_BYTE_INSPECTOR", "product_package_validation" to "NOT_EXECUTED", "native_execution" to "NOT_EXECUTED",
        "product_p0_verified" to false, "all_eleven_assets_verified" to false, "publish_enabled" to false,
    )
}
