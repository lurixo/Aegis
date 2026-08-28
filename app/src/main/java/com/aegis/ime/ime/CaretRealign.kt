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

package com.aegis.ime.ime

import android.view.inputmethod.InputConnection

object CaretRealign {

    const val RECHECK = 64

    fun breaksIn(text: CharSequence): Int = text.count { it == '\n' }

    fun following(ic: InputConnection, bound: Int): String =
        if (bound <= 0) "" else ic.getTextAfterCursor(bound, 0)?.toString() ?: ""

    fun lagBetween(before: String, after: String): Int = when {
        after == before -> 0
        before.isEmpty() -> after.length
        else -> after.indexOf(before).coerceAtLeast(0)
    }
}
