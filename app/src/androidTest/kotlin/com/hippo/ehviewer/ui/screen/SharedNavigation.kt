package com.hippo.ehviewer.ui.screen

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Opens a shared top-level destination the way a user does: phones open the modal drawer first,
 * while tablets choose the always visible rail item. [navigation] is the localized drawer button.
 */
internal fun UiDevice.openSharedDestination(label: String, navigation: String = "Open navigation") {
    val compact = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp < 600
    if (compact) requireNotNull(wait(Until.findObject(By.desc(navigation)), 15_000)).click()
    val item = requireNotNull(wait(Until.findObject(By.text(label)), 15_000))
    if (compact) {
        // Tap the drawer item only after its opening animation has settled.
        runBlocking {
            withTimeout(5_000) {
                var bounds = item.visibleBounds
                while (true) {
                    delay(100)
                    val current = item.visibleBounds
                    if (current == bounds && current.left >= 0) break
                    bounds = current
                }
            }
        }
    }
    item.click()
}

/** Submits the shared floating search bar's query with its Search icon button. */
internal fun UiDevice.submitSharedSearch() = requireNotNull(wait(Until.findObject(By.desc("Search")), 15_000)).click()
