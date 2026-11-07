package com.ehviewer.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderPageInputTest {
    @Test fun displayedPageNumbersResolveToExactZeroBasedPositionsIncludingGalleryBoundaries() {
        assertEquals(0, readerPageIndex("1", 174))
        assertEquals(173, readerPageIndex("174", 174))
        assertEquals(24, readerPageIndex(" 0025 ", 174))
        assertEquals(0, readerPageIndex("1", 1))
    }

    @Test fun invalidInputNeverClampsToAnotherPageOrAcceptsPlatformDependentNumericSyntax() {
        for (input in listOf("", " ", "0", "-1", "+1", "175", "1.0", "1e2", "１", "١", "1 2", "2\n3", "2147483648", "9".repeat(100))) assertNull(readerPageIndex(input, 174), input)
        assertNull(readerPageIndex("1", 0))
        assertNull(readerPageIndex("1", -1))
    }

    @Test fun maximumIntPageCannotOverflowItsIndexAndLongDigitStringsAreRejected() {
        assertEquals(Int.MAX_VALUE - 1, readerPageIndex(Int.MAX_VALUE.toString(), Int.MAX_VALUE))
        assertNull(readerPageIndex("00000000001", 10))
    }

    @Test fun restoringAfterLayoutChangesKeepsOffsetsInsideTheRequestedPageIncludingShortLastPages() {
        assertEquals(80, readerRestoreOffset(80, 700, 500, lastPage = true))
        assertEquals(200, readerRestoreOffset(900, 700, 500, lastPage = true))
        assertEquals(0, readerRestoreOffset(80, 200, 500, lastPage = true))
        assertEquals(699, readerRestoreOffset(900, 700, 500, lastPage = false))
        assertEquals(0, readerRestoreOffset(80, null, 500, lastPage = false))
        assertEquals(0, readerRestoreOffset(-1, 700, 500, lastPage = false))
        assertEquals(Int.MAX_VALUE - 1, readerRestoreOffset(Int.MAX_VALUE, Int.MAX_VALUE, 1, lastPage = true))
    }
}
