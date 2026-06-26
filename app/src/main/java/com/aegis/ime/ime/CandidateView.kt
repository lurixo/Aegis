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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * Horizontal candidate strip above the keyboard. Taps report the candidate index via [onPick].
 * When empty it shows the composing buffer (left) or a faint brand hint.
 */
class CandidateView(context: Context) : View(context) {

    var onPick: (Int) -> Unit = {}

    private var items: List<String> = emptyList()
    private var composing: String = ""
    private val hitRects = ArrayList<RectF>() // reused pool (no per-draw allocation)
    private var hitCount = 0

    private fun hitRect(i: Int): RectF {
        while (hitRects.size <= i) hitRects.add(RectF())
        return hitRects[i]
    }

    private val density = resources.displayMetrics.density
    private val padding = 14f * density

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202124.toInt()
        textSize = sp(18f)
    }
    private val composingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
        textSize = sp(16f)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0BEC5.toInt()
        textSize = sp(15f)
    }
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD5DADF.toInt() }

    fun setContent(candidates: List<String>, composingText: String) {
        items = candidates
        composing = composingText
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFFF2F4F6.toInt())
        hitCount = 0
        val cy = height / 2f
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2

        if (items.isEmpty()) {
            if (composing.isNotEmpty()) {
                canvas.drawText(composing, padding, baseline, composingPaint)
            } else {
                canvas.drawText("Aegis", padding, baseline, hintPaint)
            }
            return
        }

        var x = padding
        hitCount = items.size
        for ((i, item) in items.withIndex()) {
            val tw = textPaint.measureText(item)
            val cellW = tw + padding * 2
            hitRect(i).set(x, 0f, x + cellW, height.toFloat())
            canvas.drawText(item, x + padding, baseline, textPaint)
            x += cellW
            if (i != items.lastIndex) {
                canvas.drawRect(x, height * 0.25f, x + density, height * 0.75f, sepPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            for (i in 0 until hitCount) {
                if (hitRects[i].contains(event.x, event.y)) {
                    performClick()
                    onPick(i)
                    break
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
