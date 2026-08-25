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

package com.aegis.ime.ime.theme

import android.util.DisplayMetrics
import android.util.TypedValue
import kotlin.math.roundToInt

object ImeType {
    const val caption = 12f
    const val body = 16f
    const val candidate = 18f

    fun popupInsetPx(metrics: DisplayMetrics): Int =
        (2 * TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, body, metrics)).roundToInt()
}
