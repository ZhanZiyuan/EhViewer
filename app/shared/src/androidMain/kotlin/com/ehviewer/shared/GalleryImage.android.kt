package com.ehviewer.shared

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.asImage

internal actual fun ImageBitmap.asCoilCover(): coil3.Image = asAndroidBitmap().asImage()

internal actual fun decodePlatformImage(bytes: ByteArray): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    checkedImageDimensions(bounds.outWidth, bounds.outHeight)
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Invalid gallery image" }
    return bitmap.asImageBitmap()
}

internal actual fun decodePlatformCoverResult(bytes: ByteArray): DecodedGalleryCover {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    coverDimensions(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (maxOf((bounds.outWidth + sample - 1) / sample, (bounds.outHeight + sample - 1) / sample) > COVER_MAX_EDGE * 2) sample *= 2
    val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
    try {
        require(maxOf(decoded.width, decoded.height) <= COVER_MAX_EDGE * 2)
        val (width, height) = coverDimensions(decoded.width, decoded.height)
        val sampled = width != bounds.outWidth || height != bounds.outHeight
        if (width == decoded.width && height == decoded.height) return DecodedGalleryCover(decoded.asImageBitmap(), sampled)
        return DecodedGalleryCover(Bitmap.createScaledBitmap(decoded, width, height, true).asImageBitmap(), sampled).also { decoded.recycle() }
    } catch (error: Throwable) {
        decoded.recycle()
        throw error
    }
}
