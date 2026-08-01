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
import android.content.SharedPreferences
import android.widget.Toast
import com.aegis.ime.R

internal fun persistUserSetting(
    context: Context,
    preferences: SharedPreferences,
    update: SharedPreferences.Editor.() -> Unit,
): Boolean {
    val saved = preferences.edit().apply(update).commit()
    if (!saved) Toast.makeText(context, R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
    return saved
}
