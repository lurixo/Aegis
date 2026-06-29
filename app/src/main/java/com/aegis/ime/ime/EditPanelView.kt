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

import com.aegis.ime.ime.theme.ImePalette
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

enum class EditAction { UP, DOWN, LEFT, RIGHT, START_SELECT, DELETE, COPY, CUT, SELECT_ALL, HOME, END, PASTE, BACK }

class EditPanelView(context: Context) : LinearLayout(context), ResettablePanel {

    var onAction: (EditAction) -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT
    private val copyBtn: TextView
    private val cutBtn: TextView
    private val selectBtn: TextView
    private val copyIcon: GlyphDrawable
    private val cutIcon: GlyphDrawable
    private val icons = mutableListOf<GlyphDrawable>()

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        recolor(this)
        for (g in icons) g.applyTint(p.keyLabel)
        setHasSelection(copyBtn.isEnabled)
    }

    private fun recolor(v: View) {
        when (v) {
            is TextView -> v.setTextColor(palette.keyLabel)
            is android.view.ViewGroup -> for (i in 0 until v.childCount) recolor(v.getChildAt(i))
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg)

        addView(
            textBtn("‹  文字编辑", EditAction.BACK, sp = 16f).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, 0, 0) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(40)),
        )

        val mid = LinearLayout(context).apply { orientation = HORIZONTAL }
        val dpad = LinearLayout(context).apply { orientation = VERTICAL }
        selectBtn = textBtn("开始选择", EditAction.START_SELECT, sp = 16f)
        dpad.addView(dpadRow(null, arrowBtn(EditAction.UP, Glyphs.Arrow.UP), null), rowLp())
        dpad.addView(dpadRow(arrowBtn(EditAction.LEFT, Glyphs.Arrow.LEFT), selectBtn, arrowBtn(EditAction.RIGHT, Glyphs.Arrow.RIGHT)), rowLp())
        dpad.addView(dpadRow(null, arrowBtn(EditAction.DOWN, Glyphs.Arrow.DOWN), null), rowLp())
        mid.addView(dpad, LayoutParams(0, LayoutParams.MATCH_PARENT, 3f))

        copyIcon = icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawCopy(c, p, x, y, s) }
        cutIcon = icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawCut(c, p, x, y, s) }
        copyBtn = iconBtn("复制", EditAction.COPY, copyIcon)
        cutBtn = iconBtn("剪切", EditAction.CUT, cutIcon)
        val rightCol = LinearLayout(context).apply { orientation = VERTICAL }
        rightCol.addView(iconBtn("删除", EditAction.DELETE, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }), rowLp())
        rightCol.addView(copyBtn, rowLp())
        rightCol.addView(cutBtn, rowLp())
        mid.addView(rightCol, LayoutParams(0, LayoutParams.MATCH_PARENT, 2f))
        addView(mid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL }
        bottom.addView(iconBtn("段首", EditAction.HOME, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawParagraphEdge(c, p, x, y, s, toStart = true) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(iconBtn("全选", EditAction.SELECT_ALL, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawSelectAll(c, p, x, y, s) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(iconBtn("段尾", EditAction.END, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawParagraphEdge(c, p, x, y, s, toStart = false) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(iconBtn("粘贴", EditAction.PASTE, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawClipboard(c, p, x, y, s) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 2f))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))

        setHasSelection(false)
    }

    fun setHasSelection(has: Boolean) {
        val tint = if (has) palette.keyLabel else palette.disabled
        for (b in listOf(copyBtn, cutBtn)) { b.isEnabled = has; b.setTextColor(tint) }
        copyIcon.applyTint(tint); cutIcon.applyTint(tint)
    }

    fun setSelecting(selecting: Boolean) {
        selectBtn.text = if (selecting) "结束选择" else "开始选择"
    }

    override fun resetToDefault() = setSelecting(false)

    internal fun selectingLabelForTest(): CharSequence = selectBtn.text

    private fun rowLp() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)

    private fun dpadRow(left: View?, center: View, right: View?): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(left ?: spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(center, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(right ?: spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

    private fun spacer(): View = View(context)

    private fun textBtn(label: String, action: EditAction, sp: Float): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(palette.keyLabel)
        isClickable = true
        setOnClickListener { onAction(action) }
    }

    private fun iconBtn(label: String, action: EditAction, glyph: GlyphDrawable): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(palette.keyLabel)
        setCompoundDrawablesWithIntrinsicBounds(null, glyph, null, null)
        compoundDrawablePadding = dp(2)
        isClickable = true
        setOnClickListener { onAction(action) }
    }

    private fun arrowBtn(action: EditAction, dir: Glyphs.Arrow): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        setCompoundDrawablesWithIntrinsicBounds(null, icon(32, 0.40f) { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, dir) }, null, null)
        isClickable = true
        setOnClickListener { onAction(action) }
    }

    private fun icon(boxDp: Int, sFactor: Float, render: (Canvas, Paint, Float, Float, Float) -> Unit): GlyphDrawable =
        GlyphDrawable(dp(boxDp), sFactor, 2f * density, render).also { it.applyTint(palette.keyLabel); icons += it }

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
