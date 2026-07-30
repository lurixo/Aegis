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

import android.graphics.Canvas
import android.graphics.Paint

internal object ToolbarPhrasePlaceholderGlyph {
    const val NATURAL_WIDTH = 1.56f
    const val NATURAL_HEIGHT = 1.00f

    fun draw(canvas: Canvas, paint: Paint, cx: Float, cy: Float, size: Float) {
        Glyphs.drawList(canvas, paint, cx, cy, size)
    }
}
