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

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import kotlin.math.abs

/**
  * Chinese IME behavior note.
 *  - LEFT  = pinyin-combination selector (scroll) — tap re-ranks via [onPickReading].
 *  - MIDDLE = candidate grid (scroll), single chars included (no forced combining) — tap commits [onPick].
  * Chinese IME behavior note.
 */
class CandidateGridView(context: Context) : LinearLayout(context), ResettablePanel {

    var onPick: (Int) -> Unit = {}
    var onPickReading: (Int) -> Unit = {}
    var onClose: () -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onClear: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT
    private val readingColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val gridColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val readingScroll = ScrollView(context).apply { addView(readingColumn) }
    private val gridScroll = ScrollView(context).apply { addView(gridColumn) }
    private val rightColumn = FrameLayout(context)
    // debug.17 A2: the right column's ⌫ is a self-drawn [Glyphs.drawBackspace] icon (same GlyphDrawable wrapper
    // Chinese IME behavior note.
    private val backspaceGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }
    private val measurePaint = Paint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.title, resources.displayMetrics)
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var renderedCandidates: List<String>? = null
    private var renderedCandidateWidth = 0
    private var renderedReadings: List<String>? = null
    private var renderedSelected = Int.MIN_VALUE
    private var candidateRebuilds = 0
    private var readingRebuilds = 0

    // --- view-recycling pools (jank root-cause fix) ---
    // The old setCandidates/setReadings did removeAllViews + a fresh TextView + RippleDrawable per candidate /
    // per reading on EVERY render (i.e. every keystroke), allocating hundreds of Views + ripples in one frame.
    // These pools keep the tiles alive across renders and only REBIND text / width / index / colour in place;
    // the ripple is built ONCE per pooled tile (and re-tinted, never re-allocated). A content change now costs
    // O(new tiles past the high-water mark) allocations, not O(list). The left column's selected state moves
    // via an MD3 state-layer colour cross-fade instead of a full rebuild + colour snap.
    private val chipPool = ArrayList<TextView>()        // middle grid chips, chipPool[i] renders candidate i
    private val rowPool = ArrayList<LinearLayout>()      // reusable row containers for the greedy wrap
    private val readingPool = ArrayList<TextView>()      // left column tiles, readingPool[i] renders reading i
    private val chipWidths = ArrayList<Int>()            // measured cell width per candidate (parallel to chipPool)
    private var chipsAllocated = 0                       // pool high-water mark — proves O(peak), not O(sum)
    private var chipReparents = 0                        // rows a chip was (re)attached to — the incremental delta
    private var readingsAllocated = 0
    private val readingColorAnimators = HashMap<TextView, ValueAnimator>()
    private val chipClick = OnClickListener { v -> onPick(v.tag as Int) }
    private val readingClick = OnClickListener { v -> onPickReading(v.tag as Int) }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(palette.keyboardBg) // P-A: panel floor == the strip/keyboard floor (no top seam)

        // LEFT — pinyin-combination selector (scroll).
        readingColumn.setBackgroundColor(palette.keyboardBg)
        addView(
            readingScroll,
            LayoutParams(dp(60), LayoutParams.MATCH_PARENT),
        )
        // MIDDLE — candidate grid (scroll), takes the remaining width.
        addView(
            gridScroll,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        // Chinese IME behavior note.
        rightColumn.setBackgroundColor(palette.keyboardBg) // Chinese IME behavior note.
        rightColumn.addView(funcButton("返回") { onClose() }, rowAlignedLp(0))
        // debug.17 A2: ⌫ → self-drawn glyph. TOP compound slot (not LEFT) so it centres horizontally in the
        // narrow column like the D-pad's icon-only arrowBtn — LEFT/RIGHT slots pin to the padding edge and
        // Chinese IME behavior note.
        rightColumn.addView(
            backspaceButton().apply {
                setCompoundDrawablesWithIntrinsicBounds(null, backspaceGlyph, null, null)
                backspaceGlyph.tint(palette.keyLabelSecondary)
            },
            centeredLp(),
        )
        rightColumn.addView(funcButton("重输") { onClear() }, rowAlignedLp(4))
        addView(rightColumn, LayoutParams(dp(64), LayoutParams.MATCH_PARENT))
    }

    override fun resetToDefault() {
        readingScroll.scrollTo(0, 0)
        gridScroll.scrollTo(0, 0)
    }

    /** F1: recolour the panel from the Monet palette (content recolours on the next setReadings/setCandidates). */
    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg) // P-A: see init
        readingColumn.setBackgroundColor(p.keyboardBg)
        rightColumn.setBackgroundColor(p.keyboardBg) // P-A: see init
        for (i in 0 until rightColumn.childCount) (rightColumn.getChildAt(i) as? TextView)?.let {
            it.setTextColor(p.keyLabelSecondary)
            Motion.applyTapFeedback(it, p.keyLabelSecondary)
        }
        backspaceGlyph.tint(p.keyLabelSecondary) // debug.17 A2: keep the self-drawn ⌫ in step with the column
        // Re-tint the pooled tiles in place (no re-allocation): chips are always the resting candidate colour;
        // reading tiles recolour by their current selected state.
        for (chip in chipPool) { chip.setTextColor(p.candidateText); retintRipple(chip, p.candidateText) }
        for (i in readingPool.indices) {
            val color = readingColor(i == renderedSelected)
            readingPool[i].setTextColor(color)
            retintRipple(readingPool[i], color)
        }
        renderedCandidates = null
        renderedReadings = null
    }

    private fun readingColor(on: Boolean): Int = if (on) palette.accentBottom else palette.candidateText

    /** Update a pooled tile's ripple tint WITHOUT allocating a new [RippleDrawable] (setColor mutates in place). */
    private fun retintRipple(v: View, color: Int) {
        val fg = v.foreground
        if (fg is RippleDrawable) fg.setColor(ColorStateList.valueOf(Motion.withAlpha(color, 0x24)))
        else Motion.applyTapFeedback(v, color)
    }

    private fun rowAlignedLp(rowIndex: Int) =
        FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46), Gravity.TOP).apply { topMargin = dp(46 * rowIndex) }

    private fun centeredLp() = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46), Gravity.CENTER)

    private fun funcButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.keyLabelSecondary)
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabelSecondary)
        setOnClickListener { onClick() }
    }

    private fun backspaceButton(): TextView {
        var downX = 0f
        var downY = 0f
        return funcButton("") { onBackspace() }.apply {
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val upSwipe = downY - e.y > touchSlop && abs(e.x - downX) <= dp(28)
                        if (upSwipe) {
                            onClear()
                        } else {
                            v.performClick()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
    }

    /**
      * Chinese IME behavior note.
     * syllables (26-key UI-2, tap = drill); tapping [i] fires [onPickReading]. [selected] (UI-2) highlights
      * Chinese IME behavior note.
     */
    fun setReadings(readings: List<String>, selected: Int = -1) {
        if (readings == renderedReadings && selected == renderedSelected) return
        val listChanged = readings != renderedReadings
        val prevSelected = renderedSelected
        renderedReadings = readings.toList()
        renderedSelected = selected
        readingRebuilds++
        // Bind one pooled tile per reading (leading children of readingColumn); park any surplus tiles GONE.
        for (i in readings.indices) {
            val tile = obtainReading(i)
            tile.tag = i
            if (tile.text != readings[i]) tile.text = readings[i]
            tile.visibility = View.VISIBLE
        }
        for (i in readings.size until readingColumn.childCount) readingColumn.getChildAt(i).visibility = View.GONE
        if (listChanged) {
            // Content change (new syllable / decode): style every tile instantly — a per-decode colour fade
            // would strobe, so the fluid path wins (spec: 不得为动画牺牲流畅).
            for (i in readings.indices) styleReading(i, on = i == selected, animate = false)
        } else {
            // Pure selection change (e.g. locking a different reading): MD3 state-layer colour cross-fade on
            // ONLY the two tiles whose selected state flipped — never a rebuild, never an instant colour snap.
            if (prevSelected in readings.indices && prevSelected != selected) styleReading(prevSelected, on = false, animate = true)
            if (selected in readings.indices) styleReading(selected, on = true, animate = true)
        }
    }

    private fun obtainReading(index: Int): TextView {
        if (index < readingPool.size) return readingPool[index]
        val tv = TextView(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
            setTextColor(palette.candidateText)
            isClickable = true
            Motion.applyTapFeedback(this, palette.candidateText)
            setOnClickListener(readingClick)
        }
        readingPool.add(tv)
        readingColumn.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        readingsAllocated++
        return tv
    }

    private fun styleReading(index: Int, on: Boolean, animate: Boolean) {
        val tile = readingColumn.getChildAt(index) as? TextView ?: return
        val target = readingColor(on)
        readingColorAnimators.remove(tile)?.cancel()
        if (animate) {
            Motion.crossfadeColor(tile, tile.currentTextColor, target) { tile.setTextColor(it) }
                ?.let { readingColorAnimators[tile] = it }
        } else {
            tile.setTextColor(target)
        }
        tile.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        retintRipple(tile, target)
    }

    /**
     * MIDDLE: rebind the candidate grid for [candidates], wrapping greedily to the column width. Reuses the
     * pooled chips + row containers — only chips past the pool high-water mark allocate, and a row is
     * re-populated only when its chip run actually changed (early unchanged rows are skipped).
     */
    fun setCandidates(candidates: List<String>) {
        // Width budget: screen minus the left selector + right function column (+ a little padding).
        val maxRowW = resources.displayMetrics.widthPixels - dp(60 + 64 + 16)
        if (candidates == renderedCandidates && maxRowW == renderedCandidateWidth) return
        renderedCandidates = candidates.toList()
        renderedCandidateWidth = maxRowW
        candidateRebuilds++
        val cellPad = dp(14)
        // 1) Bind one pooled chip per candidate (allocate only past the high-water mark) + measure its width.
        while (chipWidths.size < candidates.size) chipWidths.add(0)
        for ((i, c) in candidates.withIndex()) {
            val chip = obtainChip(i)
            if (chip.text != c) chip.text = c
            chip.tag = i
            chipWidths[i] = (measurePaint.measureText(c) + cellPad * 2).toInt()
        }
        // 2) Greedy-wrap to row boundaries — pixel-identical to the old layout (new row when adding a chip
        //    would overflow AND the current row is non-empty; the overflowing chip starts the new row).
        val rowEnds = ArrayList<Int>()
        var rowW = 0
        var start = 0
        for (i in candidates.indices) {
            val w = chipWidths[i]
            if (rowW + w > maxRowW && i > start) { rowEnds.add(i); start = i; rowW = 0 }
            rowW += w
        }
        if (candidates.isNotEmpty()) rowEnds.add(candidates.size)
        // 3) Reconcile row containers: reuse, add/remove only the row-count delta.
        while (gridColumn.childCount < rowEnds.size) gridColumn.addView(obtainRow(gridColumn.childCount))
        while (gridColumn.childCount > rowEnds.size) gridColumn.removeViewAt(gridColumn.childCount - 1)
        var from = 0
        for ((r, end) in rowEnds.withIndex()) {
            bindRow(gridColumn.getChildAt(r) as LinearLayout, from, end)
            from = end
        }
    }

    private fun obtainChip(index: Int): TextView {
        if (index < chipPool.size) return chipPool[index]
        val tv = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
            setTextColor(palette.candidateText)
            isClickable = true
            Motion.applyTapFeedback(this, palette.candidateText)
            setOnClickListener(chipClick)
        }
        chipPool.add(tv)
        chipsAllocated++
        return tv
    }

    private fun obtainRow(rowIndex: Int): LinearLayout {
        if (rowIndex < rowPool.size) return rowPool[rowIndex]
        val row = newRow()
        rowPool.add(row)
        return row
    }

    /** Populate [row] with chips [start, end) in order. Fast-path skips re-parenting when the run is unchanged. */
    private fun bindRow(row: LinearLayout, start: Int, end: Int) {
        val n = end - start
        if (row.childCount == n) {
            var same = true
            for (k in 0 until n) if (row.getChildAt(k) !== chipPool[start + k]) { same = false; break }
            if (same) {
                for (k in 0 until n) {
                    val chip = chipPool[start + k]
                    val lp = chip.layoutParams as LayoutParams
                    if (lp.width != chipWidths[start + k]) { lp.width = chipWidths[start + k]; chip.layoutParams = lp }
                }
                return
            }
        }
        row.removeAllViews()
        for (k in 0 until n) {
            val i = start + k
            val chip = chipPool[i]
            (chip.parent as? ViewGroup)?.takeIf { it !== row }?.removeView(chip)
            row.addView(chip, LayoutParams(chipWidths[i], dp(46)))
            chipReparents++
        }
    }

    private fun newRow(): LinearLayout = LinearLayout(context).apply { orientation = HORIZONTAL }

    internal fun candidateRebuildsForTest(): Int = candidateRebuilds
    internal fun readingRebuildsForTest(): Int = readingRebuilds
    // Recycling instrumentation: total tiles ever allocated (pool high-water mark) vs re-parent operations.
    internal fun chipsAllocatedForTest(): Int = chipsAllocated
    internal fun chipReparentsForTest(): Int = chipReparents
    internal fun readingsAllocatedForTest(): Int = readingsAllocated
    /** Flattened chip texts (row by row) currently laid out in the middle grid. */
    internal fun renderedCandidateTextsForTest(): List<String> {
        val out = ArrayList<String>()
        for (r in 0 until gridColumn.childCount) {
            val row = gridColumn.getChildAt(r) as LinearLayout
            for (k in 0 until row.childCount) out.add((row.getChildAt(k) as TextView).text.toString())
        }
        return out
    }
    /** Visible reading tiles (parked surplus pooled tiles excluded), in order. */
    internal fun renderedReadingTextsForTest(): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until readingColumn.childCount) {
            val t = readingColumn.getChildAt(i) as TextView
            if (t.visibility == View.VISIBLE) out.add(t.text.toString())
        }
        return out
    }
    /** Tap the [flat]-th chip (row-major) — asserts the click index stayed bound to the right candidate. */
    internal fun tapCandidateForTest(flat: Int): Boolean {
        var seen = 0
        for (r in 0 until gridColumn.childCount) {
            val row = gridColumn.getChildAt(r) as LinearLayout
            for (k in 0 until row.childCount) {
                if (seen == flat) return row.getChildAt(k).performClick()
                seen++
            }
        }
        return false
    }
    internal fun tapReadingForTest(index: Int): Boolean =
        (readingColumn.getChildAt(index) as? TextView)?.takeIf { it.visibility == View.VISIBLE }?.performClick() ?: false
    internal fun readingTypefaceBoldForTest(index: Int): Boolean =
        (readingColumn.getChildAt(index) as? TextView)?.typeface?.isBold ?: false
    internal fun firstChipForegroundForTest(): Drawable? =
        chipPool.firstOrNull()?.foreground
    internal fun activeReadingColorAnimatorsForTest(): Int =
        readingColorAnimators.values.count { it.isRunning }
    internal fun returnButtonForTest(): TextView = rightColumn.getChildAt(0) as TextView
    internal fun backspaceButtonForTest(): TextView = rightColumn.getChildAt(1) as TextView
    internal fun clearButtonForTest(): TextView = rightColumn.getChildAt(2) as TextView
    internal fun gridScrollYForTest(): Int = gridScroll.scrollY
    internal fun readingScrollYForTest(): Int = readingScroll.scrollY
    internal fun scrollForTest(gridY: Int, readingY: Int = 0) {
        gridScroll.scrollTo(0, gridY)
        readingScroll.scrollTo(0, readingY)
    }
    internal fun readingTextColorForTest(index: Int): Int? =
        (readingColumn.getChildAt(index) as? TextView)?.currentTextColor
    internal fun selectedReadingBackgroundForTest(index: Int): Drawable? =
        (readingColumn.getChildAt(index) as? TextView)?.background

    /** debug.17 A2: a palette-tinted [Drawable] drawing one self-drawn [Glyphs] icon (the right column's ⌫), so
      * Chinese IME behavior note.
      */
    private class IconDrawable(
        private val density: Float,
        private val sFactor: Float,
        private val render: (Canvas, Paint, Float, Float, Float) -> Unit,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun tint(color: Int) { paint.color = color; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            val b = bounds
            render(canvas, paint, b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) * sFactor)
        }
        override fun getIntrinsicWidth() = (22 * density).toInt()
        override fun getIntrinsicHeight() = (22 * density).toInt()
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
