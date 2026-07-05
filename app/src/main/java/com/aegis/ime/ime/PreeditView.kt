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
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes

open class PreeditView(context: Context) : View(context) {

    private var text: String = ""
    private val density = resources.displayMetrics.density
    private val pad = 12f * density
    private val candPad = 14f * density
    private var leftInset = 0f
    private val tab = RectF()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private var palette = ImePalette.STATIC_LIGHT

    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.keySurface
        setShadowLayer(5f * density, 0f, 2f * density, palette.shadow)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.preeditText
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        tabPaint.color = p.keySurface
        tabPaint.setShadowLayer(5f * density, 0f, 2f * density, p.shadow)
        textPaint.color = p.preeditText
        invalidate()
    }

    fun setText(s: String) {
        if (s == text) return
        val appearing = text.isEmpty() && s.isNotEmpty()
        text = s
        if (appearing) Motion.fadeIn(this) else invalidate()
    }

    fun setLeftInset(px: Float) {
        if (px == leftInset) return
        leftInset = px
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        val textX = leftInset + candPad
        val w = textPaint.measureText(text) + pad * 2
        val r = ImeShapes.keyRadiusDp * density
        tab.set(textX - pad, 0f, textX - pad + w, height.toFloat() + r)
        canvas.drawRoundRect(tab, r, r, tabPaint)
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, textX, baseline, textPaint)
    }
}
