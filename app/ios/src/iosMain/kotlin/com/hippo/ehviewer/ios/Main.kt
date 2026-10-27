@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.hippo.ehviewer.ios

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.ObjCObjectBase.OverrideInit
import kotlinx.cinterop.autoreleasepool
import platform.Foundation.NSLog
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

@ExportObjCClass(name = "EhViewerAppDelegate")
class EhViewerDelegate : UIResponder, UIApplicationDelegateProtocol {
    @OverrideInit
    constructor() : super()

    override fun application(application: UIApplication, didFinishLaunchingWithOptions: Map<Any?, *>?): Boolean {
        NSLog("EhViewerM1:cold-start")
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
                StartupContent { NSLog("EhViewerM1:compose-ready") }
            }
            makeKeyAndVisible()
        }
    }

    override fun sceneDidBecomeActive(scene: UIScene) {
        NSLog("EhViewerM1:active")
    }

    override fun sceneDidEnterBackground(scene: UIScene) {
        NSLog("EhViewerM1:background")
    }
}

fun main() {
    autoreleasepool {
        UIApplicationMain(0, null, null, "EhViewerAppDelegate")
    }
}
