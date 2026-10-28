package verification

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class WindowsJdkTest {
    @Test
    fun extractionPreservesFilesRejectsEscapesAndNeverOverwritesAnExistingDirectory() = temporary { directory ->
        fun archive(name: String, entry: String) = directory.resolve(name).also { path ->
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write("vendor fixture".toByteArray())
                zip.closeEntry()
            }
        }
        val valid = archive("valid.zip", "jdk/bin/java.exe")
        val extracted = directory.resolve("valid")
        extractWindowsJdk(valid, extracted)
        assertEquals("vendor fixture", extracted.resolve("jdk/bin/java.exe").readText())
        val marker = extracted.resolve("owner.txt").also { it.writeText("preserve") }
        assertThrows(IllegalArgumentException::class.java) { extractWindowsJdk(valid, extracted) }
        assertEquals("preserve", marker.readText())
        val outside = directory.resolve("outside.txt")
        for ((index, entry) in listOf("../outside.txt", outside.toString(), "C:/outside.txt", "jdk\\..\\outside.txt").withIndex()) {
            val bad = archive("bad-$index.zip", entry)
            assertThrows(IllegalArgumentException::class.java) { extractWindowsJdk(bad, directory.resolve("bad-$index")) }
            assertFalse(outside.exists())
        }
    }
}
