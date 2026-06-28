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

/**
 * debug.13: the single source of truth for the IME's self-drawn, monochrome (palette-tinted) line glyphs, so
 * every surface draws the SAME icon instead of mixing in multi-colour emoji that ignore the theme (and look
 * wrong in dark mode). [paint] must be a STROKE paint the caller owns (colour/width set by the caller); each
 * glyph is centred on (cx, cy) and scaled by [s] (the icon's ~half-extent in px), matching the candidate
 * toolbar's icon language.
 */
object Glyphs {

    /** 剪贴板 board + clip + two lines. Extracted verbatim from the candidate toolbar so the copy bar's left
     *  marker is pixel-identical to the toolbar's clipboard icon (debug.13 P-B). */
    fun drawClipboard(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.58f; val h = s * 0.78f
        c.drawRoundRect(cx - w, cy - h + s * 0.18f, cx + w, cy + h, s * 0.22f, s * 0.22f, paint)            // board
        c.drawRoundRect(cx - s * 0.26f, cy - h - s * 0.02f, cx + s * 0.26f, cy - h + s * 0.28f, s * 0.17f, s * 0.17f, paint) // clip
        c.drawLine(cx - w * 0.5f, cy - h * 0.1f, cx + w * 0.5f, cy - h * 0.1f, paint)                       // line 1
        c.drawLine(cx - w * 0.5f, cy + h * 0.3f, cx + w * 0.5f, cy + h * 0.3f, paint)                       // line 2
    }

    /** Padlock for the symbols panel's 锁定 key (debug.13 P-C): a rounded body with a shackle on top. [closed]
     *  draws the shackle seated on the body (locked); open lifts it and detaches the right leg (unlocked). */
    fun drawLock(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, closed: Boolean) {
        val bw = s * 0.62f                 // body half-width
        val bTop = cy - s * 0.16f          // body top edge
        val bBot = cy + s * 0.74f          // body bottom edge
        c.drawRoundRect(cx - bw, bTop, cx + bw, bBot, s * 0.20f, s * 0.20f, paint) // body
        val sr = s * 0.40f                 // shackle half-width
        if (closed) {
            val top = bTop - s * 0.74f     // shackle seated low, both legs reach the body
            c.drawLine(cx - sr, bTop, cx - sr, top + sr, paint)                    // left leg
            c.drawLine(cx + sr, bTop, cx + sr, top + sr, paint)                    // right leg
            c.drawArc(cx - sr, top, cx + sr, top + sr * 2f, 180f, 180f, false, paint) // arch
        } else {
            val top = bTop - s * 1.02f     // shackle lifted, right leg swung open (detached)
            c.drawLine(cx - sr, bTop, cx - sr, top + sr, paint)                    // left leg still seated
            c.drawArc(cx - sr, top, cx + sr, top + sr * 2f, 180f, 150f, false, paint) // arch, right side open
        }
    }
}
