package com.hippo.ehviewer.ui.screen

import androidx.appcompat.app.AppCompatDelegate
import com.ehviewer.shared.settings.GalleryAppearanceException
import com.ehviewer.shared.settings.GalleryAppearancePreferences
import com.ehviewer.shared.settings.GalleryAppearanceStore
import com.ehviewer.shared.settings.GalleryThemeMode
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Use the original preference and its existing application-night-mode observer. */
internal class AndroidGalleryAppearanceStore : GalleryAppearanceStore {
    private suspend fun <T> stored(block: suspend () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw GalleryAppearanceException()
    }

    override suspend fun preferences(): GalleryAppearancePreferences = stored {
        val snapshot = Settings.snapshot()
        val mode = when (snapshot[Settings.theme]) {
            AppCompatDelegate.MODE_NIGHT_NO -> GalleryThemeMode.LIGHT
            AppCompatDelegate.MODE_NIGHT_YES -> GalleryThemeMode.DARK
            else -> GalleryThemeMode.SYSTEM
        }
        GalleryAppearancePreferences(mode, snapshot[Settings.blackDarkTheme], snapshot[Settings.harmonizeCategoryColor])
    }

    override suspend fun setMode(mode: GalleryThemeMode) = stored {
        Settings.withMutableSnapshot { snapshot ->
            snapshot[Settings.theme] = when (mode) {
                GalleryThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                GalleryThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                GalleryThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        }
    }

    override suspend fun setBlackDarkTheme(enabled: Boolean) = stored {
        Settings.withMutableSnapshot { it[Settings.blackDarkTheme] = enabled }
    }

    override suspend fun setHarmonizeCategoryColor(enabled: Boolean) = stored {
        Settings.withMutableSnapshot { it[Settings.harmonizeCategoryColor] = enabled }
    }
}
