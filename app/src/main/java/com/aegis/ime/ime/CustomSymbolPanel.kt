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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.layout.SymbolCatalog

class CustomSymbolPanel(context: Context) : LinearLayout(context), ResettablePanel {

    var current: () -> List<String> = { emptyList() }
    var onAdd: (String) -> Unit = {}
    var onRemove: (String) -> Unit = {}
    var onBack: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private var colors = ImePalette.STATIC_LIGHT
    private val addedRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val paletteRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val contentColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val contentScroll = ScrollView(context).apply { addView(contentColumn) }
    private val headerBar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }
    private val titleText = PanelBackButton.control(
        context,
        context.getString(R.string.csp_punctuation_title),
        colors.keyLabel,
    ) { onBack() }
    private val sectionLabels = mutableListOf<TextView>()
    private val addedLabel = sectionLabel(context.getString(R.string.csp_section_added))
    private val paletteLabel = sectionLabel(context.getString(R.string.csp_section_all_punctuation))
    private val addedEmpty = TextView(context).apply {
        text = context.getString(R.string.csp_added_empty)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.sectionTitle)
        setTextColor(colors.keyLabelSecondary)
        setPadding(dp(EDGE_DP), 0, dp(EDGE_DP), 0)
        gravity = Gravity.CENTER_VERTICAL
    }
    private var measuringWidthOverride = 0
    private var lastFlowWidth = -1
    private val headerHeight = dp(PanelBackButton.HIT_DP)

    var backTitle: String = context.getString(R.string.csp_punctuation_title)
        set(v) { field = v; titleText.text = v }

    var paletteTitle: String = context.getString(R.string.csp_section_all_punctuation)
        set(v) { field = v; paletteLabel.text = v }

    var addPalette: List<String> = SymbolCatalog.categories.flatMap { it.symbols }.distinct()
        set(v) { field = v; refresh() }

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.keyboardBg)
        headerBar.setBackgroundColor(colors.keyboardBg)
        headerBar.addView(titleText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(headerBar, LayoutParams(LayoutParams.MATCH_PARENT, headerHeight))

        contentColumn.addView(addedLabel, columnParams(dp(GAP_DP)))
        contentColumn.addView(addedRows, columnParams(dp(GAP_DP)))
        contentColumn.addView(
            addedEmpty,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(CHIP_DP)).apply { topMargin = dp(GAP_DP) },
        )
        contentColumn.addView(paletteLabel, columnParams(dp(SECTION_GAP_DP)))
        contentColumn.addView(paletteRows, columnParams(dp(GAP_DP)))
        contentColumn.setPadding(0, 0, 0, dp(GAP_DP))
        addView(contentScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun resetToDefault() {
        Motion.reset(contentColumn)
        contentScroll.scrollTo(0, 0)
        contentScroll.fling(0)
    }

    fun applyPalette(p: ImePalette) {
        colors = p
        setBackgroundColor(p.keyboardBg)
        headerBar.setBackgroundColor(p.keyboardBg)
        titleText.applyTint(p.keyLabel)
        addedEmpty.setTextColor(p.keyLabelSecondary)
        sectionLabels.forEach { it.setTextColor(p.keyLabelSecondary) }
        rebuildFlows()
    }

    private fun columnParams(topMargin: Int) =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { this.topMargin = topMargin }

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(colors.keyLabelSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.sectionTitle)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(EDGE_DP), 0, dp(EDGE_DP), 0)
        sectionLabels.add(this)
    }

    fun refresh() {
        rebuildFlows()
    }

    private fun rebuildFlows() {
        val added = current()
        fillFlow(addedRows, added, dp(ADDED_CELL_DP)) { sym -> addedChip(sym) }
        addedRows.visibility = if (added.isEmpty()) View.GONE else View.VISIBLE
        addedEmpty.visibility = if (added.isEmpty()) View.VISIBLE else View.GONE
        fillFlow(paletteRows, addPalette.filter { it !in added }, dp(PALETTE_CELL_DP)) { sym -> paletteChip(sym) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val incomingWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (incomingWidth > 0 && incomingWidth != lastFlowWidth) {
            measuringWidthOverride = incomingWidth
            lastFlowWidth = incomingWidth
            rebuildFlows()
            measuringWidthOverride = 0
        }
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val available = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
            (headerBar.layoutParams as LayoutParams).height = headerHeight.coerceAtMost(available)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun fillFlow(container: LinearLayout, items: List<String>, minCellW: Int, make: (String) -> View) {
        container.removeAllViews()
        val configuredWidth = resources.configuration.screenWidthDp
            .takeIf { it > 0 }
            ?.let { (it * density).toInt() }
            ?: resources.displayMetrics.widthPixels
        val liveWidth = measuringWidthOverride.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: configuredWidth
        val maxRowW = (liveWidth - dp(EDGE_DP) * 2).coerceAtLeast(minCellW)
        val cellH = dp(CHIP_DP)
        var row = newRow(); var rowW = 0; var rows = 0
        for (sym in items) {
            val cell = make(sym)
            val cellW = cellWidth(cell, minCellW, cellH, maxRowW)
            if (rowW + cellW > maxRowW && row.childCount > 0) {
                addRow(container, row, rows); rows++; row = newRow(); rowW = 0
            }
            row.addView(cell, LayoutParams(cellW, cellH).apply { marginEnd = dp(ROW_GAP_DP) })
            rowW += cellW + dp(ROW_GAP_DP)
        }
        if (row.childCount > 0) addRow(container, row, rows)
    }

    private fun cellWidth(cell: View, minCellW: Int, cellH: Int, maxRowW: Int): Int {
        cell.measure(
            MeasureSpec.makeMeasureSpec(maxRowW, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY),
        )
        return cell.measuredWidth.coerceIn(minCellW, maxRowW)
    }

    private fun addRow(container: LinearLayout, row: LinearLayout, index: Int) {
        container.addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = dp(ROW_GAP_DP)
            },
        )
    }

    internal fun contentCanScrollForwardForTest(): Boolean = contentScroll.canScrollVertically(1)
    internal fun contentScrollForTest(y: Int) {

        val viewport = (contentScroll.height - contentScroll.paddingTop - contentScroll.paddingBottom).coerceAtLeast(0)
        val maxScroll = (contentColumn.height - viewport).coerceAtLeast(0)
        contentScroll.scrollTo(0, y.coerceIn(0, maxScroll))
    }
    internal fun contentScrollYForTest(): Int = contentScroll.scrollY
    internal fun contentViewportForTest(): View = contentScroll
    private fun addedChips(): List<View> = (0 until addedRows.childCount).flatMap { rowIndex ->
        val row = addedRows.getChildAt(rowIndex) as? ViewGroup ?: return@flatMap emptyList()
        (0 until row.childCount).map { row.getChildAt(it) }
    }
    internal fun addedChipForTest(symbol: String): View? =
        addedChips().firstOrNull { ((it as? ViewGroup)?.getChildAt(0) as? TextView)?.text?.toString() == symbol }
    internal fun addedRemoveMarkForTest(symbol: String): View? =
        (addedChipForTest(symbol) as? ViewGroup)?.getChildAt(1)
    internal fun addedEmptyHintForTest(): TextView = addedEmpty
    internal fun addedRowsForTest(): View = addedRows
    internal fun paletteRowsForTest(): View = paletteRows
    internal fun addedSectionLabelForTest(): TextView = addedLabel
    internal fun paletteSectionLabelForTest(): TextView = paletteLabel
    internal fun titleForTest(): TextView = titleText
    internal fun backButtonForTest(): View = titleText
    internal fun backIconForTest(): android.graphics.drawable.Drawable = titleText.glyphForTest()
    internal fun paletteChipForTest(symbol: String): View? {
        for (r in 0 until paletteRows.childCount) {
            val row = paletteRows.getChildAt(r) as? ViewGroup ?: continue
            for (i in 0 until row.childCount) {
                val chip = row.getChildAt(i) as? TextView ?: continue
                if (chip.text.toString() == symbol) return chip
            }
        }
        return null
    }

    private fun newRow(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(EDGE_DP), 0, dp(EDGE_DP), 0)
    }

    private fun chipBackground() = GradientDrawable().apply {
        setColor(this@CustomSymbolPanel.colors.keySurface)
        cornerRadius = ImeShapes.keyRadiusDp * density
    }

    private fun paletteChip(symbol: String): View = TextView(context).apply {
        text = symbol
        gravity = Gravity.CENTER
        isSingleLine = true
        setPadding(dp(GAP_DP), 0, dp(GAP_DP), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
        setTextColor(colors.keyLabel)
        background = chipBackground()
        isClickable = true
        Motion.applyTapFeedback(this, colors.keyLabel)
        setOnClickListener { onAdd(symbol) }
    }

    private fun addedChip(symbol: String): View {
        val label = TextView(context).apply {
            text = symbol
            gravity = Gravity.CENTER
            isSingleLine = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
            setTextColor(colors.keyLabel)
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(GAP_DP), 0, dp(GAP_DP), 0)
            background = chipBackground()
            isClickable = true
            contentDescription = context.getString(R.string.csp_remove_symbol, symbol)
            Motion.applyTapFeedback(this, colors.keyLabel)
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(
                removeMark(),
                LayoutParams(dp(REMOVE_MARK_DP), dp(REMOVE_MARK_DP)).apply { marginStart = dp(GAP_DP) },
            )
            setOnClickListener { onRemove(symbol) }
        }
    }

    private fun removeMark(): View = object : View(context) {
        private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2f * density
            color = colors.keyLabelSecondary
        }

        override fun onDraw(canvas: Canvas) {
            Glyphs.drawClose(canvas, markPaint, width / 2f, height / 2f, width / 2f)
        }
    }

    private companion object {
        const val EDGE_DP = 8
        const val GAP_DP = 8
        const val ROW_GAP_DP = 4
        const val SECTION_GAP_DP = 16
        const val CHIP_DP = 48
        const val PALETTE_CELL_DP = 56
        const val ADDED_CELL_DP = 72
        const val REMOVE_MARK_DP = 16
    }
}
