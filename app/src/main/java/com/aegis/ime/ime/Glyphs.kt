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

    // ---- debug.16 文字编辑面板 (issue #55): self-drawn outline icons in the SAME monochrome-stroke language ----

    enum class Arrow { UP, DOWN, LEFT, RIGHT }

    /** Hollow (outline / stroke-only, never filled) directional arrow for the edit-panel D-pad — a shaft with a
     *  chevron head, so it reads bigger and cleaner than the old "↑←→↓" text glyphs (debug.16 item6). */
    fun drawArrow(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, dir: Arrow) {
        val dx: Float; val dy: Float
        when (dir) {
            Arrow.UP -> { dx = 0f; dy = -1f }
            Arrow.DOWN -> { dx = 0f; dy = 1f }
            Arrow.LEFT -> { dx = -1f; dy = 0f }
            Arrow.RIGHT -> { dx = 1f; dy = 0f }
        }
        val tipX = cx + dx * s; val tipY = cy + dy * s
        c.drawLine(cx - dx * s, cy - dy * s, tipX, tipY, paint)         // shaft
        val hw = s * 0.66f                                              // chevron-head wing length
        val px = -dy; val py = dx                                       // unit perpendicular to (dx,dy)
        val baseX = tipX - dx * hw; val baseY = tipY - dy * hw          // where the wings meet the shaft
        c.drawLine(tipX, tipY, baseX + px * hw, baseY + py * hw, paint) // wing 1
        c.drawLine(tipX, tipY, baseX - px * hw, baseY - py * hw, paint) // wing 2
    }

    /** ⌫ backspace = a left-pointing tag outline with an ✕ on its face (debug.16 item5 删除). */
    fun drawBackspace(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val h = s * 0.62f
        val path = Path().apply {
            moveTo(cx - s, cy)                       // left tip
            lineTo(cx - s * 0.34f, cy - h)           // up to the top edge
            lineTo(cx + s, cy - h)                   // top-right
            lineTo(cx + s, cy + h)                   // bottom-right
            lineTo(cx - s * 0.34f, cy + h)           // bottom edge
            close()
        }
        c.drawPath(path, paint)
        val xc = cx + s * 0.36f; val a = s * 0.24f   // ✕ on the right face
        c.drawLine(xc - a, cy - a, xc + a, cy + a, paint)
        c.drawLine(xc - a, cy + a, xc + a, cy - a, paint)
    }

    /** Two overlapping sheets = 复制 (debug.16 item5). */
    fun drawCopy(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.5f; val h = s * 0.66f; val r = s * 0.16f; val d = s * 0.3f
        c.drawRoundRect(cx - w + d, cy - h - d, cx + w + d, cy + h - d, r, r, paint) // back sheet (upper-right)
        c.drawRoundRect(cx - w - d, cy - h + d, cx + w - d, cy + h + d, r, r, paint) // front sheet (lower-left)
    }

    /** Scissors = 剪切: two handle rings whose blades cross above them and open at the top (debug.16 item5). */
    fun drawCut(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val ringR = s * 0.26f
        val hy = cy + s * 0.62f                                  // handle-ring centre height
        c.drawCircle(cx - s * 0.42f, hy, ringR, paint)
        c.drawCircle(cx + s * 0.42f, hy, ringR, paint)
        c.drawLine(cx - s * 0.42f + ringR * 0.4f, hy - ringR * 0.4f, cx + s * 0.5f, cy - s * 0.72f, paint) // left ring → right tip
        c.drawLine(cx + s * 0.42f - ringR * 0.4f, hy - ringR * 0.4f, cx - s * 0.5f, cy - s * 0.72f, paint) // right ring → left tip
    }

    /** Ticked box = 全选 (matches the old ☑ marker; debug.16 item5). */
    fun drawSelectAll(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val hw = s * 0.74f
        c.drawRoundRect(cx - hw, cy - hw, cx + hw, cy + hw, s * 0.22f, s * 0.22f, paint) // box
        c.drawLine(cx - hw * 0.44f, cy + hw * 0.04f, cx - hw * 0.06f, cy + hw * 0.42f, paint) // check ↙
        c.drawLine(cx - hw * 0.06f, cy + hw * 0.42f, cx + hw * 0.52f, cy - hw * 0.4f, paint)  // check ↗
    }

    /** An edge bar with an arrow pointing into it = 段首 ([toStart]) / 段尾 (mirror) (debug.16 item5/item1). */
    fun drawParagraphEdge(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, toStart: Boolean) {
        val sign = if (toStart) -1f else 1f                     // -1 = bar on the left, arrow points left
        val barX = cx + sign * s
        c.drawLine(barX, cy - s * 0.72f, barX, cy + s * 0.72f, paint)          // edge bar
        val tipX = barX - sign * s * 0.46f                                     // arrow tip just off the bar
        c.drawLine(cx - sign * s * 0.92f, cy, tipX, cy, paint)                 // shaft
        c.drawLine(tipX, cy, tipX - sign * s * 0.44f, cy - s * 0.4f, paint)    // head wing
        c.drawLine(tipX, cy, tipX - sign * s * 0.44f, cy + s * 0.4f, paint)    // head wing
    }
}
