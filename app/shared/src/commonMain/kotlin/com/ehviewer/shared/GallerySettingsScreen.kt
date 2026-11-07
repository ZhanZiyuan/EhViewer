package com.ehviewer.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ehviewer.core.domain.AppLanguageException
import com.ehviewer.core.domain.GalleryListDensity
import com.ehviewer.core.domain.GalleryListPreferences
import com.ehviewer.core.domain.GalleryNetworkPreferences
import com.ehviewer.core.domain.download.GalleryStorageSettings
import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReaderStateStore
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
import ehviewer.app.shared.generated.resources.gallery_reading_preferences_reload
import ehviewer.app.shared.generated.resources.gallery_reading_preferences_save_failed
import ehviewer.app.shared.generated.resources.gallery_reading_preferences_unavailable
import ehviewer.app.shared.generated.resources.gallery_settings
import ehviewer.app.shared.generated.resources.gallery_settings_dark_theme
import ehviewer.app.shared.generated.resources.gallery_theme_dark
import ehviewer.app.shared.generated.resources.gallery_theme_light
import ehviewer.app.shared.generated.resources.gallery_theme_system
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.ProvidePreferenceTheme
import me.zhanghai.compose.preference.preferenceTheme
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
    listPreferences: GalleryListPreferences? = null,
    listDensity: GalleryListDensity? = null,
    listFailed: Boolean = false,
    onListChanged: (GalleryListDensity) -> Unit = {},
    showComments: Boolean? = null,
    onCommentsChanged: (Boolean) -> Unit = {},
    onRetryList: () -> Unit = {},
    onAccount: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
) {
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    val page = GallerySettingsPage.entries.firstOrNull { it.name == section }
    fun back() {
        if (page == null) onBack() else section = null
    }
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
    BackHandler(onBack = ::back)
    // Match v1.15.10 setMD3Content on every host, including standalone shared destinations.
    ProvidePreferenceTheme(preferenceTheme(iconColor = MaterialTheme.colorScheme.primary, titleTextStyle = MaterialTheme.typography.titleMedium)) {
        Column(Modifier.fillMaxSize()) {
            GallerySettingsHeader(title = page?.let { galleryString(it.title) } ?: galleryString(Res.string.gallery_settings), onBack = ::back)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (page == null) {
                GallerySettingsOverview { section = it.name }
                return@Column
            }
            val originalRows = page == GallerySettingsPage.EH || page == GallerySettingsPage.ADVANCED
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = if (originalRows) 0.dp else 16.dp, vertical = if (originalRows) 0.dp else 12.dp), verticalArrangement = if (originalRows) Arrangement.Top else Arrangement.spacedBy(8.dp)) {
                if (page == GallerySettingsPage.ADVANCED) {
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
                    val currentLanguage = languages.firstOrNull { it.first == languageTag }?.second
                        ?: galleryString(Res.string.gallery_language_current, languageTag.orEmpty())
                    GallerySettingsMenu(
                        galleryString(Res.string.gallery_language),
                        currentLanguage,
                        languageTag,
                        languages,
                        enabled = !busy && languageReady && !languageSaveFailed,
                    ) { tag ->
                        perform {
                            language.setTag(tag)
                            onLanguageChanged(tag)
                        }
                    }
                }
                if (page == GallerySettingsPage.EH) {
                    val themes = listOf(
                        GalleryThemeMode.SYSTEM to galleryString(Res.string.gallery_theme_system),
                        GalleryThemeMode.LIGHT to galleryString(Res.string.gallery_theme_light),
                        GalleryThemeMode.DARK to galleryString(Res.string.gallery_theme_dark),
                    )
                    if (mode == null) {
                        Text(if (appearanceFailed) galleryString(Res.string.gallery_appearance_unavailable) else galleryString(Res.string.gallery_appearance_loading))
                        if (appearanceFailed) TextButton(onClick = onRetryAppearance) { Text(galleryString(Res.string.gallery_appearance_retry)) }
                    }
                    GallerySettingsMenu(
                        galleryString(Res.string.gallery_settings_dark_theme),
                        themes.firstOrNull { it.first == mode }?.second.orEmpty(),
                        mode,
                        themes,
                        enabled = !busy && mode != null,
                    ) { value ->
                        perform {
                            appearance.setMode(value)
                            onModeChanged(value)
                        }
                    }
                    listPreferences?.let { GalleryListSettings(it, listDensity, showComments, listFailed, onListChanged, onCommentsChanged, onRetryList) }
                    Column(Modifier.padding(16.dp)) {
                        Text(galleryString(Res.string.gallery_reading), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                        val current = preferences
                        if (current != null) {
                            fun update(changed: ReaderPreferences) = perform {
                                readerState.setPreferences(changed)
                                preferences = changed
                            }
                            GalleryReaderModePreference(current, !busy) { update(current.withMode(it)) }
                            GalleryReaderGeneralSettings(current, !busy, ::update)
                        }
                    }
                }
                if (page == GallerySettingsPage.ADVANCED) networkPreferences?.let { Column(Modifier.padding(16.dp)) { GalleryNetworkSettings(it) } }
                if (page == GallerySettingsPage.DOWNLOAD) GalleryStorageSettingsSection(storageSettings, onPlatformStorageSettings)
                if (page == GallerySettingsPage.PRIVACY) GalleryPrivacyDestinations(onAccount, onHistory)
                if (page == GallerySettingsPage.ABOUT) GalleryAboutLinks()
                failure?.let { error ->
                    Text(galleryString(error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { attempt++ }) { Text(galleryString(Res.string.gallery_reading_preferences_reload)) }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun GallerySettingsHeader(title: String = galleryString(Res.string.gallery_settings), onBack: () -> Unit) {
    // The surrounding shared screen already applies the platform's safe drawing insets.
    TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(GalleryIcons.ArrowBack, galleryString(Res.string.gallery_back)) }
    }, windowInsets = WindowInsets(0))
}

@Composable
internal fun SettingsChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
