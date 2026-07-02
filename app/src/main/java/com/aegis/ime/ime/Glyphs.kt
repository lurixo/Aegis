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

    /**
     *  marker is pixel-identical to the toolbar's clipboard icon (debug.13 P-B). */
    fun drawClipboard(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.58f; val h = s * 0.78f
        c.drawRoundRect(cx - w, cy - h + s * 0.18f, cx + w, cy + h, s * 0.22f, s * 0.22f, paint)            // board
        c.drawRoundRect(cx - s * 0.26f, cy - h - s * 0.02f, cx + s * 0.26f, cy - h + s * 0.28f, s * 0.17f, s * 0.17f, paint) // clip
        c.drawLine(cx - w * 0.5f, cy - h * 0.1f, cx + w * 0.5f, cy - h * 0.1f, paint)                       // line 1
        c.drawLine(cx - w * 0.5f, cy + h * 0.3f, cx + w * 0.5f, cy + h * 0.3f, paint)                       // line 2
    }

    /**
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

    // Chinese IME behavior note.

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

    /**
     *  back affordance reads as a real outline icon (clipboard top bar + edit-panel header) instead of the tiny
     *  "‹" text glyph. Tip at the left (cx - w, cy); two wings open to the upper-/lower-right. */
    fun drawBack(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.6f   // horizontal half-extent (tip → wing ends)
        val h = s * 0.82f  // vertical half-extent (wing spread)
        c.drawLine(cx + w, cy - h, cx - w, cy, paint) // top-right → tip
        c.drawLine(cx - w, cy, cx + w, cy + h, paint) // tip → bottom-right
    }

    /** Chinese IME behavior note. */
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

    /** Chinese IME behavior note. */
    fun drawCopy(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.5f; val h = s * 0.66f; val r = s * 0.16f; val d = s * 0.3f
        c.drawRoundRect(cx - w + d, cy - h - d, cx + w + d, cy + h - d, r, r, paint) // back sheet (upper-right)
        c.drawRoundRect(cx - w - d, cy - h + d, cx + w - d, cy + h + d, r, r, paint) // front sheet (lower-left)
    }

    /** Chinese IME behavior note. */
    fun drawCut(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val ringR = s * 0.26f
        val hy = cy + s * 0.62f                                  // handle-ring centre height
        c.drawCircle(cx - s * 0.42f, hy, ringR, paint)
        c.drawCircle(cx + s * 0.42f, hy, ringR, paint)
        c.drawLine(cx - s * 0.42f + ringR * 0.4f, hy - ringR * 0.4f, cx + s * 0.5f, cy - s * 0.72f, paint) // left ring → right tip
        c.drawLine(cx + s * 0.42f - ringR * 0.4f, hy - ringR * 0.4f, cx - s * 0.5f, cy - s * 0.72f, paint) // right ring → left tip
    }

    /** Chinese IME behavior note. */
    fun drawSelectAll(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val hw = s * 0.74f
        c.drawRoundRect(cx - hw, cy - hw, cx + hw, cy + hw, s * 0.22f, s * 0.22f, paint) // box
        c.drawLine(cx - hw * 0.44f, cy + hw * 0.04f, cx - hw * 0.06f, cy + hw * 0.42f, paint) // check ↙
        c.drawLine(cx - hw * 0.06f, cy + hw * 0.42f, cx + hw * 0.52f, cy - hw * 0.4f, paint)  // check ↗
    }

    /** Chinese IME behavior note. */
    fun drawParagraphEdge(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, toStart: Boolean) {
        val sign = if (toStart) -1f else 1f                     // -1 = bar on the left, arrow points left
        val barX = cx + sign * s
        c.drawLine(barX, cy - s * 0.72f, barX, cy + s * 0.72f, paint)          // edge bar
        val tipX = barX - sign * s * 0.46f                                     // arrow tip just off the bar
        c.drawLine(cx - sign * s * 0.92f, cy, tipX, cy, paint)                 // shaft
        c.drawLine(tipX, cy, tipX - sign * s * 0.44f, cy - s * 0.4f, paint)    // head wing
        c.drawLine(tipX, cy, tipX - sign * s * 0.44f, cy + s * 0.4f, paint)    // head wing
    }

    // Chinese IME behavior note.
    //      ONE monochrome-stroke family at a consistent ~1.5s box (matching [drawClipboard]). ----

    /**
     *  toolbar emoji used a 0.6s circle that read ~20% small). Pure stroke — eyes are short lines, not filled dots. */
    fun drawEmoji(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        c.drawCircle(cx, cy, s * 0.82f, paint)                                  // face
        val ey = cy - s * 0.18f; val ex = s * 0.32f; val eh = s * 0.17f
        c.drawLine(cx - ex, ey - eh, cx - ex, ey + eh, paint)                   // left eye
        c.drawLine(cx + ex, ey - eh, cx + ex, ey + eh, paint)                   // right eye
        c.drawArc(cx - s * 0.4f, cy - s * 0.05f, cx + s * 0.4f, cy + s * 0.45f, 20f, 140f, false, paint) // smile
    }

    /** The Aegis brand "A" mark (candidate toolbar slot 1 → settings). One round-joined path so the apex is a
     *  clean peak, plus the crossbar. ~1.5s tall to match the other toolbar icons. */
    fun drawBrandA(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val h = s * 0.82f; val apexY = cy - h; val baseY = cy + h; val legX = s * 0.62f
        val p = Path().apply { moveTo(cx - legX, baseY); lineTo(cx, apexY); lineTo(cx + legX, baseY) }
        c.drawPath(p, paint)
        val t = 0.58f; val ly = apexY + (baseY - apexY) * t
        c.drawLine(cx - legX * t, ly, cx + legX * t, ly, paint)                 // crossbar
    }

    /** Chinese IME behavior note. */
    fun drawEditCaret(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val h = s * 0.82f; val w = s * 0.5f
        c.drawLine(cx, cy - h, cx, cy + h, paint)                               // stem
        c.drawLine(cx - w, cy - h, cx + w, cy - h, paint)                       // top serif
        c.drawLine(cx - w, cy + h, cx + w, cy + h, paint)                       // bottom serif
    }

    /** ⇧ Shift key arrow (outline). [locked] (caps-lock) adds an underline bar below the stem (⬆ state). */
    fun drawShift(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, locked: Boolean) {
        val tipY = cy - s * 0.85f; val headW = s * 0.8f; val midY = cy - s * 0.04f
        val stemW = s * 0.34f; val botY = cy + s * 0.66f
        c.drawLine(cx, tipY, cx - headW, midY, paint)                           // head left
        c.drawLine(cx, tipY, cx + headW, midY, paint)                           // head right
        c.drawLine(cx - headW, midY, cx - stemW, midY, paint)                   // shoulder left
        c.drawLine(cx + headW, midY, cx + stemW, midY, paint)                   // shoulder right
        c.drawLine(cx - stemW, midY, cx - stemW, botY, paint)                   // stem left
        c.drawLine(cx + stemW, midY, cx + stemW, botY, paint)                   // stem right
        c.drawLine(cx - stemW, botY, cx + stemW, botY, paint)                   // stem bottom
        if (locked) c.drawLine(cx - headW, botY + s * 0.3f, cx + headW, botY + s * 0.3f, paint) // caps-lock bar
    }

    /** Chinese IME behavior note. */
    fun drawPencil(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val e = s * 0.78f; val o = s * 0.2f
        val tipX = cx - e; val tipY = cy + e; val endX = cx + e; val endY = cy - e
        c.drawLine(tipX + o, tipY + o, endX + o, endY + o, paint)               // body edge 1
        c.drawLine(tipX - o, tipY - o, endX - o, endY - o, paint)               // body edge 2
        c.drawLine(endX + o, endY + o, endX - o, endY - o, paint)               // eraser cap
        c.drawLine(tipX + o, tipY + o, tipX, tipY, paint)                       // tip wedge 1
        c.drawLine(tipX - o, tipY - o, tipX, tipY, paint)                       // tip wedge 2
        val bx = endX - e * 0.42f; val by = endY + e * 0.42f
        c.drawLine(bx + o, by + o, bx - o, by - o, paint)                       // ferrule band
    }

    // Chinese IME behavior note.

    /** Chinese IME behavior note. */
    fun drawPlus(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val r = s * 0.72f
        c.drawLine(cx - r, cy, cx + r, cy, paint)
        c.drawLine(cx, cy - r, cx, cy + r, paint)
    }

    /** Chinese IME behavior note. */
    fun drawList(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.78f; val g = s * 0.5f
        c.drawLine(cx - w, cy - g, cx + w, cy - g, paint)
        c.drawLine(cx - w, cy, cx + w, cy, paint)
        c.drawLine(cx - w, cy + g, cx + w, cy + g, paint)
    }

    /** Chinese IME behavior note. */
    fun drawGear(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val rIn = s * 0.5f; val rOut = s * 0.82f
        c.drawCircle(cx, cy, rIn, paint)
        for (i in 0 until 8) {
            val a = Math.toRadians(45.0 * i).toFloat()
            val ca = kotlin.math.cos(a); val sa = kotlin.math.sin(a)
            c.drawLine(cx + rIn * ca, cy + rIn * sa, cx + rOut * ca, cy + rOut * sa, paint) // tooth
        }
        c.drawCircle(cx, cy, s * 0.2f, paint) // hub hole
    }

    /** Chinese IME behavior note. */
    fun drawTrash(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.56f; val top = cy - s * 0.46f; val bot = cy + s * 0.8f
        c.drawLine(cx - w, top, cx - w * 0.8f, bot, paint)                      // body left
        c.drawLine(cx + w, top, cx + w * 0.8f, bot, paint)                      // body right
        c.drawLine(cx - w * 0.8f, bot, cx + w * 0.8f, bot, paint)               // body bottom
        c.drawLine(cx - w * 1.22f, top, cx + w * 1.22f, top, paint)             // lid
        val hx = s * 0.26f; val hy = top - s * 0.22f
        c.drawLine(cx - hx, top, cx - hx, hy, paint)                            // handle left
        c.drawLine(cx + hx, top, cx + hx, hy, paint)                            // handle right
        c.drawLine(cx - hx, hy, cx + hx, hy, paint)                             // handle top
        c.drawLine(cx - s * 0.2f, top + s * 0.22f, cx - s * 0.16f, bot - s * 0.16f, paint) // rib
        c.drawLine(cx + s * 0.2f, top + s * 0.22f, cx + s * 0.16f, bot - s * 0.16f, paint) // rib
    }

    /** ⌄ / ⌃ expand chevron (a v / ^) for the card expand/collapse toggle. */
    fun drawChevron(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, down: Boolean) {
        val w = s * 0.7f; val h = s * 0.38f
        val ty = if (down) cy - h else cy + h
        val my = if (down) cy + h else cy - h
        c.drawLine(cx - w, ty, cx, my, paint)
        c.drawLine(cx, my, cx + w, ty, paint)
    }

    /** Chinese IME behavior note. */
    fun drawTag(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val l = cx - s * 0.85f; val r = cx + s * 0.45f; val tip = cx + s * 0.92f
        val top = cy - s * 0.6f; val bot = cy + s * 0.6f
        val p = Path().apply { moveTo(l, top); lineTo(r, top); lineTo(tip, cy); lineTo(r, bot); lineTo(l, bot); close() }
        c.drawPath(p, paint)
        c.drawCircle(l + s * 0.3f, cy, s * 0.13f, paint)                        // hole
    }

    /**
     *  flips the caller's paint to FILL for the dot and restores it. */
    fun drawRadio(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, on: Boolean) {
        c.drawCircle(cx, cy, s * 0.72f, paint)
        if (on) {
            val saved = paint.style
            paint.style = Paint.Style.FILL
            c.drawCircle(cx, cy, s * 0.36f, paint)
            paint.style = saved
        }
    }
}
