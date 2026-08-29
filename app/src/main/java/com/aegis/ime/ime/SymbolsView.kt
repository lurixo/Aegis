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
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.layout.SymbolCatalog

class SymbolsView(context: Context) :
    LinearLayout(context), ResettablePanel, CoversToolbar, KeyHapticsAware, BackspaceBubbleSource {

    var onSymbol: (String, String?) -> Unit = { _, _ -> }
    var onClearRecents: () -> Unit = {}
    var onDeleteRecent: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onBackspaceSwipe: (Boolean) -> Unit = {}

    var backspaceSwipeAvailable: (Boolean) -> Boolean
        get() = backspaceTouch.canSwipe
        set(value) { backspaceTouch.canSwipe = value }
    var onBack: () -> Unit = {}
    var recentProvider: () -> List<String> = { emptyList() }
    var recentOriginOf: (String) -> String? = { null }
    override var hapticEnabled = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val surfaceMetrics = ImePanelSurfaceMetrics.resolve(density, resources.displayMetrics.density * resources.configuration.fontScale)
    private var cellHeightPx = surfaceMetrics.gridCellHeightPx

    private val titles: List<String> =
        listOf(context.getString(SymbolCatalog.RECENT_TITLE_RES)) + SymbolCatalog.categories.map { context.getString(it.titleRes) }
    private var selected = 0
    private var locked = false
    private var showingUrlCompletions = false
    private var measuringWidthOverride = 0
    private var lastFlowWidth = -1

    private var palette = ImePalette.STATIC_LIGHT
    private val rail = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val railScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(rail)
    }
    private val grid = ImePanelTableGrid(context, density).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        columnCount = COLUMNS
    }
    private var gridCellWidthPx = surfaceMetrics.minimumGridCellWidthPx
    private val netBar = LinearLayout(context).apply { orientation = VERTICAL; visibility = View.GONE }
    private val gridHolder = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(netBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val gridScroll = ImePanelTableViewport(context, density).apply {
        addView(gridHolder)
        isFillViewport = true
    }
    private var gridRow: LinearLayout? = null
    private val clearDialog = PanelConfirmationOverlay(context)
    private val backGlyph = IconDrawable(density, 0.41f) { c, p, x, y, s -> Glyphs.drawBack(c, p, x, y, s) }
    private val backBtn = barButton("") { onBack() }
    private val backSlot = FrameLayout(context)
    private val lockBtn = barButton("") { toggleLock() }
    private val lockSlot = FrameLayout(context)
    private val lockGlyph = LockDrawable(density)
    private val clearGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y - s * 0.06f, s) }
    private val clearBtn = barButton("") { showClearConfirmation() }
    private val clearSlot = FrameLayout(context)
    private val backspaceGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }
    private val backspaceBtn = barButton("") { onBackspace() }
    private val backspaceSlot = FrameLayout(context)
    private val actionSlots = listOf(backSlot, clearSlot, lockSlot, backspaceSlot)
    private val actionColumnView = actionColumn()
    private val backFeedback = ImeKeyFeedback(backBtn, Color.TRANSPARENT, palette.keyLabelSecondary, faceInsetDp = 0f)
    private val lockFeedback = ImeKeyFeedback(lockBtn, Color.TRANSPARENT, palette.keyLabelSecondary, faceInsetDp = 0f)
    private val clearFeedback = ImeKeyFeedback(clearBtn, Color.TRANSPARENT, palette.keyLabelSecondary, faceInsetDp = 0f)
    private val backspaceFeedback = ImeKeyFeedback(backspaceBtn, Color.TRANSPARENT, palette.keyLabelSecondary, faceInsetDp = 0f)
    private val backspaceTouch = ImeBackspaceTouch(
        backspaceBtn,
        backspaceFeedback,
        density,
        { hapticEnabled },
        { onBackspace() },
        { onBackspaceSwipe(it) },
        { backspaceBubbleObserver?.run() },
    )
    private var backspaceBubbleObserver: Runnable? = null

    override fun bindBackspaceBubbleObserver(observer: Runnable) {
        backspaceBubbleObserver = observer
    }

    override fun backspaceBubbleDirectionUp(): Boolean? = backspaceTouch.bubbleDirectionUp()

    override fun backspaceBubbleArmed(): Boolean = backspaceTouch.bubbleArmed()

    override fun backspaceBubbleAnchor(): View = backspaceBtn
    private val tileFeedback = HashMap<FrameLayout, ImeKeyFeedback>()
    private val tileSpans = HashMap<FrameLayout, Int>()
    private val netFeedback = HashMap<View, ImeKeyFeedback>()
    private val railFeedback = HashMap<TextView, ImeKeyFeedback>()

    private val tilePool = ArrayList<FrameLayout>()
    private var emptySpanView: TextView? = null
    private val colorAnimators = HashMap<TextView, ValueAnimator>()
    private val glyphInk = Rect()
    private val glyphMetrics = Paint.FontMetrics()
    private val badgeClearancePx = dp(BADGE_CLEARANCE_DP).toFloat()
    private val symbolClick = View.OnClickListener { v ->
        val s = ((v as FrameLayout).getChildAt(0) as TextView).text.toString()
        onSymbol(s, originForCurrent(s)); if (!locked) onBack()
    }
    private val symbolLongClick = View.OnLongClickListener { view ->
        if (selected != 0) {
            false
        } else {
            val symbol = when (view) {
                is FrameLayout -> (view.getChildAt(0) as TextView).text.toString()
                is TextView -> view.text.toString()
                else -> ""
            }
            if (symbol.isEmpty()) false else {
                showDeleteConfirmation(symbol)
                true
            }
        }
    }

    internal companion object {
        const val COLUMNS = 4
        const val ROWS = 4
        const val CATEGORY_PADDING_DP = 12
        const val CATEGORY_MIN_WIDTH_DP = 48
        const val MIN_KEY_TARGET_DP = ImePanelSurfaceMetrics.MINIMUM_GRID_CELL_WIDTH_DP
        const val WIDE_GLYPH_SCALE = 0.82f
        const val BADGE_CLEARANCE_DP = 6

        private val CELL_PLACEMENT: Map<String, Pair<Float, Float>> = mapOf(
            "，" to (-0.20f to 0.24f),
            "。" to (-0.20f to 0.24f),
            "、" to (-0.20f to 0.24f),
            "「" to (0.20f to -0.22f),
            "『" to (0.20f to -0.22f),
            "」" to (-0.20f to 0.22f),
            "』" to (-0.20f to 0.22f),
            "（" to (0.18f to 0.0f),
            "）" to (-0.18f to 0.0f),
            "《" to (0.16f to 0.0f),
            "》" to (-0.16f to 0.0f),
            "〈" to (0.16f to 0.0f),
            "〉" to (-0.16f to 0.0f),
            "【" to (0.16f to 0.0f),
            "】" to (-0.16f to 0.0f),
            "〖" to (0.16f to 0.0f),
            "〗" to (-0.16f to 0.0f),
            "〔" to (0.16f to 0.0f),
            "〕" to (-0.16f to 0.0f),
            "“" to (0.16f to -0.20f),
            "”" to (-0.16f to -0.20f),
            "‘" to (0.16f to -0.20f),
            "’" to (-0.16f to -0.20f),
        )

        fun wideMetricGlyph(ch: Char): Boolean {
            val c = ch.code
            return c in 0x3200..0x33FF ||
                c == 0x2103 || c == 0x2109 ||
                c == 0x2102 || c == 0x210D || c == 0x2115 || c == 0x2119 || c == 0x211A || c == 0x211D || c == 0x2124
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg)
        backBtn.contentDescription = context.getString(R.string.panel_back)
        backBtn.setCompoundDrawablesWithIntrinsicBounds(backGlyph, null, null, null)
        backGlyph.tint(palette.keyLabelSecondary)
        lockBtn.contentDescription = context.getString(R.string.panel_lock)
        lockBtn.setCompoundDrawablesWithIntrinsicBounds(lockGlyph, null, null, null)
        clearBtn.contentDescription = context.getString(R.string.symbols_clear_recent)
        clearBtn.setCompoundDrawablesWithIntrinsicBounds(clearGlyph, null, null, null)
        clearGlyph.tint(palette.keyLabelSecondary)
        backspaceBtn.contentDescription = context.getString(R.string.edit_delete)
        backspaceBtn.setCompoundDrawablesWithIntrinsicBounds(backspaceGlyph, null, null, null)
        backspaceGlyph.tint(palette.keyLabelSecondary)
        backFeedback.bind { hapticEnabled }
        lockFeedback.bind { hapticEnabled }
        clearFeedback.bind { hapticEnabled }

        for ((i, t) in titles.withIndex()) rail.addView(railTab(i, t))

        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(gridScroll, LayoutParams(gridWidthPx(), LayoutParams.MATCH_PARENT))
            addView(actionColumnView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        gridRow = content
        railScroll.setBackgroundColor(palette.keyboardBg)
        val panelColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, gridAreaHeightPx()))
            addView(railScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val panelFrame = FrameLayout(context).apply {
            addView(panelColumn, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(clearDialog, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addView(panelFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        updateLockFace()
    }

    fun refresh() = showCategory(selected)

    fun resetLock() { locked = false; updateLockFace() }

    override fun resetToDefault() {
        resetLock()
        Motion.reset(lockBtn)
        clearDialog.dismissImmediately()
        Motion.reset(gridScroll)
        showCategory(0, animate = false)
        gridScroll.scrollTo(0, 0)
        gridScroll.fling(0)
        railScroll.scrollTo(0, 0)
        railScroll.fling(0)
        backspaceTouch.cancel()
        backFeedback.reset()
        lockFeedback.reset()
        clearFeedback.reset()
        for (feedback in railFeedback.values) feedback.reset()
        for (feedback in tileFeedback.values) feedback.reset()
        for (feedback in netFeedback.values) feedback.reset()
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        railScroll.setBackgroundColor(p.keyboardBg)
        actionColumnView.setBackgroundColor(p.keyboardBg)
        grid.separatorColor = p.separator
        gridScroll.applyOutlineColor(p.separator)
        for (button in listOf(backBtn, clearBtn, backspaceBtn)) button.setTextColor(p.keyLabelSecondary)
        backFeedback.update(Color.TRANSPARENT, p.keyLabelSecondary)
        clearFeedback.update(Color.TRANSPARENT, p.keyLabelSecondary)
        backspaceFeedback.update(Color.TRANSPARENT, p.keyLabelSecondary)
        backGlyph.tint(p.keyLabelSecondary)
        clearGlyph.tint(p.keyLabelSecondary)
        backspaceGlyph.tint(p.keyLabelSecondary)
        for (tile in tilePool) {
            tileFeedback[tile]?.update(Color.TRANSPARENT, p.keyLabel)
            (tile.getChildAt(0) as TextView).setTextColor(p.keyLabel)
            (tile.getChildAt(1) as TextView).setTextColor(p.keyLabelSecondary)
        }
        for ((view, feedback) in netFeedback) {
            (view as? TextView)?.setTextColor(p.keyLabel)
            feedback.update(Color.TRANSPARENT, p.keyLabel)
        }
        emptySpanView?.setTextColor(p.keyHint)
        updateLockFace()
        styleRail(-1)
    }

    private fun showCategory(index: Int, animate: Boolean = true) {
        val tabChanged = index != selected
        val prev = selected
        selected = index
        styleRail(if (tabChanged) prev else -1)
        val swap = {
            bindGrid(selected)
            if (tabChanged) {
                gridScroll.scrollTo(0, 0)
                gridScroll.fling(0)
            }
        }
        if (animate && tabChanged && gridScroll.isShown) Motion.coverThrough(gridScroll, palette.keyboardBg, swap)
        else swap()
    }

    private fun styleRail(crossfadeFrom: Int) {
        for (i in 0 until rail.childCount) {
            val tab = rail.getChildAt(i) as TextView
            val on = i == selected
            tab.isSelected = on
            val color = if (on) palette.candidateFirst else palette.keyLabelSecondary
            if (crossfadeFrom >= 0 && (i == selected || i == crossfadeFrom)) crossfadeTabColor(tab, color) else tab.setTextColor(color)
            railFeedback[tab]?.update(if (on) palette.keySurface else Color.TRANSPARENT, color)
        }
    }

    private fun bindGrid(index: Int) {
        grid.removeAllViews()
        netBar.removeAllViews()
        netFeedback.clear()
        val symbols = symbolsFor(index)
        if (symbols.isEmpty()) { netBar.visibility = View.GONE; grid.addView(obtainEmptySpan()); return }
        val isNet = index != 0 && SymbolCatalog.categories.getOrNull(index - 1)?.id == "net"
        if (isNet) {
            netBar.visibility = View.GONE
            showingUrlCompletions = false
            val (wide, single) = symbols.partition { it.length > 1 }
            for ((slot, s) in (wide + single).withIndex()) {
                val tile = obtainTile(slot)
                setTileSpan(tile, if (s.length > 1) 2 else 1)
                bindTile(tile, s, badge = null)
                grid.addView(tile)
            }
            return
        }
        val completions = symbols.filter { it.length > 1 }
        val urlCompletions = if (completions.isNotEmpty() && completions.any { isUrlLike(it) })
            completions.filter { isUrlLike(it) } else emptyList()
        showingUrlCompletions = urlCompletions.isNotEmpty()
        if (showingUrlCompletions) {
            netBar.visibility = View.VISIBLE
            addCompletionChips(urlCompletions)
        } else {
            netBar.visibility = View.GONE
        }
        var slot = 0
        for (s in symbols) if (s !in urlCompletions) {
            val tile = obtainTile(slot); slot++
            setTileSpan(tile, 1)
            bindTile(tile, s, badge = if (index == 0) badgeFor(s) else null)
            grid.addView(tile)
        }
    }

    private fun setTileSpan(tile: FrameLayout, span: Int) {
        tileSpans[tile] = span
        applyTileSpan(tile)
    }

    private fun applyTileSpan(tile: FrameLayout) {
        val span = (tileSpans[tile] ?: 1).coerceIn(1, grid.columnCount)
        val lp = tile.layoutParams as GridLayout.LayoutParams
        lp.width = surfaceMetrics.outerWidth(gridCellWidthPx, span)
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span)
        lp.setGravity(Gravity.FILL)
        tile.layoutParams = lp
    }

    private fun crossfadeTabColor(tab: TextView, color: Int) {
        colorAnimators.remove(tab)?.cancel()
        Motion.crossfadeColor(tab, tab.currentTextColor, color) { tab.setTextColor(it) }?.let { colorAnimators[tab] = it }
    }

    private fun addCompletionChips(completions: List<String>) {
        val screenWidth = resources.displayMetrics.widthPixels
        val maxRowW = screenWidth - surfaceMetrics.actionWidthPx(screenWidth, COLUMNS) - dp(16)
        val configuredWidth = resources.configuration.screenWidthDp
            .takeIf { it > 0 }
            ?.let { (it * density).toInt() }
            ?: resources.displayMetrics.widthPixels

        val liveWidth = measuringWidthOverride.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: configuredWidth
        val liveMaxRowW = minOf(
            maxRowW,
            (liveWidth - surfaceMetrics.actionWidthPx(liveWidth, COLUMNS) - dp(16)).coerceAtLeast(dp(44)),
        )
        val gap = dp(8)
        var row = netRow()
        var rowW = 0
        for (c in completions) {
            val chip = netChip(c)
            val w = measureW(chip) + gap
            if (rowW + w > liveMaxRowW && row.childCount > 0) { netBar.addView(row); row = netRow(); rowW = 0 }
            row.addView(
                chip,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = gap
                    topMargin = surfaceMetrics.gridTopPaddingPx
                },
            )
            rowW += w
        }
        if (row.childCount > 0) netBar.addView(row)
    }

    private fun gridWidthPx(): Int = grid.columnCount * gridCellWidthPx


    private fun fitActionIcons(boxWidthPx: Int) {
        if (boxWidthPx <= 0) return
        if (lockGlyph.boxWidthPx != boxWidthPx) {
            lockGlyph.boxWidthPx = boxWidthPx
            lockBtn.setCompoundDrawablesWithIntrinsicBounds(lockGlyph, null, null, null)
        }
        for ((button, glyph) in listOf(backBtn to backGlyph, clearBtn to clearGlyph, backspaceBtn to backspaceGlyph)) {
            if (glyph.boxWidthPx == boxWidthPx) continue
            glyph.boxWidthPx = boxWidthPx
            button.setCompoundDrawablesWithIntrinsicBounds(glyph, null, null, null)
        }
    }

    private fun gridAreaHeightPx(): Int = ROWS * cellHeightPx

    private fun updateRowHeight(px: Int) {
        val next = px.coerceAtLeast(1)
        if (next == cellHeightPx) return
        cellHeightPx = next
        for (tile in tilePool) {
            tile.minimumHeight = next
            tile.layoutParams?.let { if (it.height != next) { it.height = next; tile.layoutParams = it } }
        }
    }

    private fun applyPanelBands() {
        gridScroll.layoutParams?.let { lp ->
            val want = gridWidthPx()
            if (lp.width != want) { lp.width = want; gridScroll.layoutParams = lp }
        }
        gridRow?.let { row ->
            row.layoutParams?.let { lp ->
                val want = gridAreaHeightPx()
                if (lp.height != want) { lp.height = want; row.layoutParams = lp }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val incomingWidth = MeasureSpec.getSize(widthMeasureSpec)
        val incomingHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (incomingWidth > 0) {
            updateGridMetrics(surfaceMetrics.fitGrid(incomingWidth, COLUMNS))
            fitActionIcons(incomingWidth - gridWidthPx())
        }
        if (incomingHeight > 0) {
            updateRowHeight(incomingHeight / (ROWS + 1))
        }
        applyPanelBands()
        if (incomingWidth > 0 && incomingWidth != lastFlowWidth) {
            lastFlowWidth = incomingWidth
            if (showingUrlCompletions) {
                measuringWidthOverride = incomingWidth
                showCategory(selected)
                measuringWidthOverride = 0
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updateGridMetrics(metrics: ImePanelGridMetrics) {
        if (grid.columnCount == metrics.columns && gridCellWidthPx == metrics.cellWidthPx) return
        val children = (0 until grid.childCount).map { grid.getChildAt(it) }
        grid.removeAllViews()
        grid.columnCount = metrics.columns
        gridCellWidthPx = metrics.cellWidthPx
        for (tile in tilePool) {
            val params = tile.layoutParams as GridLayout.LayoutParams
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            tile.layoutParams = params
            applyTileSpan(tile)
        }
        emptySpanView?.let { empty ->
            val params = empty.layoutParams as GridLayout.LayoutParams
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            params.columnSpec = GridLayout.spec(0, metrics.columns, 1f)
            params.width = 0
            empty.layoutParams = params
        }
        for (child in children) grid.addView(child, child.layoutParams)
    }

    private fun netRow(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(surfaceMetrics.gridSidePaddingPx, 0, surfaceMetrics.gridSidePaddingPx, 0)
    }

    private fun netChip(symbol: String): View = TextView(context).apply {
        text = symbol
        maxLines = 1
        ellipsize = null
        minimumWidth = surfaceMetrics.minimumGridCellWidthPx
        minimumHeight = cellHeightPx
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
        setTextColor(palette.keyLabel)
        val ph = dp(14); setPadding(ph, 0, ph, 0)
        isClickable = true
        isLongClickable = true
        setOnClickListener { onSymbol(symbol, originForCurrent(symbol)); if (!locked) onBack() }
        setOnLongClickListener(symbolLongClick)
        contentDescription = symbol
        ImeKeyFeedback(
            this,
            Color.TRANSPARENT,
            palette.keyLabel,
            faceInsetPxOverride = surfaceMetrics.faceInsetPx.toFloat(),
        ).also {
            it.bind { hapticEnabled }
            netFeedback[this] = it
        }
    }

    private fun measureW(v: View): Int {
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        v.measure(unspec, unspec)
        return v.measuredWidth
    }

    private fun isUrlLike(s: String): Boolean = s.any { it == '/' || it == ':' || it == '.' }

    private fun symbolsFor(index: Int): List<String> =
        if (index == 0) recentProvider() else SymbolCatalog.categories[index - 1].symbols

    private fun originForCurrent(symbol: String): String? =
        if (selected == 0) recentOriginOf(symbol) else SymbolCatalog.categories.getOrNull(selected - 1)?.id

    private fun badgeFor(symbol: String): String? =
        (recentOriginOf(symbol) ?: SymbolCatalog.categoryIdOf(symbol))
            ?.let { SymbolCatalog.titleResOf(it) }
            ?.let { context.getString(it) }
            ?.let { title ->
                if (title.isEmpty() || Character.isIdeographic(title[0].code)) title.take(1) else title.take(2)
            }

    private fun railTab(index: Int, title: String): TextView = TextView(context).apply {
        text = title
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(CATEGORY_PADDING_DP), 0, dp(CATEGORY_PADDING_DP), 0)
        minimumWidth = dp(CATEGORY_MIN_WIDTH_DP)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
        isClickable = true
        val on = index == selected
        isSelected = on
        railFeedback[this] = ImeKeyFeedback(
            this,
            if (on) palette.keySurface else Color.TRANSPARENT,
            if (on) palette.candidateFirst else palette.keyLabelSecondary,
            faceInsetDp = 0f,
            radiusDp = ImeShapes.keyRadiusDp,
        ).also { it.bind { hapticEnabled } }
        setOnClickListener { showCategory(index) }
    }

    private fun obtainTile(index: Int): FrameLayout {
        if (index < tilePool.size) return tilePool[index]
        val glyph = TextView(context).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            setTextColor(palette.keyLabel)
        }
        val badge = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.caption)
            setTextColor(palette.keyLabelSecondary)
            setPadding(0, 0, dp(4), dp(2))
            visibility = View.GONE
        }
        val tile = FrameLayout(context).apply {
            minimumHeight = cellHeightPx
            isClickable = true
            isLongClickable = true
            setOnClickListener(symbolClick)
            setOnLongClickListener(symbolLongClick)
            layoutParams = GridLayout.LayoutParams().apply {
                width = gridCellWidthPx
                height = cellHeightPx
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
                setGravity(Gravity.FILL)
                setMargins(0, 0, 0, 0)
            }
            addView(
                glyph,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER),
            )
            addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        }
        ImeKeyFeedback(
            tile,
            Color.TRANSPARENT,
            palette.keyLabel,
            faceInsetPxOverride = 0f,
            faceRadiusDp = 0f,
        ).also {
            it.bind { hapticEnabled }
            tileFeedback[tile] = it
        }
        tilePool.add(tile)
        return tile
    }

    private fun bindTile(tile: FrameLayout, symbol: String, badge: String?) {
        tile.contentDescription = symbol
        val glyph = tile.getChildAt(0) as TextView
        bindGlyph(glyph, symbol)
        inkCenterGlyph(glyph, symbol, badged = badge != null)
        val badgeView = tile.getChildAt(1) as TextView
        if (badge != null) { badgeView.text = badge; badgeView.visibility = View.VISIBLE } else badgeView.visibility = View.GONE
    }

    private fun inkCenterGlyph(tv: TextView, symbol: String, badged: Boolean) {
        if (symbol.length != 1) {
            tv.translationX = 0f
            tv.translationY = 0f
            return
        }
        val paint = tv.paint
        paint.getTextBounds(symbol, 0, 1, glyphInk)
        paint.getFontMetrics(glyphMetrics)
        val clearance = if (badged) badgeClearancePx else 0f
        val em = paint.textSize
        val inkCenterX = paint.measureText(symbol) / 2f - glyphInk.exactCenterX() - clearance
        val placement = CELL_PLACEMENT[symbol]
        if (placement == null) {
            tv.translationX = inkCenterX
            tv.translationY = 0f
        } else {
            tv.translationX = inkCenterX + placement.first * em
            tv.translationY = (glyphMetrics.ascent + glyphMetrics.descent) / 2f - glyphInk.exactCenterY() + placement.second * em
        }
    }

    private fun bindGlyph(tv: TextView, symbol: String) {
        tv.text = symbol
        if (symbol.length > 1) {
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(tv, 9, ImeType.display.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        } else {
            TextViewCompat.setAutoSizeTextTypeWithDefaults(tv, TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            val sizeSp = if (wideMetricGlyph(symbol[0])) ImeType.display * WIDE_GLYPH_SCALE else ImeType.display
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        }
        tv.setTextColor(palette.keyLabel)
    }

    private fun obtainEmptySpan(): TextView = emptySpanView ?: emptySpan().also { emptySpanView = it }

    private fun emptySpan(): TextView = TextView(context).apply {
        text = context.getString(R.string.symbols_empty_hint)
        gravity = Gravity.CENTER
        setTextColor(palette.keyHint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(dp(16), dp(40), dp(16), dp(16))
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(0, grid.columnCount, 1f)
            setGravity(Gravity.FILL_HORIZONTAL)
        }
    }

    private fun toggleLock() {
        locked = !locked
        Motion.coverThrough(lockBtn, palette.keyboardBg) { updateLockFace() }
    }

    internal fun selectedCategoryForTest(): Int = selected
    internal fun lockedForTest(): Boolean = locked
    internal fun openCategoryForTest(index: Int) = showCategory(index)
    internal fun toggleLockForTest() = toggleLock()
    internal fun gridScrollYForTest(): Int = gridScroll.scrollY
    internal fun gridViewportForTest(): View = gridScroll
    internal fun backBtnForTest(): TextView = backBtn
    internal fun clearBtnForTest(): TextView = clearBtn
    internal fun backspaceBtnForTest(): TextView = backspaceBtn
    internal fun lockBtnForTest(): TextView = lockBtn
    internal fun lockSlotForTest(): View = lockSlot
    internal fun railTabForTest(index: Int): TextView = rail.getChildAt(index) as TextView
    internal fun railTabFeedbackLevelForTest(index: Int): Float =
        railFeedback[railTabForTest(index)]?.levelForTest() ?: 0f
    internal fun clearDialogVisibleForTest(): Boolean = clearDialog.visibility == View.VISIBLE
    internal fun confirmClearForTest(): Boolean = clearDialog.confirmForTest()
    internal fun cancelClearForTest(): Boolean = clearDialog.cancelForTest()
    internal fun dismissClearForTest(): Boolean = clearDialog.performClick()

    internal fun netBarVisibleForTest(): Boolean = showingUrlCompletions
    internal fun chipBarVisibleForTest(): Boolean = netBar.visibility == View.VISIBLE
    internal fun gridCellCountForTest(): Int = grid.childCount
    internal fun gridColumnCountForTest(): Int = grid.columnCount
    internal fun gridCellTextsForTest(): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until grid.childCount) {
            val tile = grid.getChildAt(i) as? android.view.ViewGroup ?: continue
            val tv = (0 until tile.childCount).map { tile.getChildAt(it) }.filterIsInstance<TextView>().firstOrNull()
            if (tv != null) out.add(tv.text.toString())
        }
        return out
    }
    internal fun netChipTextsForTest(): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until netBar.childCount) {
            val child = netBar.getChildAt(i)
            if (child is LinearLayout) {
                for (j in 0 until child.childCount) (child.getChildAt(j) as? TextView)?.let { out.add(it.text.toString()) }
            }
        }
        return out
    }
    private fun netChipFor(symbol: String): TextView? {
        for (i in 0 until netBar.childCount) {
            val row = netBar.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val chip = row.getChildAt(j) as? TextView ?: continue
                if (chip.text?.toString() == symbol) return chip
            }
        }
        return null
    }

    internal fun cellHeightForTest(): Int = cellHeightPx
    internal fun categoryBarForTest(): View = railScroll
    internal fun gridSeparatorColorForTest(): Int = grid.separatorColor
    internal fun gridOutlineColorForTest(): Int = gridScroll.outlineColorForTest()
    internal fun actionColumnForTest(): View = actionColumnView
    internal fun gridTileHeightsForTest(): List<Int> =
        (0 until grid.childCount).map { grid.getChildAt(it).layoutParams.height }
    internal fun netChipMeasuredHeightsForTest(): List<Int> {
        val out = ArrayList<Int>()
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        for (i in 0 until netBar.childCount) {
            val row = netBar.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val chip = row.getChildAt(j)
                chip.measure(unspec, unspec)
                out.add(chip.measuredHeight)
            }
        }
        return out
    }
    internal fun netChipMeasuredWidthsForTest(): List<Int> {
        val out = ArrayList<Int>()
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        for (i in 0 until netBar.childCount) {
            val row = netBar.getChildAt(i) as? LinearLayout ?: continue
            for (j in 0 until row.childCount) {
                val chip = row.getChildAt(j)
                chip.measure(unspec, unspec)
                out.add(chip.measuredWidth)
            }
        }
        return out
    }
    private fun tileFor(symbol: String): android.view.ViewGroup? {
        for (i in 0 until grid.childCount) {
            val tile = grid.getChildAt(i) as? android.view.ViewGroup ?: continue
            val tv = (0 until tile.childCount).map { tile.getChildAt(it) }.filterIsInstance<TextView>().firstOrNull()
            if (tv?.text?.toString() == symbol) return tile
        }
        return null
    }
    internal fun gridGlyphForTest(symbol: String): TextView? =
        tileFor(symbol)?.let { t -> (0 until t.childCount).map { t.getChildAt(it) }.filterIsInstance<TextView>().firstOrNull() }
    internal fun gridBadgeForTest(symbol: String): String? =
        tileFor(symbol)?.let { t ->
            (0 until t.childCount).map { t.getChildAt(it) }.filterIsInstance<TextView>().getOrNull(1)
                ?.takeIf { it.visibility == View.VISIBLE }?.text?.toString()
        }
    internal fun tapCellForTest(symbol: String): Boolean = tileFor(symbol)?.performClick() ?: false
    internal fun longPressCellForTest(symbol: String): Boolean =
        tileFor(symbol)?.performLongClick() ?: netChipFor(symbol)?.performLongClick() ?: false
    internal fun gridCellPixelWidthForTest(symbol: String): Int = tileFor(symbol)?.width ?: -1
    internal fun gridCellForTest(symbol: String): View? = tileFor(symbol)
    internal fun gridCellFeedbackLevelForTest(symbol: String): Float =
        (tileFor(symbol) as? FrameLayout)?.let { tileFeedback[it]?.levelForTest() } ?: 0f
    internal fun tilesAllocatedForTest(): Int = tilePool.size

    private fun updateLockFace() {
        val tint = if (locked) palette.candidateFirst else palette.keyLabelSecondary
        lockGlyph.closed = locked
        lockGlyph.tint(tint)
        lockBtn.isSelected = locked
        lockFeedback.update(Color.TRANSPARENT, tint)
    }

    private fun showClearConfirmation() {
        clearDialog.show(
            context.getString(R.string.symbols_clear_recent_confirm),
            context.getString(R.string.clip_clear),
            context.getString(R.string.clip_cancel),
            palette,
        ) {
            onClearRecents()
            showCategory(selected)
        }
    }

    private fun showDeleteConfirmation(symbol: String) {
        clearDialog.show(
            context.getString(R.string.symbols_delete_recent_confirm, symbol),
            context.getString(R.string.clip_delete),
            context.getString(R.string.clip_cancel),
            palette,
        ) {
            onDeleteRecent(symbol)
            showCategory(selected)
        }
    }

    private fun actionColumn(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setBackgroundColor(palette.keyboardBg)
        backBtn.gravity = Gravity.CENTER; backBtn.setPadding(0, 0, 0, 0)
        clearBtn.gravity = Gravity.CENTER; clearBtn.setPadding(0, 0, 0, 0)
        lockBtn.gravity = Gravity.CENTER; lockBtn.setPadding(0, 0, 0, 0)
        backspaceBtn.gravity = Gravity.CENTER; backspaceBtn.setPadding(0, 0, 0, 0)
        val slots = listOf(
            panelActionSlot(backSlot, backBtn),
            panelActionSlot(clearSlot, clearBtn),
            panelActionSlot(lockSlot, lockBtn),
            panelActionSlot(backspaceSlot, backspaceBtn),
        )
        for (slot in slots) {
            slot.layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(slot, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.keyLabelSecondary)
        isClickable = true
        setOnClickListener { onClick() }
    }

    override fun onDetachedFromWindow() {
        backspaceTouch.cancel()
        backFeedback.reset()
        lockFeedback.reset()
        clearFeedback.reset()
        for (feedback in railFeedback.values) feedback.reset()
        for (feedback in tileFeedback.values) feedback.reset()
        for (feedback in netFeedback.values) feedback.reset()
        super.onDetachedFromWindow()
    }

    private class LockDrawable(private val density: Float) : Drawable() {
        var boxWidthPx = (ImePanelSurfaceMetrics.ACTION_WIDTH_DP * density).toInt()
        var closed = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        fun tint(color: Int) { paint.color = color; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            val b = bounds
            Glyphs.drawLock(canvas, paint, b.exactCenterX(), b.exactCenterY(), 18 * density * 0.48f, closed)
        }
        init { paint.strokeWidth = 2f * density }
        override fun getIntrinsicWidth() = boxWidthPx
        override fun getIntrinsicHeight() = (ImePanelSurfaceMetrics.FACE_HEIGHT_DP * density).toInt()
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class IconDrawable(
        private val density: Float,
        private val sFactor: Float,
        private val render: (Canvas, Paint, Float, Float, Float) -> Unit,
    ) : Drawable() {
        var boxWidthPx = (ImePanelSurfaceMetrics.ACTION_WIDTH_DP * density).toInt()
        private val iconBoxPx = (22 * density).toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun tint(color: Int) { paint.color = color; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            val b = bounds
            render(canvas, paint, b.exactCenterX(), b.exactCenterY(), iconBoxPx * sFactor)
        }
        override fun getIntrinsicWidth() = boxWidthPx
        override fun getIntrinsicHeight() = (ImePanelSurfaceMetrics.FACE_HEIGHT_DP * density).toInt()
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
