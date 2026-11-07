package com.hippo.ehviewer.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/** Use the same source-derived mark in actual windows and native installation packages. */
internal fun desktopApplicationIcon(): ImageBitmap {
    val bytes = checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("EhViewer.png")).use { it.readBytes() }
    return Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
}
