package com.hippo.ehviewer.desktop

import com.ehviewer.core.domain.reader.ArchiveBook
import com.ehviewer.core.domain.reader.ArchiveExport
import com.ehviewer.core.domain.reader.ArchiveLibrary
import java.awt.EventQueue
import java.awt.FileDialog
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

private suspend fun chooseExportDestination(snapshot: ArchiveExport): Path? = when {
    !snapshot.isDirectory -> chooseNativeFile("Export comic archive (choose a new filename)", FileDialog.SAVE, initialFile = snapshot.name)
    System.getProperty("os.name").startsWith("Mac") -> {
        // Select an existing parent; the common exporter creates the new child exclusively.
        chooseNativeFile("Export images (choose a parent folder)", FileDialog.LOAD, selectDirectory = true)?.resolve(snapshot.name)
    }
    else -> chooseSwingExportDirectory(snapshot.name)
}

private suspend fun chooseSwingExportDirectory(name: String): Path? = suspendCancellableCoroutine { continuation ->
    EventQueue.invokeLater {
        if (!continuation.isActive) return@invokeLater
        var picker: JFileChooser? = null
        try {
            val dialog = JFileChooser().apply {
                dialogTitle = "Export images (choose a new folder name)"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
                selectedFile = File(System.getProperty("user.home"), name)
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
