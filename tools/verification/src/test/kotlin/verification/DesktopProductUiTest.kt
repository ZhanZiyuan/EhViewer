package verification

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopProductUiTest {
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
