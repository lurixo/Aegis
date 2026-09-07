// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.aegis.ime.SettingsHotApply
import kotlinx.coroutines.delay
import com.aegis.ime.R
import com.aegis.ime.ime.KeyHaptic
import com.aegis.ime.ime.previewImeKeyHaptic
import com.aegis.ime.ui.theme.AppSpacing

internal const val PREF_KEY_HAPTICS = "pref_key_haptics"
internal const val PREF_KEY_HAPTIC_STYLE = "pref_key_haptic_style"
internal const val PREF_KEY_HAPTIC_STRENGTH = "pref_key_haptic_strength"
internal const val KEY_HAPTICS_DEFAULT = false
internal const val PREF_KEY_PREVIEW_MASTER = "pref_key_preview_master"
internal const val PREF_KEY_PREVIEW_NINE = "pref_key_preview_nine"
internal const val PREF_KEY_PREVIEW_ALPHA = "pref_key_preview_alpha"
internal const val KEY_PREVIEW_MASTER_DEFAULT = false
internal const val KEY_PREVIEW_SUB_DEFAULT = true

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun KeyVibrationToggleCard() {
    val context = LocalContext.current
    val view = LocalView.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var on by remember { mutableStateOf(prefs.flagOr(PREF_KEY_HAPTICS, KEY_HAPTICS_DEFAULT)) }
    var style by remember { mutableStateOf(KeyHaptic.of(prefs.textOr(PREF_KEY_HAPTIC_STYLE, "crisp"))) }
    var strength by remember { mutableFloatStateOf(SettingsHotApply.keyHapticStrength(prefs)) }
    var lastPreviewAt by remember { mutableLongStateOf(0L) }
    val latestStrength by rememberUpdatedState(strength)
    val saveStrength = { prefs.edit { putFloat(PREF_KEY_HAPTIC_STRENGTH, strength) } }
    LaunchedEffect(strength) {
        delay(150L)
        if (SettingsHotApply.keyHapticStrength(prefs) != strength) saveStrength()
    }
    DisposableEffect(prefs) {
        onDispose {
            if (SettingsHotApply.keyHapticStrength(prefs) != latestStrength) {
                prefs.edit { putFloat(PREF_KEY_HAPTIC_STRENGTH, latestStrength) }
            }
        }
    }
    val toggle = {
        on = !on
        prefs.edit { putBoolean(PREF_KEY_HAPTICS, on) }
        if (on) view.previewImeKeyHaptic(style, strength)
    }

    AppSection {
        AppSettingRow(
            title = stringResource(R.string.key_vibration_title),
            description = stringResource(R.string.key_vibration_description),
            onClick = toggle,
            trailing = {
                AegisSwitch(
                    checked = on,
                    onCheckedChange = { toggle() },
                )
            },
        )
        if (on) {
            AppSectionDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.rowHorizontal, vertical = 12.dp)) {
                val strengthLabel = stringResource(R.string.key_haptic_strength)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(strengthLabel, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (style == KeyHaptic.SYSTEM) stringResource(R.string.key_haptic_strength_system_value)
                        else stringResource(R.string.key_haptic_strength_value, strength),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = strength,
                    onValueChange = { value ->
                        strength = value
                        val now = SystemClock.uptimeMillis()
                        if (now - lastPreviewAt >= 120L || value == 0f) {
                            view.previewImeKeyHaptic(style, strength)
                            lastPreviewAt = now
                        }
                    },
                    onValueChangeFinished = {
                        saveStrength()
                        view.previewImeKeyHaptic(style, strength)
                    },
                    enabled = style != KeyHaptic.SYSTEM,
                    valueRange = 0f..100f,
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            enabled = style != KeyHaptic.SYSTEM,
                            thumbTrackGapSize = 0.dp,
                        )
                    },
                    thumb = {
                        val colors = SwitchDefaults.colors()
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(
                                if (style != KeyHaptic.SYSTEM) colors.checkedThumbColor
                                else colors.disabledCheckedThumbColor,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = strengthLabel },
                )
                if (style == KeyHaptic.SYSTEM) {
                    Text(
                        stringResource(R.string.key_haptic_system_strength_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AppSectionDivider()
            AppChoiceGroup {
                KeyHaptic.entries.forEach { choice ->
                    AppChoiceRow(
                        label = stringResource(choice.labelRes),
                        description = stringResource(choice.descriptionRes),
                        selected = style == choice,
                        onSelect = {
                            style = choice
                            prefs.edit { putString(PREF_KEY_HAPTIC_STYLE, choice.value) }
                            view.previewImeKeyHaptic(choice, strength)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun KeyPreviewCard() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var master by remember { mutableStateOf(prefs.flagOr(PREF_KEY_PREVIEW_MASTER, KEY_PREVIEW_MASTER_DEFAULT)) }
    var nine by remember { mutableStateOf(prefs.flagOr(PREF_KEY_PREVIEW_NINE, KEY_PREVIEW_SUB_DEFAULT)) }
    var alpha by remember { mutableStateOf(prefs.flagOr(PREF_KEY_PREVIEW_ALPHA, KEY_PREVIEW_SUB_DEFAULT)) }
    val toggleMaster = {
        master = !master
        prefs.edit { putBoolean(PREF_KEY_PREVIEW_MASTER, master) }
    }

    AppSection {
        AppSettingRow(
            title = stringResource(R.string.key_preview_title),
            description = stringResource(R.string.key_preview_description),
            onClick = toggleMaster,
            trailing = {
                AegisSwitch(
                    checked = master,
                    onCheckedChange = { toggleMaster() },
                )
            },
        )
        AppSectionDivider()
        KeyPreviewSubRow(R.string.key_preview_nine_label, checked = nine, enabled = master) {
            nine = it
            prefs.edit { putBoolean(PREF_KEY_PREVIEW_NINE, it) }
        }
        AppSectionDivider()
        KeyPreviewSubRow(R.string.key_preview_alpha_label, checked = alpha, enabled = master) {
            alpha = it
            prefs.edit { putBoolean(PREF_KEY_PREVIEW_ALPHA, it) }
        }
    }
}

@Composable
private fun KeyPreviewSubRow(labelRes: Int, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.rowMinHeight)
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = AppSpacing.rowHorizontal),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.contentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        AegisSwitch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
