package com.ehviewer.core.domain.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderModeTest {
    @Test fun legacyPreferencesKeepTheirActualLayoutAndDirection() {
        val left = ReaderPreferences()
        val right = left.copy(direction = ReadingDirection.RIGHT_TO_LEFT)
        val continuous = right.copy(layout = ReadingLayout.CONTINUOUS)
        assertNull(left.mode)
        assertEquals(ReaderMode.LEFT_TO_RIGHT, left.resolvedMode(webtoon = true))
        assertEquals(ReaderMode.RIGHT_TO_LEFT, right.resolvedMode())
        assertEquals(ReaderMode.WEBTOON, continuous.resolvedMode())
        assertEquals(ReadingDirection.RIGHT_TO_LEFT, continuous.direction)
    }

    @Test fun explicitDefaultUsesGalleryTagsWithoutReplacingGeneralSettings() {
        val before = ReaderPreferences(background = ReaderBackground.GRAY, showPageNumber = false, doubleTapToZoom = false)
        val automatic = before.withMode(ReaderMode.DEFAULT)
        assertEquals(ReaderMode.DEFAULT, automatic.selectedMode)
        assertEquals(ReaderMode.RIGHT_TO_LEFT, automatic.resolvedMode())
        assertEquals(ReaderMode.WEBTOON, automatic.resolvedMode(webtoon = true))
        assertEquals(before.background, automatic.background)
        assertEquals(before.showPageNumber, automatic.showPageNumber)
        assertEquals(before.doubleTapToZoom, automatic.doubleTapToZoom)
    }

    @Test fun explicitModePreservesCompatibleLegacyFieldsAndGeneralOptions() {
        val before = ReaderPreferences(direction = ReadingDirection.RIGHT_TO_LEFT, background = ReaderBackground.WHITE, showSeekBar = false)
        for (mode in ReaderMode.entries) {
            val changed = before.withMode(mode)
            assertEquals(mode, changed.selectedMode)
            assertEquals(if (mode.resolve(false).continuous) ReadingLayout.CONTINUOUS else ReadingLayout.PAGED, changed.layout)
            assertEquals(before.background, changed.background)
            assertEquals(before.showSeekBar, changed.showSeekBar)
        }
        assertEquals(ReadingDirection.LEFT_TO_RIGHT, before.withMode(ReaderMode.VERTICAL).direction)
        assertEquals(ReadingDirection.RIGHT_TO_LEFT, before.withMode(ReaderMode.CONTINUOUS_VERTICAL).direction)
    }
}
