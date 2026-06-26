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
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

object Glyphs {

    class Ink internal constructor(
        private val boxLeft: Float,
        private val boxTop: Float,
        private val boxWidth: Float,
        private val boxHeight: Float,
        private val render: (Canvas, Paint, Float, Float, Float) -> Unit,
    ) {
        fun draw(c: Canvas, paint: Paint, cx: Float, cy: Float, sizePx: Float) {
            val s = (sizePx - paint.strokeWidth) / maxOf(boxWidth, boxHeight)
            render(c, paint, cx - (boxLeft + boxWidth / 2f) * s, cy - (boxTop + boxHeight / 2f) * s, s)
        }
    }

    val backspaceInk = Ink(-0.9f, -0.7f, 1.8f, 1.4f) { c, p, x, y, s -> drawBackspace(c, p, x, y, s) }

    fun drawClipboard(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.58f; val h = s * 0.78f
        c.drawRoundRect(cx - w, cy - h + s * 0.18f, cx + w, cy + h, s * 0.22f, s * 0.22f, paint)
        c.drawRoundRect(cx - s * 0.26f, cy - h - s * 0.02f, cx + s * 0.26f, cy - h + s * 0.28f, s * 0.17f, s * 0.17f, paint)
        c.drawLine(cx - w * 0.5f, cy - h * 0.1f, cx + w * 0.5f, cy - h * 0.1f, paint)
        c.drawLine(cx - w * 0.5f, cy + h * 0.3f, cx + w * 0.5f, cy + h * 0.3f, paint)
    }

    private val translateClearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    fun drawTranslate(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val ext = s * 0.84f
        val tile = s * 0.54f
        val off = s * 0.30f
        val r = s * 0.15f
        val layer = c.saveLayer(cx - ext - paint.strokeWidth, cy - ext - paint.strokeWidth, cx + ext + paint.strokeWidth, cy + ext + paint.strokeWidth, null)
        val bx = cx + off; val by = cy - off
        c.drawRoundRect(bx - tile, by - tile, bx + tile, by + tile, r, r, paint)
        val gs = tile * 0.64f
        val gx = bx + tile * 0.10f
        val gy = by - tile * 0.10f
        val save = paint.strokeWidth
        paint.strokeWidth = save * 0.80f
        c.drawLine(gx, gy - gs, gx, gy - gs * 0.52f, paint)
        c.drawLine(gx - gs * 0.80f, gy - gs * 0.52f, gx + gs * 0.80f, gy - gs * 0.52f, paint)
        c.drawLine(gx + gs * 0.52f, gy - gs * 0.12f, gx - gs * 0.58f, gy + gs * 0.92f, paint)
        c.drawLine(gx - gs * 0.52f, gy - gs * 0.12f, gx + gs * 0.58f, gy + gs * 0.92f, paint)
        paint.strokeWidth = save
        val fx = cx - off; val fy = cy + off
        val gap = paint.strokeWidth * 0.9f
        c.drawRoundRect(fx - tile - gap, fy - tile - gap, fx + tile + gap, fy + tile + gap, r + gap, r + gap, translateClearPaint)
        c.drawRoundRect(fx - tile, fy - tile, fx + tile, fy + tile, r, r, paint)
        val ah = tile * 0.60f
        val aw = tile * 0.52f
        paint.strokeWidth = save * 0.80f
        c.drawLine(fx - aw, fy + ah, fx, fy - ah, paint)
        c.drawLine(fx, fy - ah, fx + aw, fy + ah, paint)
        c.drawLine(fx - aw * 0.62f, fy + ah * 0.35f, fx + aw * 0.62f, fy + ah * 0.35f, paint)
        paint.strokeWidth = save
        c.restoreToCount(layer)
    }


    enum class Arrow { UP, DOWN, LEFT, RIGHT }

    fun drawArrow(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, dir: Arrow) {
        val angle = when (dir) {
            Arrow.UP -> 0f
            Arrow.RIGHT -> 90f
            Arrow.DOWN -> 180f
            Arrow.LEFT -> 270f
        }
        c.save()
        c.rotate(angle, cx, cy)
        drawUpArrow(c, paint, cx, cy, s)
        c.restore()
    }

    fun drawBack(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.6f
        val h = s * 0.82f
        c.drawLine(cx + w, cy - h, cx - w, cy, paint)
        c.drawLine(cx - w, cy, cx + w, cy + h, paint)
    }

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

    fun drawArrowToEdge(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, toStart: Boolean) {
        val angle = if (toStart) 270f else 90f
        val wingX = s * 0.62f
        c.save()
        c.rotate(angle, cx, cy)
        drawUpArrow(c, paint, cx, cy, s)
        c.drawLine(cx - wingX, cy - s * 1.10f, cx + wingX, cy - s * 1.10f, paint)
        c.restore()
    }

    private fun drawUpArrow(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val tipY = cy - s * 0.82f
        val tailY = cy + s * 0.82f
        val wingY = cy - s * 0.18f
        val wingX = s * 0.62f
        c.drawLine(cx, tailY, cx, tipY, paint)
        c.drawLine(cx, tipY, cx - wingX, wingY, paint)
        c.drawLine(cx, tipY, cx + wingX, wingY, paint)
    }


    fun drawEmoji(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        c.drawCircle(cx, cy, s * 0.82f, paint)
        val ey = cy - s * 0.18f; val ex = s * 0.32f; val eh = s * 0.17f
        c.drawLine(cx - ex, ey - eh, cx - ex, ey + eh, paint)
        c.drawLine(cx + ex, ey - eh, cx + ex, ey + eh, paint)
        c.drawArc(cx - s * 0.4f, cy - s * 0.05f, cx + s * 0.4f, cy + s * 0.45f, 20f, 140f, false, paint)
    }

    fun drawBrandWeldedA(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        c.drawPath(heaterPath(cx, cy, s), paint)
        val fx = s * 0.45f; val fy = cy + s * 0.47f; val topY = cy - s * 0.76f
        val a = Path().apply { moveTo(cx - fx, fy); lineTo(cx, topY); lineTo(cx + fx, fy) }
        c.drawPath(a, paint)
        c.drawLine(cx - s * 0.27f, cy - s * 0.03f, cx + s * 0.27f, cy - s * 0.03f, paint)
    }

    private fun heaterPath(cx: Float, cy: Float, s: Float): Path {
        val w = s * 0.64f; val top = cy - s * 0.76f; val mid = cy - s * 0.12f
        return Path().apply {
            moveTo(cx - w, top); lineTo(cx + w, top); lineTo(cx + w, mid)
            quadTo(cx + w, cy + s * 0.48f, cx, cy + s * 0.83f)
            quadTo(cx - w, cy + s * 0.48f, cx - w, mid)
            close()
        }
    }

    fun drawKeyboard(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val w = s * 0.70f; val h = s * 0.700f; val rx = s * 0.15f; val ry = s * 0.22f
        c.drawRoundRect(cx - w, cy - h, cx + w, cy + h, rx, ry, paint)
        val dotY = cy - s * 0.224f; val dotX = s * 0.3667f
        c.drawPoint(cx - dotX, dotY, paint)
        c.drawPoint(cx, dotY, paint)
        c.drawPoint(cx + dotX, dotY, paint)
        val barY = cy + s * 0.266f; val barX = s * 0.30f
        c.drawLine(cx - barX, barY, cx + barX, barY, paint)
    }

    fun drawEditCaret(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val h = s * 0.82f; val w = s * 0.5f
        c.drawLine(cx, cy - h, cx, cy + h, paint)
        c.drawLine(cx - w, cy - h, cx + w, cy - h, paint)
        c.drawLine(cx - w, cy + h, cx + w, cy + h, paint)
    }

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

    fun drawChevron(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float, down: Boolean) {
        val bounds = chevronBounds(cx, cy, s)
        val ty = if (down) bounds.top else bounds.bottom
        val my = if (down) bounds.bottom else bounds.top
        c.drawLine(bounds.left, ty, cx, my, paint)
        c.drawLine(cx, my, bounds.right, ty, paint)
    }

    internal fun chevronBounds(cx: Float, cy: Float, s: Float): RectF =
        RectF(cx - s * 0.7f, cy - s * 0.38f, cx + s * 0.7f, cy + s * 0.38f)

    fun drawUndo(c: Canvas, paint: Paint, cx: Float, cy: Float, s: Float) {
        val left = cx - s * 0.85f
        val topY = cy - s * 0.30f
        c.drawLine(left + s * 0.52f, topY - s * 0.52f, left, topY, paint)
        c.drawLine(left, topY, left + s * 0.52f, topY + s * 0.52f, paint)
        c.drawLine(left, topY, cx + s * 0.32f, topY, paint)
        c.drawArc(cx - s * 0.20f, topY, cx + s * 0.84f, topY + s * 1.04f, 270f, 180f, false, paint)
        c.drawLine(cx + s * 0.32f, topY + s * 1.04f, cx - s * 0.16f, topY + s * 1.04f, paint)
    }

    fun drawAppMark(c: Canvas, cx: Float, cy: Float, sizePx: Float, mono: Boolean, monoColor: Int) {
        val scale = sizePx / 58f
        c.save()
        c.translate(cx, cy)
        c.scale(scale, scale)
        c.translate(-54f, -56.5f)
        if (mono) drawMonoAppMark(c, monoColor) else drawFilledAppMark(c)
        c.restore()
    }

    private fun drawFilledAppMark(c: Canvas) {
        val black = appMarkFill(0xFF000000.toInt())
        val white = appMarkFill(0xFFFFFFFF.toInt())
        val shield = Path().apply {
            moveTo(35f, 31f); lineTo(73f, 31f); lineTo(73f, 54f)
            cubicTo(73f, 68f, 64f, 78f, 54f, 82f)
            cubicTo(44f, 78f, 35f, 68f, 35f, 54f)
            close()
        }
        c.drawPath(shield, black)
        c.drawRoundRect(45f, 39f, 63f, 55f, 4f, 4f, white)
        c.drawPath(appMarkIPath(), black)
        c.drawRoundRect(44f, 60f, 64f, 67f, 3f, 3f, white)
    }

    private fun drawMonoAppMark(c: Canvas, color: Int) {
        val wide = appMarkStroke(color, 5f)
        val thin = appMarkStroke(color, 4f)
        val shield = Path().apply {
            moveTo(37f, 33f); lineTo(71f, 33f); lineTo(71f, 53f)
            cubicTo(71f, 66f, 63f, 76f, 54f, 80f)
            cubicTo(45f, 76f, 37f, 66f, 37f, 53f)
            close()
        }
        c.drawPath(shield, wide)
        c.drawRoundRect(45f, 39f, 63f, 55f, 4f, 4f, thin)
        c.drawPath(appMarkIPath(), appMarkFill(color))
        c.drawRoundRect(44f, 60f, 64f, 67f, 3f, 3f, thin)
    }

    private fun appMarkIPath(): Path = Path().apply {
        moveTo(51f, 42f); lineTo(57f, 42f); lineTo(57f, 44f); lineTo(55.5f, 44f); lineTo(55.5f, 50f)
        lineTo(57f, 50f); lineTo(57f, 52f); lineTo(51f, 52f); lineTo(51f, 50f); lineTo(52.5f, 50f)
        lineTo(52.5f, 44f); lineTo(51f, 44f)
        close()
    }

    private fun appMarkFill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    private fun appMarkStroke(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }
}
