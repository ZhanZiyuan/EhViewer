package com.ehviewer.shared

import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException

/** Bounded container scan before a platform decoder allocates animation state. No pixel decoding here. */
internal fun galleryAnimationFrames(bytes: ByteArray): Int {
    fun invalid(): Nothing = throw GalleryRequestException(GalleryFailure.INVALID_RESPONSE)
    if (bytes.size > 32 * 1024 * 1024) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
    fun tag(at: Int, value: String) = at >= 0 && at.toLong() + value.length <= bytes.size && value.indices.all { bytes[at + it].toInt() and 255 == value[it].code }
    fun byte(at: Int): Int = bytes.getOrNull(at)?.toInt()?.and(255) ?: invalid()
    fun frames(value: Int): Int {
        if (value > 1000) throw GalleryRequestException(GalleryFailure.RESPONSE_TOO_LARGE)
        return value
    }
    if (tag(0, "GIF87a") || tag(0, "GIF89a")) {
        var position = 13
        val flags = byte(10)
        if (flags and 128 != 0) position += 3 * (1 shl ((flags and 7) + 1))
        fun skip(size: Int) {
            if (size < 0 || position.toLong() + size > bytes.size) invalid()
            position += size
        }
        fun blocks() {
            while (true) {
                val size = byte(position++)
                if (size == 0) return
                skip(size)
            }
        }
        var count = 0
        while (true) {
            when (byte(position++)) {
                0x21 -> {
                    skip(1) // Extension label; all extension payloads use sub-blocks.
                    blocks()
                }
                0x2c -> {
                    val localFlags = byte(position + 8)
                    skip(9)
                    if (localFlags and 128 != 0) skip(3 * (1 shl ((localFlags and 7) + 1)))
                    skip(1) // LZW minimum code size; the actual decoder validates compressed pixels.
                    blocks()
                    count = frames(count + 1)
                }
                0x3b -> return count.takeIf { it > 0 } ?: invalid()
                else -> invalid()
            }
        }
    }
    if (tag(0, "RIFF") && tag(8, "WEBP")) {
        fun word(at: Int): Long = (0..3).fold(0L) { value, shift -> value or (byte(at + shift).toLong() shl (shift * 8)) }
        val end = word(4) + 8
        if (end != bytes.size.toLong()) invalid()
        var position = 12
        var count = 0
        var animated = false
        while (position < bytes.size) {
            val size = word(position + 4)
            val next = position.toLong() + 8 + size + (size and 1)
            if (next > end || next <= position) invalid()
            if (tag(position, "VP8X")) {
                if (size != 10L) invalid()
                animated = byte(position + 8) and 2 != 0
            }
            if (tag(position, "ANMF")) {
                if (size < 16) invalid()
                count = frames(count + 1)
            }
            position = next.toInt()
        }
        if (count > 0 && !animated || animated && count == 0) invalid()
        return count.coerceAtLeast(1)
    }
    return 1
}
