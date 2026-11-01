package com.ehviewer.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal val LocalGalleryLanguage = staticCompositionLocalOf<String?> { null }

/** Refresh only each resource lookup when the platform locale changes; keep navigation/reader state. */
@Composable
internal fun galleryString(resource: StringResource, vararg arguments: Any): String = key(LocalGalleryLanguage.current) {
    stringResource(resource, *arguments)
}
