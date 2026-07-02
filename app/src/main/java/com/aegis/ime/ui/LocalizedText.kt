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

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

internal sealed interface LocalizedText {
    data class Raw(val value: String) : LocalizedText
    data class Resource(@StringRes val id: Int) : LocalizedText
    data class ResourceLong(@StringRes val id: Int, val value: Long) : LocalizedText
}

@Composable
internal fun LocalizedText.asString(): String =
    when (this) {
        is LocalizedText.Raw -> value
        is LocalizedText.Resource -> stringResource(id)
        is LocalizedText.ResourceLong -> stringResource(id, value)
    }
