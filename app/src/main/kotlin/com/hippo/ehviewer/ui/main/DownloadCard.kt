package com.hippo.ehviewer.ui.main

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.CrystalCard
import com.ehviewer.core.ui.component.GalleryListCardRating
import com.ehviewer.core.ui.util.TransitionsVisibilityScope
import com.ehviewer.core.ui.util.listThumbGenerator
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.util.FileUtils

@Composable
context(_: SharedTransitionScope, _: TransitionsVisibilityScope)
fun DownloadCard(
    onClick: () -> Unit,
    onThumbClick: () -> Unit,
    onLongClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    info: DownloadInfo,
    selectMode: Boolean,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    dragHandle: (@Composable () -> Unit)? = null,
) = CrystalCard(modifier = modifier, onClick = onClick, onLongClick = onLongClick, interactionSource = interactionSource) {
    Row(modifier = Modifier.fillMaxWidth()) {
        val thumbnailModifier = Modifier.layout { measurable, constraints ->
            // Keep room for the rating and both row actions on narrow displays.
            val maxWidth = maxOf(constraints.maxWidth / 4, constraints.maxWidth - 192.dp.roundToPx())
            val thumbnail = measurable.measure(constraints.copy(maxWidth = maxWidth))
            layout(thumbnail.width, thumbnail.height) { thumbnail.placeRelative(0, 0) }
        }
        val thumb = remember {
            movableContentOf<DownloadInfo> {
                with(listThumbGenerator) {
                    EhAsyncCropThumb(
                        key = it,
                        modifier = Modifier.aspectRatio(DEFAULT_RATIO).fillMaxSize(),
                    )
                }
            }
        }
        if (selectMode) {
            Card(modifier = thumbnailModifier) {
                thumb(info)
            }
        } else {
            Card(onClick = onThumbClick, modifier = thumbnailModifier) {
                thumb(info)
            }
        }
        val stateFailed = stringResource(R.string.download_state_failed)
        val stateFailed2 = stringResource(R.string.download_state_failed_2, info.legacy)
        val downloadState by DownloadManager.collectDownloadState(info.gid)
        val archiving = DownloadManager.updatedDownloadInfo(info) { archiving }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp, top = 2.dp, end = 4.dp)) {
            Text(
                text = EhUtils.getSuitableTitle(info),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (downloadState != DownloadInfo.STATE_DOWNLOAD) {
                val stateText = when (downloadState) {
                    DownloadInfo.STATE_NONE -> stringResource(R.string.download_state_none)
                    DownloadInfo.STATE_WAIT -> stringResource(R.string.download_state_wait)
                    DownloadInfo.STATE_FAILED -> when {
                        info.archiveFailed -> stringResource(R.string.download_state_archive_failed)
                        info.legacy > 0 -> stateFailed2
                        else -> stateFailed
                    }
                    DownloadInfo.STATE_FINISH -> {
                        if (showProgress) {
                            val readProgress by remember { EhDB.getReadProgressFlow(info.gid) }.collectAsState(0)
                            if (readProgress > 0) "${readProgress + 1}/${info.pages}P" else "${info.pages}P"
                        } else {
                            "${info.pages}P"
                        }
                    }
                    else -> null // The item has been removed and this will be disposed soon
                }
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    Row {
                        Text(
                            text = info.uploader.orEmpty(),
                            modifier = Modifier.weight(1f).alignByBaseline().alpha(if (info.disowned) 0.5f else 1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stateText.orEmpty(),
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
            } else if (archiving) {
                Text(stringResource(R.string.download_state_archiving), style = MaterialTheme.typography.labelMedium)
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                var total by remember { mutableIntStateOf(info.total) }
                var finished by remember { mutableIntStateOf(info.finished) }
                var speed by remember { mutableLongStateOf(info.speed) }
                DownloadManager.updatedDownloadInfo(info) {
                    total = this.total
                    finished = this.finished
                    speed = this.speed
                }
                ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                    if (total <= 0 || finished < 0) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Row {
                            Text(text = "$finished/$total")
                            Spacer(modifier = Modifier.weight(1f))
                            Text(text = FileUtils.humanReadableByteCount(speed.coerceAtLeast(0)) + "/S")
                        }
                        LinearWavyProgressIndicator(
                            progress = { finished.toFloat() / total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    // Place the rating near the uploader text as there's more visual space
                    GalleryListCardRating(rating = info.rating, modifier = Modifier.padding(top = 1.dp, bottom = 3.dp))
                    val categoryColor = EhUtils.getCategoryColor(info.category)
                    val categoryText = EhUtils.getCategory(info.category).uppercase()
                    Text(
                        text = categoryText,
                        modifier = Modifier.clip(ShapeDefaults.Small).background(categoryColor).padding(vertical = 2.dp, horizontal = 8.dp),
                        color = EhUtils.getCategoryTextColor(categoryColor),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                dragHandle?.invoke()
                val running = downloadState == DownloadInfo.STATE_WAIT || downloadState == DownloadInfo.STATE_DOWNLOAD
                val icon = when (downloadState) {
                    DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD -> Icons.Default.Pause
                    DownloadInfo.STATE_FINISH -> Icons.Default.DownloadDone
                    else -> Icons.Default.PlayArrow
                }
                if (selectMode) {
                    Box(modifier = Modifier.offset(4.dp).minimumInteractiveComponentSize()) {
                        Icon(imageVector = icon, contentDescription = null)
                    }
                } else {
                    IconButton(onClick = if (running) onStop else onStart, shapes = IconButtonDefaults.shapes(), modifier = Modifier.offset(4.dp)) {
                        Icon(imageVector = icon, contentDescription = null)
                    }
                }
            }
        }
    }
}
