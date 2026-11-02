package com.ehviewer.shared

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SamplingMode

internal actual fun decodePlatformImage(bytes: ByteArray): ImageBitmap {
    val data = Data.makeFromBytes(bytes)
    try {
        val codec = Codec.makeFromData(data)
        try {
            checkedImageDimensions(codec.width, codec.height)
        } finally {
            codec.close()
        }
    } finally {
        data.close()
    }
    // Compose copies the decoded image into its own bitmap; temporary Skia owners close here.
    val image = Image.makeFromEncoded(bytes)
    return try {
        image.toComposeImageBitmap()
    } finally {
        image.close()
    }
}

internal actual fun decodePlatformCover(bytes: ByteArray): ImageBitmap {
    val dimensions = Data.makeFromBytes(bytes).let { data ->
        try {
            val codec = Codec.makeFromData(data)
            try {
                coverDimensions(codec.width, codec.height)
            } finally {
                codec.close()
            }
        } finally {
            data.close()
        }
    }
    val source = Image.makeFromEncoded(bytes)
    try {
        val target = Bitmap()
        try {
            check(target.allocPixels(ImageInfo.makeN32Premul(dimensions.first, dimensions.second)))
            val pixels = checkNotNull(target.peekPixels())
            try {
                check(source.scalePixels(pixels, SamplingMode.LINEAR, false))
            } finally {
                pixels.close()
            }
            val scaled = Image.makeFromBitmap(target)
            return try {
                scaled.toComposeImageBitmap()
            } finally {
                scaled.close()
            }
        } finally {
            target.close()
        }
    } finally {
        source.close()
    }
}
