package com.hippo.ehviewer.coil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.Buffer
import java.nio.ByteBuffer
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimatedGalleryNativeTest {
    private val webp = Base64.decode("UklGRoQAAABXRUJQVlA4WAoAAAACAAAAAQAAAAAAQU5JTQYAAAD/////AABBTk1GKAAAAAAAAAAAAAEAAAAAAHgAAAJWUDhMDwAAAC8BAAAABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAAEAAAAAALQAAABWUDhMDwAAAC8BAAAABxDR//4HIqL/AQA=")

    private fun buffer(bytes: ByteArray) = ByteBuffer.allocateDirect(bytes.size).apply {
        put(bytes)
        (this as Buffer).flip()
    }

    @Test fun actualRustFramesCloseWhileRunningAndCannotRestartOrDoubleFree() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val drawable = AnimatedWebPDrawable(buffer(webp))
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        try {
            instrumentation.runOnMainSync {
                drawable.setBounds(0, 0, 2, 1)
                drawable.draw(canvas)
                assertTrue(Color.red(bitmap.getPixel(0, 0)) > 250)
                drawable.start()
            }
            runBlocking {
                withTimeout(10_000) {
                    var blue = false
                    while (!blue) {
                        instrumentation.runOnMainSync {
                            drawable.draw(canvas)
                            blue = Color.blue(bitmap.getPixel(0, 0)) > 250 && Color.red(bitmap.getPixel(0, 0)) < 5
                        }
                        if (!blue) delay(20)
                    }
                }
            }
            instrumentation.runOnMainSync {
                drawable.close()
                drawable.dispose()
                drawable.start()
                drawable.draw(canvas)
                assertFalse(drawable.isRunning)
            }
        } finally {
            instrumentation.runOnMainSync { drawable.close() }
            bitmap.recycle()
        }
    }

    @Test fun repeatedNativeOwnershipAndMalformedInputKeepTheProcessUsable() {
        repeat(50) {
            AnimatedWebPDrawable(buffer(webp)).apply {
                start()
                close()
                close()
                assertFalse(isRunning)
            }
        }
        assertThrows(IllegalStateException::class.java) { AnimatedWebPDrawable(buffer(byteArrayOf(1, 2, 3))) }
        AnimatedWebPDrawable(buffer(webp)).close()
    }

    @Test fun corruptLaterFrameReportsFailureAndStopsWithoutAnUnhandledCoroutineException() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val reported = CompletableDeferred<Throwable>()
        val broken = webp.copyOf().apply { fill(0, 130, 139) }
        val drawable = AnimatedWebPDrawable(buffer(broken)) { reported.complete(it) }
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        try {
            instrumentation.runOnMainSync {
                drawable.setBounds(0, 0, 2, 1)
                drawable.draw(canvas)
                assertTrue(Color.red(bitmap.getPixel(0, 0)) > 250)
                drawable.start()
            }
            runBlocking {
                withTimeout(10_000) {
                    while (!reported.isCompleted) {
                        instrumentation.runOnMainSync { drawable.draw(canvas) }
                        delay(20)
                    }
                    assertTrue(reported.await() is IllegalStateException)
                }
            }
            instrumentation.runOnMainSync {
                drawable.start()
                drawable.draw(canvas)
                assertFalse(drawable.isRunning)
            }
        } finally {
            instrumentation.runOnMainSync { drawable.close() }
            bitmap.recycle()
        }
    }
}
