@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ehviewer.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import platform.UIKit.popoverPresentationController

@Composable
internal actual fun rememberGalleryLinkSharer(): ((String) -> Unit)? {
    val controller = LocalUIViewController.current
    return remember(controller) {
        { url ->
            check(controller.presentedViewController == null) { "A presentation is already open" }
            val activity = UIActivityViewController(listOf(requireNotNull(NSURL.URLWithString(url))), null)
            if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) {
                activity.popoverPresentationController?.let { popover ->
                    val view = controller.view
                    popover.sourceView = view
                    popover.sourceRect = view.bounds.useContents { CGRectMake(size.width / 2, size.height / 2, 1.0, 1.0) }
                    popover.permittedArrowDirections = 0uL
                }
            }
            controller.presentViewController(activity, animated = true, completion = null)
        }
    }
}
