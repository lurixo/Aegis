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

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
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
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType
import kotlin.math.abs

class CandidateGridView(context: Context) : LinearLayout(context), ResettablePanel {

    var onPick: (Int) -> Unit = {}
    var onPickReading: (Int) -> Unit = {}
    var onClose: () -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onClear: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun spPx(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private var palette = ImePalette.STATIC_LIGHT
    private val readingColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val table = TableColumn(context, density)
    private val readingScroll = RailScrollView(context, density)
    private val gridScroll = ScrollView(context)
    private val rightColumn = FrameLayout(context)
    private val backspaceGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }
    private val measurePaint = Paint()
    private val readingMeasurePaint = Paint().apply { typeface = Typeface.DEFAULT_BOLD }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var renderedCandidates: List<String>? = null
    private var renderedCandidateWidth = 0
    private var renderedReadings: List<String>? = null
    private var renderedSelected = Int.MIN_VALUE
    private var candidateRebuilds = 0
    private var readingRebuilds = 0
    private var measuringWidthOverride = 0

    private val chipPool = ArrayList<TextView>()
    private val rowPool = ArrayList<LinearLayout>()
    private val readingPool = ArrayList<TextView>()
    private val chipWidths = ArrayList<Int>()
    private val chipTextSizes = ArrayList<Float>()
    private var chipsAllocated = 0
    private var chipReparents = 0
    private var readingsAllocated = 0
    private val readingColorAnimators = HashMap<TextView, ValueAnimator>()
    private val chipClick = OnClickListener { v -> onPick(v.tag as Int) }
    private val readingClick = OnClickListener { v -> onPickReading(v.tag as Int) }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(palette.keyboardBg)
        readingScroll.applyPalette(palette)
        table.applyPalette(palette)

        readingScroll.addView(readingColumn, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            readingScroll,
            LayoutParams(dp(60 - 6 - 3), LayoutParams.MATCH_PARENT).apply {
                leftMargin = dp(6)
                rightMargin = dp(3)
                topMargin = dp(8)
                bottomMargin = dp(8)
            },
        )
        gridScroll.addView(
            table,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
                topMargin = dp(8)
                bottomMargin = dp(8)
            },
        )
        addView(
            gridScroll,
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        rightColumn.setBackgroundColor(palette.keyboardBg)
        rightColumn.addView(funcButton(context.getString(R.string.panel_back)) { onClose() }, rowAlignedLp(0))
        rightColumn.addView(
            backspaceButton().apply {
                setCompoundDrawablesWithIntrinsicBounds(null, backspaceGlyph, null, null)
                backspaceGlyph.tint(palette.keyLabelSecondary)
            },
            centeredLp(),
        )
        rightColumn.addView(funcButton(context.getString(R.string.kbd_redo)) { onClear() }, rowAlignedLp(4))
        addView(rightColumn, LayoutParams(dp(64), LayoutParams.MATCH_PARENT))
    }

    override fun resetToDefault() {
        readingScroll.scrollTo(0, 0)
        gridScroll.scrollTo(0, 0)
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        readingScroll.applyPalette(p)
        table.applyPalette(p)
        rightColumn.setBackgroundColor(p.keyboardBg)
        for (i in 0 until rightColumn.childCount) (rightColumn.getChildAt(i) as? TextView)?.let {
            it.setTextColor(p.keyLabelSecondary)
            Motion.applyTapFeedback(it, p.keyLabelSecondary)
        }
        backspaceGlyph.tint(p.keyLabelSecondary)
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

    private fun retintRipple(v: View, color: Int) {
        val fg = v.foreground
        if (fg is RippleDrawable) fg.setColor(ColorStateList.valueOf(Motion.withAlpha(color, 0x24)))
        else Motion.applyTapFeedback(v, color)
    }

    private fun rowAlignedLp(rowIndex: Int) =
        FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46), Gravity.TOP).apply { topMargin = dp(46 * rowIndex) }

    private fun centeredLp() = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46), Gravity.CENTER)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        if (mode != MeasureSpec.UNSPECIFIED) updateRightActionLayout(MeasureSpec.getSize(heightMeasureSpec))
        val incomingWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (incomingWidth > 0) {

            measuringWidthOverride = incomingWidth
            renderedCandidates?.let { setCandidates(it) }
            measuringWidthOverride = 0
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updateRightActionLayout(availableHeight: Int) {
        val preferred = dp(46)
        val fullRail = preferred * 5
        val back = returnButtonForTest()
        val delete = backspaceButtonForTest()
        val clear = clearButtonForTest()
        if (availableHeight >= fullRail) {
            setActionFrame(back, preferred, Gravity.TOP, 0)
            setActionFrame(delete, preferred, Gravity.CENTER, 0)
            setActionFrame(clear, preferred, Gravity.TOP, preferred * 4)
            return
        }
        val actionHeight = minOf(preferred, availableHeight.coerceAtLeast(0) / 3)
        setActionFrame(back, actionHeight, Gravity.TOP, 0)
        setActionFrame(delete, actionHeight, Gravity.TOP, (availableHeight - actionHeight) / 2)
        setActionFrame(clear, actionHeight, Gravity.TOP, (availableHeight - actionHeight).coerceAtLeast(0))
    }

    private fun setActionFrame(view: View, height: Int, gravity: Int, topMargin: Int) {
        val lp = view.layoutParams as FrameLayout.LayoutParams
        lp.height = height.coerceAtLeast(0)
        lp.gravity = gravity
        lp.topMargin = topMargin.coerceAtLeast(0)
    }

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

    fun setReadings(readings: List<String>, selected: Int = -1) {
        if (readings == renderedReadings && selected == renderedSelected) return
        val listChanged = readings != renderedReadings
        val prevSelected = renderedSelected
        renderedReadings = readings.toList()
        renderedSelected = selected
        readingRebuilds++
        for (i in readings.indices) {
            val tile = obtainReading(i)
            tile.tag = i
            if (tile.text != readings[i]) {
                tile.text = readings[i]
                val target = spPx(readingTextSize(readings[i]))
                if (abs(tile.textSize - target) > 0.5f) tile.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
            }
            tile.visibility = View.VISIBLE
        }
        for (i in readings.size until readingColumn.childCount) readingColumn.getChildAt(i).visibility = View.GONE
        if (listChanged) {
            for (i in readings.indices) styleReading(i, on = i == selected, animate = false)
        } else {
            if (prevSelected in readings.indices && prevSelected != selected) styleReading(prevSelected, on = false, animate = true)
            if (selected in readings.indices) styleReading(selected, on = true, animate = true)
        }
    }

    private fun readingTextSize(text: String): Float {
        val avail = (dp(60 - 6 - 3) - dp(12)).toFloat()
        if (avail <= 0f) return 11f
        readingMeasurePaint.textSize = spPx(ImeType.title)
        val w = readingMeasurePaint.measureText(text)
        if (w <= avail) return ImeType.title
        return (ImeType.title * avail / w).coerceAtLeast(11f)
    }

    private fun obtainReading(index: Int): TextView {
        if (index < readingPool.size) return readingPool[index]
        val tv = TextView(context).apply {
            gravity = Gravity.CENTER
            maxLines = 1
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

    private fun capFor(len: Int): Int = when {
        len <= 1 -> 6
        len == 2 -> 4
        else -> 3
    }

    fun setCandidates(candidates: List<String>) {

        val configuredWidth = resources.configuration.screenWidthDp
            .takeIf { it > 0 }
            ?.let { (it * density).toInt() }
            ?: resources.displayMetrics.widthPixels
        val liveWidth = measuringWidthOverride.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: configuredWidth
        val tableW = (liveWidth - dp(60 + 64) - dp(4 + 4)).coerceAtLeast(dp(46))
        if (candidates == renderedCandidates && tableW == renderedCandidateWidth) return
        renderedCandidates = candidates.toList()
        renderedCandidateWidth = tableW
        candidateRebuilds++
        while (chipWidths.size < candidates.size) chipWidths.add(0)
        while (chipTextSizes.size < candidates.size) chipTextSizes.add(ImeType.title)
        val lens = IntArray(candidates.size)
        val rowStarts = ArrayList<Int>()
        val rowCols = ArrayList<Int>()
        var start = 0
        var count = 0
        var rowMaxLen = 0
        for (i in candidates.indices) {
            lens[i] = GraphemeText.clusterCount(candidates[i])
            val newMax = maxOf(rowMaxLen, lens[i])
            if (count + 1 > capFor(newMax)) {
                rowStarts.add(start)
                rowCols.add(capFor(rowMaxLen))
                start = i
                count = 1
                rowMaxLen = lens[i]
            } else {
                count++
                rowMaxLen = newMax
            }
        }
        if (count > 0) {
            rowStarts.add(start)
            rowCols.add(capFor(rowMaxLen))
        }
        for (r in rowStarts.indices) {
            val from = rowStarts[r]
            val to = if (r + 1 < rowStarts.size) rowStarts[r + 1] else candidates.size
            val cols = rowCols[r]
            for (k in 0 until to - from) {
                val i = from + k
                chipWidths[i] = tableW * (k + 1) / cols - tableW * k / cols
                chipTextSizes[i] = candidateTextSize(candidates[i], lens[i], chipWidths[i])
                val chip = obtainChip(i)
                if (chip.text != candidates[i]) chip.text = candidates[i]
                chip.tag = i
            }
        }
        while (table.childCount < rowStarts.size) table.addView(obtainRow(table.childCount))
        while (table.childCount > rowStarts.size) table.removeViewAt(table.childCount - 1)
        table.setRowColumns(rowCols)
        for (r in rowStarts.indices) {
            val from = rowStarts[r]
            val to = if (r + 1 < rowStarts.size) rowStarts[r + 1] else candidates.size
            bindRow(table.getChildAt(r) as LinearLayout, from, to)
        }
    }

    private fun candidateTextSize(text: String, len: Int, cellWidth: Int): Float {
        val base = when {
            len <= 2 -> ImeType.title
            len == 3 -> ImeType.body
            else -> ImeType.label
        }
        if (len <= 4) return base
        val avail = (cellWidth - dp(8 + 8)).toFloat()
        if (avail <= 0f) return 10f
        measurePaint.textSize = spPx(base)
        val w = measurePaint.measureText(text)
        if (w <= avail) return base
        return (base * avail / w).coerceAtLeast(10f)
    }

    private fun obtainChip(index: Int): TextView {
        if (index < chipPool.size) return chipPool[index]
        val tv = TextView(context).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
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

    private fun bindRow(row: LinearLayout, start: Int, end: Int) {
        val n = end - start
        if (row.childCount == n) {
            var same = true
            for (k in 0 until n) if (row.getChildAt(k) !== chipPool[start + k]) { same = false; break }
            if (same) {
                for (k in 0 until n) applyCell(chipPool[start + k], start + k)
                return
            }
        }
        row.removeAllViews()
        for (k in 0 until n) {
            val i = start + k
            val chip = chipPool[i]
            (chip.parent as? ViewGroup)?.takeIf { it !== row }?.removeView(chip)
            row.addView(chip, LayoutParams(chipWidths[i], dp(46)))
            applyCell(chip, i)
            chipReparents++
        }
    }

    private fun applyCell(chip: TextView, index: Int) {
        val lp = chip.layoutParams as LayoutParams
        if (lp.width != chipWidths[index]) {
            lp.width = chipWidths[index]
            chip.layoutParams = lp
        }
        val target = spPx(chipTextSizes[index])
        if (abs(chip.textSize - target) > 0.5f) chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
    }

    private fun newRow(): LinearLayout = LinearLayout(context).apply { orientation = HORIZONTAL }

    internal fun candidateRebuildsForTest(): Int = candidateRebuilds
    internal fun readingRebuildsForTest(): Int = readingRebuilds
    internal fun chipsAllocatedForTest(): Int = chipsAllocated
    internal fun needsPoolGrowth(candidateCount: Int, readingCount: Int): Boolean =
        candidateCount > chipPool.size || readingCount > readingPool.size
    internal fun setSelectionContentVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.INVISIBLE
        readingScroll.visibility = target
        gridScroll.visibility = target
    }
    internal fun chipReparentsForTest(): Int = chipReparents
    internal fun readingsAllocatedForTest(): Int = readingsAllocated
    internal fun selectionContentVisibleForTest(): Boolean =
        readingScroll.visibility == View.VISIBLE && gridScroll.visibility == View.VISIBLE
    internal fun renderedCandidateTextsForTest(): List<String> {
        val out = ArrayList<String>()
        for (r in 0 until table.childCount) {
            val row = table.getChildAt(r) as LinearLayout
            for (k in 0 until row.childCount) out.add((row.getChildAt(k) as TextView).text.toString())
        }
        return out
    }
    internal fun rowTextsForTest(): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (r in 0 until table.childCount) {
            val row = table.getChildAt(r) as LinearLayout
            out.add((0 until row.childCount).map { (row.getChildAt(it) as TextView).text.toString() })
        }
        return out
    }
    internal fun rowColumnCountsForTest(): List<Int> = table.rowColumnsForTest()
    internal fun chipTextSizeSpForTest(index: Int): Float = chipPool[index].textSize / spPx(1f)
    internal fun chipEllipsizeForTest(index: Int): TextUtils.TruncateAt? = chipPool[index].ellipsize
    internal fun chipCellWidthForTest(index: Int): Int = chipPool[index].layoutParams.width
    internal fun readingTextSizeSpForTest(index: Int): Float = readingPool[index].textSize / spPx(1f)
    internal fun railLayoutForTest(): IntArray {
        val lp = readingScroll.layoutParams as LayoutParams
        return intArrayOf(lp.width, lp.leftMargin, lp.rightMargin, lp.topMargin, lp.bottomMargin)
    }
    internal fun tableLayoutForTest(): IntArray {
        val lp = table.layoutParams as FrameLayout.LayoutParams
        return intArrayOf(lp.leftMargin, lp.rightMargin, lp.topMargin, lp.bottomMargin)
    }
    internal fun railThumbRectForTest(): RectF? = readingScroll.thumbRect()
    internal fun railTrackAndContentForTest(): Pair<Int, Int> = readingScroll.height to readingColumn.height
    internal fun railColorsForTest(): Triple<Int, Int, Int> =
        Triple(readingScroll.railColorForTest(), readingScroll.separatorColorForTest(), readingScroll.thumbColorForTest())
    internal fun railCornerRadiusForTest(): Float = readingScroll.cornerRadiusForTest()
    internal fun railScrollbarEnabledForTest(): Boolean = readingScroll.isVerticalScrollBarEnabled
    internal fun tableSeparatorColorForTest(): Int = table.separatorColorForTest()
    internal fun tableCornerRadiusForTest(): Float = table.cornerRadiusForTest()
    internal fun tableBackgroundForTest(): Drawable? = table.background
    internal fun renderedReadingTextsForTest(): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until readingColumn.childCount) {
            val t = readingColumn.getChildAt(i) as TextView
            if (t.visibility == View.VISIBLE) out.add(t.text.toString())
        }
        return out
    }
    internal fun tapCandidateForTest(flat: Int): Boolean {
        var seen = 0
        for (r in 0 until table.childCount) {
            val row = table.getChildAt(r) as LinearLayout
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
    internal fun gridCanScrollForwardForTest(): Boolean = gridScroll.canScrollVertically(1)
    internal fun readingCanScrollForwardForTest(): Boolean = readingScroll.canScrollVertically(1)
    internal fun scrollForTest(gridY: Int, readingY: Int = 0) {
        gridScroll.scrollTo(0, gridY)
        readingScroll.scrollTo(0, readingY)
    }
    internal fun readingTextColorForTest(index: Int): Int? =
        (readingColumn.getChildAt(index) as? TextView)?.currentTextColor
    internal fun selectedReadingBackgroundForTest(index: Int): Drawable? =
        (readingColumn.getChildAt(index) as? TextView)?.background

    private class RailScrollView(context: Context, private val density: Float) : ScrollView(context) {
        private val radius = ImeShapes.keyRadiusDp * density
        private val bg = GradientDrawable().apply { cornerRadius = radius }
        private val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
        private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbRect = RectF()
        private var railColor = 0

        init {
            isVerticalScrollBarEnabled = false
            background = bg
            clipToOutline = true
        }

        fun applyPalette(p: ImePalette) {
            railColor = p.railBg
            bg.setColor(p.railBg)
            separatorPaint.color = p.separator
            thumbPaint.color = Motion.withAlpha(p.icon, 0x55)
            invalidate()
        }

        fun thumbRect(): RectF? {
            val column = getChildAt(0) ?: return null
            val contentH = column.height.toFloat()
            val trackH = height.toFloat()
            if (contentH <= trackH + 0.5f) return null
            val thumbH = maxOf(18f * density, trackH * trackH / contentH)
            val fraction = (scrollY / (contentH - trackH)).coerceIn(0f, 1f)
            val top = scrollY + fraction * (trackH - thumbH)
            val right = width - 2f * density
            thumbRect.set(right - 2.5f * density, top, right, top + thumbH)
            return thumbRect
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val column = getChildAt(0) as? ViewGroup
            if (column != null) {
                var last = -1
                for (i in column.childCount - 1 downTo 0) {
                    if (column.getChildAt(i).visibility == VISIBLE) {
                        last = i
                        break
                    }
                }
                val inset = 6f * density
                val viewportTop = scrollY.toFloat()
                val viewportBottom = viewportTop + height
                for (i in 0 until last) {
                    val child = column.getChildAt(i)
                    if (child.visibility != VISIBLE) continue
                    val y = (column.top + child.bottom).toFloat()
                    if (y < viewportTop || y >= viewportBottom) continue
                    canvas.drawLine(inset, y, width - inset, y, separatorPaint)
                }
            }
            thumbRect()?.let { canvas.drawRoundRect(it, 2f * density, 2f * density, thumbPaint) }
        }

        fun railColorForTest(): Int = railColor
        fun separatorColorForTest(): Int = separatorPaint.color
        fun thumbColorForTest(): Int = thumbPaint.color
        fun cornerRadiusForTest(): Float = radius
    }

    private class TableColumn(context: Context, private val density: Float) : LinearLayout(context) {
        private val radius = ImeShapes.cardRadiusDp * density
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val outlineRect = RectF()
        private val rowColumns = ArrayList<Int>()

        init {
            orientation = VERTICAL
        }

        fun applyPalette(p: ImePalette) {
            linePaint.color = p.separator
            outlinePaint.color = p.separator
            invalidate()
        }

        fun setRowColumns(cols: List<Int>) {
            rowColumns.clear()
            rowColumns.addAll(cols)
            invalidate()
        }

        fun rowColumnsForTest(): List<Int> = rowColumns.toList()
        fun separatorColorForTest(): Int = linePaint.color
        fun cornerRadiusForTest(): Float = radius

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (childCount == 0) return
            val w = width.toFloat()
            for (r in 0 until childCount) {
                val row = getChildAt(r)
                val cols = rowColumns.getOrElse(r) { 1 }
                for (k in 1 until cols) {
                    val x = (width * k / cols).toFloat()
                    canvas.drawLine(x, row.top.toFloat(), x, row.bottom.toFloat(), linePaint)
                }
                if (r < childCount - 1) canvas.drawLine(0f, row.bottom.toFloat(), w, row.bottom.toFloat(), linePaint)
            }
            val half = density / 2f
            outlineRect.set(half, half, w - half, height - half)
            canvas.drawRoundRect(outlineRect, radius, radius, outlinePaint)
        }
    }

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
