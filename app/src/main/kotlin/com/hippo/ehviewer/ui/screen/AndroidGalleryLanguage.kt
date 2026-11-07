package com.hippo.ehviewer.ui.screen

import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import com.ehviewer.core.domain.AppLanguageStore
import com.ehviewer.shared.settings.GalleryLanguageSettings
import com.ehviewer.shared.settings.galleryResourceLanguageTag
import com.hippo.ehviewer.util.setAppLanguage
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Retain the original Android per-app locale and its existing persistence/recreation behavior. */
internal fun androidGalleryLanguageSettings() = GalleryLanguageSettings(
    object : AppLanguageStore {
        override suspend fun tag(): String? = withContext(Dispatchers.Main) {
            AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag()
        }

        override suspend fun setTag(tag: String?) = withContext(Dispatchers.Main) {
            setAppLanguage(tag ?: "system")
        }
    },
) { tag ->
    val effective = tag ?: Resources.getSystem().configuration.locales[0].toLanguageTag()
    Locale.setDefault(Locale.forLanguageTag(galleryResourceLanguageTag(effective)))
}
