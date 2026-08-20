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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AppSpacing

internal const val PREF_KEY_HAPTICS = "pref_key_haptics"
internal const val KEY_HAPTICS_DEFAULT = false
internal const val PREF_KEY_PREVIEW_MASTER = "pref_key_preview_master"
internal const val PREF_KEY_PREVIEW_NINE = "pref_key_preview_nine"
internal const val PREF_KEY_PREVIEW_ALPHA = "pref_key_preview_alpha"
internal const val KEY_PREVIEW_MASTER_DEFAULT = false
internal const val KEY_PREVIEW_SUB_DEFAULT = true

@Composable
private fun FeedbackToggleCard(prefKey: String, default: Boolean, titleRes: Int, descRes: Int) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var on by remember { mutableStateOf(prefs.getBoolean(prefKey, default)) }

    AppSection {
        AppSettingRow(
            title = stringResource(titleRes),
            description = stringResource(descRes),
            trailing = {
                Switch(
                    checked = on,
                    onCheckedChange = {
                        on = it
                        prefs.edit { putBoolean(prefKey, it) }
                    },
                )
            },
        )
    }
}

@Composable
internal fun KeyVibrationToggleCard() =
    FeedbackToggleCard(PREF_KEY_HAPTICS, KEY_HAPTICS_DEFAULT, R.string.key_vibration_title, R.string.key_vibration_description)

@Composable
internal fun KeyPreviewCard() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var master by remember { mutableStateOf(prefs.getBoolean(PREF_KEY_PREVIEW_MASTER, KEY_PREVIEW_MASTER_DEFAULT)) }
    var nine by remember { mutableStateOf(prefs.getBoolean(PREF_KEY_PREVIEW_NINE, KEY_PREVIEW_SUB_DEFAULT)) }
    var alpha by remember { mutableStateOf(prefs.getBoolean(PREF_KEY_PREVIEW_ALPHA, KEY_PREVIEW_SUB_DEFAULT)) }

    AppSection {
        AppSettingRow(
            title = stringResource(R.string.key_preview_title),
            description = stringResource(R.string.key_preview_description),
            trailing = {
                Switch(
                    checked = master,
                    onCheckedChange = {
                        master = it
                        prefs.edit { putBoolean(PREF_KEY_PREVIEW_MASTER, it) }
                    },
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
            .padding(horizontal = AppSpacing.rowHorizontal),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.contentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
