package com.hippo.ehviewer.desktop

import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveExport
import com.ehviewer.core.domain.reader.ArchiveLibrary
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Native save UI; all bytes, no-overwrite rules and cancellation belong to the common snapshot. */
internal suspend fun exportComicArchive(library: ArchiveLibrary, book: ArchiveBook): Boolean {
    val snapshot = library.prepareExport(book)
    try {
        val target = chooseExportDestination(snapshot) ?: return false
        snapshot.saveTo(target.toString())
        return true
    } finally {
        snapshot.close()
    }
}

private suspend fun chooseExportDestination(snapshot: ArchiveExport): Path? = suspendCancellableCoroutine { continuation ->
    EventQueue.invokeLater {
        if (!continuation.isActive) return@invokeLater
        if (!snapshot.isDirectory) {
            var picker: FileDialog? = null
            try {
                val dialog = FileDialog(null as Frame?, "Export comic archive (choose a new filename)", FileDialog.SAVE)
                picker = dialog
                dialog.file = snapshot.name
                continuation.invokeOnCancellation { EventQueue.invokeLater { dialog.dispose() } }
                dialog.isVisible = true
                if (continuation.isActive) continuation.resume(dialog.file?.let { Path.of(dialog.directory, it) })
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            } finally {
                picker?.dispose()
            }
        } else {
            var picker: JFileChooser? = null
            try {
                val dialog = JFileChooser().apply {
                    dialogTitle = "Export images (choose a new folder name)"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                    selectedFile = File(System.getProperty("user.home"), snapshot.name)
                }
                picker = dialog
                continuation.invokeOnCancellation {
                    EventQueue.invokeLater {
                        dialog.cancelSelection()
                        SwingUtilities.getWindowAncestor(dialog)?.dispose()
                    }
                }
                val approved = dialog.showSaveDialog(null) == JFileChooser.APPROVE_OPTION
                if (continuation.isActive) continuation.resume(dialog.selectedFile?.toPath().takeIf { approved })
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            } finally {
                picker?.let { SwingUtilities.getWindowAncestor(it)?.dispose() }
            }
        }
    }
}
