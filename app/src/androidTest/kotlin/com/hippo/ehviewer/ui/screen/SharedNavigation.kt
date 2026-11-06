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
    // Recomposition after activity restoration may replace a rail node before dispatch.
    // Reuse the existing bounded lookup/click rather than retaining that obsolete node.
    if (!compact) return clickSharedControl(label)
    requireNotNull(wait(Until.findObject(By.desc(navigation)), 15_000)).click()
    val item = requireNotNull(wait(Until.findObject(By.text(label)), 15_000))
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
    item.click()
}

/** Submits the shared floating search bar without letting Gboard's first-use stylus tour consume it. */
internal fun UiDevice.submitSharedSearch() {
    val deadline = SystemClock.uptimeMillis() + 15_000
    val keyboard = "com.google.android.inputmethod.latin"
    val application = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    while (SystemClock.uptimeMillis() < deadline) {
        // This exact external onboarding window was retained in a real tablet failure.
        // Never dismiss an application dialog, disable the IME or bypass the focus checks.
        if (hasObject(By.pkg(keyboard).text("Try out your stylus"))) {
            requireNotNull(findObject(By.pkg(keyboard).text("Cancel"))).click()
            check(wait(Until.hasObject(By.pkg(application).clazz("android.widget.EditText").focused(true)), (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1))) {
                "Dismissing the input-method tutorial lost the actual search field focus"
            }
        }
        val search = findObject(By.desc("Search"))
        if (search != null) {
            search.click()
            return
        }
        SystemClock.sleep(25)
    }
    throw AssertionError("Shared Search action was unavailable within its original 15s deadline")
}

/** Reacquire a replaced search node before ACTION_SET_TEXT, within the original 5s deadline. */
internal fun UiDevice.setSharedSearchText(value: String) {
    val deadline = SystemClock.uptimeMillis() + 5_000
    var stale: StaleObjectException? = null
    while (SystemClock.uptimeMillis() < deadline) {
        try {
            findObject(By.clazz("android.widget.EditText"))?.let {
                it.text = value
                return
            }
        } catch (failure: StaleObjectException) {
            stale = failure
        }
        SystemClock.sleep(25)
    }
    throw AssertionError("Shared search field was unavailable within its original 5s deadline", stale)
}

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

/** Assert the real hidden indicator or both visible ChapterNavigator end labels. */
internal fun UiDevice.hasSharedReaderPosition(current: Int, total: Int, timeoutMillis: Long = 15_000): Boolean {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (SystemClock.uptimeMillis() < deadline) {
        try {
            if (hasObject(By.text("$current / $total"))) return true
            val page = findObject(By.desc("Go to page").text("$current"))?.visibleBounds
            if (page != null && !page.isEmpty && findObjects(By.text("$total")).any {
                    val bounds = it.visibleBounds
                    // The page label's 48dp accessibility touch bounds are larger
                    // than the total's text bounds; integer centers can differ by1px.
                    !bounds.isEmpty && bounds.top >= page.top && bounds.bottom <= page.bottom &&
                        (bounds.right <= page.left || bounds.left >= page.right)
                }
            ) {
                return true
            }
        } catch (_: StaleObjectException) { /* Reacquire within the original bound. */ }
        SystemClock.sleep(25)
    }
    return false
}
