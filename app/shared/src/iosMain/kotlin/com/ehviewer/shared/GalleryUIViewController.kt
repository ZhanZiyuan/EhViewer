@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ehviewer.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.ComposeUIViewController
import kotlin.native.ref.WeakReference
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController

/** UIKit owns system bars; the same Compose reader owns content and navigation on every platform. */
fun galleryViewController(content: @Composable () -> Unit): UIViewController {
    val presentation = AppleReaderPresentation()
    val compose = ComposeUIViewController {
        CompositionLocalProvider(LocalGalleryReaderPresentation provides presentation, content = content)
    }
    val host = GalleryReaderViewController(compose, presentation)
    // Compose retains its content: its presentation callback must not retain the UIKit parent.
    val weakHost = WeakReference(host)
    presentation.changed = { weakHost.get()?.setNeedsStatusBarAppearanceUpdate() }
    return host
}

private class GalleryReaderViewController(private val compose: UIViewController, private val presentation: AppleReaderPresentation) : UIViewController(nibName = null, bundle = null) {
    override fun viewDidLoad() {
        super.viewDidLoad()
        addChildViewController(compose)
        val child = compose.view
        child.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(child)
        // Pin to the actual viewport, not a fixed initial frame or the safe-area rectangle.
        // Compose applies safe drawing insets on ordinary pages and owns the full reader canvas.
        NSLayoutConstraint.activateConstraints(
            listOf(
                child.leadingAnchor.constraintEqualToAnchor(view.leadingAnchor),
                child.trailingAnchor.constraintEqualToAnchor(view.trailingAnchor),
                child.topAnchor.constraintEqualToAnchor(view.topAnchor),
                child.bottomAnchor.constraintEqualToAnchor(view.bottomAnchor),
            ),
        )
        compose.didMoveToParentViewController(this)
    }

    override fun prefersStatusBarHidden(): Boolean = presentation.statusBarHidden

    override fun preferredStatusBarStyle() = compose.preferredStatusBarStyle()
}

private class AppleReaderPresentation : GalleryReaderPresentation {
    private val requests = mutableMapOf<Any, Boolean>()
    val statusBarHidden get() = requests.isNotEmpty() && requests.values.none { it }
    var changed: () -> Unit = {}
    override fun enter(controlsVisible: Boolean): () -> Unit {
        val owner = Any()
        requests[owner] = controlsVisible
        changed()
        return {
            check(requests.remove(owner) != null)
            changed()
        }
    }
}
