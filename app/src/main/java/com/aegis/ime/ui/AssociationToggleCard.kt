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
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/**
 * Cross-session contract (debug.13 D1): the "联想" (next-word prediction) on/off flag, in prefs "aegis".
 * KeyboardController (D2) reads the SAME key to gate showing predictions — this card only
 * exposes the toggle and persists it. Default OFF since debug.17 (see [ASSOCIATIONS_DEFAULT_ON]).
 */
internal const val PREF_ASSOCIATIONS_ON = "pref_associations_on"

/**
 * debug.17: 联想 now ships OFF — a new install, or any user who never flipped the toggle, gets no next-word
 * predictions. This is the single source of truth for that default: both this card and the IME service read
 * [PREF_ASSOCIATIONS_ON] with this default, so a user's explicit choice is still honoured (the stored pref
 * always wins). Flipped to false from the original debug.13 default of true.
 */
internal const val ASSOCIATIONS_DEFAULT_ON = false

/**
 * D1 (debug.13) — the 联想 settings toggle. UI + pref only; the read-side effect (KeyboardController showing
 * next-word predictions) is wired (D2) against [PREF_ASSOCIATIONS_ON]. Default off (debug.17).
 */
@Composable
internal fun AssociationToggleCard() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var on by remember { mutableStateOf(prefs.getBoolean(PREF_ASSOCIATIONS_ON, ASSOCIATIONS_DEFAULT_ON)) }

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
                Text("联想", style = MaterialTheme.typography.titleMedium)
                Text(
                    "打字时在候选栏显示下一词预测/联想。默认关,可开。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = on,
                onCheckedChange = {
                    on = it
                    prefs.edit { putBoolean(PREF_ASSOCIATIONS_ON, it) }
                },
            )
        }
    }
}
