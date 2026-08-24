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

@Composable
internal fun LayoutChoiceCard() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var choice by remember { mutableStateOf(prefs.textOr("cn_layout", "nine")) }

    AppSection {
        Column(
            modifier = Modifier.padding(AppSpacing.sectionPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.compactGap),
        ) {
            Text(stringResource(R.string.layout_card_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.layout_card_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppSectionDivider()
        AppChoiceGroup {
            listOf(
                "nine" to R.string.layout_nine,
                "alpha" to R.string.layout_alpha,
            ).forEach { (value, labelRes) ->
                AppChoiceRow(
                    label = stringResource(labelRes),
                    selected = choice == value,
                    onSelect = {
                        choice = value
                        prefs.edit { putString("cn_layout", value) }
                    },
                )
            }
        }
    }
}
