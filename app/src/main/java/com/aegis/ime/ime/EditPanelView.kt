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
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class EditPanelView {
    internal class GlyphDrawable(
        private val boxPx: Int,
        private val sFactor: Float,
        private val strokePx: Float,
        private val leftExtent: Float,
        private val render: (Canvas, Paint, Float, Float, Float) -> Unit,
    ) : Drawable() {
        internal var verticalOffsetPx = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = strokePx
        }
        fun applyTint(color: Int) { paint.color = color; invalidateSelf() }
        override fun getIntrinsicWidth() = boxPx
        override fun getIntrinsicHeight() = boxPx
        override fun draw(canvas: Canvas) {
            val b = bounds
            lastCenterX = b.exactCenterX()
            lastCenterY = b.exactCenterY() + verticalOffsetPx
            render(canvas, paint, lastCenterX, lastCenterY, minOf(boxPx.toFloat(), b.width().toFloat(), b.height().toFloat()) * sFactor)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        private var lastCenterX = Float.NaN
        private var lastCenterY = Float.NaN
    }
}
