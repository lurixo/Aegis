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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.layout.Layouts
import kotlin.math.abs
import kotlin.math.roundToInt

data class CandidateProjectionPolicy(val maxPhraseRows: Int) {
    init {
        require(maxPhraseRows >= 0)
    }

    companion object {
        val PINYIN = CandidateProjectionPolicy(maxPhraseRows = 3)
    }
}

class CandidateGridView(context: Context) : LinearLayout(context), ResettablePanel, CoversToolbar, KeyHapticsAware {

    var onPick: (Int) -> Unit = {}
    var onPickReading: (Int) -> Unit = {}
    var onClose: () -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onClear: () -> Unit = {}
    override var hapticEnabled = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun spPx(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private val rowHeightPx = dp(48)
    private val backActionRow = 0
    private val deleteActionBoundary = 2
    private val clearActionBoundary = 4

    private var palette = ImePalette.STATIC_LIGHT
    private val readingColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val table = TableColumn(context, density)
    private val readingScroll = RailScrollView(context, density)
    private val gridScroll = FrameLayout(context)
    private val rightColumn = FrameLayout(context)
    private val backspaceGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }
    private val collapseGlyph = IconDrawable(density, 9f * (1.64f / 1.40f) / 22f) { c, p, x, y, s -> Glyphs.drawChevron(c, p, x, y, s, down = false) }
    private val measurePaint = Paint()
    private var sourceCandidates: List<String>? = null
    private var sourceCandidateProjection: CandidateProjectionPolicy? = null
    private var renderedCandidates: List<String>? = null
    private var renderedSourceIndices: List<Int> = emptyList()
    private var renderedCandidateWidth = 0
    private var renderedReadings: List<String>? = null
    private var renderedSelected = Int.MIN_VALUE
    private var candidateRebuilds = 0
    private var readingRebuilds = 0
    private var measuringWidthOverride = 0
    private var lastMeasuredWidth = 0
    private var resetViewportOnLayout = false

    private val readingPool = ArrayList<TextView>()
    private val chipWidths = ArrayList<Int>()
    private val chipTextSizes = ArrayList<Float>()
    private val rowStarts = ArrayList<Int>()
    private val rowCounts = ArrayList<Int>()
    private var chipsAllocated = 0
    private var readingsAllocated = 0
    private var gridScrollOffsetForTest = 0
    private val readingColorAnimators = HashMap<TextView, ValueAnimator>()
    private val readingFeedback = HashMap<TextView, ImeKeyFeedback>()
    private val chipFeedback = HashMap<TextView, ImeKeyFeedback>()
    private val chipClick = OnClickListener { v -> onPick(v.tag as Int) }
    private val readingClick = OnClickListener { v ->
        val reading = v.tag as? String ?: return@OnClickListener
        val index = renderedReadings?.indexOf(reading) ?: -1
        if (index >= 0) onPickReading(index)
    }
    private val candidateAdapter = CandidateAdapter()
    private val returnFeedback: ImeKeyFeedback
    private val backspaceFeedback: ImeKeyFeedback
    private val clearFeedback: ImeKeyFeedback
    private val backspaceTouch: ImeBackspaceTouch

    init {
        orientation = HORIZONTAL
        setBackgroundColor(palette.keyboardBg)
        readingScroll.applyPalette(palette)
        table.applyPalette(palette)
        table.adapter = candidateAdapter

        readingScroll.addView(readingColumn, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            readingScroll,
            LayoutParams(dp(51), LayoutParams.MATCH_PARENT).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
                topMargin = dp(8)
                bottomMargin = dp(8)
            },
        )
        gridScroll.addView(
            table,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
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
        rightColumn.addView(
            funcButton("") { onClose() }.apply {
                contentDescription = context.getString(R.string.panel_back)
                setCompoundDrawablesWithIntrinsicBounds(collapseGlyph, null, null, null)
                setPadding(0, 0, 0, 0)
                collapseGlyph.tint(palette.keyLabelSecondary)
            },
            actionLp(backActionRow),
        )
        rightColumn.addView(
            createBackspaceButton().apply {
                setCompoundDrawablesWithIntrinsicBounds(backspaceGlyph, null, null, null)
                setPadding(0, 0, 0, 0)
                backspaceGlyph.tint(palette.keyLabelSecondary)
            },
            actionBoundaryLp(deleteActionBoundary),
        )
        rightColumn.addView(
            funcButton(context.getString(R.string.kbd_redo)) { onClear() }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setTypeface(null, Typeface.BOLD)
            },
            actionBoundaryLp(clearActionBoundary),
        )
        addView(rightColumn, LayoutParams(dp(Layouts.CANDIDATE_ACTION_WIDTH_DP), LayoutParams.MATCH_PARENT))
        returnFeedback = ImeKeyFeedback(returnButton(), Color.TRANSPARENT, palette.keyLabelSecondary)
        returnFeedback.bind { hapticEnabled }
        backspaceFeedback = ImeKeyFeedback(backspaceButton(), Color.TRANSPARENT, palette.keyLabelSecondary)
        backspaceTouch = ImeBackspaceTouch(
            backspaceButton(),
            backspaceFeedback,
            density,
            { hapticEnabled },
            { onBackspace() },
            { up -> if (up) onClear() },
        )
        clearFeedback = ImeKeyFeedback(clearButton(), Color.TRANSPARENT, palette.keyLabelSecondary)
        clearFeedback.bind { hapticEnabled }
    }

    override fun resetToDefault() {
        resetViewportToStart()
        readingFeedback.values.forEach(ImeKeyFeedback::reset)
        chipFeedback.values.forEach(ImeKeyFeedback::reset)
        returnFeedback.reset()
        backspaceTouch.cancel()
        clearFeedback.reset()
    }

    fun prepareForOpen() {
        resetViewportOnLayout = true
        resetViewportToStart()
    }

    private fun resetViewportToStart() {
        table.fling(0)
        readingScroll.scrollTo(0, 0)
        table.setSelectionFromTop(0, 0)
        gridScrollOffsetForTest = 0
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        readingScroll.applyPalette(p)
        table.applyPalette(p)
        rightColumn.setBackgroundColor(p.keyboardBg)
        for (i in 0 until rightColumn.childCount) (rightColumn.getChildAt(i) as? TextView)?.setTextColor(p.keyLabelSecondary)
        returnFeedback.update(Color.TRANSPARENT, p.keyLabelSecondary)
        backspaceFeedback.update(Color.TRANSPARENT, p.keyLabelSecondary)
        clearFeedback.update(Color.TRANSPARENT, p.keyLabelSecondary)
        backspaceGlyph.tint(p.keyLabelSecondary)
        collapseGlyph.tint(p.keyLabelSecondary)
        candidateAdapter.notifyDataSetChanged()
        for (i in readingPool.indices) {
            val color = readingColor(i == renderedSelected)
            readingPool[i].setTextColor(color)
            readingFeedback[readingPool[i]]?.update(Color.TRANSPARENT, color)
        }
        renderedReadings = null
    }

    private fun readingColor(on: Boolean): Int = if (on) palette.candidateFirst else palette.candidateText

    private fun actionLp(rowIndex: Int) =
        FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx, Gravity.TOP)
            .apply { topMargin = candidateRowTop(rowIndex) }

    private fun actionBoundaryLp(rowIndex: Int) =
        FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx, Gravity.TOP)
            .apply { topMargin = candidateBoundaryTop(rowIndex) }

    private fun tableFrame(): FrameLayout.LayoutParams = table.layoutParams as FrameLayout.LayoutParams

    private fun candidateRowStride(): Int = rowHeightPx + table.dividerHeight

    private fun candidateRowTop(rowIndex: Int): Int = tableFrame().topMargin + candidateRowStride() * rowIndex

    private fun candidateBoundaryCenter(rowIndex: Int): Int =
        candidateRowTop(rowIndex) + rowHeightPx + table.dividerHeight / 2

    private fun candidateBoundaryTop(rowIndex: Int): Int =
        candidateBoundaryCenter(rowIndex) - rowHeightPx / 2

    private fun candidateBoundaryFits(availableHeight: Int, rowIndex: Int): Boolean {
        val frame = tableFrame()
        val content = availableHeight - frame.topMargin - frame.bottomMargin
        return content >= candidateBoundaryTop(rowIndex) - frame.topMargin + rowHeightPx
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        if (mode != MeasureSpec.UNSPECIFIED) updateRightActionLayout(MeasureSpec.getSize(heightMeasureSpec))
        val incomingWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (incomingWidth > 0) updateSideColumns(incomingWidth)
        if (incomingWidth > 0 && incomingWidth != lastMeasuredWidth) {
            lastMeasuredWidth = incomingWidth
            sourceCandidates?.let {
                measuringWidthOverride = incomingWidth
                setCandidates(it, sourceCandidateProjection)
                measuringWidthOverride = 0
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (resetViewportOnLayout) resetViewportToStart()
        super.onLayout(changed, left, top, right, bottom)
        if (resetViewportOnLayout) {
            resetViewportOnLayout = false
            resetViewportToStart()
        }
    }

    private fun sideSpan(width: Int): Int = (width * Layouts.NINE_SIDE_FRACTION).roundToInt()
    private fun actionSpan(width: Int): Int = minOf(sideSpan(width), dp(Layouts.CANDIDATE_ACTION_WIDTH_DP))

    private fun updateSideColumns(width: Int) {
        val span = sideSpan(width)
        val actions = actionSpan(width)
        (readingScroll.layoutParams as LayoutParams).apply {
            this.width = (span - dp(6)).coerceAtLeast(1)
            leftMargin = dp(3)
            rightMargin = dp(3)
        }
        (rightColumn.layoutParams as LayoutParams).width = actions
        returnButton().setPadding((actions - collapseGlyph.intrinsicWidth) / 2, 0, 0, 0)
        backspaceButton().setPadding((actions - backspaceGlyph.intrinsicWidth) / 2, 0, 0, 0)
    }

    private fun updateRightActionLayout(availableHeight: Int) {
        val back = returnButton()
        val delete = backspaceButton()
        val clear = clearButton()
        if (candidateBoundaryFits(availableHeight, clearActionBoundary)) {
            clear.visibility = View.VISIBLE
            setActionFrame(back, rowHeightPx, Gravity.TOP, candidateRowTop(backActionRow))
            setActionFrame(delete, rowHeightPx, Gravity.TOP, candidateBoundaryTop(deleteActionBoundary))
            setActionFrame(clear, rowHeightPx, Gravity.TOP, candidateBoundaryTop(clearActionBoundary))
        } else if (availableHeight >= rowHeightPx * 3) {
            clear.visibility = View.VISIBLE
            setActionFrame(back, rowHeightPx, Gravity.TOP, 0)
            setActionFrame(delete, rowHeightPx, Gravity.TOP, (availableHeight - rowHeightPx) / 2)
            setActionFrame(clear, rowHeightPx, Gravity.TOP, availableHeight - rowHeightPx)
        } else {
            clear.visibility = View.GONE
            setActionFrame(back, rowHeightPx, Gravity.TOP, 0)
            setActionFrame(delete, rowHeightPx, Gravity.TOP, (availableHeight - rowHeightPx).coerceAtLeast(0))
        }
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
        setOnClickListener { onClick() }
    }

    private fun createBackspaceButton(): TextView = funcButton("") { onBackspace() }

    override fun onDetachedFromWindow() {
        readingFeedback.values.forEach(ImeKeyFeedback::reset)
        chipFeedback.values.forEach(ImeKeyFeedback::reset)
        returnFeedback.reset()
        backspaceTouch.cancel()
        clearFeedback.reset()
        super.onDetachedFromWindow()
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
            readingFeedback[tile]?.reset()
            val swappedUnderPress = tile.isPressed && tile.text != readings[i]
            tile.tag = if (swappedUnderPress) null else readings[i]
            if (tile.text != readings[i]) {
                tile.text = readings[i]
            }
            val target = spPx(ImeType.title)
            if (abs(tile.textSize - target) > 0.5f) tile.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
            tile.visibility = View.VISIBLE
        }
        for (i in readings.size until readingColumn.childCount) {
            val tile = readingColumn.getChildAt(i)
            tile.visibility = View.GONE
            (tile as? TextView)?.let { readingFeedback[it]?.reset() }
        }
        if (listChanged) {
            for (i in readings.indices) styleReading(i, on = i == selected, animate = false)
        } else {
            if (prevSelected in readings.indices && prevSelected != selected) styleReading(prevSelected, on = false, animate = true)
            if (selected in readings.indices) styleReading(selected, on = true, animate = true)
        }
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
            setOnClickListener(readingClick)
        }
        readingFeedback[tv] = ImeKeyFeedback(
            tv,
            Color.TRANSPARENT,
            palette.candidateText,
        ).also { it.bind { hapticEnabled } }
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
        readingFeedback[tile]?.update(Color.TRANSPARENT, target)
    }

    private fun capFor(len: Int, tableW: Int): Int {
        val natural = (spPx(ImeType.title) * len).toInt() + dp(8 + 8)
        val naturalCap = (tableW / natural).coerceAtLeast(1)
        return when (len) {
            1 -> minOf(5, naturalCap)
            2 -> minOf(4, naturalCap)
            else -> naturalCap
        }
    }

    private fun candidateRowStarts(candidates: List<String>, tableW: Int): List<Int> {
        val starts = ArrayList<Int>()
        var start = 0
        var count = 0
        var rowMaxLen = 0
        for (i in candidates.indices) {
            val len = GraphemeText.clusterCount(candidates[i])
            val newMax = maxOf(rowMaxLen, len)
            if (count + 1 > capFor(newMax, tableW)) {
                starts.add(start)
                start = i
                count = 1
                rowMaxLen = len
            } else {
                count++
                rowMaxLen = newMax
            }
        }
        if (count > 0) starts.add(start)
        return starts
    }

    private fun projectedCandidateIndices(
        candidates: List<String>,
        tableW: Int,
        policy: CandidateProjectionPolicy,
    ): List<Int> {
        val phraseIndices = ArrayList<Int>()
        val singleIndices = ArrayList<Int>()
        for (i in candidates.indices) {
            if (GraphemeText.clusterCount(candidates[i]) == 1) singleIndices.add(i) else phraseIndices.add(i)
        }
        val phrases = phraseIndices.map(candidates::get)
        val starts = candidateRowStarts(phrases, tableW)
        val phraseCount = if (starts.size > policy.maxPhraseRows) starts[policy.maxPhraseRows] else phrases.size
        return ArrayList<Int>(phraseCount + singleIndices.size).apply {
            addAll(phraseIndices.subList(0, phraseCount))
            addAll(singleIndices)
        }
    }

    fun setCandidates(candidates: List<String>, projection: CandidateProjectionPolicy? = null) {
        val configuredWidth = resources.configuration.screenWidthDp
            .takeIf { it > 0 }
            ?.let { (it * density).toInt() }
            ?: resources.displayMetrics.widthPixels
        val liveWidth = measuringWidthOverride.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: configuredWidth
        val tableW = (liveWidth - sideSpan(liveWidth) - actionSpan(liveWidth) - dp(4 + 4)).coerceAtLeast(dp(46))
        val sourceUnchanged = candidates == sourceCandidates
        val nextSourceIndices = projection?.let { projectedCandidateIndices(candidates, tableW, it) }
            ?: candidates.indices.toList()
        if (sourceUnchanged && nextSourceIndices == renderedSourceIndices && tableW == renderedCandidateWidth) {
            sourceCandidateProjection = projection
            return
        }
        val contentChanged = !sourceUnchanged || nextSourceIndices != renderedSourceIndices
        sourceCandidates = candidates.toList()
        sourceCandidateProjection = projection
        renderedSourceIndices = nextSourceIndices
        renderedCandidates = renderedSourceIndices.map(candidates::get)
        renderedCandidateWidth = tableW
        candidateRebuilds++
        chipWidths.clear()
        chipTextSizes.clear()
        val visible = renderedCandidates.orEmpty()
        repeat(visible.size) {
            chipWidths.add(0)
            chipTextSizes.add(ImeType.title)
        }
        rowStarts.clear()
        rowCounts.clear()
        rowStarts.addAll(candidateRowStarts(visible, tableW))
        for (r in rowStarts.indices) {
            val from = rowStarts[r]
            val to = if (r + 1 < rowStarts.size) rowStarts[r + 1] else visible.size
            val n = to - from
            rowCounts.add(n)
            val size = rowTextSize(visible, from, to, tableW / n)
            for (k in 0 until n) {
                val i = from + k
                chipWidths[i] = tableW * (k + 1) / n - tableW * k / n
                chipTextSizes[i] = size
            }
        }
        chipFeedback.values.forEach(ImeKeyFeedback::reset)
        candidateAdapter.notifyDataSetChanged()
        if (contentChanged) {
            table.setSelection(0)
            gridScrollOffsetForTest = 0
        }
    }

    private fun rowTextSize(candidates: List<String>, from: Int, to: Int, cellWidth: Int): Float {
        val base = ImeType.title
        val avail = (cellWidth - dp(8 + 8)).toFloat()
        if (avail <= 0f) return 10f
        measurePaint.textSize = spPx(base)
        var widest = 0f
        for (i in from until to) widest = maxOf(widest, measurePaint.measureText(candidates[i]))
        if (widest <= 0f) return base
        return (base * avail / widest).coerceIn(10f, base)
    }

    private fun newChip(): TextView {
        val chip = TextView(context).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
            setTextColor(palette.candidateText)
            isClickable = true
            setOnClickListener(chipClick)
        }
        chipFeedback[chip] = ImeKeyFeedback(
            chip,
            Color.TRANSPARENT,
            palette.candidateText,
        ).also { it.bind { hapticEnabled } }
        chipsAllocated++
        return chip
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

    private inner class CandidateAdapter : BaseAdapter() {
        override fun getCount(): Int = rowStarts.size

        override fun getItem(position: Int): Any = position

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? CandidateRow ?: CandidateRow(context, density)
            val start = rowStarts[position]
            val count = rowCounts[position]
            while (row.childCount < count) {
                row.addView(newChip(), LayoutParams(0, rowHeightPx))
            }
            row.columns = count
            row.separatorColor = palette.separator
            for (k in 0 until row.childCount) {
                val chip = row.getChildAt(k) as TextView
                if (k < count) {
                    val index = start + k
                    chip.visibility = View.VISIBLE
                    chip.text = renderedCandidates?.get(index).orEmpty()
                    chip.tag = renderedSourceIndices[index]
                    chip.setTextColor(palette.candidateText)
                    chipFeedback[chip]?.run {
                        reset()
                        update(Color.TRANSPARENT, palette.candidateText)
                    }
                    applyCell(chip, index)
                } else {
                    chipFeedback[chip]?.reset()
                    chip.visibility = View.GONE
                }
            }
            return row
        }
    }

    internal fun candidateRebuildsForTest(): Int = candidateRebuilds
    internal fun readingRebuildsForTest(): Int = readingRebuilds
    internal fun chipsAllocatedForTest(): Int = chipsAllocated
    internal fun needsPoolGrowth(candidateCount: Int, readingCount: Int): Boolean =
        (candidateCount > 0 && table.childCount == 0) || readingCount > readingPool.size
    internal fun candidatesWouldChange(
        candidates: List<String>,
        projection: CandidateProjectionPolicy?,
    ): Boolean {
        if (candidates != sourceCandidates) return true
        if (projection == sourceCandidateProjection) return false
        if (renderedCandidateWidth <= 0) return true
        val nextSourceIndices = projection?.let {
            projectedCandidateIndices(candidates, renderedCandidateWidth, it)
        } ?: candidates.indices.toList()
        return nextSourceIndices != renderedSourceIndices
    }
    internal fun setSelectionContentVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.INVISIBLE
        readingScroll.visibility = target
        gridScroll.visibility = target
    }
    internal fun readingsAllocatedForTest(): Int = readingsAllocated
    internal fun selectionContentVisibleForTest(): Boolean =
        readingScroll.visibility == View.VISIBLE && gridScroll.visibility == View.VISIBLE
    internal fun renderedCandidateTextsForTest(): List<String> = renderedCandidates.orEmpty()
    internal fun renderedSourceIndicesForTest(): List<Int> = renderedSourceIndices
    internal fun rowTextsForTest(): List<List<String>> {
        val out = ArrayList<List<String>>()
        val candidates = renderedCandidates.orEmpty()
        for (r in rowStarts.indices) {
            val from = rowStarts[r]
            out.add(candidates.subList(from, from + rowCounts[r]))
        }
        return out
    }
    internal fun rowColumnCountsForTest(): List<Int> = rowCounts.toList()
    internal fun chipTextSizeSpForTest(index: Int): Float = chipTextSizes[index]
    internal fun chipEllipsizeForTest(index: Int): TextUtils.TruncateAt? = TextUtils.TruncateAt.END
    internal fun chipCellWidthForTest(index: Int): Int = chipWidths[index]
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
    internal fun tableDividerHeightForTest(): Int = table.dividerHeight
    internal fun tableDividerForTest(): Drawable? = table.divider
    internal fun candidateRowHeightForTest(): Int = rowHeightPx
    internal fun candidateRowStrideForTest(): Int = candidateRowStride()
    internal fun visibleCandidateRowsForTest(): List<Rect> {
        val out = ArrayList<Rect>()
        for (offset in 0 until table.childCount) {
            val child = table.getChildAt(offset) ?: continue
            if (child.visibility != View.VISIBLE) continue
            out.add(Rect(0, 0, child.width, child.height).also { offsetDescendantRectToMyCoords(child, it) })
        }
        return out
    }
    internal fun actionBoundsForTest(index: Int): Rect {
        val child = rightColumn.getChildAt(index)
        return Rect(0, 0, child.width, child.height).also { offsetDescendantRectToMyCoords(child, it) }
    }
    internal fun rightColumnWidthForTest(): Int = rightColumn.layoutParams.width
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
        if (flat !in renderedCandidates.orEmpty().indices) return false
        onPick(renderedSourceIndices[flat])
        return true
    }
    internal fun tapReadingForTest(index: Int): Boolean =
        (readingColumn.getChildAt(index) as? TextView)?.takeIf { it.visibility == View.VISIBLE }?.performClick() ?: false
    internal fun firstChipForTest(): TextView? =
        (table.getChildAt(0) as? CandidateRow)?.getChildAt(0) as? TextView
    internal fun readingTileForTest(index: Int): TextView? = readingPool.getOrNull(index)
    internal fun firstChipBackgroundForTest(): Drawable? = firstChipForTest()?.background
    internal fun firstChipForegroundForTest(): Drawable? = firstChipForTest()?.foreground
    internal fun firstChipFeedbackLevelForTest(): Float? =
        firstChipForTest()?.let { chipFeedback[it]?.levelForTest() }
    internal fun readingFeedbackLevelForTest(index: Int): Float? =
        readingPool.getOrNull(index)?.let { readingFeedback[it]?.levelForTest() }
    internal fun activeReadingColorAnimatorsForTest(): Int =
        readingColorAnimators.values.count { it.isRunning }
    private fun returnButton(): TextView = rightColumn.getChildAt(0) as TextView
    private fun backspaceButton(): TextView = rightColumn.getChildAt(1) as TextView
    private fun clearButton(): TextView = rightColumn.getChildAt(2) as TextView
    internal fun returnButtonForTest(): TextView = returnButton()
    internal fun returnFeedbackLevelForTest(): Float = returnFeedback.levelForTest()
    internal fun backspaceButtonForTest(): TextView = backspaceButton()
    internal fun backspaceFeedbackLevelForTest(): Float = backspaceFeedback.levelForTest()
    internal fun clearButtonForTest(): TextView = clearButton()
    internal fun clearFeedbackLevelForTest(): Float = clearFeedback.levelForTest()
    internal fun collapseGlyphForTest(): Drawable = collapseGlyph
    internal fun backspaceGlyphForTest(): Drawable = backspaceGlyph
    internal fun gridScrollYForTest(): Int = gridScrollOffsetForTest
    internal fun firstVisibleCandidateRowForTest(): Int = table.firstVisiblePosition
    internal fun firstVisibleCandidateTopForTest(): Int? = table.getChildAt(0)?.top
    internal fun readingScrollYForTest(): Int = readingScroll.scrollY
    internal fun gridCanScrollForwardForTest(): Boolean =
        table.canScrollVertically(1) || rowStarts.size * rowHeightPx > table.height
    internal fun readingCanScrollForwardForTest(): Boolean = readingScroll.canScrollVertically(1)
    internal fun scrollForTest(gridY: Int, readingY: Int = 0) {
        val stride = candidateRowStride()
        val bounded = gridY.coerceAtLeast(0)
        table.setSelectionFromTop((bounded / stride).coerceAtMost((rowStarts.size - 1).coerceAtLeast(0)), -(bounded % stride))
        gridScrollOffsetForTest = bounded
        readingScroll.scrollTo(0, readingY)
    }
    internal fun readingTextColorForTest(index: Int): Int? =
        (readingColumn.getChildAt(index) as? TextView)?.currentTextColor
    internal fun selectedReadingBackgroundForTest(index: Int): Drawable? =
        (readingColumn.getChildAt(index) as? TextView)?.background

    private class RailScrollView(context: Context, private val density: Float) : ScrollView(context) {

    override fun shouldDelayChildPressedState(): Boolean = false

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

    private class CandidateRow(context: Context, density: Float) : LinearLayout(context) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
        var columns = 0
        var separatorColor: Int
            get() = linePaint.color
            set(value) {
                linePaint.color = value
                invalidate()
            }

        init {
            orientation = HORIZONTAL
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            for (i in 0 until (columns - 1).coerceAtLeast(0)) {
                val child = getChildAt(i)
                canvas.drawLine(child.right.toFloat(), 0f, child.right.toFloat(), height.toFloat(), linePaint)
            }
        }
    }

    private class TableColumn(context: Context, private val density: Float) : ListView(context) {
        private val radius = ImeShapes.cardRadiusDp * density
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val outlineRect = RectF()
        private var separatorColor = 0

        init {
            isVerticalScrollBarEnabled = false
            dividerHeight = density.toInt().coerceAtLeast(1)
            selector = 0x00000000.toDrawable()
        }

        fun applyPalette(p: ImePalette) {
            separatorColor = p.separator
            divider = p.separator.toDrawable()
            dividerHeight = density.toInt().coerceAtLeast(1)
            outlinePaint.color = p.separator
            invalidate()
        }

        fun separatorColorForTest(): Int = separatorColor
        fun cornerRadiusForTest(): Float = radius

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (childCount == 0) return
            val w = width.toFloat()
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
