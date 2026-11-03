package com.ehviewer.shared

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import coil3.asImage
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.SamplingMode

internal actual fun ImageBitmap.asCoilCover(): coil3.Image = asSkiaBitmap().asImage()

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

internal actual fun decodePlatformCoverResult(bytes: ByteArray): DecodedGalleryCover {
    val original = Data.makeFromBytes(bytes).let { data ->
        try {
            val codec = Codec.makeFromData(data)
            try {
                checkedImageDimensions(codec.width, codec.height)
                codec.width to codec.height
            } finally {
                codec.close()
            }
        } finally {
            data.close()
        }
    }
    val dimensions = coverDimensions(original.first, original.second)
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
                DecodedGalleryCover(scaled.toComposeImageBitmap(), dimensions != original)
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
