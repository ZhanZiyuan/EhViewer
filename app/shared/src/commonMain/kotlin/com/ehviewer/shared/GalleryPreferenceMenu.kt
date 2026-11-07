/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ehviewer.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import me.zhanghai.compose.preference.LocalPreferenceTheme
import me.zhanghai.compose.preference.Preference

/**
 * Adapted from this repository's v1.15.10 me/zhanghai/compose/preference/DropdownListPreference.kt.
 * Same preference widget, anchor, selected color and padding. Add selected semantics and commit
 * only changed values through the existing real stores. No Animeko source is used.
 */
@Composable
internal fun <T> GallerySettingsMenu(title: String, summary: String, selectedValue: T?, choices: List<Pair<T, String>>, enabled: Boolean, onChoose: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (open && enabled) {
        val theme = LocalPreferenceTheme.current
        val padding = with(theme.padding) {
            val direction = LocalLayoutDirection.current
            PaddingValues(start = calculateStartPadding(direction), end = calculateStartPadding(direction))
        }
        // The original places this anchor before the preference rather than below its row.
        Box(Modifier.fillMaxWidth().padding(padding)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((value, label) in choices) {
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            if (value != selectedValue) onChoose(value)
                            open = false
                        },
                        modifier = Modifier.background(if (value == selectedValue) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).semantics { selected = value == selectedValue },
                    )
                }
            }
        }
    }
    Preference(title = { Text(title) }, enabled = enabled, summary = { Text(summary) }, onClick = { open = true })
}
