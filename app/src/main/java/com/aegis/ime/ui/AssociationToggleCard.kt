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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.aegis.ime.R

internal const val PREF_ASSOCIATIONS_ON = "pref_associations_on"

internal const val ASSOCIATIONS_DEFAULT_ON = false

@Composable
internal fun AssociationToggleCard() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    var on by remember { mutableStateOf(prefs.flagOr(PREF_ASSOCIATIONS_ON, ASSOCIATIONS_DEFAULT_ON)) }
    val toggle = {
        on = !on
        prefs.edit { putBoolean(PREF_ASSOCIATIONS_ON, on) }
    }

    AppSection {
        AppSettingRow(
            title = stringResource(R.string.association_title),
            description = stringResource(R.string.association_description),
            onClick = toggle,
            trailing = {
                AegisSwitch(
                    checked = on,
                    onCheckedChange = { toggle() },
                )
            },
        )
    }
}
