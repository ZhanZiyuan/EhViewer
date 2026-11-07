package com.ehviewer.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.reader.ReaderBackground
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap

class GalleryReaderThemeTest {
    @Test
    fun automaticCanvasUsesSystemDarknessAndKeepsOriginalExactGray() {
        assertEquals(Color.White, readerBackgroundColor(ReaderBackground.AUTOMATIC, systemDark = false))
        assertEquals(Color(0xff202125), readerBackgroundColor(ReaderBackground.AUTOMATIC, systemDark = true))
        for (systemDark in listOf(false, true)) {
            assertEquals(Color.Black, readerBackgroundColor(ReaderBackground.BLACK, systemDark))
            assertEquals(Color(0xff202125), readerBackgroundColor(ReaderBackground.GRAY, systemDark))
            assertEquals(Color.White, readerBackgroundColor(ReaderBackground.WHITE, systemDark))
        }
    }

    @Test
    fun renderedReaderSurfacesFollowCanvasReusePlatformPaletteAndRestoreAppTheme() {
        val lightSurface = 0xfff8f1de.toInt()
        val darkSurface = 0xff132327.toInt()
        val light = lightColorScheme(surface = Color(lightSurface))
        val dark = darkColorScheme(surface = Color(darkSurface))
        val platformTheme: GalleryPlatformTheme = { useDark, content ->
            MaterialTheme(colorScheme = if (useDark) dark else light, content = content)
        }
        for (appDark in listOf(false, true)) {
            var background by mutableStateOf(ReaderBackground.BLACK)
            var systemDark = false
            val scene = ImageComposeScene(60, 120, Density(1f)) {
                systemDark = isSystemInDarkTheme()
                GalleryTheme(appDark, platformTheme) {
                    Column {
                        GalleryReaderTheme(background) {
                            Surface(Modifier.size(60.dp)) {}
                        }
                        Surface(Modifier.size(60.dp)) {}
                    }
                }
            }
            var frame = 0L
            try {
                for (value in ReaderBackground.entries) {
                    background = value
                    repeat(8) { scene.render(frame++ * 16_666_667L).close() }
                    Bitmap().use { pixels ->
                        pixels.allocN32Pixels(60, 120)
                        scene.render(frame++ * 16_666_667L).use { it.readPixels(pixels) }
                        val readerDark = when (value) {
                            ReaderBackground.WHITE -> false
                            ReaderBackground.AUTOMATIC -> systemDark
                            else -> true
                        }
                        assertEquals(if (readerDark) darkSurface else lightSurface, pixels.getColor(30, 30), "$value must use the original canvas-driven reader theme and retain the platform palette")
                        assertEquals(if (appDark) darkSurface else lightSurface, pixels.getColor(30, 90), "Reader overrides must not change the surrounding application theme")
                    }
                }
            } finally {
                scene.close()
            }
        }
    }
}
