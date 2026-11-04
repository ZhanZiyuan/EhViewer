package verification

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopProductUiTest {
    @Test
    fun activationUsesOnlyTheOwnedNumericPidWithoutATitleOrAnotherProcess() {
        val command = windowsProductActivation(12345)
        assertEquals("powershell.exe", command.first())
        assertEquals("(New-Object -ComObject WScript.Shell).AppActivate(12345)", command.last())
        for (invalid in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1)) {
            assertThrows(IllegalArgumentException::class.java) { windowsProductActivation(invalid) }
        }
    }

    @Test
    fun ownedComicContainsThreeDistinctRealDecodablePagesInNaturalOrder() = temporary { directory ->
        val file = directory.resolve("Owned offline comic 资料.cbz")
        writeDesktopComic(file)
        ZipFile(file.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(listOf("1.png", "2.png", "3.png"), entries.map { it.name })
            entries.forEachIndexed { index, entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val page = checkNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
                assertEquals(640, page.width)
                assertEquals(960, page.height)
                assertEquals(desktopComicColors[index], page.getRGB(320, 480))
                assertTrue(bytes.size > 1000)
            }
        }
        assertTrue(Files.size(file) > 3000)
    }
}
