package com.ehviewer.shared

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.ehviewer.core.domain.reader.ReaderBackground
import com.ehviewer.shared.settings.GalleryAppearancePreferences

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

private val LocalGalleryPlatformTheme = staticCompositionLocalOf<GalleryPlatformTheme?> { null }
internal val LocalGalleryAppearance = staticCompositionLocalOf { GalleryAppearancePreferences() }

/**
 * Material 3 theme shared by all platforms. Android supplies its original EhTheme as [platformTheme]
 * (wallpaper/AMOLED colors and expressive motion); elsewhere dynamic color is used when the platform
 * has it, falling back to Android's original schemes.
 */
@Composable
internal fun GalleryTheme(dark: Boolean, platformTheme: GalleryPlatformTheme?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGalleryPlatformTheme provides platformTheme) {
        if (platformTheme != null) {
            platformTheme(dark, content)
        } else {
            val base = platformDynamicColorScheme(dark) ?: galleryFallbackColorScheme(dark)
            val colors = if (dark && LocalGalleryAppearance.current.blackDarkTheme) {
                // Original EhTheme changes only these four tokens; preserve dynamic accents.
                base.copy(surface = Color.Black, onSurface = Color.White, background = Color.Black, onBackground = Color.White)
            } else {
                base
            }
            MaterialTheme(colorScheme = colors, typography = galleryTypography()) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
            }
        }
    }
}

/** v1.15.10 ReaderScreen selects its Material theme from the reader canvas, independently of the app. */
@Composable
internal fun GalleryReaderTheme(background: ReaderBackground, content: @Composable () -> Unit) {
    GalleryTheme(readerBackgroundColor(background) != Color.White, LocalGalleryPlatformTheme.current, content)
}
