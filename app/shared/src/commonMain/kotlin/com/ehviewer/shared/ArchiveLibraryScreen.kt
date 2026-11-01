package com.ehviewer.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveDocument
import com.ehviewer.core.domain.reader.ArchiveLibrary
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.network.GalleryLibrary
import com.ehviewer.core.network.archive.ArchiveGalleryContent
import com.ehviewer.shared.reader.CachedReaderStateStore
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_archive_password
import ehviewer.app.shared.generated.resources.gallery_archive_password_failed
import ehviewer.app.shared.generated.resources.gallery_archive_unlock
import ehviewer.app.shared.generated.resources.gallery_back
import ehviewer.app.shared.generated.resources.gallery_back_library
import ehviewer.app.shared.generated.resources.gallery_comic_delete
import ehviewer.app.shared.generated.resources.gallery_comic_export
import ehviewer.app.shared.generated.resources.gallery_comic_read
import ehviewer.app.shared.generated.resources.gallery_comic_share
import ehviewer.app.shared.generated.resources.gallery_export_cancel
import ehviewer.app.shared.generated.resources.gallery_export_cancelled
import ehviewer.app.shared.generated.resources.gallery_export_cancelled_partial
import ehviewer.app.shared.generated.resources.gallery_export_failed
import ehviewer.app.shared.generated.resources.gallery_export_saved
import ehviewer.app.shared.generated.resources.gallery_import_cancel
import ehviewer.app.shared.generated.resources.gallery_library_empty
import ehviewer.app.shared.generated.resources.gallery_library_import_archive
import ehviewer.app.shared.generated.resources.gallery_library_import_folder
import ehviewer.app.shared.generated.resources.gallery_library_open_failed
import ehviewer.app.shared.generated.resources.gallery_library_retry_unavailable
import ehviewer.app.shared.generated.resources.gallery_library_state_failed
import ehviewer.app.shared.generated.resources.gallery_library_unavailable
import ehviewer.app.shared.generated.resources.gallery_local_library
import ehviewer.app.shared.generated.resources.gallery_page_count
import ehviewer.app.shared.generated.resources.gallery_page_count_protected
import ehviewer.app.shared.generated.resources.gallery_retry
import ehviewer.app.shared.generated.resources.gallery_sharing_cancel
import ehviewer.app.shared.generated.resources.gallery_sharing_cancelled
import ehviewer.app.shared.generated.resources.gallery_sharing_cancelled_partial
import ehviewer.app.shared.generated.resources.gallery_sharing_completed
import ehviewer.app.shared.generated.resources.gallery_sharing_failed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource

/** One local-library/password/reader UI; platforms supply only their real file chooser and vault. */
private enum class ArchiveTransfer { EXPORT, SHARE }

@Composable
fun ArchiveLibraryScreen(library: ArchiveLibrary, online: GalleryLibrary, pickAndImport: suspend () -> ArchiveBook?, pickAndImportDirectory: suspend () -> ArchiveBook?, pickAndExport: suspend (ArchiveBook) -> Boolean, readingState: (ArchiveBook) -> ReaderStateStore, onBack: () -> Unit, shareComic: (suspend (ArchiveBook) -> Boolean)? = null) {
    var books by remember(library) { mutableStateOf<List<ArchiveBook>>(emptyList()) }
    var unavailableCopies by remember(library) { mutableIntStateOf(0) }
    var selected by remember(library) { mutableStateOf<ArchiveBook?>(null) }
    var document by remember(library) { mutableStateOf<ArchiveDocument?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<ArchiveTransfer?>(null) }
    var transferFailed by remember { mutableStateOf<ArchiveTransfer?>(null) }
    var transferMessage by remember { mutableStateOf<StringResource?>(null) }
    var request by remember { mutableStateOf<Job?>(null) }
    var unlocked by remember(selected) { mutableStateOf(false) }
    var password by remember(selected) { mutableStateOf("") }
    var passwordFailed by remember(selected) { mutableStateOf(false) }
    val owners = remember(library) { mutableListOf<ArchiveDocument>() }
    val scope = rememberCoroutineScope()
    DisposableEffect(owners) { onDispose { owners.forEach { it.close() } } }
    fun back() {
        request?.cancel()
        password = ""
        if (selected == null) onBack() else selected = null
    }
    BackHandler(onBack = ::back)
    LaunchedEffect(library, selected, attempt) {
        document?.let {
            owners.remove(it)
            it.close()
        }
        document = null
        failure = false
        loading = true
        try {
            val book = selected
            if (book == null) {
                val snapshot = library.inspect()
                books = snapshot.books
                unavailableCopies = snapshot.unavailableCopies
            } else {
                val opened = library.open(book)
                owners += opened
                document = opened
                unlocked = !opened.needsPassword
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure = true
        } finally {
            loading = false
        }
    }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = false
        transferFailed = null
        transferMessage = null
        request = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = true
            } finally {
                password = ""
                if (scope.isActive && selected == null) {
                    withContext(NonCancellable) {
                        try {
                            val snapshot = library.inspect()
                            books = snapshot.books
                            unavailableCopies = snapshot.unavailableCopies
                        } catch (_: Exception) {
                            failure = true
                        }
                    }
                }
                busy = false
            }
        }
    }
    fun transferComic(item: ArchiveBook, kind: ArchiveTransfer, action: suspend (ArchiveBook) -> Boolean) {
        perform {
            transfer = kind
            try {
                val completed = action(item)
                transferMessage = when (kind) {
                    ArchiveTransfer.EXPORT -> if (completed) Res.string.gallery_export_saved else Res.string.gallery_export_cancelled
                    ArchiveTransfer.SHARE -> if (completed) Res.string.gallery_sharing_completed else Res.string.gallery_sharing_cancelled
                }
            } catch (cancelled: CancellationException) {
                transferMessage = if (kind == ArchiveTransfer.EXPORT) Res.string.gallery_export_cancelled_partial else Res.string.gallery_sharing_cancelled_partial
                throw cancelled
            } catch (error: Exception) {
                transferFailed = kind
                throw error
            } finally {
                transfer = null
            }
        }
    }
    val opened = document
    val book = selected
    if (book != null && opened != null && unlocked) {
        val content = remember(book, opened) { ArchiveGalleryContent(book, opened) }
        val local = remember(online, content) {
            object : GalleryLibrary by online {
                override val content = content
                override suspend fun details(gid: Long, token: String) = content.details(gid, token)
            }
        }
        val state = remember(book, readingState) { CachedReaderStateStore(readingState(book)) }
        var ready by remember(book, opened) { mutableStateOf<Pair<GalleryDetail, String>?>(null) }
        var stateFailed by remember(book, opened) { mutableStateOf(false) }
        var stateAttempt by remember { mutableIntStateOf(0) }
        LaunchedEffect(content, state, stateAttempt) {
            stateFailed = false
            try {
                val index = state.position(ArchiveGalleryContent.LOCAL_DOCUMENT)?.index?.takeIf { it in 0 until book.pages } ?: 0
                ready = content.details(ArchiveGalleryContent.LOCAL_DOCUMENT, content.token) to content.localUrl(index).toString()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                stateFailed = true
            }
        }
        val start = ready
        if (start != null) {
            GalleryReaderScreen(local, start.first, start.second, state, onBack = ::back)
            return
        }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = ::back) { Text(galleryString(Res.string.gallery_back_library)) }
            if (stateFailed) {
                Text(galleryString(Res.string.gallery_library_state_failed))
                TextButton(onClick = { stateAttempt++ }) { Text(galleryString(Res.string.gallery_retry)) }
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = ::back) { Text(if (book == null) galleryString(Res.string.gallery_back) else galleryString(Res.string.gallery_back_library)) }
        Text(book?.title ?: galleryString(Res.string.gallery_local_library))
        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failure) {
            Text(
                when (transferFailed) {
                    ArchiveTransfer.EXPORT -> galleryString(Res.string.gallery_export_failed)
                    ArchiveTransfer.SHARE -> galleryString(Res.string.gallery_sharing_failed)
                    null -> galleryString(Res.string.gallery_library_open_failed)
                },
            )
            TextButton(onClick = { attempt++ }, enabled = !busy) { Text(galleryString(Res.string.gallery_retry)) }
        }
        transferMessage?.let { Text(galleryString(it)) }
        if (book == null) {
            TextButton(onClick = { perform { pickAndImport()?.let { selected = it } } }, enabled = !busy && !loading) { Text(galleryString(Res.string.gallery_library_import_archive)) }
            TextButton(onClick = { perform { pickAndImportDirectory()?.let { selected = it } } }, enabled = !busy && !loading) { Text(galleryString(Res.string.gallery_library_import_folder)) }
            if (busy) {
                TextButton(onClick = { request?.cancel() }) {
                    Text(
                        when (transfer) {
                            ArchiveTransfer.EXPORT -> galleryString(Res.string.gallery_export_cancel)
                            ArchiveTransfer.SHARE -> galleryString(Res.string.gallery_sharing_cancel)
                            null -> galleryString(Res.string.gallery_import_cancel)
                        },
                    )
                }
            }
            if (unavailableCopies > 0) {
                Text(galleryString(Res.string.gallery_library_unavailable, unavailableCopies))
                TextButton(onClick = { attempt++ }, enabled = !busy && !loading) { Text(galleryString(Res.string.gallery_library_retry_unavailable)) }
            }
            if (!loading && books.isEmpty() && unavailableCopies == 0) Text(galleryString(Res.string.gallery_library_empty))
            LazyColumn(Modifier.weight(1f)) {
                items(books, key = { it.id }) { item ->
                    Column {
                        Text(item.title)
                        Text(galleryString(if (item.encrypted) Res.string.gallery_page_count_protected else Res.string.gallery_page_count, item.pages))
                        FlowRow {
                            TextButton(onClick = { selected = item }, enabled = !busy) { Text(galleryString(Res.string.gallery_comic_read)) }
                            TextButton(onClick = { transferComic(item, ArchiveTransfer.EXPORT, pickAndExport) }, enabled = !busy) { Text(galleryString(Res.string.gallery_comic_export)) }
                            shareComic?.let { share ->
                                TextButton(onClick = { transferComic(item, ArchiveTransfer.SHARE, share) }, enabled = !busy) { Text(galleryString(Res.string.gallery_comic_share)) }
                            }
                            TextButton(onClick = {
                                perform {
                                    library.remove(item)
                                    val snapshot = library.inspect()
                                    books = snapshot.books
                                    unavailableCopies = snapshot.unavailableCopies
                                }
                            }, enabled = !busy) { Text(galleryString(Res.string.gallery_comic_delete)) }
                        }
                    }
                }
            }
        } else if (opened != null && !unlocked) {
            OutlinedTextField(password, {
                password = it.take(4096)
                passwordFailed = false
            }, label = { Text(galleryString(Res.string.gallery_archive_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
            if (passwordFailed) Text(galleryString(Res.string.gallery_archive_password_failed))
            TextButton(onClick = {
                val candidate = password
                perform {
                    val accepted = withContext(Dispatchers.IO) { opened.providePassword(candidate) }
                    unlocked = accepted
                    passwordFailed = !accepted
                }
            }, enabled = !busy) { Text(galleryString(Res.string.gallery_archive_unlock)) }
        }
    }
}
