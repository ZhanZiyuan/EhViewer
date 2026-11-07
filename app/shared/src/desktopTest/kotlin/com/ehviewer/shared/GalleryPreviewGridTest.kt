package com.ehviewer.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ehviewer.core.model.V1GalleryPreview
import com.ehviewer.core.model.V2GalleryPreview
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_page_number
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GalleryPreviewGridTest {
    @Test fun cropPolicyMatchesOriginalImageAndSpriteAspectRatios() {
        val image = V1GalleryPreview("https://ehgt.org/owned.png", 0, "0123456789")
        assertEquals(ContentScale.Crop, galleryPreviewContentScale(image, 50, 100))
        assertEquals(ContentScale.Crop, galleryPreviewContentScale(image, 80, 100))
        assertEquals(ContentScale.Fit, galleryPreviewContentScale(image, 49, 100))
        assertEquals(ContentScale.Fit, galleryPreviewContentScale(image, 81, 100))
        val sprite = V2GalleryPreview(image.url, 0, image.pToken, 100, 68, 101)
        assertEquals(ContentScale.Crop, galleryPreviewContentScale(sprite, 800, 100), "Crop policy uses the tile dimensions, not the whole sprite")
        assertEquals(3, galleryPreviewColumns(320.dp))
        assertEquals(3, galleryPreviewColumns(411.dp))
        assertEquals(12, galleryPreviewColumns(10_000.dp))
    }

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun renderedBitmapCellsKeepOriginalRatioSpacingAndPageActionsAcrossWidthsAndFonts() {
        val previews = (0..5).map { V1GalleryPreview("https://ehgt.org/owned.png", it, (it + 1).toString().repeat(10)) }
        for (width in listOf(320, 411, 1280)) {
            for (font in listOf(1f, 1.8f)) {
                var loads = 0
                val cache = GalleryPreviewCache {
                    loads++
                    PreviewBitmap(ImageBitmap(67, 100), 67, 100)
                }
                val opened = mutableListOf<Int>()
                var firstLabel = ""
                val columns = galleryPreviewColumns(width.dp)
                val scene = ImageComposeScene(width, 1000, Density(1f, font)) {
                    MaterialTheme {
                        firstLabel = galleryString(Res.string.gallery_page_number, 1)
                        LazyVerticalGrid(GridCells.Fixed(columns), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(previews, key = { it.position }) { preview ->
                                GalleryPreviewCell(preview, cache, onOpen = { opened += preview.position })
                            }
                        }
                    }
                }
                var frame = 0L
                fun render() {
                    scene.render(frame).close()
                    frame += 16_666_667
                }
                fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                try {
                    repeat(5) { render() }
                    val cards = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.filter { it.config.contains(SemanticsProperties.StateDescription) }
                    assertEquals(previews.size, cards.size)
                    val expectedWidth = (width - (columns - 1) * 8f) / columns
                    for (card in cards) {
                        val bounds = card.boundsInRoot
                        assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.height > 0, "Clipped preview at $width/$font: $bounds")
                        assertTrue(abs(bounds.width - expectedWidth) <= 2, "Wrong column width at $width/$font: $bounds")
                        assertTrue(abs(bounds.width / bounds.height - 0.67f) < 0.02f, "Wrong original preview ratio: $bounds")
                        assertTrue(card.config.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true, "Actual bitmap accessibility label must be present after decode")
                    }
                    val sorted = cards.sortedBy { it.boundsInRoot.left }.filter { abs(it.boundsInRoot.top - cards.minOf { node -> node.boundsInRoot.top }) < 1 }
                    if (sorted.size > 1) assertTrue(abs(sorted[1].boundsInRoot.left - sorted[0].boundsInRoot.right - 8) <= 1)
                    val first = cards.single { it.config.getOrNull(SemanticsProperties.StateDescription) == firstLabel }.boundsInRoot.center
                    scene.sendPointerEvent(PointerEventType.Press, first)
                    render()
                    scene.sendPointerEvent(PointerEventType.Release, first)
                    repeat(3) { render() }
                    assertEquals(listOf(0), opened)
                    assertEquals(1, loads, "All cells with the same real image URL share the bounded decoded cache")
                } finally {
                    scene.close()
                }
            }
        }
    }
}
