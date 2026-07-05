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
 * ② The letter-case DISPLAY setting. Affects only how single a–z letters are drawn on the 26-key face and in
 * the preview bubble ([com.aegis.ime.ime.KeyboardView.displayLabel]); the committed character and the shift
 * logic are untouched, so "always uppercase" never corrupts CN pinyin (which types lowercase).
 *  - AUTO  = follow shift (lowercase at rest, uppercase when shifted — the original behaviour, the default)
 *  - UPPER = always show letters uppercase
 *  - LOWER = always show letters lowercase
 */
enum class LetterCase { AUTO, UPPER, LOWER }

internal const val PREF_LETTER_CASE = "pref_letter_case"
internal const val LETTER_CASE_DEFAULT = "auto"

/** Map a stored pref string ("auto" / "upper" / "lower") to its [LetterCase] (unknown / null → AUTO). */
fun letterCaseOf(value: String?): LetterCase = when (value) {
    "upper" -> LetterCase.UPPER
    "lower" -> LetterCase.LOWER
    else -> LetterCase.AUTO
}

/** The three radio choices, in display order, as (pref value, label resource). */
private val LETTER_CASE_CHOICES = listOf(
    "auto" to R.string.letter_case_auto,
    "upper" to R.string.letter_case_upper,
    "lower" to R.string.letter_case_lower,
)

/**
 * A three-way radio card persisting [PREF_LETTER_CASE]. The IME service reads the SAME key via
 * [com.aegis.ime.SettingsHotApply] and hot-applies the change the moment a radio flips (immediately).
 */
@Composable
internal fun LetterCaseCard() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
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
                    choice = value
                    prefs.edit { putString(PREF_LETTER_CASE, value) } // fires the in-process listener → hot-applies live
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
