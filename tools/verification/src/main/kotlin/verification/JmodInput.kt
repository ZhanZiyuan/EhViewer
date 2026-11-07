package verification

import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.inputStream

/** Bind jlink's copied input to the actual JMOD payload, not the installed JDK's DLL. */
fun jmodEntrySha256(archive: Path, entry: String): String {
    require(entry.isNotBlank() && !entry.startsWith("/") && !entry.contains('\\') && entry.split('/').none { it.isEmpty() || it == "." || it == ".." } && !entry.contains(':')) { "Expected an exact relative JMOD entry" }
    val magic = archive.inputStream().use { it.readNBytes(4) }
    require(magic.contentEquals(byteArrayOf(0x4a, 0x4d, 0x01, 0x00))) { "Expected a version 1.0 JMOD archive" }
    return ZipFile(archive.toFile()).use { zip ->
        val matches = zip.entries().asSequence().filter { it.name == entry }.toList()
        require(matches.size == 1 && !matches.single().isDirectory) { "Missing or duplicate JMOD input: $entry" }
        val input = matches.single()
        val limit = 128L * 1024 * 1024
        require(input.size in 1..limit) { "Unexpected JMOD input size" }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        zip.getInputStream(input).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "JMOD input exceeds the verification budget" }
                digest.update(buffer, 0, count)
            }
        }
        require(total == input.size) { "Truncated JMOD input" }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
