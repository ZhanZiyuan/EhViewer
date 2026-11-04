package com.ehviewer.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlin.test.assertTrue

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

    // Material's rail is 80dp and grows only when a label needs more room, which depends on platform fonts.
    private fun assertRail(width: Int, contentWidth: Int) = assertTrue(width - contentWidth in 80..160, "rail beside content: window $width, content $contentWidth")

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
            assertRail(width, observed.contentWidth)
        }
    }

    @Test
    fun readersHideTheRail() {
        val observed = Observed()
        var reading by mutableStateOf(false)
        shell(1280, observed, { reading }) { scene ->
            scene.settle(0)
            assertRail(1280, observed.contentWidth)
            val beside = observed.contentWidth
            reading = true
            scene.settle(1)
            assertEquals(1280, observed.contentWidth)
            reading = false
            scene.settle(2)
            assertEquals(beside, observed.contentWidth)
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
            assertRail(1280, observed.contentWidth)
        }
        assertEquals(1, observed.contentInstances)
    }

    @Test
    fun nativePresentationRestoresEachReaderAcrossHostChangesResizeAndDisposal() {
        class Presentation : GalleryReaderPresentation {
            var entered = 0
            var restored = 0
            val states = mutableListOf<Boolean>()
            val active get() = entered - restored
            override fun enter(controlsVisible: Boolean): () -> Unit {
                entered++
                states += controlsVisible
                return { restored++ }
            }
        }
        val first = Presentation()
        val second = Presentation()
        var host by mutableStateOf<GalleryReaderPresentation>(first)
        var readers by mutableIntStateOf(0)
        var controlsVisible by mutableStateOf(false)
        val scene = ImageComposeScene(1280, 720, Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalGalleryReaderPresentation provides host) {
                    GalleryNavigationShell(GalleryDestination.entries, GalleryDestination.GALLERIES, onSelect = {}) {
                        repeat(readers) { GalleryImmersive(controlsVisible) }
                    }
                }
            }
        }
        try {
            scene.settle(0)
            assertEquals(0, first.entered)
            readers = 2
            scene.settle(1)
            assertEquals(2, first.active)
            scene.constraints = Constraints.fixed(411, 720)
            scene.settle(2)
            assertEquals(2, first.entered, "Resizing must not reenter a native reader lease")
            controlsVisible = true
            scene.settle(3)
            assertEquals(listOf(false, false, true, true), first.states, "Native bars follow the actual shared controls state")
            assertEquals(2, first.active)
            host = second
            scene.settle(4)
            assertEquals(0, first.active, "Changing the native host must restore the previous window")
            assertEquals(4, first.restored)
            assertEquals(2, second.active)
            assertEquals(listOf(true, true), second.states)
            controlsVisible = false
            scene.settle(5)
            assertEquals(listOf(true, true, false, false), second.states)
            readers = 1
            scene.settle(6)
            assertEquals(1, second.active, "One remaining reader keeps its native presentation")
            readers = 0
            scene.settle(7)
            assertEquals(0, second.active, "Returning from reading restores the system bars")
            readers = 1
            scene.settle(8)
            assertEquals(1, second.active)
        } finally {
            scene.close()
        }
        assertEquals(0, second.active, "Closing the whole window also restores the last reader lease")
        assertEquals(5, second.entered)
        assertEquals(5, second.restored)
    }
}
