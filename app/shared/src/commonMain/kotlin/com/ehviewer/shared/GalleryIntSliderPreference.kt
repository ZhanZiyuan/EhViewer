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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderDefaults.drawStopIndicator
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import me.zhanghai.compose.preference.LocalPreferenceTheme
import me.zhanghai.compose.preference.Preference

/** v1.15.10's preference keyline and Material 3 ticks, committing only a completed gesture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryIntSliderPreference(title: String, value: Int, range: IntRange, steps: Int, enabled: Boolean, onChoose: (Int) -> Unit) {
    val slider = remember(range, steps) { SliderState(value.toFloat(), steps, valueRange = range.first.toFloat()..range.last.toFloat()) }
    LaunchedEffect(value, enabled, slider) {
        if (enabled) slider.value = value.toFloat()
    }
    slider.onValueChangeFinished = {
        val choice = slider.value.roundToInt().coerceIn(range)
        if (enabled && value != choice) onChoose(choice)
    }
    val style = MaterialTheme.typography.bodyLarge
    val numberMeasure = rememberTextMeasurer()
    val numberWidth = with(LocalDensity.current) { numberMeasure.measure("00000", style).size.width.toDp() }
    val colors = SliderDefaults.colors()
    Preference(
        title = { Text(title) },
        enabled = enabled,
        summary = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    state = slider,
                    enabled = enabled,
                    colors = colors,
                    modifier = Modifier.weight(1f),
                    track = { state ->
                        SliderDefaults.Track(
                            state,
                            trackCornerSize = Dp.Unspecified,
                            enabled = enabled,
                            colors = colors,
                            drawTick = { offset, color -> drawStopIndicator(offset = offset, color = color, size = SliderDefaults.TickSize) },
                        )
                    },
                )
                Box(Modifier.padding(start = LocalPreferenceTheme.current.horizontalSpacing).width(numberWidth)) {
                    Text(slider.value.roundToInt().toString(), modifier = Modifier.align(Alignment.CenterEnd), style = style, textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        },
    )
}
