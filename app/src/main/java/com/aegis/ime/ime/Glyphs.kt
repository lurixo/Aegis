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
}
