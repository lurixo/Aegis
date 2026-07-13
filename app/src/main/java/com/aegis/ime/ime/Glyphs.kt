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

    fun drawClipboard(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.58f; val h = s * 0.78f
        c.drawRoundRect(cx - w, cy - h + s * 0.18f, cx + w, cy + h, s * 0.22f, s * 0.22f, paint)
        c.drawRoundRect(cx - s * 0.26f, cy - h - s * 0.02f, cx + s * 0.26f, cy - h + s * 0.28f, s * 0.17f, s * 0.17f, paint)
        c.drawLine(cx - w * 0.5f, cy - h * 0.1f, cx + w * 0.5f, cy - h * 0.1f, paint)
        c.drawLine(cx - w * 0.5f, cy + h * 0.3f, cx + w * 0.5f, cy + h * 0.3f, paint)
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


    enum class Arrow { UP, DOWN, LEFT, RIGHT }

    fun drawArrow(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, dir: Arrow) {
        val dx: Float; val dy: Float
        when (dir) {
            Arrow.UP -> { dx = 0f; dy = -1f }
            Arrow.DOWN -> { dx = 0f; dy = 1f }
            Arrow.LEFT -> { dx = -1f; dy = 0f }
            Arrow.RIGHT -> { dx = 1f; dy = 0f }
        }
        val tipX = cx + dx * s; val tipY = cy + dy * s
        c.drawLine(cx - dx * s, cy - dy * s, tipX, tipY, paint)
        val hw = s * 0.66f
        val px = -dy; val py = dx
        val baseX = tipX - dx * hw; val baseY = tipY - dy * hw
        c.drawLine(tipX, tipY, baseX + px * hw, baseY + py * hw, paint)
        c.drawLine(tipX, tipY, baseX - px * hw, baseY - py * hw, paint)
    }

    fun drawBack(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.6f
        val h = s * 0.82f
        c.drawLine(cx + w, cy - h, cx - w, cy, paint)
        c.drawLine(cx - w, cy, cx + w, cy + h, paint)
    }

    fun drawBackspace(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val h = s * 0.62f
        val path = Path().apply {
            moveTo(cx - s, cy)
            lineTo(cx - s * 0.34f, cy - h)
            lineTo(cx + s, cy - h)
            lineTo(cx + s, cy + h)
            lineTo(cx - s * 0.34f, cy + h)
            close()
        }
        c.drawPath(path, paint)
        val xc = cx + s * 0.36f; val a = s * 0.24f
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

    fun drawCopy(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.5f; val h = s * 0.66f; val r = s * 0.16f; val d = s * 0.3f
        c.drawRoundRect(cx - w + d, cy - h - d, cx + w + d, cy + h - d, r, r, paint)
        c.drawRoundRect(cx - w - d, cy - h + d, cx + w - d, cy + h + d, r, r, paint)
    }

    fun drawCut(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val ringR = s * 0.26f
        val hy = cy + s * 0.62f
        c.drawCircle(cx - s * 0.42f, hy, ringR, paint)
        c.drawCircle(cx + s * 0.42f, hy, ringR, paint)
        c.drawLine(cx - s * 0.42f + ringR * 0.4f, hy - ringR * 0.4f, cx + s * 0.5f, cy - s * 0.72f, paint)
        c.drawLine(cx + s * 0.42f - ringR * 0.4f, hy - ringR * 0.4f, cx - s * 0.5f, cy - s * 0.72f, paint)
    }

    fun drawSelectAll(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val hw = s * 0.74f
        c.drawRoundRect(cx - hw, cy - hw, cx + hw, cy + hw, s * 0.22f, s * 0.22f, paint)
        c.drawLine(cx - hw * 0.44f, cy + hw * 0.04f, cx - hw * 0.06f, cy + hw * 0.42f, paint)
        c.drawLine(cx - hw * 0.06f, cy + hw * 0.42f, cx + hw * 0.52f, cy - hw * 0.4f, paint)
    }

    fun drawParagraphEdge(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, toStart: Boolean) {
        val sign = if (toStart) -1f else 1f
        val barX = cx + sign * s
        c.drawLine(barX, cy - s * 0.72f, barX, cy + s * 0.72f, paint)
        val tipX = barX - sign * s * 0.46f
        c.drawLine(cx - sign * s * 0.92f, cy, tipX, cy, paint)
        c.drawLine(tipX, cy, tipX - sign * s * 0.44f, cy - s * 0.4f, paint)
        c.drawLine(tipX, cy, tipX - sign * s * 0.44f, cy + s * 0.4f, paint)
    }


    fun drawEmoji(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        c.drawCircle(cx, cy, s * 0.82f, paint)
        val ey = cy - s * 0.18f; val ex = s * 0.32f; val eh = s * 0.17f
        c.drawLine(cx - ex, ey - eh, cx - ex, ey + eh, paint)
        c.drawLine(cx + ex, ey - eh, cx + ex, ey + eh, paint)
        c.drawArc(cx - s * 0.4f, cy - s * 0.05f, cx + s * 0.4f, cy + s * 0.45f, 20f, 140f, false, paint)
    }

    fun drawBrandA(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val h = s * 0.82f; val apexY = cy - h; val baseY = cy + h; val legX = s * 0.62f
        val p = Path().apply { moveTo(cx - legX, baseY); lineTo(cx, apexY); lineTo(cx + legX, baseY) }
        c.drawPath(p, paint)
        val t = 0.58f; val ly = apexY + (baseY - apexY) * t
        c.drawLine(cx - legX * t, ly, cx + legX * t, ly, paint)
    }

    fun drawEditCaret(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val h = s * 0.82f; val w = s * 0.5f
        c.drawLine(cx, cy - h, cx, cy + h, paint)
        c.drawLine(cx - w, cy - h, cx + w, cy - h, paint)
        c.drawLine(cx - w, cy + h, cx + w, cy + h, paint)
    }

    fun drawShift(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, locked: Boolean) {
        val tipY = cy - s * 0.85f; val headW = s * 0.8f; val midY = cy - s * 0.04f
        val stemW = s * 0.34f; val botY = cy + s * 0.66f
        c.drawLine(cx, tipY, cx - headW, midY, paint)
        c.drawLine(cx, tipY, cx + headW, midY, paint)
        c.drawLine(cx - headW, midY, cx - stemW, midY, paint)
        c.drawLine(cx + headW, midY, cx + stemW, midY, paint)
        c.drawLine(cx - stemW, midY, cx - stemW, botY, paint)
        c.drawLine(cx + stemW, midY, cx + stemW, botY, paint)
        c.drawLine(cx - stemW, botY, cx + stemW, botY, paint)
        if (locked) c.drawLine(cx - headW, botY + s * 0.3f, cx + headW, botY + s * 0.3f, paint)
    }

    fun drawPencil(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val e = s * 0.78f; val o = s * 0.2f
        val tipX = cx - e; val tipY = cy + e; val endX = cx + e; val endY = cy - e
        c.drawLine(tipX + o, tipY + o, endX + o, endY + o, paint)
        c.drawLine(tipX - o, tipY - o, endX - o, endY - o, paint)
        c.drawLine(endX + o, endY + o, endX - o, endY - o, paint)
        c.drawLine(tipX + o, tipY + o, tipX, tipY, paint)
        c.drawLine(tipX - o, tipY - o, tipX, tipY, paint)
        val bx = endX - e * 0.42f; val by = endY + e * 0.42f
        c.drawLine(bx + o, by + o, bx - o, by - o, paint)
    }


    fun drawPlus(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val r = s * 0.72f
        c.drawLine(cx - r, cy, cx + r, cy, paint)
        c.drawLine(cx, cy - r, cx, cy + r, paint)
    }

    fun drawList(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.78f; val g = s * 0.5f
        c.drawLine(cx - w, cy - g, cx + w, cy - g, paint)
        c.drawLine(cx - w, cy, cx + w, cy, paint)
        c.drawLine(cx - w, cy + g, cx + w, cy + g, paint)
    }

    fun drawGear(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val rIn = s * 0.5f; val rOut = s * 0.82f
        c.drawCircle(cx, cy, rIn, paint)
        for (i in 0 until 8) {
            val a = Math.toRadians(45.0 * i).toFloat()
            val ca = kotlin.math.cos(a); val sa = kotlin.math.sin(a)
            c.drawLine(cx + rIn * ca, cy + rIn * sa, cx + rOut * ca, cy + rOut * sa, paint)
        }
        c.drawCircle(cx, cy, s * 0.2f, paint)
    }

    fun drawTrash(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.56f; val top = cy - s * 0.46f; val bot = cy + s * 0.8f
        c.drawLine(cx - w, top, cx - w * 0.8f, bot, paint)
        c.drawLine(cx + w, top, cx + w * 0.8f, bot, paint)
        c.drawLine(cx - w * 0.8f, bot, cx + w * 0.8f, bot, paint)
        c.drawLine(cx - w * 1.22f, top, cx + w * 1.22f, top, paint)
        val hx = s * 0.26f; val hy = top - s * 0.22f
        c.drawLine(cx - hx, top, cx - hx, hy, paint)
        c.drawLine(cx + hx, top, cx + hx, hy, paint)
        c.drawLine(cx - hx, hy, cx + hx, hy, paint)
        c.drawLine(cx - s * 0.2f, top + s * 0.22f, cx - s * 0.16f, bot - s * 0.16f, paint)
        c.drawLine(cx + s * 0.2f, top + s * 0.22f, cx + s * 0.16f, bot - s * 0.16f, paint)
    }

    fun drawChevron(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, down: Boolean) {
        val bounds = chevronBounds(cx, cy, s)
        val ty = if (down) bounds.top else bounds.bottom
        val my = if (down) bounds.bottom else bounds.top
        c.drawLine(bounds.left, ty, cx, my, paint)
        c.drawLine(cx, my, bounds.right, ty, paint)
    }

    internal fun chevronBounds(cx: Float, cy: Float, s: Float): RectF =
        RectF(cx - s * 0.7f, cy - s * 0.38f, cx + s * 0.7f, cy + s * 0.38f)

    fun drawTag(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val l = cx - s * 0.85f; val r = cx + s * 0.45f; val tip = cx + s * 0.92f
        val top = cy - s * 0.6f; val bot = cy + s * 0.6f
        val p = Path().apply { moveTo(l, top); lineTo(r, top); lineTo(tip, cy); lineTo(r, bot); lineTo(l, bot); close() }
        c.drawPath(p, paint)
        c.drawCircle(l + s * 0.3f, cy, s * 0.13f, paint)
    }

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
