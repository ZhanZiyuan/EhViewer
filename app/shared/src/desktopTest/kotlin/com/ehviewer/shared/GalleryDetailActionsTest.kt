package com.ehviewer.shared

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GalleryDetailActionsTest {
    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun realCompactControlsWaitForConfirmedFavoritesAndNavigateToSimilarAtLargeFonts() {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
        try {
            for (width in listOf(320, 411, 1280)) {
                for (font in listOf(1f, 1.8f)) {
                    var confirmed by mutableStateOf(false)
                    var edits = 0
                    val searches = mutableListOf<GallerySimilarSearch>()
                    val scene = ImageComposeScene(width, 200, Density(1f, font)) {
                        MaterialTheme {
                            GalleryDetailActions(
                                GalleryDetail(
                                    galleryInfo = BaseGalleryInfo(title = "Alice in Wonderland [English]", favoriteSlot = if (confirmed) 7 else GalleryInfo.NOT_FAVORITED, favoriteName = if (confirmed) "Shelf7" else null),
                                    tagGroups = emptyList(),
                                    comments = GalleryCommentList(emptyList(), false),
                                    previewList = emptyList(),
                                ),
                                onFavorite = { edits++ },
                                onSimilar = { searches += it },
                            )
                        }
                    }
                    var time = 0L
                    fun render() = repeat(3) {
                        scene.render(time).close()
                        time += 16_666_667
                    }
                    fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                    fun action(label: String) = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }.single { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                    fun click(point: Offset) {
                        scene.sendPointerEvent(PointerEventType.Press, point)
                        render()
                        scene.sendPointerEvent(PointerEventType.Release, point)
                        render()
                    }
                    try {
                        render()
                        val favorite = action("Add to favorites")
                        assertEquals(ToggleableState.Off, favorite.config[SemanticsProperties.ToggleableState])
                        click(favorite.touchBoundsInRoot.center)
                        assertEquals(1, edits)
                        assertEquals(ToggleableState.Off, action("Add to favorites").config[SemanticsProperties.ToggleableState], "Opening or cancelling the editor must not pretend the server changed")
                        confirmed = true
                        render()
                        assertEquals(ToggleableState.On, action("Edit favorite").config[SemanticsProperties.ToggleableState])
                        var bounds = action("Similar").touchBoundsInRoot
                        if (bounds.right > width) {
                            scene.sendPointerEvent(PointerEventType.Scroll, Offset(width / 2f, 32f), scrollDelta = Offset(20f, 0f))
                            render()
                            bounds = action("Similar").touchBoundsInRoot
                        }
                        assertTrue(bounds.width >= 48 && bounds.height >= 48 && bounds.center.x in 0f..width.toFloat(), "Real Similar target inaccessible at $width/$font: $bounds")
                        click(bounds.center)
                        assertEquals(listOf(GallerySimilarSearch("\"Alice in Wonderland\"")), searches)
                        assertEquals(1, edits)
                    } finally {
                        scene.close()
                    }
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
