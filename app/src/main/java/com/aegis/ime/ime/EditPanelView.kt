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
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

enum class EditAction { UP, DOWN, LEFT, RIGHT, START_SELECT, DELETE, COPY, CUT, SELECT_ALL, HOME, END, PASTE, BACK }

class EditPanelView(context: Context) : LinearLayout(context), ResettablePanel, CoversToolbar {

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
    private val tintAnimators = HashMap<View, ValueAnimator>()
    private var selecting = false
    private val titleBar: LinearLayout
    private val actionColumn: LinearLayout
    private val actionScroll: ScrollView

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        recolor(this)
        Motion.applyTapFeedback(selectBtn, p.keyLabel, pressedOnly = true)
        for (action in listOf(EditAction.DELETE, EditAction.COPY, EditAction.CUT, EditAction.PASTE)) {
            actionViews[action]?.let { Motion.applyTapFeedback(it, p.keyLabel, radiusDp = ImeShapes.keyRadiusDp) }
        }
        for (g in icons) g.applyTint(p.keyLabel)
        applySelectionTint(copyBtn.isEnabled, animate = false)
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
        val dpad = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        selectBtn = textBtn(
            context.getString(R.string.edit_start_select),
            EditAction.START_SELECT,
            sp = 16f,
            pressedOnly = true,
        ).apply { includeFontPadding = false }
        val upRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(arrowBtn(EditAction.UP, icon(38, 13f / 38f, strokeDp = 2.25f) { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, Glyphs.Arrow.UP) }).apply { isFocusable = false }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        val centerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(arrowBtn(EditAction.LEFT, icon(38, 13f / 38f, strokeDp = 2.25f) { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, Glyphs.Arrow.LEFT) }).apply { isFocusable = false }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(selectBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(arrowBtn(EditAction.RIGHT, icon(38, 13f / 38f, strokeDp = 2.25f) { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, Glyphs.Arrow.RIGHT) }).apply { isFocusable = false }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        val downRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(arrowBtn(EditAction.DOWN, icon(38, 13f / 38f, strokeDp = 2.25f) { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, Glyphs.Arrow.DOWN) }).apply { isFocusable = false }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        dpad.addView(upRow, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        dpad.addView(centerRow, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        dpad.addView(downRow, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        mid.addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 0.1f))
        mid.addView(dpad, LayoutParams(0, LayoutParams.MATCH_PARENT, 3f))

        copyIcon = icon(27, 0.299f, 0.8f) { c, p, x, y, s -> Glyphs.drawCopy(c, p, x, y, s) }
        cutIcon = icon(27, 0.359f, 0.68f) { c, p, x, y, s -> Glyphs.drawCut(c, p, x, y, s) }
        copyBtn = iconBtn(context.getString(R.string.edit_copy), EditAction.COPY, copyIcon, iconOnStart = true)
        cutBtn = iconBtn(context.getString(R.string.edit_cut), EditAction.CUT, cutIcon, iconOnStart = true)
        val deleteBtn = iconBtn(context.getString(R.string.edit_delete), EditAction.DELETE, icon(27, 0.319f, 0.9f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }, iconOnStart = true)
        val rightActions = listOf(deleteBtn, copyBtn, cutBtn)
        val rightCol = LinearLayout(context).apply { orientation = VERTICAL }
        rightCol.addView(deleteBtn, rowLp())
        rightCol.addView(copyBtn, rowLp())
        rightCol.addView(cutBtn, rowLp())
        mid.addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 0.75f))
        mid.addView(rightCol, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        mid.addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 0.15f))

        val bottom = object : LinearLayout(context) {
            init { orientation = HORIZONTAL; isBaselineAligned = false; gravity = Gravity.CENTER_VERTICAL }

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                super.onLayout(changed, left, top, right, bottom)
                val paste = actionViews[EditAction.PASTE] ?: return
                paste.layout(rightCol.left + deleteBtn.left, paste.top, rightCol.left + deleteBtn.right, paste.bottom)
            }
        }
        bottom.addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 0.1f))
        bottom.addView(
            arrowBtn(EditAction.HOME, icon(38, 13f / 38f, strokeDp = 2.25f) { c, p, x, y, s -> Glyphs.drawArrowToEdge(c, p, x, y, s, toStart = true) }).apply {
                isFocusable = false
                contentDescription = context.getString(R.string.edit_paragraph_start)
            },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        bottom.addView(iconBtn(context.getString(R.string.edit_select_all), EditAction.SELECT_ALL, icon(27, 0.388f, 0.74f) { c, p, x, y, s -> Glyphs.drawSelectAll(c, p, x, y, s) }, iconOnStart = true), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(
            arrowBtn(EditAction.END, icon(38, 13f / 38f, strokeDp = 2.25f) { c, p, x, y, s -> Glyphs.drawArrowToEdge(c, p, x, y, s, toStart = false) }).apply {
                isFocusable = false
                contentDescription = context.getString(R.string.edit_paragraph_end)
            },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        bottom.addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 0.9f))
        bottom.addView(iconBtn(context.getString(R.string.edit_paste), EditAction.PASTE, icon(27, 0.363f, 0.58f) { c, p, x, y, s -> Glyphs.drawClipboard(c, p, x, y, s) }, iconOnStart = true), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        val pasteBtn = requireNotNull(actionViews[EditAction.PASTE])
        val navigationActions = listOf(EditAction.HOME, EditAction.SELECT_ALL, EditAction.END).map { requireNotNull(actionViews[it]) }

        actionColumn = object : LinearLayout(context) {
            init { orientation = VERTICAL }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val viewport = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    0
                } else {
                    MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
                }
                val bottomHeight = dp(56)
                val contentHeight = maxOf(dp(44 * 3) + bottomHeight, viewport)
                val bottomContainerHeight = maxOf(bottomHeight, (contentHeight + 3) / 4)
                val midHeight = contentHeight - bottomContainerHeight
                for (action in rightActions) {
                    (action.layoutParams as LayoutParams).apply { height = 0; weight = 1f }
                }
                (mid.layoutParams as LayoutParams).height = midHeight
                (bottom.layoutParams as LayoutParams).height = bottomContainerHeight
                for (action in navigationActions) (action.layoutParams as LayoutParams).height = bottomHeight
                (pasteBtn.layoutParams as LayoutParams).height = bottomHeight
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY),
                )
                val referenceHeight = deleteBtn.measuredHeight
                for (action in rightActions) {
                    (action.layoutParams as LayoutParams).apply { height = referenceHeight; weight = 0f }
                }
                (pasteBtn.layoutParams as LayoutParams).height = referenceHeight
                val measuredBottomHeight = maxOf(bottomContainerHeight, referenceHeight)
                (bottom.layoutParams as LayoutParams).height = measuredBottomHeight
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(midHeight + measuredBottomHeight, MeasureSpec.EXACTLY),
                )
                pasteBtn.measure(
                    MeasureSpec.makeMeasureSpec(deleteBtn.measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(deleteBtn.measuredHeight, MeasureSpec.EXACTLY),
                )
            }
        }.apply {
            addView(mid, LayoutParams(LayoutParams.MATCH_PARENT, dp(48 * 3)))
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

    fun setHasSelection(has: Boolean) = applySelectionTint(has, animate = true)

    private fun applySelectionTint(has: Boolean, animate: Boolean) {
        val tint = if (has) palette.keyLabel else palette.disabled
        for ((b, icon) in listOf(copyBtn to copyIcon, cutBtn to cutIcon)) {
            b.isEnabled = has
            Motion.applyTapFeedback(b, tint, radiusDp = ImeShapes.keyRadiusDp)
            tintAnimators.remove(b)?.cancel()
            if (animate) {
                Motion.crossfadeColor(b, b.currentTextColor, tint) {
                    b.setTextColor(it)
                    icon.applyTint(it)
                }?.let { tintAnimators[b] = it }
            } else {
                b.setTextColor(tint)
                icon.applyTint(tint)
            }
        }
    }

    fun setSelecting(selecting: Boolean) {
        val changed = this.selecting != selecting
        this.selecting = selecting
        if (changed) Motion.coverThrough(selectBtn, palette.keyboardBg) { renderSelectingLabel() } else renderSelectingLabel()
    }

    private fun renderSelectingLabel() {
        selectBtn.text = if (selecting) context.getString(R.string.edit_end_select) else context.getString(R.string.edit_start_select)
    }

    override fun resetToDefault() {
        selecting = false
        Motion.reset(selectBtn)
        renderSelectingLabel()
        actionScroll.scrollTo(0, 0)
    }

    internal fun selectingLabelForTest(): CharSequence = selectBtn.text
    internal fun selectionTintAnimatingForTest(): Boolean = tintAnimators.values.any { it.isRunning }
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

    private fun spacer(): View = View(context)

    private fun textBtn(
        label: String,
        action: EditAction,
        sp: Float,
        pressedOnly: Boolean = false,
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(palette.keyLabel)
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabel, pressedOnly = pressedOnly)
        setOnClickListener { onAction(action) }
        actionViews[action] = this
    }

    private fun iconBtn(label: String, action: EditAction, glyph: GlyphDrawable, iconOnStart: Boolean = false): TextView = object : TextView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val avail = MeasureSpec.getSize(widthMeasureSpec) -
                (if (iconOnStart) glyph.intrinsicWidth + compoundDrawablePadding else 0)
            if (avail > 0) {
                val nominal = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TITLE_SP, resources.displayMetrics)
                val floor = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
                val nominalWidth = paint.measureText(label) * nominal / paint.textSize
                val fitted = if (nominalWidth > avail) maxOf(floor, nominal * avail / nominalWidth) else nominal
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted)
            }
            if (iconOnStart) {
                val textBounds = Rect()
                paint.getTextBounds(label, 0, label.length, textBounds)
                val textWidth = paint.measureText(label)
                val slack = MeasureSpec.getSize(widthMeasureSpec) -
                    glyph.intrinsicWidth - compoundDrawablePadding - textWidth
                val rightBearing = textWidth - textBounds.right
                val inset = (slack / 2f - glyph.leftInkInset() + rightBearing).toInt().coerceAtLeast(0)
                setPadding(inset, paddingTop, inset, paddingBottom)
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
        setTextColor(palette.keyLabel)
        setCompoundDrawablesWithIntrinsicBounds(
            if (iconOnStart) glyph else null,
            if (iconOnStart) null else glyph,
            null,
            null,
        )
        compoundDrawablePadding = dp(2)
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabel, radiusDp = ImeShapes.keyRadiusDp)
        setOnClickListener { onAction(action) }
        actionViews[action] = this
    }

    private fun arrowBtn(action: EditAction, glyph: GlyphDrawable): View = View(context).apply {
        arrowIcons[action] = glyph
        background = glyph
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabel)
        setOnClickListener { onAction(action) }
        actionViews[action] = this
    }

    private fun icon(
        boxDp: Int,
        sFactor: Float,
        leftExtent: Float = 0f,
        strokeDp: Float = 2f,
        render: (Canvas, Paint, Float, Float, Float) -> Unit,
    ): GlyphDrawable =
        GlyphDrawable(dp(boxDp), sFactor, strokeDp * density, leftExtent, render).also { it.applyTint(palette.keyLabel); icons += it }

    private class GlyphDrawable(
        private val boxPx: Int,
        private val sFactor: Float,
        private val strokePx: Float,
        private val leftExtent: Float,
        private val render: (Canvas, Paint, Float, Float, Float) -> Unit,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = strokePx
        }
        fun applyTint(color: Int) { paint.color = color; invalidateSelf() }
        fun leftInkInset(): Float = boxPx / 2f - boxPx * sFactor * leftExtent - strokePx / 2f
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
