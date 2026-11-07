package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.reader.ReaderBackground
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GalleryReaderCropTest {
    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun realCropControlChangesPresentedPixelsOnlyAfterThePreferenceIsCommitted() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        val media = GalleryMedia(borderedReaderBitmap(), null, galleryMediaCost(80, 96, 0))
        var crop by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        val requests = mutableListOf<Boolean>()
        val scene = ImageComposeScene(360, 600, Density(1f)) {
            GalleryTheme(false, null) {
                GalleryReaderTheme(ReaderBackground.BLACK) {
                    Column {
                        GalleryReaderCropButton(crop, enabled) { requests += !crop }
                        GalleryReaderMediaImage(rememberGalleryReaderMedia(media, crop), "Owned bordered page", Modifier.size(200.dp, 240.dp))
                    }
                }
            }
        }
        var frame = 0L
        fun render() = repeat(10) { scene.render(frame++ * 16_666_667L).close() }
        fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        fun all() = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
        fun control() = all().single { it.config.contains(SemanticsProperties.Selected) }
        fun click() {
            val point = control().touchBoundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, point)
            render()
            scene.sendPointerEvent(PointerEventType.Release, point)
            render()
        }
        fun capture(name: String): Color {
            val bounds = all().single { "Owned bordered page" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.boundsInRoot
            val probe = Offset(bounds.left + 5, bounds.center.y)
            val image = scene.render(frame++ * 16_666_667L)
            return try {
                val color = image.toComposeImageBitmap().toPixelMap()[probe.x.toInt(), probe.y.toInt()]
                val data = checkNotNull(image.encodeToData())
                try {
                    File("build/reports/reader-crop/$name.png").apply { parentFile.mkdirs() }.writeBytes(data.bytes)
                } finally {
                    data.close()
                }
                color
            } finally {
                image.close()
            }
        }
        try {
            render()
            assertFalse(control().config[SemanticsProperties.Selected])
            assertEquals(Color.White, capture("off"))
            click()
            assertEquals(listOf(true), requests)
            assertFalse(control().config[SemanticsProperties.Selected], "A pending or failed save must retain the committed crop state")
            assertEquals(Color.White, capture("pending"))
            crop = true
            val deadline = System.nanoTime() + 5_000_000_000L
            var color: Color
            do {
                render()
                color = capture("on")
                if (color != Color.Green) Thread.sleep(5)
            } while (color != Color.Green && System.nanoTime() < deadline)
            assertEquals(Color.Green, color, "The actual BitmapPainter must display the cropped source, not the original border")
            assertTrue(control().config[SemanticsProperties.Selected])
            enabled = false
            render()
            click()
            assertEquals(1, requests.size, "Disabled controls must not issue another preference write")
            enabled = true
            render()
            click()
            assertEquals(listOf(true, false), requests)
            crop = false
            render()
            assertEquals(Color.White, capture("restored"))
        } finally {
            scene.close()
            Locale.setDefault(before)
        }
    }
}
