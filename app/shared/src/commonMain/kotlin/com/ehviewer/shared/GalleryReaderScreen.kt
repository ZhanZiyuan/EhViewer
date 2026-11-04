package com.ehviewer.shared

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    GalleryImmersive()
    val chrome = rememberGalleryReaderChromeState()
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
            GalleryContinuousReader(session, detail, start, readerState, onBack, chrome, onPaged = { position ->
                if (!settingsSaving) {
                    changePreferences(preferences.copy(layout = ReadingLayout.PAGED)) {
                        url = position
                        chrome.visible = false
                    }
                }
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
    var pendingSeek by remember { mutableStateOf<Int?>(null) }
    var seekFailure by remember { mutableStateOf<GalleryFailure?>(null) }
    var seekAttempt by remember { mutableIntStateOf(0) }
    val seeking = pendingSeek != null && seekFailure == null
    if (jumpOpen) {
        GalleryReaderPageDialog(index, detail.pages, onJump = { target ->
            if (target != index) {
                val resolved = readingResumeUrl(session.content, detail, ReadingPosition(detail.gid, target, null, 0))
                currentCoroutineContext().ensureActive()
                pendingSeek = null
                seekFailure = null
                url = resolved
            }
        }, onDismiss = { jumpOpen = false })
    }
    LaunchedEffect(pendingSeek, seekAttempt) {
        val target = pendingSeek ?: return@LaunchedEffect
        seekFailure = null
        try {
            val resolved = readingResumeUrl(session.content, detail, ReadingPosition(detail.gid, target, null, 0))
            currentCoroutineContext().ensureActive()
            url = resolved
            pendingSeek = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GalleryRequestException) {
            currentCoroutineContext().ensureActive()
            seekFailure = error.failure
        }
    }
    fun navigate(target: String) {
        pendingSeek = null
        seekFailure = null
        url = target
    }
    GalleryReaderChrome(
        title = detail.title.orEmpty(), index = index, pages = detail.pages, state = chrome,
        onBack = onBack, onJump = { jumpOpen = true },
        onSeek = {
            if (it != index) {
                seekFailure = null
                pendingSeek = it
            }
        },
        seekEnabled = !loading && !seeking, rtl = rtl,
        modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || seeking && event.key != Key.Escape) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Escape -> onBack()
                Key.Spacebar -> chrome.toggle()
                Key.DirectionLeft -> (if (rtl) next else previous)?.let(::navigate) ?: return@onPreviewKeyEvent false
                Key.DirectionRight -> (if (rtl) previous else next)?.let(::navigate) ?: return@onPreviewKeyEvent false
                Key.PageUp -> previous?.let(::navigate) ?: return@onPreviewKeyEvent false
                Key.PageDown -> next?.let(::navigate) ?: return@onPreviewKeyEvent false
                else -> return@onPreviewKeyEvent false
            }
            true
        }.focusable(),
        status = {
            if (loading || seeking) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (preferencesFailure) ReaderStateError(galleryString(Res.string.gallery_reading_preferences_unavailable)) { preferencesAttempt++ }
            if (saveFailure) ReaderStateError(galleryString(Res.string.gallery_reading_progress_save_failed)) { scope.launch { saveProgress() } }
            seekFailure?.let {
                GalleryError(it) {
                    seekFailure = null
                    seekAttempt++
                }
            }
            failure?.let {
                GalleryError(it) {
                    reloadKey = document?.skipHathKey
                    attempt++
                }
            }
        },
        navigation = {
            ReaderPageButton(if (rtl) galleryString(Res.string.gallery_next_page) else galleryString(Res.string.gallery_previous_page), (if (rtl) next else previous)?.takeUnless { seeking }, right = false, navigate = ::navigate)
            ReaderPageButton(if (rtl) galleryString(Res.string.gallery_previous_page) else galleryString(Res.string.gallery_next_page), (if (rtl) previous else next)?.takeUnless { seeking }, right = true, navigate = ::navigate)
        },
        actions = {
            GalleryReaderAction(galleryString(Res.string.gallery_reading_continuous), GalleryIcons.LibraryBooks, !settingsSaving && !seeking) { changePreferences(preferences.copy(layout = ReadingLayout.CONTINUOUS)) { chrome.visible = false } }
            GalleryReaderAction(if (rtl) galleryString(Res.string.gallery_reading_rtl) else galleryString(Res.string.gallery_reading_ltr), GalleryIcons.SwapHoriz, !settingsSaving) {
                changePreferences(preferences.copy(direction = if (rtl) ReadingDirection.LEFT_TO_RIGHT else ReadingDirection.RIGHT_TO_LEFT))
            }
            GalleryReaderAction(galleryString(Res.string.gallery_reset_zoom), GalleryIcons.ZoomOutMap) {
                zoom = 1f
                pan = Offset.Zero
            }
            if (original || document?.originImageUrl != null) {
                GalleryReaderAction(if (original) galleryString(Res.string.gallery_display_image) else galleryString(Res.string.gallery_original_image), GalleryIcons.Download, !loading && !seeking) { original = !original }
            }
        },
    ) {
        Box(
            Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }
                .pointerInput(url, previous, next, rtl) {
                    detectTapGestures(onTap = { point ->
                        val target = if (zoom == 1f && !seeking) {
                            when {
                                point.x < size.width / 3f -> if (rtl) next else previous
                                point.x > size.width * 2f / 3f -> if (rtl) previous else next
                                else -> null
                            }
                        } else {
                            null
                        }
                        if (target == null) chrome.toggle() else navigate(target)
                    }, onDoubleTap = {
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
    }
}

@Composable
private fun ReaderPageButton(label: String, target: String?, right: Boolean, navigate: (String) -> Unit) {
    IconButton(onClick = { target?.let(navigate) }, enabled = target != null) { Icon(GalleryIcons.ArrowBack, label, Modifier.graphicsLayer { rotationZ = if (right) 180f else 0f }) }
}

@Composable
private fun ReaderStateError(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(message, Modifier.weight(1f))
        TextButton(onClick = retry) { Text(galleryString(Res.string.gallery_retry)) }
    }
}
