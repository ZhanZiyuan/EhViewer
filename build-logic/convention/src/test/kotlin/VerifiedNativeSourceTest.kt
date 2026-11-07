import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedNativeSourceTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun server(reply: (Int) -> Pair<Int, ByteArray>, test: (String, AtomicInteger, File) -> Unit) {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val root = Files.createTempDirectory("owned-native-source-").toFile()
        server.createContext("/source") { exchange ->
            val (status, bytes) = reply(calls.incrementAndGet())
            try {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            test("http://127.0.0.1:${server.address.port}/source", calls, root)
        } finally {
            server.stop(0)
            check(root.deleteRecursively())
        }
    }

    @Test fun transientResponsesRetryButOnlyTheExactDecodedSourceReachesDiskAndCacheAvoidsAnotherRequest() {
        val source = "Owned fixed JNI source\n".toByteArray()
        server({ attempt -> if (attempt < 3) (if (attempt == 1) 503 else 429) to "Owned temporary failure".toByteArray() else 200 to Base64.getMimeEncoder().encode(source) }) { url, calls, root ->
            val reports = mutableListOf<String>()
            val file = root.resolve("source.cpp")
            verifiedNativeSource(file, url, sha(source), 1024, true, reports::add)
            assertArrayEquals(source, file.readBytes())
            assertEquals(3, calls.get())
            assertEquals(listOf("Pinned source source.cpp: HTTP 503 (attempt 1/3)", "Pinned source source.cpp: HTTP 429 (attempt 2/3)"), reports)
            verifiedNativeSource(file, url, sha(source), 1024, true, reports::add)
            assertEquals(3, calls.get())
        }
    }

    @Test fun persistentTransientFailureStopsAfterThreeRequestsAndPreservesExistingBytes() = server({ 503 to "not logged private sentinel".toByteArray() }) { url, calls, root ->
        val file = root.resolve("source.cpp").apply { writeText("Original invalid cache") }
        val before = file.readBytes()
        val reports = mutableListOf<String>()
        val failure = assertThrows(IOException::class.java) { verifiedNativeSource(file, url, sha("expected".toByteArray()), 1024, report = reports::add) }
        assertEquals(3, calls.get())
        assertEquals(3, reports.size)
        assertFalse(failure.toString().contains("sentinel"))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun authorizationFailureIsNotRetriedAndNeverWritesTheResponse() = server({ 403 to "forbidden".toByteArray() }) { url, calls, root ->
        val file = root.resolve("source.cpp")
        val reports = mutableListOf<String>()
        assertThrows(IOException::class.java) { verifiedNativeSource(file, url, sha("expected".toByteArray()), 1024, report = reports::add) }
        assertEquals(1, calls.get())
        assertEquals(1, reports.size)
        assertFalse(file.exists())
    }

    @Test fun checksumMismatchAndOversizedTransportFailOnceWithoutOverwritingExistingBytes() {
        for (limit in listOf(4, 1024)) {
            server({ 200 to "incorrect source bytes".toByteArray() }) { url, calls, root ->
                val file = root.resolve("source.cpp").apply { writeText("Original invalid cache") }
                val before = file.readBytes()
                val reports = mutableListOf<String>()
                assertThrows(IllegalStateException::class.java) { verifiedNativeSource(file, url, sha("expected".toByteArray()), limit, report = reports::add) }
                assertEquals(1, calls.get())
                assertTrue(reports.isEmpty())
                assertArrayEquals(before, file.readBytes())
            }
        }
    }

    @Test fun rawPinnedBytesAreNotBase64DecodedAndInvalidEncodingDoesNotRetry() {
        val bytes = byteArrayOf(0, 1, 2, 3, -1)
        server({ 200 to bytes }) { url, calls, root ->
            val file = root.resolve("source.zip")
            verifiedNativeSource(file, url, sha(bytes), 1024, report = {})
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(1, calls.get())
        }
        server({ 200 to "Z".toByteArray() }) { url, calls, root ->
            val file = root.resolve("source.cpp")
            assertThrows(IllegalArgumentException::class.java) { verifiedNativeSource(file, url, sha(bytes), 1024, true, report = {}) }
            assertEquals(1, calls.get())
            assertFalse(file.exists())
        }
    }
}
