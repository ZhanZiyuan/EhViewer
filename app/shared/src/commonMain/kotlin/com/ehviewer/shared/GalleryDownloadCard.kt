package com.ehviewer.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.Image
import com.ehviewer.core.domain.download.GalleryDownload
import com.ehviewer.core.domain.download.GalleryDownloadFailure
import com.ehviewer.core.domain.download.GalleryDownloadState
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_download_complete
import ehviewer.app.shared.generated.resources.gallery_download_delete
import ehviewer.app.shared.generated.resources.gallery_download_downloading
import ehviewer.app.shared.generated.resources.gallery_download_failed
import ehviewer.app.shared.generated.resources.gallery_download_failure_caption
import ehviewer.app.shared.generated.resources.gallery_download_idle
import ehviewer.app.shared.generated.resources.gallery_download_paused
import ehviewer.app.shared.generated.resources.gallery_download_queued
import ehviewer.app.shared.generated.resources.gallery_download_storage_failed
import ehviewer.app.shared.generated.resources.gallery_download_waiting
import ehviewer.app.shared.generated.resources.gallery_more_options
import ehviewer.app.shared.generated.resources.gallery_pause
import ehviewer.app.shared.generated.resources.gallery_read_offline
import ehviewer.app.shared.generated.resources.gallery_resume

internal val GalleryDownload.previewRequest get() = GalleryCoverRequest(gid, token, "download:$gid:$token")

/** v1.15.10 DownloadCard geometry; local previews and public metadata keep the same common UI. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GalleryDownloadCard(task: GalleryDownload, previews: GalleryCoverCache<Image>, enabled: Boolean, previewReady: Boolean, onAction: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(task.gid) { mutableStateOf(false) }
    val running = task.state in setOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING)
    val complete = task.state == GalleryDownloadState.COMPLETE
    val action = galleryString(
        if (running) {
            Res.string.gallery_pause
        } else if (complete) {
            Res.string.gallery_read_offline
        } else {
            Res.string.gallery_resume
        },
    )
    val state = galleryString(
        when (task.state) {
            GalleryDownloadState.QUEUED -> Res.string.gallery_download_queued
            GalleryDownloadState.DOWNLOADING -> Res.string.gallery_download_downloading
            GalleryDownloadState.PAUSED -> Res.string.gallery_download_paused
            GalleryDownloadState.FAILED -> if (task.failure == GalleryDownloadFailure.STORAGE) Res.string.gallery_download_storage_failed else Res.string.gallery_download_failed
            GalleryDownloadState.COMPLETE -> Res.string.gallery_download_complete
        },
    )
    val more = galleryString(Res.string.gallery_more_options)
    val density = LocalDensity.current
    val baseHeight = galleryListCardHeight(density)
    val height = baseHeight * maxOf(1f, density.fontScale)
    Box {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth().height(height)
                .semantics { stateDescription = "$state · ${task.finished} / ${task.pages}" }
                .combinedClickable(enabled = enabled, onClick = { if (complete) onAction() else menu = true }, onLongClickLabel = more, onLongClick = { menu = true })
                .onKeyEvent { event ->
                    if (enabled && event.type == KeyEventType.KeyDown && event.key == Key.F10 && event.isShiftPressed) {
                        menu = true
                        true
                    } else {
                        false
                    }
                }
                .pointerInput(enabled) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (enabled && event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                menu = true
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
            border = galleryCrystalBorder(),
        ) {
            Row {
                Card(Modifier.width(baseHeight * GALLERY_COVER_RATIO).height(baseHeight).align(Alignment.CenterVertically)) { GalleryCoverImage(task.previewRequest.takeIf { previewReady && task.finished > 0 }, previews) }
                Column(Modifier.padding(start = 8.dp, top = 2.dp, end = 4.dp)) {
                    Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    if (task.state == GalleryDownloadState.DOWNLOADING) {
                        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                            if (task.pages > 0) {
                                Text("${task.finished}/${task.pages}")
                                LinearWavyProgressIndicator(progress = { task.finished.toFloat() / task.pages }, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    } else {
                        Row {
                            Text(task.metadata?.uploader.orEmpty(), Modifier.weight(1f).alpha(if (task.metadata?.disowned == true) 0.5f else 1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                            val caption = when (task.state) {
                                GalleryDownloadState.COMPLETE -> "${task.pages}P"
                                GalleryDownloadState.PAUSED -> galleryString(Res.string.gallery_download_idle)
                                GalleryDownloadState.QUEUED -> galleryString(Res.string.gallery_download_waiting)
                                GalleryDownloadState.FAILED -> if (task.failure == GalleryDownloadFailure.STORAGE) state else galleryString(Res.string.gallery_download_failure_caption)
                                else -> state
                            }
                            Text(caption, maxLines = 2, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Row {
                        task.metadata?.let { metadata ->
                            Column {
                                metadata.rating?.let { GalleryRating(it, Modifier.padding(top = 1.dp, bottom = 3.dp)) }
                                val color = galleryCategoryColor(metadata.category)
                                Text(galleryCategoryName(metadata.category).uppercase(), Modifier.clip(ShapeDefaults.Small).background(color).padding(vertical = 2.dp, horizontal = 8.dp), color = galleryCategoryTextColor(color), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onAction, enabled = enabled, shapes = IconButtonDefaults.shapes(), modifier = Modifier.offset(4.dp)) {
                            Icon(
                                if (running) {
                                    GalleryIcons.Pause
                                } else if (complete) {
                                    GalleryIcons.DownloadDone
                                } else {
                                    GalleryIcons.PlayArrow
                                },
                                action,
                            )
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(action) }, enabled = enabled, onClick = {
                menu = false
                onAction()
            })
            DropdownMenuItem(text = { Text(galleryString(Res.string.gallery_download_delete)) }, enabled = enabled, onClick = {
                menu = false
                onDelete()
            })
        }
    }
}
