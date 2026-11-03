package com.ehviewer.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Renders the real shared shell headlessly at phone, tablet and desktop widths. */
class GalleryNavigationShellTest {
    private class Observed {
        var opener: (() -> Unit)? = null
        var contentWidth = 0
        var contentInstances = 0
    }

    private fun shell(width: Int, observed: Observed, reading: () -> Boolean = { false }, block: (ImageComposeScene) -> Unit) {
        val scene = ImageComposeScene(width, 720, Density(1f)) {
            MaterialTheme {
                GalleryNavigationShell(GalleryDestination.entries, GalleryDestination.GALLERIES, onSelect = {}) { openNavigation ->
                    observed.opener = openNavigation
                    remember { ++observed.contentInstances }
                    Box(Modifier.fillMaxSize().onSizeChanged { observed.contentWidth = it.width }) {
                        if (reading()) GalleryImmersive()
                    }
                }
            }
        }
        try {
            block(scene)
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.settle(frame: Int) = repeat(3) { render((frame * 3L + it) * 16_000_000L) }

    @Test
    fun compactWindowsUseTheDrawerAndTheFullWidth() {
        val observed = Observed()
        shell(411, observed) { it.settle(0) }
        assertNotNull(observed.opener)
        assertEquals(411, observed.contentWidth)
    }

    @Test
    fun tabletAndDesktopWindowsShowTheRailBesideTheContent() {
        for (width in listOf(600, 800, 1280)) {
            val observed = Observed()
            shell(width, observed) { it.settle(0) }
            assertNull(observed.opener, "width $width")
            assertEquals(width - 80, observed.contentWidth, "width $width")
        }
    }

    @Test
    fun readersHideTheRail() {
        val observed = Observed()
        var reading by mutableStateOf(false)
        shell(1280, observed, { reading }) { scene ->
            scene.settle(0)
            assertEquals(1200, observed.contentWidth)
            reading = true
            scene.settle(1)
            assertEquals(1280, observed.contentWidth)
            reading = false
            scene.settle(2)
            assertEquals(1200, observed.contentWidth)
        }
    }

    @Test
    fun resizingKeepsContentState() {
        val observed = Observed()
        shell(1280, observed) { scene ->
            scene.settle(0)
            scene.constraints = Constraints.fixed(411, 720)
            scene.settle(1)
            assertNotNull(observed.opener)
            assertEquals(411, observed.contentWidth)
            scene.constraints = Constraints.fixed(1280, 720)
            scene.settle(2)
            assertNull(observed.opener)
            assertEquals(1200, observed.contentWidth)
        }
        assertEquals(1, observed.contentInstances)
    }
}
