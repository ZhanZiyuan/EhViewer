package com.hippo.ehviewer.ui.screen

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
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

private val readerControls = setOf("Back", "Go to page", "Previous page", "Next page", "Continuous reading", "Paged reading", "Left to right", "Right to left", "Reset zoom", "Load original image", "Use display image", "Hide reader controls", "Seek page")

/** Reveal the actual overlay before addressing a reader control by its text or accessibility name. */
internal fun UiDevice.sharedControl(label: String): UiObject2? {
    val node = findObject(By.text(label)) ?: findObject(By.desc(label))
    if (node == null && label in readerControls) findObject(By.desc("Show reader controls"))?.click()
    return node
}

internal fun UiDevice.clickSharedControl(label: String) {
    val deadline = SystemClock.uptimeMillis() + 15_000
    var previous: Rect? = null
    while (SystemClock.uptimeMillis() < deadline) {
        try {
            var node = sharedControl(label)
            while (node != null && !node.isClickable) node = node.parent
            val bounds = node?.visibleBounds
            if (node?.isEnabled == true && bounds != null && !bounds.isEmpty && bounds == previous) {
                node.click()
                return
            }
            previous = bounds
        } catch (_: StaleObjectException) {
            previous = null
        }
        SystemClock.sleep(25)
    }
    throw AssertionError("Missing enabled shared control: $label")
}

internal fun UiDevice.hasSharedControl(label: String): Boolean {
    val deadline = SystemClock.uptimeMillis() + 15_000
    while (SystemClock.uptimeMillis() < deadline) {
        try {
            if (sharedControl(label) != null) return true
        } catch (_: StaleObjectException) { /* Reacquire within the same deadline. */ }
        SystemClock.sleep(25)
    }
    return false
}
