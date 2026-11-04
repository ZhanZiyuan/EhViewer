package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_refresh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GalleryDetailAppBarTest {
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)

    @Test
    @OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.InternalComposeUiApi::class)
    fun realScrollCollapsesTheTitleWithoutLosingBackOrReenablingBusyRefresh() {
        for (width in listOf(320, 411, 1280)) {
            for (fontScale in listOf(1f, 1.8f)) {
                var refreshEnabled by mutableStateOf(false)
                var refreshes = 0
                var backs = 0
                var page = Rect.Zero
                var refreshLabel = ""
                var barHeight = 0f
                val scene = ImageComposeScene(width, 900, Density(1f, fontScale)) {
                    MaterialTheme {
                        refreshLabel = galleryString(Res.string.gallery_refresh)
                        val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                        Column(galleryPageModifier(false, false, edgeToEdge = true).onGloballyPositioned { page = it.boundsInRoot() }) {
                            androidx.compose.foundation.layout.Box(Modifier.onGloballyPositioned { barHeight = it.size.height.toFloat() }) {
                                GalleryDetailAppBar("A long gallery title 日本語 简体中文 ".repeat(5), "Owned detail Back", { backs++ }, { refreshes++ }, refreshEnabled, scroll)
                            }
                            LazyColumn(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection)) {
                                items(40) { Text("Actual scrollable content $it", Modifier.height(80.dp)) }
                            }
                        }
                    }
                }
                var frame = 0L
                fun render(count: Int = 5) = repeat(count) {
                    scene.render(frame).close()
                    frame += 16_666_667
                }
                fun control(label: String) = scene.semanticsOwners.flatMap { nodes(it.rootSemanticsNode) }
                    .first { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }.touchBoundsInRoot
                fun click(point: Offset) {
                    scene.sendPointerEvent(PointerEventType.Press, point)
                    render()
                    scene.sendPointerEvent(PointerEventType.Release, point)
                    render()
                }
                try {
                    render(10)
                    val expanded = barHeight
                    assertTrue(expanded > 64, "Detail title must start expanded: $width/$fontScale height=$expanded")
                    click(control(refreshLabel).center)
                    assertEquals(0, refreshes, "Loading must retain the disabled refresh guard")
                    repeat(4) {
                        scene.sendPointerEvent(PointerEventType.Scroll, Offset(width / 2f, 700f), scrollDelta = Offset(0f, 10f))
                        render(20)
                    }
                    assertTrue(barHeight < expanded, "Actual nested scroll must collapse the title at $width/$fontScale: $expanded -> $barHeight")
                    val back = control("Owned detail Back")
                    assertTrue(back.width >= 48 && back.height >= 48 && back.left >= page.left && back.right <= page.right)
                    refreshEnabled = true
                    render()
                    click(control(refreshLabel).center)
                    assertEquals(1, refreshes)
                    click(Offset(back.left + 1, back.center.y))
                    assertEquals(1, backs, "The actual 48dp Back edge remains clickable after collapsing")
                } finally {
                    scene.close()
                }
            }
        }
    }
}
