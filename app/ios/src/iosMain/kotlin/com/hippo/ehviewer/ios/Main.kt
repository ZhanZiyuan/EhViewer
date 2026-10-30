@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.hippo.ehviewer.ios

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.ehviewer.core.network.GallerySession
import com.ehviewer.shared.AppleGalleryLoginBrowser
import com.ehviewer.shared.GallerySearchScreen
import com.ehviewer.shared.reader.ReaderStateRepository
import com.ehviewer.shared.verifyAppleBrowserCookieTransfer
import com.ehviewer.shared.verifyGalleryProduct
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.ObjCObjectBase.OverrideInit
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDelegateProtocol
import platform.UIKit.UIApplicationMain
import platform.UIKit.UIResponder
import platform.UIKit.UIScene
import platform.UIKit.UISceneConnectionOptions
import platform.UIKit.UISceneSession
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneDelegateProtocol

private val startupRegression: Boolean
    get() = NSProcessInfo.processInfo.arguments.any { it as String == "--startup-regression" }

private fun applicationDirectory(): String {
    val support = NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL
    return checkNotNull(support.URLByAppendingPathComponent("EhViewer", true)?.path)
}

private fun applicationSession(): GallerySession {
    val directory = applicationDirectory()
    val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "development"
    return GallerySession(AppleSessionVault(directory), NativeGalleryDecoder, "EhViewer/$version")
}

@ExportObjCClass(name = "EhViewerAppDelegate")
class EhViewerDelegate : UIResponder, UIApplicationDelegateProtocol {
    @OverrideInit
    constructor() : super()

    override fun application(application: UIApplication, didFinishLaunchingWithOptions: Map<Any?, *>?): Boolean {
        if (startupRegression) {
            NSLog("EhViewerM1:cold-start")
            val rust = verifyRustInterop()
            NSLog("EhViewerM1:rust-ffi-ready completed=${rust.completed} live=${rust.live}")
            check(verifyGalleryProduct(NativeGalleryDecoder) == 3)
            NSLog("EhViewerM3:gallery-product checks=3")
        }
        return true
    }
}

@ExportObjCClass(name = "EhViewerSceneDelegate")
class EhViewerSceneDelegate : UIResponder, UIWindowSceneDelegateProtocol {
    @OverrideInit
    constructor() : super()

    private var appWindow: UIWindow? = null

    override fun scene(scene: UIScene, willConnectToSession: UISceneSession, options: UISceneConnectionOptions) {
        val windowScene = scene as? UIWindowScene ?: error("Expected UIWindowScene")
        appWindow = UIWindow(windowScene = windowScene).apply {
            rootViewController = ComposeUIViewController {
                val session = remember { applicationSession() }
                val readerState = remember { ReaderStateRepository(AppleSessionVault("${applicationDirectory()}/reader-state")) }
                if (startupRegression) {
                    LaunchedEffect(session) {
                        check(verifyAppleBrowserCookieTransfer(AppleSessionVault("${applicationDirectory()}/browser-regression")) == 4)
                        NSLog("EhViewerM1:browser-cookie-ready checks=4")
                    }
                }
                GallerySearchScreen(session, readerState, browserFactory = ::AppleGalleryLoginBrowser) { if (startupRegression) NSLog("EhViewerM1:compose-ready") }
            }
            makeKeyAndVisible()
        }
    }

    override fun sceneDidBecomeActive(scene: UIScene) {
        if (startupRegression) NSLog("EhViewerM1:active")
    }

    override fun sceneDidEnterBackground(scene: UIScene) {
        if (startupRegression) NSLog("EhViewerM1:background")
    }
}

fun main() {
    autoreleasepool {
        memScoped {
            val arguments = NSProcessInfo.processInfo.arguments.map { it as String }
            require(arguments.isNotEmpty()) { "UIKit requires the executable argument" }
            val argv = allocArray<CPointerVar<ByteVar>>(arguments.size + 1)
            arguments.forEachIndexed { index, argument -> argv[index] = argument.cstr.ptr }
            argv[arguments.size] = null
            UIApplicationMain(arguments.size, argv, null, "EhViewerAppDelegate")
        }
    }
}
