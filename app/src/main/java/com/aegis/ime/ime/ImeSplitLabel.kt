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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal class ImeSplitLabel(private val density: Float, activeTextPx: Float, idleTextPx: Float) {
    val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = activeTextPx; typeface = Typeface.DEFAULT }
    val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = idleTextPx; typeface = Typeface.DEFAULT }
    private val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val ink = Rect()

    class Placement(
        val scale: Float,
        val leading: RectF,
        val trailing: RectF,
        val slash: FloatArray,
        val leadingOrigin: FloatArray,
        val trailingOrigin: FloatArray,
    )

    fun applyColors(active: Int, idle: Int) {
        activePaint.color = active
        idlePaint.color = idle
        slashPaint.color = idle
    }

    fun draw(canvas: Canvas, rect: RectF, leading: String, trailing: String, leadingActive: Boolean, scale: Float = 1f) {
        val placed = layout(rect, leading, trailing, leadingActive, scale)
        drawWord(canvas, leading, paintFor(leadingActive), placed.scale, placed.leadingOrigin)
        drawWord(canvas, trailing, paintFor(!leadingActive), placed.scale, placed.trailingOrigin)
        slashPaint.strokeWidth = density * placed.scale
        val s = placed.slash
        canvas.drawLine(s[0], s[1], s[2], s[3], slashPaint)
    }

    fun layout(rect: RectF, leading: String, trailing: String, leadingActive: Boolean, scale: Float = 1f): Placement {
        val side = min(rect.width(), rect.height())
        val margin = side * MARGIN
        val clearance = side * CLEARANCE
        val angle = atan2(rect.height(), rect.width()).coerceIn(MIN_ANGLE, MAX_ANGLE)
        val sin = sin(angle)
        val cos = cos(angle)
        val leadPaint = paintFor(leadingActive)
        val trailPaint = paintFor(!leadingActive)
        val leadInk = inkOf(leading, leadPaint, scale)
        val trailInk = inkOf(trailing, trailPaint, scale)
        val room = (rect.height() - 2f * margin) * cos + (rect.width() - 2f * margin) * sin - 2f * clearance
        val demand = (leadInk.height() + trailInk.height()) * cos + (leadInk.width() + trailInk.width()) * sin
        var placedScale = if (demand > 0f && room < demand) scale * (room / demand).coerceAtLeast(0f) else scale
        var lead = inkOf(leading, leadPaint, placedScale)
        var trail = inkOf(trailing, trailPaint, placedScale)
        val measured = (lead.height() + trail.height()) * cos + (lead.width() + trail.width()) * sin
        if (measured > room && measured > 0f) {
            placedScale *= (room / measured).coerceAtLeast(0f)
            lead = inkOf(leading, leadPaint, placedScale)
            trail = inkOf(trailing, trailPaint, placedScale)
        }
        val leadBox = RectF(
            rect.left + margin,
            rect.top + margin,
            rect.left + margin + lead.width(),
            rect.top + margin + lead.height(),
        )
        val trailBox = RectF(
            rect.right - margin - trail.width(),
            rect.bottom - margin - trail.height(),
            rect.right - margin,
            rect.bottom - margin,
        )
        val cx = (leadBox.right + trailBox.left) / 2f
        val cy = (leadBox.bottom + trailBox.top) / 2f
        val half = activePaint.textSize * placedScale * SLASH_HEIGHT / 2f / sin
        val up = minOf(half, (rect.right - margin - cx) / cos, (cy - rect.top - margin) / sin).coerceAtLeast(0f)
        val down = minOf(half, (cx - rect.left - margin) / cos, (rect.bottom - margin - cy) / sin).coerceAtLeast(0f)
        return Placement(
            placedScale,
            leadBox,
            trailBox,
            floatArrayOf(cx - down * cos, cy + down * sin, cx + up * cos, cy - up * sin),
            floatArrayOf(leadBox.left - lead.left, leadBox.top - lead.top),
            floatArrayOf(trailBox.left - trail.left, trailBox.top - trail.top),
        )
    }

    private fun paintFor(active: Boolean): Paint = if (active) activePaint else idlePaint

    private fun inkOf(word: String, paint: Paint, scale: Float): Rect {
        val base = paint.textSize
        paint.textSize = base * scale
        paint.getTextBounds(word, 0, word.length, ink)
        paint.textSize = base
        return Rect(ink)
    }

    private fun drawWord(canvas: Canvas, word: String, paint: Paint, scale: Float, origin: FloatArray) {
        val base = paint.textSize
        paint.textSize = base * scale
        canvas.drawText(word, origin[0], origin[1], paint)
        paint.textSize = base
    }

    companion object {
        const val MARGIN = 0.14f
        const val CLEARANCE = 0.08f
        const val SLASH_HEIGHT = 0.8f
        val MIN_ANGLE = (Math.PI / 4).toFloat()
        val MAX_ANGLE = (Math.PI / 3).toFloat()
    }
}
