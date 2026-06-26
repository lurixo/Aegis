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
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes

open class PreeditView(context: Context) : View(context) {

    private var text: String = ""
    private var shownText: String = ""
    private val density = resources.displayMetrics.density
    private val pad = 6f * density
    private val candPad = 14f * density
    private val edgeInset = ImeShapes.edgeInsetDp * density
    private val tab = RectF()
    private var downX = Float.NaN
    private var downY = Float.NaN

    var onTap: () -> Unit = {}

    private var palette = ImePalette.STATIC_LIGHT

    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.keySurface
        setShadowLayer(5f * density, 0f, 2f * density, palette.shadow)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.preeditText
        textSize = sp(16f)
    }
    private val textLeft: Float get() = candPad

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    fun applyPalette(p: ImePalette) {
        palette = p
        tabPaint.color = p.keySurface
        tabPaint.setShadowLayer(5f * density, 0f, 2f * density, p.shadow)
        textPaint.color = p.preeditText
        invalidate()
    }

    private fun layoutTab() {
        if (shownText.isEmpty()) { tab.setEmpty(); return }
        val r = ImeShapes.cardRadiusDp * density
        val corner = ImeShapes.surfaceTopRadiusDp * density
        val left = maxOf(edgeInset, corner)
            val limit = if (width > 0) minOf(width - edgeInset, width - corner) else Float.MAX_VALUE
            tab.set(left, 0f, minOf(textLeft + textPaint.measureText(shownText) + pad, limit), height.toFloat() + r)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || shownText.isEmpty()) return false
        layoutTab()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!tab.contains(event.x, event.y)) return false
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val slop = 24f * density
                val moved = downX.isNaN() || kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop
                downX = Float.NaN
                downY = Float.NaN
                if (moved || !tab.contains(event.x, event.y)) return true

                onTap()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downX = Float.NaN
                downY = Float.NaN
                return true
            }
        }
        return true
    }

    fun tabBounds(): RectF { layoutTab(); return RectF(tab) }

    fun setText(s: String) {
        if (s == text) return
        val appearing = text.isEmpty() && s.isNotEmpty()
        val disappearing = text.isNotEmpty() && s.isEmpty()
        text = s
        when {
            appearing -> {
                shownText = s
                Motion.showNow(this)
            }
            disappearing -> Motion.hideNow(this, endVisibility = VISIBLE) {
                shownText = ""
                invalidate()
            }
            else -> {
                shownText = s
                invalidate()
            }
        }
    }

    internal fun shownTextForTest(): String = shownText

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (text.isEmpty() && shownText.isNotEmpty()) {
            shownText = ""
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        layoutTab()
        if (shownText.isEmpty()) return
        val r = ImeShapes.cardRadiusDp * density
        canvas.drawRoundRect(tab, r, r, tabPaint)
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.save()
        canvas.clipRect(tab.left, 0f, tab.right - pad, height.toFloat())
        canvas.drawText(shownText, textLeft, baseline, textPaint)
        canvas.restore()
    }
}
