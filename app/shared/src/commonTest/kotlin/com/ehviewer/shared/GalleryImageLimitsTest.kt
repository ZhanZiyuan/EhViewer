package com.ehviewer.shared

import kotlin.test.Test
import kotlin.test.assertFailsWith

class GalleryImageLimitsTest {
    @Test fun normalImagesAndTheExactPixelLimitAreAccepted() {
        checkedImageDimensions(1, 1)
        checkedImageDimensions(4096, 4096)
        checkedImageDimensions(512, 32768)
    }

    @Test fun invalidDimensionsAndIntegerOverflowCannotAllocatePixels() {
        for ((width, height) in listOf(0 to 1, -1 to 2, 32769 to 1, 4096 to 4097, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { checkedImageDimensions(width, height) }
        }
    }
}
