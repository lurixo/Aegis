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
import com.aegis.ime.R
import com.aegis.ime.user.userSettings

enum class LetterCase { AUTO, UPPER, LOWER }

internal const val PREF_LETTER_CASE = "pref_letter_case"
internal const val LETTER_CASE_DEFAULT = "auto"

fun letterCaseOf(value: String?): LetterCase = when (value) {
    "upper" -> LetterCase.UPPER
    "lower" -> LetterCase.LOWER
    else -> LetterCase.AUTO
}

private val LETTER_CASE_CHOICES = listOf(
    "auto" to R.string.letter_case_auto,
    "upper" to R.string.letter_case_upper,
    "lower" to R.string.letter_case_lower,
)

@Composable
internal fun LetterCaseCard() {
    val context = LocalContext.current
    val prefs = remember(context) { userSettings(context) }
    var choice by remember { mutableStateOf(prefs.getString(PREF_LETTER_CASE, LETTER_CASE_DEFAULT) ?: LETTER_CASE_DEFAULT) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.letter_case_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.letter_case_description), style = MaterialTheme.typography.bodySmall)
            LETTER_CASE_CHOICES.forEach { (value, labelRes) ->
                val select = {
                    if (persistUserSetting(context, prefs) { putString(PREF_LETTER_CASE, value) }) choice = value
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = select),
                ) {
                    RadioButton(selected = choice == value, onClick = select)
                    Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
