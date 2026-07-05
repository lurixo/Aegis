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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.aegis.ime.R

/**
 * ⑤ touch-feedback toggles (key vibration + the magnified key-press preview), added to the 0047 input-settings group.
 *
 * Both are the single source of truth for their pref key + default; the IME service reads the SAME keys via
 * [com.aegis.ime.SettingsHotApply] and hot-applies them the moment the switch flips (immediately), and the
 * KeyboardView reads the pushed value. Defaults chosen against commercial IMEs:
 *  - [PREF_KEY_HAPTICS] default OFF: opt-in, and it respects the system haptic master (we never force a
 *    vibration on a user who disabled touch feedback system-wide). This matches the app's conservative,
 *    battery-respecting defaults (the association toggle is likewise off).
 *  - [PREF_KEY_PREVIEW] default ON: the enlarged press bubble is a standard soft-keyboard affordance
 *    (Gboard / iOS show it by default for letter keys) — it confirms which key was hit without covering it.
 */
internal const val PREF_KEY_HAPTICS = "pref_key_haptics"
internal const val KEY_HAPTICS_DEFAULT = false
internal const val PREF_KEY_PREVIEW = "pref_key_preview"
internal const val KEY_PREVIEW_DEFAULT = true

/** A labelled toggle card persisting [prefKey] with [default]; used for both feedback switches. */
@Composable
private fun FeedbackToggleCard(prefKey: String, default: Boolean, titleRes: Int, descRes: Int) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var on by remember { mutableStateOf(prefs.getBoolean(prefKey, default)) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(descRes), style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = on,
                onCheckedChange = {
                    on = it
                    prefs.edit { putBoolean(prefKey, it) } // fires the in-process listener → hot-applies live
                },
            )
        }
    }
}

@Composable
internal fun KeyVibrationToggleCard() =
    FeedbackToggleCard(PREF_KEY_HAPTICS, KEY_HAPTICS_DEFAULT, R.string.key_vibration_title, R.string.key_vibration_description)

@Composable
internal fun KeyPreviewToggleCard() =
    FeedbackToggleCard(PREF_KEY_PREVIEW, KEY_PREVIEW_DEFAULT, R.string.key_preview_title, R.string.key_preview_description)
