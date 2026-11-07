@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hippo.ehviewer.ios

import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveException
import com.ehviewer.core.domain.reader.ArchiveFailure
import com.ehviewer.core.domain.reader.ArchiveLibrary
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDevice
import platform.UIKit.UIPopoverPresentationControllerDelegateProtocol
import platform.UIKit.UIPresentationController
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UIKit.presentationController
import platform.darwin.NSObject

/** The system reads only a private immutable copy, retained until activity completion/dismissal. */
internal class AppleArchiveShare(private val presenter: () -> UIViewController?) : AutoCloseable {
    // Accessed only on Main, including composition disposal.
    private var active: Request? = null

    private class Request(val sheet: UIActivityViewController) : NSObject(), UIPopoverPresentationControllerDelegateProtocol {
        var continuation: CancellableContinuation<Boolean>? = null

        fun finish(completed: Boolean, error: NSError? = null) {
            val waiting = continuation ?: return
            continuation = null
            if (waiting.isActive) {
                if (error == null) waiting.resume(completed) else waiting.resumeWith(Result.failure(ArchiveException(ArchiveFailure.STORAGE)))
            }
        }

        override fun presentationControllerDidDismiss(presentationController: UIPresentationController) = finish(false)

        suspend fun dismiss() {
            sheet.completionWithItemsHandler = null
            sheet.presentationController?.delegate = null
            sheet.popoverPresentationController?.delegate = null
            continuation = null
            if (sheet.presentingViewController != null) {
                suspendCoroutine<Unit> { dismissed ->
                    sheet.dismissViewControllerAnimated(false) { dismissed.resume(Unit) }
                }
            }
        }
    }

    suspend fun share(library: ArchiveLibrary, book: ArchiveBook): Boolean {
        val snapshot = library.prepareExport(book)
        try {
            return withContext(Dispatchers.Main) {
                check(active == null)
                val controller = checkNotNull(presenter())
                check(controller.presentedViewController == null && controller.view.window != null)
                val url = NSURL.fileURLWithPath(snapshot.path, isDirectory = snapshot.isDirectory)
                val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
                val request = Request(sheet)
                // iPhone uses the native modal sheet; iPad needs an explicit, live window anchor.
                if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
                    val popover = checkNotNull(sheet.popoverPresentationController)
                    popover.sourceView = controller.view
                    popover.sourceRect = controller.view.bounds.useContents { CGRectMake(size.width / 2, size.height / 2, 1.0, 1.0) }
                    popover.permittedArrowDirections = 0u
                    popover.delegate = request
                }
                active = request
                try {
                    suspendCancellableCoroutine { continuation ->
                        request.continuation = continuation
                        sheet.completionWithItemsHandler = { _, completed, _, error -> request.finish(completed, error) }
                        sheet.presentationController?.delegate = request
                        controller.presentViewController(sheet, animated = true, completion = null)
                    }
                } finally {
                    // Coroutine cancellation does not permit deleting a file still in use by UIKit.
                    withContext(NonCancellable) {
                        try {
                            request.dismiss()
                        } finally {
                            if (active === request) active = null
                        }
                    }
                }
            }
        } finally {
            snapshot.close()
        }
    }

    override fun close() {
        active?.continuation?.cancel()
    }
}
