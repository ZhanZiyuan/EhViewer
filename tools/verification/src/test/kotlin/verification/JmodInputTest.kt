package verification

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class JmodInputTest {
    private fun fixture(archive: Path, payload: ByteArray) {
        Files.newOutputStream(archive).use { output ->
            output.write(byteArrayOf(0x4a, 0x4d, 0x01, 0x00))
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("classes/java/lang/Object.class"))
                zip.write(payload)
                zip.closeEntry()
            }
        }
    }

    @Test
    fun readsThePrefixedBinaryPayloadWithoutExtractingFiles() = temporary { directory ->
        val bootstrap = Any::class.java.getResourceAsStream("Object.class")!!.use { it.readBytes() }
        val archive = directory.resolve("input.jmod")
        fixture(archive, bootstrap)
        val expected = MessageDigest.getInstance("SHA-256").digest(bootstrap).joinToString("") { "%02x".format(it) }
        assertEquals(expected, jmodEntrySha256(archive, "classes/java/lang/Object.class"))
        assertFalse(Files.exists(directory.resolve("classes")))
    }

    @Test
    fun rejectsWrongArchiveMissingEntriesAndNoncanonicalNames() = temporary { directory ->
        val archive = directory.resolve("input.jmod")
        fixture(archive, byteArrayOf(0, 1, -1, -128))
        val wrong = directory.resolve("not-a-jmod").also { it.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) }
        assertThrows(IllegalArgumentException::class.java) { jmodEntrySha256(wrong, "classes/java/lang/Object.class") }
        assertThrows(IllegalArgumentException::class.java) { jmodEntrySha256(archive, "lib/no-such-jvm.dll") }
        for (entry in listOf("../jvm.dll", "/lib/server/jvm.dll", "lib/./server/jvm.dll", "lib//server/jvm.dll", "C:/jvm.dll", "lib\\server\\jvm.dll")) {
            assertThrows(IllegalArgumentException::class.java) { jmodEntrySha256(archive, entry) }
        }
    }
}
