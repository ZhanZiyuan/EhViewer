package com.ehviewer.shared

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.domain.reader.ReadingDirection
import com.ehviewer.core.domain.reader.ReadingLayout
import com.ehviewer.core.domain.reader.ReadingPosition
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryImagePage
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.shared.reader.readingResumeUrl
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_back
import ehviewer.app.shared.generated.resources.gallery_display_image
import ehviewer.app.shared.generated.resources.gallery_gallery_page
import ehviewer.app.shared.generated.resources.gallery_go_to_page
import ehviewer.app.shared.generated.resources.gallery_next_page
import ehviewer.app.shared.generated.resources.gallery_original_image
import ehviewer.app.shared.generated.resources.gallery_previous_page
import ehviewer.app.shared.generated.resources.gallery_reading_continuous
import ehviewer.app.shared.generated.resources.gallery_reading_ltr
import ehviewer.app.shared.generated.resources.gallery_reading_preferences_unavailable
import ehviewer.app.shared.generated.resources.gallery_reading_progress_save_failed
import ehviewer.app.shared.generated.resources.gallery_reading_rtl
import ehviewer.app.shared.generated.resources.gallery_reset_zoom
import ehviewer.app.shared.generated.resources.gallery_retry
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** A bounded, cancellable online page reader shared by touch and desktop applications. */
@Composable
internal fun GalleryReaderScreen(session: GalleryLibrary, detail: GalleryDetail, initial: String, readerState: ReaderStateStore, onBack: () -> Unit) {
    var url by remember(initial) { mutableStateOf(initial) }
    var reloadKey by remember(url) { mutableStateOf<String?>(null) }
    var original by remember(url) { mutableStateOf(false) }
    var document by remember { mutableStateOf<GalleryImagePage?>(null) }
    var bitmap by remember { mutableStateOf<GalleryMedia?>(null) }
    var failure by remember { mutableStateOf<GalleryFailure?>(null) }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var preferences by remember { mutableStateOf(ReaderPreferences()) }
    var preferencesFailure by remember { mutableStateOf(false) }
    var preferencesAttempt by remember { mutableIntStateOf(0) }
    var settingsSaving by remember { mutableStateOf(false) }
    var saveFailure by remember(url) { mutableStateOf(false) }
    var jumpOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val currentUrl = url
    val index = session.content.pageIndex(currentUrl, detail.gid)
    val rtl = preferences.direction == ReadingDirection.RIGHT_TO_LEFT
    suspend fun saveProgress() {
        try {
            readerState.savePosition(detail.gid, index, currentUrl.substringAfter("/s/").substringBefore('/'))
            saveFailure = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ReaderStateException) {
            saveFailure = true
        }
    }
    LaunchedEffect(readerState, preferencesAttempt) {
        try {
            preferences = readerState.preferences()
            preferencesFailure = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ReaderStateException) {
            preferencesFailure = true
        }
    }
    fun changePreferences(changed: ReaderPreferences, afterSave: () -> Unit = {}) {
        settingsSaving = true
        scope.launch {
            try {
                readerState.setPreferences(changed)
                afterSave()
                preferences = changed
                preferencesFailure = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ReaderStateException) {
                preferencesFailure = true
            } finally {
                settingsSaving = false
            }
        }
    }
    if (preferences.layout == ReadingLayout.CONTINUOUS) {
        val start = remember(preferences.layout) { currentUrl }
        Column(Modifier.fillMaxSize()) {
            if (preferencesFailure) ReaderStateError(galleryString(Res.string.gallery_reading_preferences_unavailable)) { preferencesAttempt++ }
            GalleryContinuousReader(session, detail, start, readerState, onBack, onPaged = { position ->
                if (!settingsSaving) changePreferences(preferences.copy(layout = ReadingLayout.PAGED)) { url = position }
            })
        }
        return
    }
    fun target(link: String?, delta: Int): String? = link?.takeIf { session.content.pageIndex(it, detail.gid) == index + delta && index + delta in 0 until detail.pages }
    val previous = target(document?.previousPageUrl, -1)
    val next = target(document?.nextPageUrl, 1)
    LaunchedEffect(currentUrl, attempt, original) {
        loading = true
        document = null
        bitmap = null
        failure = null
        zoom = 1f
        pan = Offset.Zero
        try {
            val page = session.content.page(currentUrl, detail.gid, reloadKey)
            document = page
            val bytes = if (original) session.content.originalImage(page, currentUrl, detail.gid) else session.content.image(page, currentUrl, detail.gid)
            bitmap = decodeGalleryMedia(bytes)
            saveProgress()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GalleryRequestException) {
            failure = error.failure
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    if (jumpOpen) {
        GalleryReaderPageDialog(index, detail.pages, onJump = { target ->
            if (target != index) {
                val resolved = readingResumeUrl(session.content, detail, ReadingPosition(detail.gid, target, null, 0))
                currentCoroutineContext().ensureActive()
                url = resolved
            }
        }, onDismiss = { jumpOpen = false })
    }
    val jumpLabel = galleryString(Res.string.gallery_go_to_page)
    Column(
        Modifier.fillMaxSize().focusRequester(focus).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Escape -> onBack()
                Key.DirectionLeft -> (if (rtl) next else previous)?.let { url = it } ?: return@onPreviewKeyEvent false
                Key.DirectionRight -> (if (rtl) previous else next)?.let { url = it } ?: return@onPreviewKeyEvent false
                Key.PageUp -> previous?.let { url = it } ?: return@onPreviewKeyEvent false
                Key.PageDown -> next?.let { url = it } ?: return@onPreviewKeyEvent false
                else -> return@onPreviewKeyEvent false
            }
            true
        }.focusable(),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(galleryString(Res.string.gallery_back)) }
            TextButton(onClick = { jumpOpen = true }, modifier = Modifier.semantics { contentDescription = jumpLabel }) { Text("${index + 1} / ${detail.pages}") }
            TextButton(onClick = {
                zoom = 1f
                pan = Offset.Zero
            }) { Text(galleryString(Res.string.gallery_reset_zoom)) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (preferencesFailure) ReaderStateError(galleryString(Res.string.gallery_reading_preferences_unavailable)) { preferencesAttempt++ }
        if (saveFailure) ReaderStateError(galleryString(Res.string.gallery_reading_progress_save_failed)) { scope.launch { saveProgress() } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { changePreferences(preferences.copy(layout = ReadingLayout.CONTINUOUS)) }, enabled = !settingsSaving) { Text(galleryString(Res.string.gallery_reading_continuous)) }
            TextButton(onClick = {
                val changed = preferences.copy(direction = if (rtl) ReadingDirection.LEFT_TO_RIGHT else ReadingDirection.RIGHT_TO_LEFT)
                changePreferences(changed)
            }, enabled = !settingsSaving) { Text(if (rtl) galleryString(Res.string.gallery_reading_rtl) else galleryString(Res.string.gallery_reading_ltr)) }
        }
        if (original || document?.originImageUrl != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { original = !original }, enabled = !loading) { Text(if (original) galleryString(Res.string.gallery_display_image) else galleryString(Res.string.gallery_original_image)) }
            }
        }
        failure?.let {
            GalleryError(it) {
                reloadKey = document?.skipHathKey
                attempt++
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport = it }
                .pointerInput(url) {
                    detectTapGestures(onDoubleTap = {
                        zoom = if (zoom > 1f) 1f else 2f
                        pan = Offset.Zero
                    })
                }
                .transformable(
                    rememberTransformableState { centroid, scale, offset, _ ->
                        val oldZoom = zoom
                        zoom = (zoom * scale).coerceIn(1f, 5f)
                        val anchor = if (centroid.isSpecified) centroid - Offset(viewport.width / 2f, viewport.height / 2f) else Offset.Zero
                        val moved = (pan - anchor) * (zoom / oldZoom) + anchor + offset
                        val image = bitmap
                        pan = if (zoom == 1f || image == null) {
                            Offset.Zero
                        } else {
                            val fit = min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
                            val maxX = ((image.width * fit * zoom - viewport.width) / 2).coerceAtLeast(0f)
                            val maxY = ((image.height * fit * zoom - viewport.height) / 2).coerceAtLeast(0f)
                            Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let { image ->
                GalleryMediaImage(
                    image,
                    galleryString(Res.string.gallery_gallery_page, index + 1),
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (rtl) {
                ReaderPageButton(galleryString(Res.string.gallery_next_page), next) { url = it }
                ReaderPageButton(galleryString(Res.string.gallery_previous_page), previous) { url = it }
            } else {
                ReaderPageButton(galleryString(Res.string.gallery_previous_page), previous) { url = it }
                ReaderPageButton(galleryString(Res.string.gallery_next_page), next) { url = it }
            }
        }
    }
}

@Composable
private fun ReaderPageButton(label: String, target: String?, navigate: (String) -> Unit) {
    TextButton(onClick = { target?.let(navigate) }, enabled = target != null) { Text(label) }
}

@Composable
private fun ReaderStateError(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(message, Modifier.weight(1f))
        TextButton(onClick = retry) { Text(galleryString(Res.string.gallery_retry)) }
    }
}
