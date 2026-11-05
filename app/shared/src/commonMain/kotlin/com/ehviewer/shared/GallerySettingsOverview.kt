package com.ehviewer.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_account
import ehviewer.app.shared.generated.resources.gallery_history
import ehviewer.app.shared.generated.resources.gallery_settings_about
import ehviewer.app.shared.generated.resources.gallery_settings_advanced
import ehviewer.app.shared.generated.resources.gallery_settings_download
import ehviewer.app.shared.generated.resources.gallery_settings_eh
import ehviewer.app.shared.generated.resources.gallery_settings_license
import ehviewer.app.shared.generated.resources.gallery_settings_privacy
import ehviewer.app.shared.generated.resources.gallery_settings_source
import org.jetbrains.compose.resources.StringResource

internal enum class GallerySettingsPage(val title: StringResource) {
    EH(Res.string.gallery_settings_eh),
    DOWNLOAD(Res.string.gallery_settings_download),
    PRIVACY(Res.string.gallery_settings_privacy),
    ADVANCED(Res.string.gallery_settings_advanced),
    ABOUT(Res.string.gallery_settings_about),
}

/** Original SettingsScreen order, theme, 16dp keyline and 56dp single-line preference rows. */
@Composable
internal fun GallerySettingsOverview(onOpen: (GallerySettingsPage) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        GallerySettingsPage.entries.forEach { page ->
            val icon = when (page) {
                GallerySettingsPage.EH -> GalleryIcons.SadPanda
                GallerySettingsPage.DOWNLOAD -> GalleryIcons.Download
                GallerySettingsPage.PRIVACY -> GalleryIcons.Security
                GallerySettingsPage.ADVANCED -> GalleryIcons.Adb
                GallerySettingsPage.ABOUT -> GalleryIcons.Info
            }
            GalleryPreferenceLink(galleryString(page.title), icon) { onOpen(page) }
        }
    }
}

@Composable
internal fun GalleryPreferenceLink(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
    }
}

/** Reuse real identity and confirmation-based history management, without exposing identity cookies. */
@Composable
internal fun GalleryPrivacyDestinations(onAccount: (() -> Unit)?, onHistory: (() -> Unit)?) {
    onAccount?.let { GalleryPreferenceLink(galleryString(Res.string.gallery_account), GalleryIcons.AccountCircle, it) }
    onHistory?.let { GalleryPreferenceLink(galleryString(Res.string.gallery_history), GalleryIcons.History, it) }
}

@Composable
internal fun GalleryAboutLinks() {
    val uri = LocalUriHandler.current
    Text("EhViewer", style = MaterialTheme.typography.headlineSmall)
    GalleryPreferenceLink(galleryString(Res.string.gallery_settings_source), GalleryIcons.Info) { uri.openUri("https://github.com/ZhanZiyuan/EhViewer") }
    GalleryPreferenceLink(galleryString(Res.string.gallery_settings_license), GalleryIcons.Info) { uri.openUri("https://github.com/ZhanZiyuan/EhViewer/blob/main/LICENSE") }
}
