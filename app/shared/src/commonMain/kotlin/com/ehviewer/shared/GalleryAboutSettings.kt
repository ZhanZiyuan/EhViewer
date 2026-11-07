package com.ehviewer.shared

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_about_author
import ehviewer.app.shared.generated.resources.gallery_about_build_version
import ehviewer.app.shared.generated.resources.gallery_about_declaration
import ehviewer.app.shared.generated.resources.gallery_about_latest_release
import ehviewer.app.shared.generated.resources.gallery_about_source
import ehviewer.app.shared.generated.resources.gallery_settings_license
import me.zhanghai.compose.preference.Preference

data class GalleryBuildInfo(val version: String, val build: String? = null)

private const val REPOSITORY_URL = "https://github.com/ZhanZiyuan/EhViewer"

@Composable
internal fun GalleryAboutSettings(buildInfo: GalleryBuildInfo?) {
    val uri = LocalUriHandler.current
    Preference(
        title = { Text("EhViewer") },
        summary = { Text(galleryString(Res.string.gallery_about_declaration)) },
        onClick = {},
    )
    Preference(
        title = { Text(galleryString(Res.string.gallery_about_author)) },
        summary = {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append("Hippo \nNekoInverter\n飛鳥澪")
                    }
                    append("\nFoolbar")
                },
            )
        },
        onClick = {},
    )
    val releases = "$REPOSITORY_URL/releases"
    Preference(
        title = { Text(galleryString(Res.string.gallery_about_latest_release)) },
        summary = { Text(releases) },
        onClick = { uri.openUri(releases) },
    )
    Preference(
        title = { Text(galleryString(Res.string.gallery_about_source)) },
        summary = { Text(REPOSITORY_URL) },
        onClick = { uri.openUri(REPOSITORY_URL) },
    )
    Preference(
        title = { Text(galleryString(Res.string.gallery_settings_license)) },
        onClick = { uri.openUri("$REPOSITORY_URL/blob/main/LICENSE") },
    )
    if (buildInfo != null) {
        val version = buildInfo.build?.takeIf { it.isNotBlank() }?.let { "${buildInfo.version} ($it)" } ?: buildInfo.version
        Preference(
            title = { Text(galleryString(Res.string.gallery_about_build_version)) },
            summary = { Text(version) },
            onClick = {},
        )
    }
}
