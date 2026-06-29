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

object Glyphs {

    fun drawClipboard(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.58f; val h = s * 0.78f
        c.drawRoundRect(cx - w, cy - h + s * 0.18f, cx + w, cy + h, s * 0.22f, s * 0.22f, paint)
        c.drawRoundRect(cx - s * 0.26f, cy - h - s * 0.02f, cx + s * 0.26f, cy - h + s * 0.28f, s * 0.17f, s * 0.17f, paint)
        c.drawLine(cx - w * 0.5f, cy - h * 0.1f, cx + w * 0.5f, cy - h * 0.1f, paint)
        c.drawLine(cx - w * 0.5f, cy + h * 0.3f, cx + w * 0.5f, cy + h * 0.3f, paint)
    }

    fun drawClipboardClear(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.58f; val h = s * 0.78f
        c.drawRoundRect(cx - w, cy - h + s * 0.18f, cx + w, cy + h, s * 0.22f, s * 0.22f, paint)
        c.drawRoundRect(cx - s * 0.26f, cy - h - s * 0.02f, cx + s * 0.26f, cy - h + s * 0.28f, s * 0.17f, s * 0.17f, paint)
        val x = s * 0.24f; val yc = cy + s * 0.14f
        c.drawLine(cx - x, yc - x, cx + x, yc + x, paint)
        c.drawLine(cx - x, yc + x, cx + x, yc - x, paint)
    }

    fun drawLock(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, closed: Boolean) {
        val bw = s * 0.62f
        val bTop = cy - s * 0.16f
        val bBot = cy + s * 0.74f
        c.drawRoundRect(cx - bw, bTop, cx + bw, bBot, s * 0.20f, s * 0.20f, paint)
        val sr = s * 0.40f
        if (closed) {
            val top = bTop - s * 0.74f
            c.drawLine(cx - sr, bTop, cx - sr, top + sr, paint)
            c.drawLine(cx + sr, bTop, cx + sr, top + sr, paint)
            c.drawArc(cx - sr, top, cx + sr, top + sr * 2f, 180f, 180f, false, paint)
        } else {
            val top = bTop - s * 1.02f
            c.drawLine(cx - sr, bTop, cx - sr, top + sr, paint)
            c.drawArc(cx - sr, top, cx + sr, top + sr * 2f, 180f, 150f, false, paint)
        }
    }
}
