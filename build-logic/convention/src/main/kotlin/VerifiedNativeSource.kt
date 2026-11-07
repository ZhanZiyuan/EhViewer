import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/** Retry only transient HTTP responses for an immutable hash-pinned build input. */
internal fun verifiedNativeSource(file: File, url: String, expected: String, limit: Int, base64: Boolean = false, report: (String) -> Unit): File {
    require(limit in 1 until Int.MAX_VALUE)
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    if (file.isFile && digest(file.readBytes()) == expected) return file
    for (attempt in 1..3) {
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        try {
            if (connection is HttpURLConnection) {
                val status = connection.responseCode
                if (status !in 200..299) {
                    // Never retain headers/body/credentials; the fixed input filename and status suffice.
                    report("Pinned source ${file.name}: HTTP $status (attempt $attempt/3)")
                    if (status in setOf(408, 429, 500, 502, 503, 504) && attempt < 3) {
                        connection.disconnect()
                        Thread.sleep(250L * attempt)
                        continue
                    }
                    throw IOException("Pinned source request failed: HTTP $status")
                }
            }
            val transport = connection.getInputStream().use { it.readNBytes(limit + 1) }
            check(transport.size <= limit) { "Pinned source download exceeded its limit" }
            val bytes = if (base64) Base64.getMimeDecoder().decode(transport) else transport
            check(digest(bytes) == expected) { "Pinned third-party source checksum mismatch" }
            file.writeBytes(bytes)
            return file
        } finally {
            if (connection is HttpURLConnection) connection.disconnect()
        }
    }
    error("Pinned source attempts exhausted")
}
