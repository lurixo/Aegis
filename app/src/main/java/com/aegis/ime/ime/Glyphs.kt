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
import android.graphics.Path
import android.graphics.RectF

object Glyphs {

    fun drawBackspace(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val hw = s * 0.9f
        val hh = s * 0.7f
        val notch = cx - s * 0.3f
        val path = Path().apply {
            moveTo(cx - hw, cy)
            lineTo(notch, cy - hh)
            lineTo(cx + hw, cy - hh)
            lineTo(cx + hw, cy + hh)
            lineTo(notch, cy + hh)
            close()
        }
        c.drawPath(path, paint)
        val xc = cx + s * 0.34f; val a = s * 0.24f
        c.drawLine(xc - a, cy - a, xc + a, cy + a, paint)
        c.drawLine(xc - a, cy + a, xc + a, cy - a, paint)
    }

    fun drawEnter(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val bounds = enterBounds(cx, cy, s)
        val turnY = cy + s * 0.1f
        c.drawLine(bounds.right, bounds.top, bounds.right, turnY, paint)
        c.drawLine(bounds.right, turnY, bounds.left, turnY, paint)
        c.drawLine(bounds.left, turnY, bounds.left + s * 0.55f, cy - s * 0.5f, paint)
        c.drawLine(bounds.left, turnY, bounds.left + s * 0.55f, bounds.bottom, paint)
    }

    internal fun enterBounds(cx: Float, cy: Float, s: Float): RectF =
        RectF(cx - s * 0.9f, cy - s * 0.7f, cx + s * 0.9f, cy + s * 0.7f)

    fun drawShift(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, locked: Boolean) {
        val tipY = cy - s * 0.7f; val headW = s * 0.9f; val midY = cy + s * 0.02f
        val stemW = s * 0.38f; val botY = cy + s * 0.7f
        c.drawLine(cx, tipY, cx - headW, midY, paint)
        c.drawLine(cx, tipY, cx + headW, midY, paint)
        c.drawLine(cx - headW, midY, cx - stemW, midY, paint)
        c.drawLine(cx + headW, midY, cx + stemW, midY, paint)
        c.drawLine(cx - stemW, midY, cx - stemW, botY, paint)
        c.drawLine(cx + stemW, midY, cx + stemW, botY, paint)
        c.drawLine(cx - stemW, botY, cx + stemW, botY, paint)
        if (locked) c.drawLine(cx - headW, botY + s * 0.28f, cx + headW, botY + s * 0.28f, paint)
    }
}
