package verification

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class DiagnosticsTest {
    @Test fun authenticatedRoundTripAndFailureDoNotRetainPlaintext() = temporary { temp ->
        val keys = temp.resolve("keys")
        diagnosticsKey(keys)
        val other = temp.resolve("other")
        diagnosticsKey(other)
        val clear = temp.resolve("clear").apply { writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() }) }
        val encrypted = temp.resolve("diagnostics.enc")
        encryptFile(clear, keys.resolve("public.pem"), encrypted)
        val retained = temp.resolve("retained")
        val output = retained.resolve("diagnostics.zip")
        decryptDiagnostics(encrypted, keys.resolve("private.pem"), output)
        assertArrayEquals(clear.readBytes(), output.readBytes())
        assertEquals(permissions600, output.getPosixFilePermissions())
        assertEquals(permissions600, keys.resolve("private.pem").getPosixFilePermissions())
        assertNotNull(runCatching { diagnosticsKey(keys) }.exceptionOrNull())
        assertNotNull(runCatching { encryptFile(clear, keys.resolve("public.pem"), encrypted) }.exceptionOrNull())
        val original = encrypted.readBytes()
        val corrupted = original.clone().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        val malformed = original.clone().apply { this[0] = 0 }
        listOf(original, corrupted, original.copyOf(20), malformed).forEachIndexed { index, bytes ->
            val probe = temp.resolve("probe-$index.enc").apply { writeBytes(bytes) }
            val rejected = retained.resolve("failed-$index.zip")
            val private = (if (index == 0) other else keys).resolve("private.pem")
            assertNotNull(runCatching { decryptDiagnostics(probe, private, rejected) }.exceptionOrNull())
            assertFalse(rejected.exists())
        }
    }

    @Test fun rejectsWeakPublicKeyBeforeWritingCiphertext() = temporary { temp ->
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val public = temp.resolve("weak.pem").apply {
            writeText("-----BEGIN PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(pair.public.encoded)}\n-----END PUBLIC KEY-----\n")
        }
        val clear = temp.resolve("input").apply { writeText("diagnostic") }
        val output = temp.resolve("output.enc")
        assertNotNull(runCatching { encryptFile(clear, public, output) }.exceptionOrNull())
        assertFalse(Files.exists(output))
    }
}
