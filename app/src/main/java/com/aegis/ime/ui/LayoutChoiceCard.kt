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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
 * B5 — 全拼 9 键 / 26 键 二选一（默认九键）。Persisted to the `cn_layout` pref; the IME service reads it in
 * onStartInputView and pushes it to KeyboardController.setCnDefaultLayout (EN is always 26-key).
 */
@Composable
internal fun LayoutChoiceCard() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var choice by remember { mutableStateOf(prefs.getString("cn_layout", "nine") ?: "nine") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.layout_card_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.layout_card_description), style = MaterialTheme.typography.bodySmall)
            listOf(
                "nine" to R.string.layout_nine,
                "alpha" to R.string.layout_alpha,
            ).forEach { (value, labelRes) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        choice = value
                        prefs.edit { putString("cn_layout", value) }
                    },
                ) {
                    RadioButton(
                        selected = choice == value,
                        onClick = {
                            choice = value
                            prefs.edit { putString("cn_layout", value) }
                        },
                    )
                    Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
