package com.ehviewer.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.AppLanguageException
import com.ehviewer.core.domain.GalleryNetworkPreferences
import com.ehviewer.core.domain.download.GalleryStorageSettings
import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.domain.reader.ReadingDirection
import com.ehviewer.core.domain.reader.ReadingLayout
import com.ehviewer.shared.settings.GalleryAppearanceException
import com.ehviewer.shared.settings.GalleryAppearanceStore
import com.ehviewer.shared.settings.GalleryLanguageSettings
import com.ehviewer.shared.settings.GalleryThemeMode
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_appearance
import ehviewer.app.shared.generated.resources.gallery_appearance_loading
import ehviewer.app.shared.generated.resources.gallery_appearance_retry
import ehviewer.app.shared.generated.resources.gallery_appearance_save_failed
import ehviewer.app.shared.generated.resources.gallery_appearance_unavailable
import ehviewer.app.shared.generated.resources.gallery_back
import ehviewer.app.shared.generated.resources.gallery_language
import ehviewer.app.shared.generated.resources.gallery_language_current
import ehviewer.app.shared.generated.resources.gallery_language_loading
import ehviewer.app.shared.generated.resources.gallery_language_reload
import ehviewer.app.shared.generated.resources.gallery_language_save_failed
import ehviewer.app.shared.generated.resources.gallery_language_system
import ehviewer.app.shared.generated.resources.gallery_language_unavailable
import ehviewer.app.shared.generated.resources.gallery_reading
import ehviewer.app.shared.generated.resources.gallery_reading_continuous
import ehviewer.app.shared.generated.resources.gallery_reading_ltr
import ehviewer.app.shared.generated.resources.gallery_reading_paged
import ehviewer.app.shared.generated.resources.gallery_reading_preferences_reload
import ehviewer.app.shared.generated.resources.gallery_reading_preferences_save_failed
import ehviewer.app.shared.generated.resources.gallery_reading_preferences_unavailable
import ehviewer.app.shared.generated.resources.gallery_reading_rtl
import ehviewer.app.shared.generated.resources.gallery_settings
import ehviewer.app.shared.generated.resources.gallery_theme_dark
import ehviewer.app.shared.generated.resources.gallery_theme_light
import ehviewer.app.shared.generated.resources.gallery_theme_system
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/** The same settings controls persist through the actual platform adapters used by the reader. */
@Composable
internal fun GallerySettingsScreen(
    networkPreferences: GalleryNetworkPreferences?,
    appearance: GalleryAppearanceStore,
    readerState: ReaderStateStore,
    mode: GalleryThemeMode?,
    appearanceFailed: Boolean,
    language: GalleryLanguageSettings,
    languageTag: String?,
    languageReady: Boolean,
    languageFailed: Boolean,
    onLanguageChanged: (String?) -> Unit,
    onRetryLanguage: () -> Unit,
    onModeChanged: (GalleryThemeMode) -> Unit,
    onRetryAppearance: () -> Unit,
    onBack: () -> Unit,
    storageSettings: GalleryStorageSettings? = null,
    onPlatformStorageSettings: (() -> Unit)? = null,
) {
    var preferences by remember { mutableStateOf<ReaderPreferences?>(null) }
    var failure by remember { mutableStateOf<StringResource?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var languageSaveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(readerState, attempt) {
        try {
            preferences = readerState.preferences()
            failure = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ReaderStateException) {
            failure = Res.string.gallery_reading_preferences_unavailable
        }
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
                failure = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GalleryAppearanceException) {
                failure = Res.string.gallery_appearance_save_failed
            } catch (_: ReaderStateException) {
                failure = Res.string.gallery_reading_preferences_save_failed
            } catch (_: AppLanguageException) {
                languageSaveFailed = true
            } finally {
                busy = false
            }
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(galleryString(Res.string.gallery_back)) }
            Text(galleryString(Res.string.gallery_settings), style = MaterialTheme.typography.headlineSmall)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(galleryString(Res.string.gallery_language), style = MaterialTheme.typography.titleMedium)
            if (!languageReady || languageSaveFailed) {
                Text(
                    galleryString(
                        when {
                            languageSaveFailed -> Res.string.gallery_language_save_failed
                            languageFailed -> Res.string.gallery_language_unavailable
                            else -> Res.string.gallery_language_loading
                        },
                    ),
                )
                if (languageFailed || languageSaveFailed) {
                    TextButton(onClick = {
                        languageSaveFailed = false
                        onRetryLanguage()
                    }, enabled = !busy) { Text(galleryString(Res.string.gallery_language_reload)) }
                }
            }
            val languages = listOf(null to galleryString(Res.string.gallery_language_system), "en" to "English", "zh-CN" to "简体中文", "zh-TW" to "繁體中文", "ja" to "日本語")
            for ((tag, label) in languages) {
                SettingsChoice(label, languageReady && tag == languageTag, !busy && languageReady && !languageSaveFailed) {
                    perform {
                        language.setTag(tag)
                        onLanguageChanged(tag)
                    }
                }
            }
            if (languageReady && languages.none { it.first == languageTag }) Text(galleryString(Res.string.gallery_language_current, languageTag.orEmpty()))
            Text(galleryString(Res.string.gallery_appearance), style = MaterialTheme.typography.titleMedium)
            if (mode == null) {
                Text(if (appearanceFailed) galleryString(Res.string.gallery_appearance_unavailable) else galleryString(Res.string.gallery_appearance_loading))
                if (appearanceFailed) TextButton(onClick = onRetryAppearance) { Text(galleryString(Res.string.gallery_appearance_retry)) }
            }
            for (value in GalleryThemeMode.entries) {
                SettingsChoice(
                    when (value) {
                        GalleryThemeMode.SYSTEM -> galleryString(Res.string.gallery_theme_system)
                        GalleryThemeMode.LIGHT -> galleryString(Res.string.gallery_theme_light)
                        GalleryThemeMode.DARK -> galleryString(Res.string.gallery_theme_dark)
                    },
                    selected = mode == value,
                    enabled = !busy && mode != null,
                ) {
                    perform {
                        appearance.setMode(value)
                        onModeChanged(value)
                    }
                }
            }
            Text(galleryString(Res.string.gallery_reading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            val current = preferences
            if (current != null) {
                fun update(changed: ReaderPreferences) = perform {
                    readerState.setPreferences(changed)
                    preferences = changed
                }
                SettingsChoice(galleryString(Res.string.gallery_reading_paged), current.layout == ReadingLayout.PAGED, !busy) { update(current.copy(layout = ReadingLayout.PAGED)) }
                SettingsChoice(galleryString(Res.string.gallery_reading_continuous), current.layout == ReadingLayout.CONTINUOUS, !busy) { update(current.copy(layout = ReadingLayout.CONTINUOUS)) }
                SettingsChoice(galleryString(Res.string.gallery_reading_ltr), current.direction == ReadingDirection.LEFT_TO_RIGHT, !busy) { update(current.copy(direction = ReadingDirection.LEFT_TO_RIGHT)) }
                SettingsChoice(galleryString(Res.string.gallery_reading_rtl), current.direction == ReadingDirection.RIGHT_TO_LEFT, !busy) { update(current.copy(direction = ReadingDirection.RIGHT_TO_LEFT)) }
            }
            networkPreferences?.let { GalleryNetworkSettings(it) }
            GalleryStorageSettingsSection(storageSettings, onPlatformStorageSettings)
            failure?.let { error ->
                Text(galleryString(error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { attempt++ }) { Text(galleryString(Res.string.gallery_reading_preferences_reload)) }
            }
        }
    }
}

@Composable
internal fun SettingsChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
