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
import android.view.View
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes

class PanelBackButton(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * density
    }
    private val glyphSize = ICON_DP * density * GLYPH_SCALE

    var tint: Int = ImePalette.STATIC_LIGHT.keyLabel
        set(value) {
            field = value
            paint.color = value
            Motion.applyTapFeedback(this, value, radiusDp = ImeShapes.toolbarFeedbackRadiusDp)
            invalidate()
        }

    init {
        contentDescription = context.getString(R.string.clip_back)
        paint.color = tint
        Motion.applyTapFeedback(this, tint, radiusDp = ImeShapes.toolbarFeedbackRadiusDp)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val target = (HIT_DP * density).toInt()
        setMeasuredDimension(resolveSize(target, widthMeasureSpec), resolveSize(target, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        Glyphs.drawBack(canvas, paint, width / 2f, height / 2f, glyphSize)
    }

    companion object {
        const val HIT_DP = 48
        const val ICON_DP = 24
        private const val GLYPH_SCALE = 0.42f
    }
}
