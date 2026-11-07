package com.ehviewer.shared

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

// Desktop and iOS expose no wallpaper-derived Material palette; Android's original fallback applies.
@Composable
internal actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null
