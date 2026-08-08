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
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.ime.theme.ImeShapes
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class CopyBarView(context: Context) : LinearLayout(context) {

    var onCommit: (String) -> Unit = {}
    var onSelectionChanged: (String) -> Unit = {}
    var onSelectionFinished: () -> Unit = {}
    var onDismiss: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT

    private var preview: CopyBarPreview? = null
    private var sliding = false

    private val ctl = CopyBarController(
        commit = { onCommit(it) },
        selectionChanged = { onSelectionChanged(it) },
        selectionFinished = { onSelectionFinished() },
        dismiss = { onDismiss() },
    )

    private val row = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = capsuleBg()
        setPadding(dp(14), 0, dp(14), 0)
        addView(row, lp(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun capsuleBg() = InsetDrawable(
        GradientDrawable().apply { setColor(palette.keySurface); cornerRadius = ImeShapes.toolbarPillRadiusDp * density },
        dp(8), dp(5), dp(8), dp(5),
    )

    fun applyPalette(p: ImePalette) {
        palette = p
        background = capsuleBg()
        setPadding(dp(14), 0, dp(14), 0)
        render()
    }

    fun show(text: String) { ctl.show(text); Motion.reset(row); render() }

    private fun toggleSplit() {
        ctl.toggleSplit()
        if (isShown) Motion.coverThrough(this, palette.keyboardBg) { render() } else render()
    }

    private fun render() {
        row.removeAllViews()
        preview = null
        row.addView(icon(), lp(dp(26), dp(26)))
        if (!ctl.splitMode) {
            row.addView(contentScroller(ctl.content.orEmpty()), lp(0, WC, 1f))
            row.addView(divider(), lp(dp(1), dp(18)))
            row.addView(pill(context.getString(R.string.copybar_split)) { toggleSplit() }, lp(WC, WC))
        } else {
            val chips = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (ctl.blocks.isEmpty()) chips.addView(TextView(context).apply {
                text = context.getString(R.string.copybar_no_splittable_content); setTextColor(palette.keyHint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label); setPadding(dp(8), 0, dp(8), 0)
            })
            for ((index, block) in ctl.blocks.withIndex()) chips.addView(chip(index, block))
            row.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, lp(0, WC, 1f))
            row.addView(pill(context.getString(R.string.copybar_collapse)) { toggleSplit() }, lp(WC, WC))
        }
        row.addView(pill("×") { ctl.close() }, lp(dp(34), WC))
    }

    private fun contentScroller(s: String): HorizontalScrollView {
        val window = CopyBarPreview(s, WINDOW_CHARS, STEP_CHARS)
        preview = window
        val text = content(window.text())
        val scroller = HorizontalScrollView(context)
        scroller.isHorizontalScrollBarEnabled = false
        scroller.addView(text)
        if (window.slides) scroller.setOnScrollChangeListener { _, _, _, _, _ -> slide(scroller, text, window, s) }
        return scroller
    }

    private fun slide(scroller: HorizontalScrollView, text: TextView, window: CopyBarPreview, source: String) {
        if (sliding) return
        val viewport = scroller.width - scroller.paddingLeft - scroller.paddingRight
        val edge = text.width - viewport
        if (viewport <= 0 || edge <= 0) return
        val zone = minOf(viewport, edge / 3)
        val x = scroller.scrollX
        val from = window.start
        val moved = when {
            x >= edge - zone -> window.forward()
            x <= zone -> window.back()
            else -> false
        }
        if (!moved) return
        val to = window.start
        val shift = text.paint.measureText(source, minOf(from, to), maxOf(from, to)).roundToInt()
        sliding = true
        try {
            text.text = window.text()
            scroller.scrollTo(if (to > from) x - shift else x + shift, 0)
        } finally {
            sliding = false
        }
    }

    internal fun previewStartForTest(): Int = preview?.start ?: 0
    internal fun toggleSplitForTest() = toggleSplit()
    internal fun splitModeForTest(): Boolean = ctl.splitMode
    internal fun contentForTest(): String? = ctl.content
    internal fun splitBlocksForTest(): List<String> = ctl.blocks
    internal fun splitSelectedForTest(): Set<Int> = ctl.selectedIndices()
    internal fun tapSplitBlockForTest(index: Int): Boolean? = ctl.tapBlock(index)
    internal fun finishSplitSelection() = ctl.finishSelection()
    internal fun splitRenderedForTest(): Boolean = scrollerForTest { it is LinearLayout } != null
    internal fun contentScrollerForTest(): HorizontalScrollView? = scrollerForTest { it is TextView }
    private fun scrollerForTest(childIs: (View) -> Boolean): HorizontalScrollView? =
        (0 until row.childCount).map(row::getChildAt).filterIsInstance<HorizontalScrollView>()
            .firstOrNull { childIs(it.getChildAt(0)) }

    private fun icon(): View = object : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = palette.icon
        }
        override fun onDraw(canvas: Canvas) =
            Glyphs.drawClipboard(canvas, p, width / 2f, height / 2f, 9f * density)
    }.apply {
        Motion.applyTapFeedback(this, palette.icon)
        setOnClickListener { ctl.tapContent() }
    }

    private fun content(s: String): TextView = TextView(context).apply {
        text = s
        maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.candidateText)
        setPadding(dp(8), 0, dp(8), 0)
        gravity = Gravity.CENTER_VERTICAL
        Motion.applyTapFeedback(this, palette.candidateText)
        setOnClickListener { ctl.tapContent() }
    }

    private fun chip(index: Int, label: String): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        applyChipState(this, index in ctl.selectedIndices())
        setOnClickListener {
            ctl.tapBlock(index)?.let { selected -> applyChipState(this, selected) }
        }
        layoutParams = LinearLayout.LayoutParams(WC, WC).apply { rightMargin = dp(6) }
    }

    private fun applyChipState(chip: TextView, selected: Boolean) {
        val textColor = if (selected) palette.chipText else palette.accentLabel
        val backgroundColor = if (selected) palette.chipBg else palette.accentBottom
        chip.setTextColor(textColor)
        chip.background = GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = ImeShapes.chipRadiusDp * density
        }
        Motion.applyTapFeedback(chip, textColor)
    }

    private fun pill(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
        setTextColor(palette.icon)
        setPadding(dp(10), 0, dp(10), 0)
        Motion.applyTapFeedback(this, palette.icon)
        setOnClickListener { onClick() }
    }

    private fun divider(): View = View(context).apply { setBackgroundColor(palette.separator) }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    internal companion object {
        const val WC = LinearLayout.LayoutParams.WRAP_CONTENT
        const val WINDOW_CHARS = 2000
        const val STEP_CHARS = 500
    }
}
