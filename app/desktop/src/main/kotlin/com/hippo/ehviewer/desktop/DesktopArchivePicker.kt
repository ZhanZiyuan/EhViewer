package com.hippo.ehviewer.desktop

import java.awt.EventQueue
import java.awt.FileDialog
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Native chooser only; copying, validation and persistence belong to the common library. */
internal suspend fun pickComicArchive(): Path? = chooseNativeFile("Import comic archive", FileDialog.LOAD)

internal suspend fun pickImageDirectory(): Path? = if (System.getProperty("os.name").startsWith("Mac")) {
    chooseNativeFile("Import image folder", FileDialog.LOAD, selectDirectory = true)
} else {
    pickSwingImageDirectory()
}

private suspend fun pickSwingImageDirectory(): Path? = suspendCancellableCoroutine { continuation ->
    EventQueue.invokeLater {
        if (!continuation.isActive) return@invokeLater
        val picker = JFileChooser().apply {
            dialogTitle = "Import image folder"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        continuation.invokeOnCancellation {
            EventQueue.invokeLater {
                picker.cancelSelection()
                SwingUtilities.getWindowAncestor(picker)?.dispose()
            }
        }
        try {
            val approved = picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
            if (continuation.isActive) continuation.resume(picker.selectedFile?.toPath().takeIf { approved })
        } catch (failure: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        } finally {
            SwingUtilities.getWindowAncestor(picker)?.dispose()
        }
    }
}
