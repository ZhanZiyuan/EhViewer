package com.ehviewer.shared

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Owned two-pixel pure-color animations generated from red/blue PNGs, not third-party content. */
internal object GalleryAnimationFixtures {
    val gif get() = Base64.decode("R0lGODlhAgABAPAAAP8AAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQADAAAACwAAAAAAgABAAACAgQKACH5BAASAAAALAAAAAACAAEAgAAA/wAAAAICBAoAOw==")
    val finiteGif get() = Base64.decode("R0lGODlhAgABAPAAAP8AAAAAACH/C05FVFNDQVBFMi4wAwEBAAAh+QQADAAAACwAAAAAAgABAAACAgQKACH5BAASAAAALAAAAAACAAEAgAAA/wAAAAICBAoAOw==")
    val disposalGif get() = Base64.decode("R0lGODlhAgABAPAAAP8AAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQEDAAAACwAAAAAAgABAAACAgQKACH5BAwMAAAALAAAAAABAAEAgAAA/wAAAAICRAEAIfkEBAwAAAAsAQAAAAEAAQCAAP8AAAAAAgJEAQA7")
    val webp get() = Base64.decode("UklGRoQAAABXRUJQVlA4WAoAAAACAAAAAQAAAAAAQU5JTQYAAAD/////AABBTk1GKAAAAAAAAAAAAAEAAAAAAHgAAAJWUDhMDwAAAC8BAAAABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAAEAAAAAALQAAABWUDhMDwAAAC8BAAAABxDR//4HIqL/AQA=")
}

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryMediaTest {
    @Test fun realContainersIdentifyAnimationsAndLeaveOtherFormatsToTheirDecoder() {
        assertEquals(2, galleryAnimationFrames(GalleryAnimationFixtures.gif))
        assertEquals(2, galleryAnimationFrames(GalleryAnimationFixtures.webp))
        assertEquals(1, galleryAnimationFrames(byteArrayOf()))
        assertEquals(1, galleryAnimationFrames(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)))
        val single = GalleryAnimationFixtures.gif.copyOfRange(0, 61) + byteArrayOf(0x3b)
        assertEquals(1, galleryAnimationFrames(single))
    }

    @Test fun truncatedAndOverflowingChunksCannotEnterAPlatformAnimationDecoder() {
        for (bytes in listOf(GalleryAnimationFixtures.gif, GalleryAnimationFixtures.webp)) {
            for (end in 14 until bytes.size) {
                assertFailsWith<GalleryRequestException> { galleryAnimationFrames(bytes.copyOf(end)) }
            }
        }
        val badLength = GalleryAnimationFixtures.webp.apply { for (index in 16..19) this[index] = 0xff.toByte() }
        assertEquals(GalleryFailure.INVALID_RESPONSE, assertFailsWith<GalleryRequestException> { galleryAnimationFrames(badLength) }.failure)
    }

    @Test fun frameBombsAndOversizedEncodedInputsAreRejectedBeforePixelAllocation() {
        val header = GalleryAnimationFixtures.gif.copyOfRange(0, 19)
        val frame = byteArrayOf(0x2c, 0, 0, 0, 0, 2, 0, 1, 0, 0, 2, 2, 4, 10, 0)
        val valid = header + ByteArray(frame.size * 1000) { frame[it % frame.size] } + byteArrayOf(0x3b)
        assertEquals(1000, galleryAnimationFrames(valid))
        assertEquals(GalleryFailure.RESPONSE_TOO_LARGE, assertFailsWith<GalleryRequestException> { galleryAnimationFrames(valid.dropLast(1).toByteArray() + frame + byteArrayOf(0x3b)) }.failure)
        assertEquals(GalleryFailure.RESPONSE_TOO_LARGE, assertFailsWith<GalleryRequestException> { galleryAnimationFrames(ByteArray(32 * 1024 * 1024 + 1)) }.failure)
    }

    @Test fun workingSetIncludesEncodedCopiesAndFrameHandoffWithoutReducingStaticLimits() {
        assertEquals(64L * 1024 * 1024, galleryMediaCost(4096, 4096, 0))
        assertEquals(432L, galleryMediaCost(2, 1, 128))
        for ((width, height, bytes) in listOf(Triple(4096, 4096, 128), Triple(Int.MAX_VALUE, Int.MAX_VALUE, 1), Triple(2, 1, -1), Triple(2, 1, Int.MAX_VALUE))) {
            assertFailsWith<IllegalArgumentException> { galleryMediaCost(width, height, bytes) }
        }
    }

    @Test fun finitePlaybackRespectsFrameDurationsAndAdditionalRepetitions() = runTest {
        val frames = mutableListOf<Pair<Int, Long>>()
        playGalleryFrames(2, 1, { if (it == 0) 120 else 180 }) { frames += it to currentTime }
        assertEquals(listOf(0 to 0L, 1 to 120L, 0 to 300L, 1 to 420L), frames)
        assertEquals(600L, currentTime)
    }

    @Test fun pageDisposalCancelsAnInfiniteAnimationWithoutPublishingAnotherFrame() = runTest {
        val frames = mutableListOf<Int>()
        val playback = launch { playGalleryFrames(2, -1, { 120 }) { frames += it } }
        runCurrent()
        advanceTimeBy(241)
        runCurrent()
        assertEquals(listOf(0, 1, 0), frames)
        playback.cancelAndJoin()
        advanceTimeBy(1000)
        assertEquals(listOf(0, 1, 0), frames)
    }

    @Test fun missingAndVeryShortDurationsCannotBusySpin() = runTest {
        val times = mutableListOf<Long>()
        playGalleryFrames(3, 0, { listOf(0, 10, 20)[it] }) { times += currentTime }
        assertEquals(listOf(0L, 100L, 200L), times)
        assertEquals(220L, currentTime)
    }

    @Test fun invalidPlaybackMetadataNeverStartsAnUnboundedLoop() = runTest {
        for ((count, repeats) in listOf(1 to 0, 1001 to 0, 2 to -2)) {
            assertFailsWith<IllegalArgumentException> { playGalleryFrames(count, repeats, { 120 }) { error("Invalid metadata reached a renderer") } }
        }
        assertFailsWith<IllegalArgumentException> { playGalleryFrames(2, 0, { -1 }) {} }
    }
}
