package com.hippo.ehviewer.desktop

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val DIRECTORY_PROPERTY = "apple.awt.fileDialogForDirectories"
private var nativeDialogActive = false

/** Scope the macOS directory switch to one modal dialog, including cancellation and failures. */
internal suspend fun chooseNativeFile(
    title: String,
    mode: Int,
    selectDirectory: Boolean = false,
    initialFile: String? = null,
): Path? = suspendCancellableCoroutine { continuation ->
    EventQueue.invokeLater {
        if (!continuation.isActive) return@invokeLater
        var picker: FileDialog? = null
        var ownsDialog = false
        val mac = System.getProperty("os.name").startsWith("Mac")
        var previous: String? = null
        try {
            // A modal dialog runs a nested EDT loop; a second request must not change its mode.
            check(!nativeDialogActive) { "A file dialog is already open" }
            nativeDialogActive = true
            ownsDialog = true
            if (mac) {
                previous = System.getProperty(DIRECTORY_PROPERTY)
                System.setProperty(DIRECTORY_PROPERTY, selectDirectory.toString())
            }
            val dialog = FileDialog(null as Frame?, title, mode)
            picker = dialog
            dialog.file = initialFile
            continuation.invokeOnCancellation { EventQueue.invokeLater { dialog.dispose() } }
            if (continuation.isActive) dialog.isVisible = true
            if (continuation.isActive) continuation.resume(dialog.file?.let { Path.of(dialog.directory, it) })
        } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        } finally {
            picker?.dispose()
            if (ownsDialog) {
                if (mac) {
                    if (previous == null) System.clearProperty(DIRECTORY_PROPERTY) else System.setProperty(DIRECTORY_PROPERTY, previous)
                }
                nativeDialogActive = false
            }
        }
    }
}
