package com.ehviewer.shared

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image

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
