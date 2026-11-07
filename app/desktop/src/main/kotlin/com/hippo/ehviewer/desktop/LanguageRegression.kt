package com.hippo.ehviewer.desktop

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ehviewer.core.data.DataStoreLanguageStore
import com.ehviewer.shared.settings.GalleryLanguageSettings
import com.ehviewer.shared.settings.galleryResourceLanguageTag
import com.ehviewer.shared.verifyGalleryLanguageProduct
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

/** Real packaged resources and owned atomic preferences, without touching the user's profile/vault. */
fun verifyDesktopLanguage(): Int = runBlocking {
    withContext(Dispatchers.Main) { check(java.awt.EventQueue.isDispatchThread()) }
    val directory = Files.createTempDirectory("ehviewer-language-runtime-")
    val original = Locale.getDefault()
    val owner = SupervisorJob()
    try {
        val store = PreferenceDataStoreFactory.createWithPath(scope = CoroutineScope(owner + Dispatchers.IO)) {
            directory.resolve("language.preferences_pb").toString().toPath()
        }
        verifyGalleryLanguageProduct(
            GalleryLanguageSettings(DataStoreLanguageStore(store)) { tag ->
                Locale.setDefault(Locale.forLanguageTag(galleryResourceLanguageTag(tag ?: original.toLanguageTag())))
            },
        )
    } finally {
        Locale.setDefault(original)
        owner.cancelAndJoin()
        Files.list(directory).use { files -> files.forEach(Files::delete) }
        Files.delete(directory)
    }
}
