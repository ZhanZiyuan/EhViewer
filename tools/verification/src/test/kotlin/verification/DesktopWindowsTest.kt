package verification

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopWindowsTest {
    @Test
    fun installationPreservesUnicodeAndBytesAndRefusesExistingUserFiles() = temporary { directory ->
        val source = directory.resolve("original").createDirectories()
        val contents = source.resolve("app/Native 资料").createDirectories()
        val bytes = ByteArray(257) { it.toByte() }
        contents.resolve("library.dll").writeBytes(bytes)
        source.resolve("release.txt").writeText("1.15.10")
        val target = directory.resolve("Installed Runtime 漫画")
        copyDesktopImage(source, target)
        assertArrayEquals(bytes, target.resolve("app/Native 资料/library.dll").readBytes())
        assertEquals("1.15.10", target.resolve("release.txt").readText())
        val user = target.resolve("user.txt").also { it.writeText("preserve user data") }
        assertThrows(IllegalArgumentException::class.java) { copyDesktopImage(source, target) }
        assertEquals("preserve user data", user.readText())
        assertArrayEquals(bytes, contents.resolve("library.dll").readBytes())
        val nested = source.resolve("nested-install")
        assertThrows(IllegalArgumentException::class.java) { copyDesktopImage(source, nested) }
        assertFalse(nested.exists())
    }

    @Test
    fun installationRejectsAnEscapingSymlinkBeforeCreatingTheDestination() = temporary { directory ->
        val outside = directory.resolve("outside").also { it.writeText("do not copy") }
        val source = directory.resolve("original").createDirectories()
        Files.createSymbolicLink(source.resolve("escape"), outside)
        val target = directory.resolve("installed")
        assertThrows(IllegalArgumentException::class.java) { copyDesktopImage(source, target) }
        assertFalse(target.exists())
        assertEquals("do not copy", outside.readText())
    }
}
