package com.ehviewer.shared

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Read a contiguous row/column as unpremultiplied ARGB without copying the decoded image. */
internal fun interface ReaderBorderPixels {
    fun read(x: Int, y: Int, width: Int, height: Int, pixels: IntArray)
}

internal suspend fun ImageBitmap.readerBorderBounds(): IntRect = withContext(Dispatchers.Default) {
    readerBorderBounds(width, height) { x, y, regionWidth, regionHeight, pixels ->
        readPixels(pixels, startX = x, startY = y, width = regionWidth, height = regionHeight, stride = regionWidth)
    }
}

/**
 * The existing EhViewer Rust border rules and v1.15.10 DetectBorderInterceptor's safeguards.
 * One reusable row/column is sufficient; even a maximum-size decoded image needs no second bitmap.
 */
internal suspend fun readerBorderBounds(width: Int, height: Int, source: ReaderBorderPixels): IntRect {
    val context = currentCoroutineContext()
    context.ensureActive()
    checkedImageDimensions(width, height)
    val full = IntRect(0, 0, width, height)
    // The original reader excludes long strips and retains strictly more than 75% on each axis.
    if (maxOf(width, height).toLong() >= minOf(width, height).toLong() * 2) return full
    val pixels = IntArray(maxOf(width, height))
    fun edge(horizontal: Boolean, reversed: Boolean, maximum: Int): Int {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val filledLimit = (length * 0.0025f / 2f).roundToInt()
        fun read(line: Int) {
            context.ensureActive()
            val position = if (reversed) lines - line - 1 else line
            if (horizontal) source.read(0, position, width, 1, pixels) else source.read(position, 0, 1, height, pixels)
            context.ensureActive()
        }
        read(0)
        var black = 0
        var white = 0
        for (index in 0 until length step 2) {
            val luma = readerBorderLuma(pixels[index])
            // Match the existing Rust classification, including its ordered thresholds.
            if (luma < 191) {
                black++
            } else if (luma > 63) {
                white++
            }
        }
        val whiteBackground = when {
            black > filledLimit && white > filledLimit -> return 0
            black > filledLimit -> false
            white > filledLimit -> true
            else -> return 0
        }
        var margin = 1
        while (margin < maximum) {
            read(margin)
            var content = 0
            for (index in 0 until length step 2) {
                val luma = readerBorderLuma(pixels[index])
                if (if (whiteBackground) luma < 191 else luma > 63) content++
                if (content > filledLimit) return margin
            }
            margin++
        }
        return margin
    }
    val verticalLimit = (height + 3) / 4
    val top = edge(horizontal = true, reversed = false, maximum = verticalLimit)
    if (top >= verticalLimit) return full
    val bottom = edge(horizontal = true, reversed = true, maximum = verticalLimit - top)
    if (top + bottom >= verticalLimit) return full
    val horizontalLimit = (width + 3) / 4
    val left = edge(horizontal = false, reversed = false, maximum = horizontalLimit)
    if (left >= horizontalLimit) return full
    val right = edge(horizontal = false, reversed = true, maximum = horizontalLimit - left)
    if (left + right >= horizontalLimit) return full
    val bounds = IntRect(left, top, width - right, height - bottom)
    return if (bounds.width > width * 0.75f && bounds.height > height * 0.75f) bounds else full
}

/** ImageBitmap exposes straight ARGB; the original Android Rust consumer sees premultiplied RGBA. */
private fun readerBorderLuma(argb: Int): Int {
    val alpha = argb ushr 24
    if (alpha == 0) return 0
    fun channel(shift: Int): Int {
        val value = argb ushr shift and 255
        return if (alpha == 255) value else (value * alpha + 127) / 255
    }
    return (channel(16) * 2126 + channel(8) * 7152 + channel(0) * 722) / 10000
}
