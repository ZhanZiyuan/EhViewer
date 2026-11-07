package com.ehviewer.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_info
import ehviewer.app.shared.generated.resources.gallery_info_category
import ehviewer.app.shared.generated.resources.gallery_info_favorite_count
import ehviewer.app.shared.generated.resources.gallery_info_favorite_name
import ehviewer.app.shared.generated.resources.gallery_info_favorited
import ehviewer.app.shared.generated.resources.gallery_info_gid
import ehviewer.app.shared.generated.resources.gallery_info_language
import ehviewer.app.shared.generated.resources.gallery_info_pages
import ehviewer.app.shared.generated.resources.gallery_info_parent
import ehviewer.app.shared.generated.resources.gallery_info_posted
import ehviewer.app.shared.generated.resources.gallery_info_rating
import ehviewer.app.shared.generated.resources.gallery_info_rating_count
import ehviewer.app.shared.generated.resources.gallery_info_size
import ehviewer.app.shared.generated.resources.gallery_info_thumb
import ehviewer.app.shared.generated.resources.gallery_info_title
import ehviewer.app.shared.generated.resources.gallery_info_title_jpn
import ehviewer.app.shared.generated.resources.gallery_info_token
import ehviewer.app.shared.generated.resources.gallery_info_torrent_url
import ehviewer.app.shared.generated.resources.gallery_info_torrents
import ehviewer.app.shared.generated.resources.gallery_info_uploader
import ehviewer.app.shared.generated.resources.gallery_info_url
import ehviewer.app.shared.generated.resources.gallery_info_visible
import ehviewer.app.shared.generated.resources.gallery_link_copied
import ehviewer.app.shared.generated.resources.gallery_link_copy_failed
import kotlinx.coroutines.CancellationException

/** The original Gallery Info fields; API credentials are deliberately never part of this list. */
internal fun galleryInformationValues(detail: GalleryDetail, url: String, thumbUrl: String) = listOf(
    Res.string.gallery_info_gid to detail.gid.toString(),
    Res.string.gallery_info_token to detail.token,
    Res.string.gallery_info_url to url,
    Res.string.gallery_info_title to detail.title.orEmpty(),
    Res.string.gallery_info_title_jpn to detail.titleJpn.orEmpty(),
    Res.string.gallery_info_thumb to thumbUrl,
    Res.string.gallery_info_category to galleryCategoryName(detail.category),
    Res.string.gallery_info_uploader to detail.uploader.orEmpty(),
    Res.string.gallery_info_posted to detail.posted.orEmpty(),
    Res.string.gallery_info_parent to detail.parent.orEmpty(),
    Res.string.gallery_info_visible to detail.visible.orEmpty(),
    Res.string.gallery_info_language to detail.language.orEmpty(),
    Res.string.gallery_info_pages to detail.pages.toString(),
    Res.string.gallery_info_size to detail.size.orEmpty(),
    Res.string.gallery_info_favorite_count to detail.favoriteCount.toString(),
    Res.string.gallery_info_favorited to (detail.favoriteSlot > GalleryInfo.LOCAL_FAVORITED).toString(),
    Res.string.gallery_info_rating_count to detail.ratingCount.toString(),
    Res.string.gallery_info_rating to detail.rating.toString(),
    Res.string.gallery_info_torrents to detail.torrentCount.toString(),
    Res.string.gallery_info_torrent_url to "${url.substringBefore("/g/")}/gallerytorrents.php?gid=${detail.gid}&t=${detail.token}",
    Res.string.gallery_info_favorite_name to detail.favoriteName.orEmpty(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION") // The same platform-backed clipboard as GalleryLinkActions.
@Composable
internal fun GalleryDetailInformation(detail: GalleryDetail, url: String, thumbUrl: String, onDismiss: () -> Unit) {
    val fields = remember(detail, url, thumbUrl) { galleryInformationValues(detail, url, thumbUrl) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<Boolean?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Text(galleryString(Res.string.gallery_info), Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp), style = MaterialTheme.typography.titleLarge)
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(fields) { (key, value) ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(enabled = value.isNotEmpty(), role = Role.Button) {
                                copied = try {
                                    clipboard.setText(AnnotatedString(value))
                                    true
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    false
                                }
                            },
                        ) {
                            Text(galleryString(key), Modifier.width(90.dp).padding(8.dp))
                            Text(value, Modifier.weight(1f).padding(8.dp))
                        }
                    }
                }
            }
            copied?.let {
                Text(galleryString(if (it) Res.string.gallery_link_copied else Res.string.gallery_link_copy_failed), Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = if (it) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            }
        }
    }
}
