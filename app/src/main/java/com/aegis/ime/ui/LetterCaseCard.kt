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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AppSpacing

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
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var choice by remember { mutableStateOf(prefs.getString(PREF_LETTER_CASE, LETTER_CASE_DEFAULT) ?: LETTER_CASE_DEFAULT) }

    AppSection {
        Column(
            modifier = Modifier.padding(AppSpacing.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.compactGap),
        ) {
            Text(stringResource(R.string.letter_case_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.letter_case_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppSectionDivider()
        AppChoiceGroup {
            LETTER_CASE_CHOICES.forEach { (value, labelRes) ->
                val select = {
                    choice = value
                    prefs.edit { putString(PREF_LETTER_CASE, value) }
                }
                AppChoiceRow(
                    label = stringResource(labelRes),
                    selected = choice == value,
                    onSelect = select,
                )
            }
        }
    }
}
