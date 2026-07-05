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
 * ⑤/① touch-feedback toggles (key vibration + the magnified key-press preview), in the input-settings group.
 *
 * Each constant is the single source of truth for its pref key + default; the IME service reads the SAME keys via
 * [com.aegis.ime.SettingsHotApply] and hot-applies them the moment the switch flips (immediately), and the
 * KeyboardView reads the pushed value. Defaults chosen against commercial IMEs:
 *  - [PREF_KEY_HAPTICS] default OFF: opt-in, and it respects the system haptic master (we never force a
 *    vibration on a user who disabled touch feedback system-wide). This matches the app's conservative,
 *    battery-respecting defaults (the association toggle is likewise off).
 *  - ① the press preview is ONE card: a master switch ([PREF_KEY_PREVIEW_MASTER]) over a 9-key
 *    ([PREF_KEY_PREVIEW_NINE]) and a 26-key ([PREF_KEY_PREVIEW_ALPHA]) sub-switch. The KeyboardView reads the
 *    RESOLVED value (master AND sub) that [com.aegis.ime.SettingsHotApply.keyPreviewNine]/[keyPreviewAlpha]
 *    compute, so the master gates BOTH worlds. The master defaults OFF so a fresh install previews nothing —
 *    exactly the pre-master state (both keyboards quiet by default); the two sub-switches default ON so flipping
 *    the single master on immediately previews both keyboards (the user then refines per keyboard). A separate
 *    master pref (rather than deriving it from the two subs) is what lets the master be a true independent
 *    kill-switch that remembers each sub-switch's position while it is off.
 */
internal const val PREF_KEY_HAPTICS = "pref_key_haptics"
internal const val KEY_HAPTICS_DEFAULT = false
internal const val PREF_KEY_PREVIEW_MASTER = "pref_key_preview_master"
internal const val PREF_KEY_PREVIEW_NINE = "pref_key_preview_nine"
internal const val PREF_KEY_PREVIEW_ALPHA = "pref_key_preview_alpha"
internal const val KEY_PREVIEW_MASTER_DEFAULT = false
internal const val KEY_PREVIEW_SUB_DEFAULT = true

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

/**
 * ① The key-press preview, as ONE card: a master "按键预览" switch on top with a short description, and below it a
 * 9-key and a 26-key sub-switch. Master OFF → both sub-switches are disabled (greyed, but keep their remembered
 * position) and nothing previews; master ON → each keyboard previews per its own sub-switch. Each of the three
 * switches persists its own pref and hot-applies immediately: the IME service reads the RESOLVED value
 * (master AND sub) via [com.aegis.ime.SettingsHotApply], and a master flip re-pushes BOTH worlds live.
 */
@Composable
internal fun KeyPreviewCard() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var master by remember { mutableStateOf(prefs.getBoolean(PREF_KEY_PREVIEW_MASTER, KEY_PREVIEW_MASTER_DEFAULT)) }
    var nine by remember { mutableStateOf(prefs.getBoolean(PREF_KEY_PREVIEW_NINE, KEY_PREVIEW_SUB_DEFAULT)) }
    var alpha by remember { mutableStateOf(prefs.getBoolean(PREF_KEY_PREVIEW_ALPHA, KEY_PREVIEW_SUB_DEFAULT)) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(R.string.key_preview_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.key_preview_description), style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = master,
                    onCheckedChange = {
                        master = it
                        prefs.edit { putBoolean(PREF_KEY_PREVIEW_MASTER, it) } // fires the listener → re-pushes BOTH worlds
                    },
                )
            }
            // Sub-switches: disabled (but keep their remembered checked state) while the master is off.
            KeyPreviewSubRow(R.string.key_preview_nine_label, checked = nine, enabled = master) {
                nine = it
                prefs.edit { putBoolean(PREF_KEY_PREVIEW_NINE, it) }
            }
            KeyPreviewSubRow(R.string.key_preview_alpha_label, checked = alpha, enabled = master) {
                alpha = it
                prefs.edit { putBoolean(PREF_KEY_PREVIEW_ALPHA, it) }
            }
        }
    }
}

/** ① One indented sub-switch row of [KeyPreviewCard]; greyed (still shows its remembered state) when [enabled] is false. */
@Composable
private fun KeyPreviewSubRow(labelRes: Int, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
