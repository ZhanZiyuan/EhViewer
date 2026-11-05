package com.ehviewer.shared

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** The platform's dynamic palette (Android 12+ wallpaper colors), or null where none exists. */
@Composable
internal expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

// Compose Multiplatform keeps expressiveLightColorScheme internal. These are its tone-30
// on-container tokens, read from AndroidX Material 3's expressiveLightColorScheme bytecode.
private val expressiveLight = lightColorScheme(
    onPrimaryContainer = Color(0xFF4F378B),
    onSecondaryContainer = Color(0xFF4A4458),
    onTertiaryContainer = Color(0xFF633B48),
    onErrorContainer = Color(0xFF8C1D18),
)

/** Android 1.15.10's colors when no dynamic palette is available. */
internal fun galleryFallbackColorScheme(dark: Boolean) = if (dark) darkColorScheme() else expressiveLight

/** A platform's own Material 3 theme for the shared screens, given whether it should be dark. */
typealias GalleryPlatformTheme = @Composable (dark: Boolean, content: @Composable () -> Unit) -> Unit

/**
 * Material 3 theme shared by all platforms. Android supplies its original EhTheme as [platformTheme]
 * (wallpaper/AMOLED colors and expressive motion); elsewhere dynamic color is used when the platform
 * has it, falling back to Android's original schemes.
 */
@Composable
internal fun GalleryTheme(dark: Boolean, platformTheme: GalleryPlatformTheme?, content: @Composable () -> Unit) {
    if (platformTheme != null) return platformTheme(dark, content)
    MaterialTheme(colorScheme = platformDynamicColorScheme(dark) ?: galleryFallbackColorScheme(dark), typography = galleryTypography()) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
    }
}
