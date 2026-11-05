package com.ehviewer.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.Image as CoilImage
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.network.gallery.GalleryCategory
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_detail_page_count
import ehviewer.app.shared.generated.resources.gallery_info

/** Android 1.15.10's header and actions, sharing its 16dp keyline and 128 × 192dp cover. */
@Composable
internal fun GalleryDetailHeaderSection(
    info: GalleryInfo,
    covers: GalleryCoverCache<CoilImage>,
    onInformation: () -> Unit,
    onCategory: () -> Unit,
    onUploader: () -> Unit,
    download: @Composable (Modifier) -> Unit,
    read: @Composable (Modifier) -> Unit,
    fallbackCoverKey: String? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Material's expanded width, independent of the navigation rail's medium-width boundary.
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                GalleryDetailHeader(info, covers, onInformation, onCategory, onUploader, Modifier.width(480.dp), fallbackCoverKey)
                Column(Modifier.weight(1f).heightIn(min = 192.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    read(Modifier.padding(horizontal = 16.dp).width(192.dp).height(56.dp))
                    Spacer(Modifier.height(24.dp))
                    download(Modifier.padding(horizontal = 16.dp).width(192.dp).height(56.dp))
                }
            }
        } else {
            Column {
                GalleryDetailHeader(info, covers, onInformation, onCategory, onUploader, Modifier.fillMaxWidth().padding(vertical = 16.dp), fallbackCoverKey)
                Row(Modifier.fillMaxWidth()) {
                    download(Modifier.padding(horizontal = 4.dp).weight(1f))
                    read(Modifier.padding(horizontal = 4.dp).weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GalleryDetailHeader(info: GalleryInfo, covers: GalleryCoverCache<CoilImage>, onInformation: () -> Unit, onCategory: () -> Unit, onUploader: () -> Unit, modifier: Modifier, fallbackCoverKey: String?) {
    val key = info.thumbKey?.takeIf(String::isNotBlank) ?: fallbackCoverKey?.takeIf(String::isNotBlank)
    val request = key?.let { GalleryCoverRequest(info.gid, info.token, it) }
    ElevatedCard(modifier) {
        Row {
            Card(Modifier.size(128.dp, 192.dp)) { GalleryCoverImage(request, covers) }
            // Bound long uploader names on narrow windows; allow large-font content to grow vertically.
            Column(Modifier.weight(1f).heightIn(min = 192.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
                if (info is GalleryDetail) {
                    val detail = info
                    val informationLabel = galleryString(Res.string.gallery_info)
                    Card(onClick = onInformation, modifier = Modifier.padding(top = 8.dp, end = 16.dp).width(IntrinsicSize.Max).semantics { contentDescription = informationLabel }) {
                        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                            Row(Modifier.padding(8.dp)) {
                                Text(detail.language.orEmpty())
                                Spacer(Modifier.width(16.dp).weight(1f))
                                Text(detail.size.orEmpty())
                            }
                            Row(Modifier.padding(horizontal = 8.dp)) {
                                Text("♥ ${detail.favoriteCount}", Modifier.alignByBaseline())
                                Spacer(Modifier.width(16.dp).weight(1f))
                                Text(galleryPlural(Res.plurals.gallery_detail_page_count, detail.pages, detail.pages), Modifier.alignByBaseline())
                            }
                            Text(detail.posted.orEmpty(), Modifier.align(Alignment.CenterHorizontally).padding(8.dp))
                        }
                    }
                } else {
                    Spacer(Modifier.height(0.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    AssistChip(
                        onClick = onCategory,
                        enabled = GalleryCategory.entries.any { it.mask == info.category },
                        label = { Text(galleryCategoryName(info.category).uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(GalleryIcons.Label, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    AssistChip(
                        onClick = onUploader,
                        enabled = !info.uploader.isNullOrBlank() && !info.disowned && info.uploader != "(Disowned)",
                        label = { Text(info.uploader.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(GalleryIcons.AccountCircle, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}
