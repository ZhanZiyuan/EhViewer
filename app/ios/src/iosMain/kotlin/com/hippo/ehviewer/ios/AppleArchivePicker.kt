@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hippo.ehviewer.ios

import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import com.ehviewer.core.domain.reader.ArchiveLibrary
import kotlin.coroutines.resume
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** UIKit owns the provider UI. Keep security-scoped access only through the common private copy. */
internal class AppleArchivePicker(private val presenter: () -> UIViewController?) : AutoCloseable {
    private var active: Request? = null

    private inner class Request(val continuation: CancellableContinuation<NSURL?>) : NSObject(), UIDocumentPickerDelegateProtocol {
        lateinit var picker: UIDocumentPickerViewController

        fun finish(url: NSURL?) {
            if (active !== this) return
            active = null
            picker.dismissViewControllerAnimated(true, null)
            if (continuation.isActive) continuation.resume(url)
        }

        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) = finish(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = finish(null)
    }

    suspend fun pickAndImport(library: ArchiveLibrary, directory: Boolean = false): ArchiveBook? {
        val url = withContext<NSURL?>(Dispatchers.Main) {
            suspendCancellableCoroutine<NSURL?> { continuation ->
                check(active == null)
                val controller = checkNotNull(presenter())
                check(controller.presentedViewController == null)
                val types = if (directory) listOfNotNull(UTType.typeWithIdentifier("public.folder")) else listOf("cbz", "zip", "cbr", "rar", "7z", "cb7", "tar", "gz", "xz").mapNotNull { UTType.typeWithFilenameExtension(it) }
                check(types.isNotEmpty())
                val request = Request(continuation)
                val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = !directory)
                request.picker = picker
                active = request
                picker.delegate = request
                picker.allowsMultipleSelection = false
                continuation.invokeOnCancellation {
                    dispatch_async(dispatch_get_main_queue()) {
                        if (active === request) {
                            active = null
                            picker.dismissViewControllerAnimated(false, null)
                        }
                    }
                }
                controller.presentViewController(picker, animated = true, completion = null)
            }
        } ?: return null
        val accessing = url.startAccessingSecurityScopedResource()
        try {
            val path = checkNotNull(url.path)
            val title = (url.lastPathComponent ?: "Imported comic").replace('\n', ' ').replace('\r', ' ').take(1024)
            return if (directory) coordinatedDirectoryImport(library, url, title) else library.importFile(path, title)
        } finally {
            if (accessing) url.stopAccessingSecurityScopedResource()
        }
    }

    private suspend fun coordinatedDirectoryImport(library: ArchiveLibrary, url: NSURL, title: String): ArchiveBook = coroutineScope {
        val coordinator = NSFileCoordinator(filePresenter = null)
        val worker = async(Dispatchers.IO) {
            val context = currentCoroutineContext()
            context.ensureActive()
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                error.value = null
                var result: ArchiveBook? = null
                var failure: Throwable? = null
                coordinator.coordinateReadingItemAtURL(url, options = 0u, error = error.ptr) { coordinated ->
                    // Exceptions must return through Kotlin, never unwind across the Objective-C block.
                    try {
                        result = runBlocking(context) { library.importDirectory(checkNotNull(coordinated?.path), title) }
                    } catch (caught: Throwable) {
                        failure = caught
                    }
                }
                failure?.let { throw it }
                if (error.value != null) throw ArchiveException(ArchiveFailure.STORAGE)
                result ?: throw ArchiveException(ArchiveFailure.STORAGE)
            }
        }
        try {
            worker.await()
        } catch (cancelled: CancellationException) {
            coordinator.cancel()
            // Keep the security-scoped URL alive until the admitted copy has actually stopped.
            withContext(NonCancellable) { worker.cancelAndJoin() }
            throw cancelled
        }
    }

    override fun close() {
        active?.continuation?.cancel()
    }
}
