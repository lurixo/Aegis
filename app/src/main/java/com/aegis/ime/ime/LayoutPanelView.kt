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
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes

enum class LayoutChoice { CN_NINE, CN_ALPHA, EN_ALPHA }

class LayoutPanelView(context: Context) : LinearLayout(context), ResettablePanel {

    var onPick: (LayoutChoice) -> Unit = {}
    var onBack: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val TITLE_SP = 16f

    private var palette = ImePalette.STATIC_LIGHT
    private var active = LayoutChoice.CN_NINE
    private val backIcon = GlyphDrawable(dp(16), 0.56f, 2f * density) { c, p, x, y, s -> Glyphs.drawBack(c, p, x, y, s) }
    private val titleBtn: TextView
    private val rows: List<Row>
    private val rowScroll: ScrollView

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg)
        backIcon.applyTint(palette.keyLabel)

        titleBtn = TextView(context).apply {
            text = context.getString(R.string.layout_panel_title)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            setTextColor(palette.keyLabel)
            isClickable = true
            isFocusable = false
            setCompoundDrawablesWithIntrinsicBounds(backIcon, null, null, null)
            compoundDrawablePadding = dp(6)
            Motion.applyTapFeedback(this, palette.keyLabel)
            setOnClickListener { onBack() }
        }
        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))

        rows = listOf(
            Row(LayoutChoice.CN_NINE, context.getString(R.string.layout_nine)),
            Row(LayoutChoice.CN_ALPHA, context.getString(R.string.layout_alpha)),
            Row(LayoutChoice.EN_ALPHA, context.getString(R.string.layout_en)),
        )
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            for (row in rows) addView(row.view, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        }
        rowScroll = ScrollView(context).apply {
            isFillViewport = true
            addView(
                column,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        addView(rowScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        restyle()
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        titleBtn.setTextColor(p.keyLabel)
        backIcon.applyTint(p.keyLabel)
        Motion.applyTapFeedback(titleBtn, p.keyLabel)
        restyle()
    }

    fun setActiveChoice(choice: LayoutChoice) {
        active = choice
        restyle()
    }

    private fun restyle() {
        for (row in rows) row.applyStyle(row.choice == active)
    }

    override fun resetToDefault() {
        rowScroll.scrollTo(0, 0)
    }

    internal fun rowViewForTest(choice: LayoutChoice): TextView = row(choice).view
    internal fun rowRadioOnForTest(choice: LayoutChoice): Boolean = row(choice).selected
    internal fun rowTintedForTest(choice: LayoutChoice): Boolean = row(choice).view.background != null
    internal fun titleButtonForTest(): TextView = titleBtn

    private fun row(choice: LayoutChoice): Row = rows.first { it.choice == choice }

    private inner class Row(val choice: LayoutChoice, label: String) {
        var selected = false
        val radio = GlyphDrawable(dp(22), 0.42f, 2f * density) { c, p, x, y, s ->
            Glyphs.drawRadio(c, p, x, y, s, on = selected)
        }
        val view: TextView = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            setCompoundDrawablesWithIntrinsicBounds(radio, null, null, null)
            compoundDrawablePadding = dp(12)
            isClickable = true
            setOnClickListener { onPick(choice) }
        }

        fun applyStyle(isActive: Boolean) {
            selected = isActive
            val tint = if (isActive) palette.accentBottom else palette.keyLabel
            view.setTextColor(tint)
            view.typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            radio.applyTint(tint)
            view.background = if (isActive) {
                GradientDrawable().apply {
                    setColor(Motion.withAlpha(palette.accentBottom, 0x22))
                    cornerRadius = ImeShapes.keyRadiusDp * density
                }
            } else {
                null
            }
            Motion.applyTapFeedback(view, tint)
        }
    }

    private class GlyphDrawable(
        private val boxPx: Int,
        private val sFactor: Float,
        strokePx: Float,
        private val render: (Canvas, Paint, Float, Float, Float) -> Unit,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = strokePx
        }
        fun applyTint(color: Int) { paint.color = color; invalidateSelf() }
        override fun getIntrinsicWidth() = boxPx
        override fun getIntrinsicHeight() = boxPx
        override fun draw(canvas: Canvas) {
            val b = bounds
            render(canvas, paint, b.exactCenterX(), b.exactCenterY(), boxPx * sFactor)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
