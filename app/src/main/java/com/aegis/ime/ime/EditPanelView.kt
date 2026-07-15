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

import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

enum class EditAction { UP, DOWN, LEFT, RIGHT, START_SELECT, DELETE, COPY, CUT, SELECT_ALL, HOME, END, PASTE, BACK }

class EditPanelView(context: Context) : LinearLayout(context), ResettablePanel {

    var onAction: (EditAction) -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val TITLE_SP = 16f

    private var palette = ImePalette.STATIC_LIGHT
    private val copyBtn: TextView
    private val cutBtn: TextView
    private val selectBtn: TextView
    private val copyIcon: GlyphDrawable
    private val cutIcon: GlyphDrawable
    private val icons = mutableListOf<GlyphDrawable>()
    private val actionViews = mutableMapOf<EditAction, View>()
    private val arrowIcons = mutableMapOf<EditAction, GlyphDrawable>()
    private val titleBar: LinearLayout
    private val actionColumn: LinearLayout
    private val actionScroll: ScrollView

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        recolor(this)
        for (action in listOf(EditAction.DELETE, EditAction.COPY, EditAction.CUT, EditAction.PASTE)) {
            actionViews[action]?.background = rightActionBackground()
        }
        for (g in icons) g.applyTint(p.keyLabel)
        setHasSelection(copyBtn.isEnabled)
    }

    private fun recolor(v: View) {
        if (v.hasOnClickListeners()) Motion.applyTapFeedback(v, palette.keyLabel)
        when (v) {
            is TextView -> {
                v.setTextColor(palette.keyLabel)
            }
            is ViewGroup -> for (i in 0 until v.childCount) recolor(v.getChildAt(i))
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg)

        titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                textBtn(context.getString(R.string.edit_title), EditAction.BACK, sp = TITLE_SP).apply {
                    isFocusable = false
                    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
                    setCompoundDrawablesWithIntrinsicBounds(
                        icon(16, 0.56f) { c, p, x, y, s -> Glyphs.drawBack(c, p, x, y, s) },
                        null,
                        null,
                        null,
                    )
                    compoundDrawablePadding = dp(6)
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
            )
        }
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))

        val mid = LinearLayout(context).apply { orientation = HORIZONTAL }
        val dpad = LinearLayout(context).apply { orientation = VERTICAL }
        selectBtn = textBtn(context.getString(R.string.edit_start_select), EditAction.START_SELECT, sp = 16f)
        dpad.addView(dpadRow(null, arrowBtn(EditAction.UP, Glyphs.Arrow.UP).apply { isFocusable = false }, null), rowLp())
        dpad.addView(dpadRow(arrowBtn(EditAction.LEFT, Glyphs.Arrow.LEFT).apply { isFocusable = false }, selectBtn, arrowBtn(EditAction.RIGHT, Glyphs.Arrow.RIGHT).apply { isFocusable = false }), rowLp())
        dpad.addView(dpadRow(null, arrowBtn(EditAction.DOWN, Glyphs.Arrow.DOWN).apply { isFocusable = false }, null), rowLp())
        mid.addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 0.1f))
        mid.addView(dpad, LayoutParams(0, LayoutParams.MATCH_PARENT, 3f))

        copyIcon = icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawCopy(c, p, x, y, s) }
        cutIcon = icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawCut(c, p, x, y, s) }
        copyBtn = iconBtn(context.getString(R.string.edit_copy), EditAction.COPY, copyIcon, iconOnStart = true)
        cutBtn = iconBtn(context.getString(R.string.edit_cut), EditAction.CUT, cutIcon, iconOnStart = true)
        val rightCol = LinearLayout(context).apply { orientation = VERTICAL }
        rightCol.addView(iconBtn(context.getString(R.string.edit_delete), EditAction.DELETE, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }, iconOnStart = true), rowLp())
        rightCol.addView(copyBtn, rowLp())
        rightCol.addView(cutBtn, rowLp())
        mid.addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 0.9f))
        mid.addView(rightCol, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL }
        bottom.addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 0.1f))
        bottom.addView(iconBtn(context.getString(R.string.edit_paragraph_start), EditAction.HOME, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawParagraphEdge(c, p, x, y, s, toStart = true) }).apply { isFocusable = false }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(iconBtn(context.getString(R.string.edit_select_all), EditAction.SELECT_ALL, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawSelectAll(c, p, x, y, s) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(iconBtn(context.getString(R.string.edit_paragraph_end), EditAction.END, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawParagraphEdge(c, p, x, y, s, toStart = false) }).apply { isFocusable = false }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 0.9f))
        bottom.addView(iconBtn(context.getString(R.string.edit_paste), EditAction.PASTE, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawClipboard(c, p, x, y, s) }, iconOnStart = true), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        actionColumn = object : LinearLayout(context) {
            init { orientation = VERTICAL }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val viewport = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    0
                } else {
                    MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
                }
                val contentHeight = maxOf(dp(44) * 4, viewport)
                val rowHeight = contentHeight / 4
                (mid.layoutParams as LayoutParams).height = rowHeight * 3
                (bottom.layoutParams as LayoutParams).height = rowHeight
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY),
                )
            }
        }.apply {
            addView(mid, LayoutParams(LayoutParams.MATCH_PARENT, dp(44 * 3)))
            addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        }
        actionScroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = true
            addView(
                actionColumn,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        addView(actionScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        setHasSelection(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val available = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)

            val titleHeight = if (available >= dp(84)) {
                dp(40)
            } else {
                maxOf(dp(20).coerceAtMost(available), available - dp(44)).coerceAtMost(available)
            }
            (titleBar.layoutParams as LayoutParams).height = titleHeight.coerceAtLeast(0)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setHasSelection(has: Boolean) {
        val tint = if (has) palette.keyLabel else palette.disabled
        for (b in listOf(copyBtn, cutBtn)) {
            b.isEnabled = has
            b.setTextColor(tint)
            Motion.applyTapFeedback(b, tint)
        }
        copyIcon.applyTint(tint); cutIcon.applyTint(tint)
    }

    fun setSelecting(selecting: Boolean) {
        selectBtn.text = if (selecting) context.getString(R.string.edit_end_select) else context.getString(R.string.edit_start_select)
    }

    override fun resetToDefault() {
        setSelecting(false)
        actionScroll.scrollTo(0, 0)
    }

    internal fun selectingLabelForTest(): CharSequence = selectBtn.text
    internal fun actionViewForTest(action: EditAction): View? = actionViews[action]
    internal fun titleBarForTest(): View = titleBar
    internal fun actionViewportForTest(): View = actionScroll
    internal fun actionContentCanScrollForTest(): Boolean = actionScroll.canScrollVertically(1)
    internal fun scrollActionIntoViewForTest(action: EditAction) {
        val target = actionViews[action] ?: return
        val targetRect = Rect(0, 0, target.width, target.height)
        actionScroll.offsetDescendantRectToMyCoords(target, targetRect)
        val maxScroll = (actionColumn.height - actionScroll.height).coerceAtLeast(0)
        val desired = targetRect.centerY() - actionScroll.height / 2
        actionScroll.scrollTo(0, desired.coerceIn(0, maxScroll))
    }
    internal fun arrowLastDrawCenterForTest(action: EditAction): Pair<Float, Float>? =
        arrowIcons[action]?.lastDrawCenterForTest()

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
        Motion.applyTapFeedback(this, palette.keyLabel)
        setOnClickListener { onAction(action) }
        actionViews[action] = this
    }

    private fun iconBtn(label: String, action: EditAction, glyph: GlyphDrawable, iconOnStart: Boolean = false): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(palette.keyLabel)
        setCompoundDrawablesWithIntrinsicBounds(
            if (iconOnStart) glyph else null,
            if (iconOnStart) null else glyph,
            null,
            null,
        )
        compoundDrawablePadding = dp(2)
        if (iconOnStart) background = rightActionBackground()
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabel)
        setOnClickListener { onAction(action) }
        actionViews[action] = this
    }

    private fun rightActionBackground() = GradientDrawable().apply {
        setColor(palette.keySurface)
        cornerRadius = ImeShapes.keyRadiusDp * density
    }

    private fun arrowBtn(action: EditAction, dir: Glyphs.Arrow): View = View(context).apply {
        val glyph = icon(32, 0.40f) { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, dir) }
        arrowIcons[action] = glyph
        background = glyph
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabel)
        setOnClickListener { onAction(action) }
        actionViews[action] = this
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
        fun lastDrawCenterForTest(): Pair<Float, Float>? {
            val x = lastCenterX
            val y = lastCenterY
            return if (x.isNaN() || y.isNaN()) null else x to y
        }
        override fun getIntrinsicWidth() = boxPx
        override fun getIntrinsicHeight() = boxPx
        override fun draw(canvas: Canvas) {
            val b = bounds
            lastCenterX = b.exactCenterX()
            lastCenterY = b.exactCenterY()
            render(canvas, paint, lastCenterX, lastCenterY, boxPx * sFactor)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        private var lastCenterX = Float.NaN
        private var lastCenterY = Float.NaN
    }
}
