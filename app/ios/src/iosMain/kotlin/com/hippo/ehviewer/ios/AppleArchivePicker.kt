@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hippo.ehviewer.ios

import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveLibrary
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    suspend fun pickAndImport(library: ArchiveLibrary): ArchiveBook? {
        val url = withContext<NSURL?>(Dispatchers.Main) {
            suspendCancellableCoroutine<NSURL?> { continuation ->
                check(active == null)
                val controller = checkNotNull(presenter())
                check(controller.presentedViewController == null)
                val types = listOf("cbz", "zip", "cbr", "rar", "7z", "cb7", "tar", "gz", "xz").mapNotNull { UTType.typeWithFilenameExtension(it) }
                check(types.isNotEmpty())
                val request = Request(continuation)
                val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
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
            return library.importFile(path, title)
        } finally {
            if (accessing) url.stopAccessingSecurityScopedResource()
        }
    }

    override fun close() {
        active?.continuation?.cancel()
    }
}
