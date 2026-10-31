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
import com.ehviewer.core.domain.reader.ReaderPreferences
import com.ehviewer.core.domain.reader.ReaderStateException
import com.ehviewer.core.domain.reader.ReaderStateStore
import com.ehviewer.core.domain.reader.ReadingDirection
import com.ehviewer.core.domain.reader.ReadingLayout
import com.ehviewer.shared.settings.GalleryAppearanceException
import com.ehviewer.shared.settings.GalleryAppearanceStore
import com.ehviewer.shared.settings.GalleryThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The same settings controls persist through the actual platform adapters used by the reader. */
@Composable
internal fun GallerySettingsScreen(
    appearance: GalleryAppearanceStore,
    readerState: ReaderStateStore,
    mode: GalleryThemeMode?,
    appearanceFailed: Boolean,
    onModeChanged: (GalleryThemeMode) -> Unit,
    onRetryAppearance: () -> Unit,
    onBack: () -> Unit,
) {
    var preferences by remember { mutableStateOf<ReaderPreferences?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(readerState, attempt) {
        try {
            preferences = readerState.preferences()
            failure = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ReaderStateException) {
            failure = "Reading preferences are unavailable."
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
                failure = "Appearance could not be saved. Retry when storage is available."
            } catch (_: ReaderStateException) {
                failure = "Reading preferences could not be saved."
            } finally {
                busy = false
            }
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            if (mode == null) {
                Text(if (appearanceFailed) "Appearance settings are unavailable." else "Loading appearance…")
                if (appearanceFailed) TextButton(onClick = onRetryAppearance) { Text("Retry appearance") }
            }
            for (value in GalleryThemeMode.entries) {
                SettingsChoice(
                    when (value) {
                        GalleryThemeMode.SYSTEM -> "System theme"
                        GalleryThemeMode.LIGHT -> "Light theme"
                        GalleryThemeMode.DARK -> "Dark theme"
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
            Text("Reading", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            val current = preferences
            if (current != null) {
                fun update(changed: ReaderPreferences) = perform {
                    readerState.setPreferences(changed)
                    preferences = changed
                }
                SettingsChoice("Paged reading", current.layout == ReadingLayout.PAGED, !busy) { update(current.copy(layout = ReadingLayout.PAGED)) }
                SettingsChoice("Continuous reading", current.layout == ReadingLayout.CONTINUOUS, !busy) { update(current.copy(layout = ReadingLayout.CONTINUOUS)) }
                SettingsChoice("Left to right", current.direction == ReadingDirection.LEFT_TO_RIGHT, !busy) { update(current.copy(direction = ReadingDirection.LEFT_TO_RIGHT)) }
                SettingsChoice("Right to left", current.direction == ReadingDirection.RIGHT_TO_LEFT, !busy) { update(current.copy(direction = ReadingDirection.RIGHT_TO_LEFT)) }
            }
            failure?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { attempt++ }) { Text("Reload preferences") }
            }
        }
    }
}

@Composable
private fun SettingsChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
