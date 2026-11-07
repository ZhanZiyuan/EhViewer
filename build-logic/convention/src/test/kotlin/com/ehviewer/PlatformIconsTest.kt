package com.ehviewer

import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformIconsTest {
    private val vector = File("../../app/src/main/res/drawable/ic_launcher_foreground.xml")

    private fun owned(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("ehviewer-platform-icons-").toFile()
        try {
            test(directory)
        } finally {
            check(directory.deleteRecursively())
        }
    }

    @Test fun pngPreservesTheExistingFourColorMarkAndPlatformBackgrounds() = owned { root ->
        PlatformIcons.generate(vector, root)
        val desktop = ImageIO.read(root.resolve("EhViewer.png"))
        val mobile = ImageIO.read(root.resolve("AppIcons.xcassets/AppIcon.appiconset/AppIcon-1024.png"))
        assertEquals(512, desktop.width)
        assertEquals(1024, mobile.height)
        assertEquals(0, desktop.getRGB(0, 0))
        assertFalse(mobile.colorModel.hasAlpha())
        assertEquals(Color.WHITE.rgb, mobile.getRGB(0, 0))
        for (image in listOf(desktop, mobile)) {
            val colors = (0 until image.height).flatMap { y -> (0 until image.width).map { x -> image.getRGB(x, y) } }.toSet()
            for (color in listOf(0xFBBD06, 0x35AA53, 0x4586F3, 0xEB4334)) assertTrue(colors.contains(Color(color).rgb))
        }
    }

    @Test fun windowsAndMacContainersContainRealDecodableImagesAtEverySize() = owned { root ->
        PlatformIcons.generate(vector, root)
        val ico = root.resolve("EhViewer.ico").readBytes()
        val table = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, table.short.toInt())
        assertEquals(1, table.short.toInt())
        assertEquals(7, table.short.toInt())
        var end = 6 + 7 * 16
        for (size in listOf(16, 24, 32, 48, 64, 128, 256)) {
            assertEquals(size % 256, table.get().toInt() and 255)
            assertEquals(size % 256, table.get().toInt() and 255)
            table.get()
            table.get()
            assertEquals(1, table.short.toInt())
            assertEquals(32, table.short.toInt())
            val length = table.int
            assertEquals(end, table.int)
            val image = ImageIO.read(ByteArrayInputStream(ico, end, length))
            assertEquals(size, image.width)
            assertEquals(size, image.height)
            end += length
        }
        assertEquals(ico.size, end)
        val mac = ByteBuffer.wrap(root.resolve("EhViewer.icns").readBytes())
        val tag = ByteArray(4)
        mac.get(tag)
        assertEquals("icns", tag.toString(Charsets.US_ASCII))
        assertEquals(mac.capacity(), mac.int)
        val sizes = mutableSetOf<Int>()
        while (mac.hasRemaining()) {
            mac.get(tag)
            val length = mac.int
            val bytes = ByteArray(length - 8)
            mac.get(bytes)
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            assertEquals(image.width, image.height)
            assertTrue(sizes.add(image.width))
        }
        assertEquals(setOf(16, 32, 64, 128, 256, 512, 1024), sizes)
    }

    @Test fun unsupportedCommandsAndExternalDocumentsFailInsteadOfSilentlyChangingTheMark() = owned { root ->
        val invalid = root.resolve("unsupported.xml")
        invalid.writeText(vector.readText().replace("M11,9H34", "M11,9A2,2,0,0,0,34,9"))
        assertThrows(IllegalArgumentException::class.java) { PlatformIcons.render(invalid, 512, false) }
        invalid.writeText("<!DOCTYPE vector SYSTEM 'file:///unread-owned-icon-entity'>" + vector.readText().substringAfter("?>"))
        assertThrows(Exception::class.java) { PlatformIcons.render(invalid, 512, false) }
    }
}
