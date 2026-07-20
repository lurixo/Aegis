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
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.layout.SymbolCatalog

class SymbolsView(context: Context) : LinearLayout(context), ResettablePanel {

    var onSymbol: (String, String?) -> Unit = { _, _ -> }
    var onClearRecents: () -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onBack: () -> Unit = {}
    var recentProvider: () -> List<String> = { emptyList() }
    var recentOriginOf: (String) -> String? = { null }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val cellHeightPx: Int = run {
        val displayPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.display, resources.displayMetrics)
        maxOf(dp(44), (displayPx * 1.35f).toInt() + dp(14))
    }

    private val titles: List<String> =
        listOf(context.getString(SymbolCatalog.RECENT_TITLE_RES)) + SymbolCatalog.categories.map { context.getString(it.titleRes) }
    private var selected = 0
    private var locked = false
    private var showingUrlCompletions = false
    private var measuringWidthOverride = 0
    private var lastFlowWidth = -1

    private var palette = ImePalette.STATIC_LIGHT
    private val rail = LinearLayout(context).apply { orientation = VERTICAL }
    private val railScroll = ScrollView(context).apply { addView(rail) }
    private val grid = GridLayout(context).apply {
        columnCount = COLUMNS
        val p = dp(4); setPadding(p, p, p, p)
    }
    private val netBar = LinearLayout(context).apply { orientation = VERTICAL; visibility = View.GONE }
    private val gridHolder = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(netBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val gridScroll = ScrollView(context).apply { addView(gridHolder); isFillViewport = true }
    private val clearDialog = PanelConfirmationOverlay(context)
    private val backGlyph = IconDrawable(density, 0.41f) { c, p, x, y, s -> Glyphs.drawBack(c, p, x, y, s) }
    private val backBtn = barButton("") { onBack() }
    private val lockBtn = barButton("") { toggleLock() }
    private val lockSlot = FrameLayout(context)
    private val lockGlyph = LockDrawable(density)
    private val clearGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y - s * 0.06f, s) }
    private val clearBtn = barButton("") { showClearConfirmation() }
    private val backspaceGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }
    private val backspaceBtn = barButton("") { onBackspace() }
    private val bottomBarView = bottomBar()

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

    internal companion object {
        const val COLUMNS = 7
        const val WIDE_GLYPH_SCALE = 0.82f
        const val BADGE_CLEARANCE_DP = 6

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
        backspaceBtn.setCompoundDrawablesWithIntrinsicBounds(backspaceGlyph, null, null, null)
        backspaceGlyph.tint(palette.keyLabelSecondary)

        for ((i, t) in titles.withIndex()) rail.addView(railTab(i, t))

        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            railScroll.setBackgroundColor(palette.keyboardBg)
            addView(railScroll, LayoutParams(dp(60), LayoutParams.MATCH_PARENT))
            addView(gridScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        val panelColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomBarView, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
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
        railScroll.scrollTo(0, 0)
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        railScroll.setBackgroundColor(p.keyboardBg)
        bottomBarView.setBackgroundColor(p.keyboardBg)
        for (button in listOf(backBtn, clearBtn, backspaceBtn)) {
            button.setTextColor(p.keyLabelSecondary)
            Motion.applyTapFeedback(button, p.keyLabelSecondary)
        }
        backGlyph.tint(p.keyLabelSecondary)
        clearGlyph.tint(p.keyLabelSecondary)
        backspaceGlyph.tint(p.keyLabelSecondary)
        for (tile in tilePool) {
            retintRipple(tile, p.keyLabel)
            (tile.background as? GradientDrawable)?.setColor(p.keySurface)
            (tile.getChildAt(0) as TextView).setTextColor(p.keyLabel)
            (tile.getChildAt(1) as TextView).setTextColor(p.keyLabelSecondary)
        }
        emptySpanView?.setTextColor(p.keyHint)
        updateLockFace()
        showCategory(selected)
    }

    private fun showCategory(index: Int, animate: Boolean = true) {
        val tabChanged = index != selected
        val prev = selected
        selected = index
        for (i in 0 until rail.childCount) {
            val tab = rail.getChildAt(i) as TextView
            val on = i == index
            tab.background = railTabBackground(on)
            tab.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            val color = if (on) palette.candidateFirst else palette.keyLabelSecondary
            if (tabChanged && (i == index || i == prev)) crossfadeTabColor(tab, color) else tab.setTextColor(color)
            retintRipple(tab, color, ImeShapes.chipRadiusDp)
        }
        if (animate && tabChanged && gridScroll.isShown) Motion.coverThrough(gridScroll, palette.keyboardBg) { bindGrid(selected) }
        else bindGrid(index)
    }

    private fun bindGrid(index: Int) {
        grid.removeAllViews()
        netBar.removeAllViews()
        val symbols = symbolsFor(index)
        if (symbols.isEmpty()) { netBar.visibility = View.GONE; grid.addView(obtainEmptySpan()); return }
        val isNet = index != 0 && SymbolCatalog.categories.getOrNull(index - 1)?.id == "net"
        val completions = symbols.filter { it.length > 1 }
        val urlCompletions = if (completions.isNotEmpty() && (isNet || completions.any { isUrlLike(it) }))
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
            bindTile(tile, s, badge = if (index == 0) badgeFor(s) else null)
            grid.addView(tile)
        }
    }

    private fun crossfadeTabColor(tab: TextView, color: Int) {
        colorAnimators.remove(tab)?.cancel()
        Motion.crossfadeColor(tab, tab.currentTextColor, color) { tab.setTextColor(it) }?.let { colorAnimators[tab] = it }
    }

    private fun retintRipple(v: View, color: Int, radiusDp: Float = ImeShapes.keyRadiusDp) {
        val fg = v.foreground
        if (fg is RippleDrawable) fg.setColor(ColorStateList.valueOf(Motion.withAlpha(color, 0x24)))
        else Motion.applyTapFeedback(v, color, radiusDp = radiusDp)
    }

    private fun addCompletionChips(completions: List<String>) {
        val maxRowW = resources.displayMetrics.widthPixels - dp(60) - dp(16)
        val configuredWidth = resources.configuration.screenWidthDp
            .takeIf { it > 0 }
            ?.let { (it * density).toInt() }
            ?: resources.displayMetrics.widthPixels

        val liveWidth = measuringWidthOverride.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: configuredWidth
        val liveMaxRowW = minOf(maxRowW, (liveWidth - dp(60) - dp(16)).coerceAtLeast(dp(44)))
        val gap = dp(8)
        var row = netRow()
        var rowW = 0
        for (c in completions) {
            val chip = netChip(c)
            val w = measureW(chip) + gap
            if (rowW + w > liveMaxRowW && row.childCount > 0) { netBar.addView(row); row = netRow(); rowW = 0 }
            row.addView(
                chip,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = gap; topMargin = dp(4) },
            )
            rowW += w
        }
        if (row.childCount > 0) netBar.addView(row)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val incomingWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (incomingWidth > 0 && incomingWidth != lastFlowWidth) {
            measuringWidthOverride = incomingWidth
            lastFlowWidth = incomingWidth
            showCategory(selected)
            measuringWidthOverride = 0
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun netRow(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun netChip(symbol: String): View = TextView(context).apply {
        text = symbol
        maxLines = 1
        ellipsize = null
        minimumHeight = dp(44)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
        setTextColor(palette.keyLabel)
        background = GradientDrawable().apply { setColor(palette.keySurface); cornerRadius = ImeShapes.keyRadiusDp * density }
        val ph = dp(14); setPadding(ph, dp(8), ph, dp(8))
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabel)
        setOnClickListener { onSymbol(symbol, originForCurrent(symbol)); if (!locked) onBack() }
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
        (recentOriginOf(symbol) ?: SymbolCatalog.categoryIdOf(symbol))?.let { SymbolCatalog.titleResOf(it) }?.let { context.getString(it).take(1) }

    private fun railTab(index: Int, title: String): TextView = TextView(context).apply {
        text = title
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(2), dp(13), dp(2), dp(13))
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 11, ImeType.label.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        background = railTabBackground(index == selected)
        isClickable = true
        Motion.applyTapFeedback(this, if (index == selected) palette.candidateFirst else palette.keyLabelSecondary, radiusDp = ImeShapes.chipRadiusDp)
        setOnClickListener { showCategory(index) }
    }

    private fun railTabBackground(on: Boolean): GradientDrawable? =
        if (!on) null else GradientDrawable().apply {
            setColor(palette.keySurface)
            cornerRadius = ImeShapes.chipRadiusDp * density
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
            background = GradientDrawable().apply { setColor(palette.keySurface); cornerRadius = ImeShapes.keyRadiusDp * density }
            isClickable = true
            Motion.applyTapFeedback(this, palette.keyLabel)
            setOnClickListener(symbolClick)
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = cellHeightPx
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setGravity(Gravity.FILL)
                val m = dp(3); setMargins(m, m, m, m)
            }
            addView(glyph, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        }
        tilePool.add(tile)
        return tile
    }

    private fun bindTile(tile: FrameLayout, symbol: String, badge: String?) {
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
        tv.translationX = paint.measureText(symbol) / 2f - glyphInk.exactCenterX() - clearance
        tv.translationY = (glyphMetrics.ascent + glyphMetrics.descent) / 2f - glyphInk.exactCenterY()
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
            columnSpec = GridLayout.spec(0, COLUMNS, 1f)
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
    internal fun clearDialogVisibleForTest(): Boolean = clearDialog.visibility == View.VISIBLE
    internal fun confirmClearForTest(): Boolean = clearDialog.confirmForTest()
    internal fun cancelClearForTest(): Boolean = clearDialog.cancelForTest()
    internal fun dismissClearForTest(): Boolean = clearDialog.performClick()

    internal fun netBarVisibleForTest(): Boolean = showingUrlCompletions
    internal fun chipBarVisibleForTest(): Boolean = netBar.visibility == View.VISIBLE
    internal fun gridCellCountForTest(): Int = grid.childCount
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

    internal fun cellHeightForTest(): Int = cellHeightPx
    internal fun gridTileHeightsForTest(): List<Int> =
        (0 until grid.childCount).map { grid.getChildAt(it).layoutParams.height }
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
    internal fun tilesAllocatedForTest(): Int = tilePool.size

    private fun updateLockFace() {
        val tint = if (locked) palette.candidateFirst else palette.keyLabelSecondary
        lockGlyph.closed = locked
        lockGlyph.tint(tint)
        Motion.applyTapFeedback(lockBtn, tint)
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

    private fun bottomBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(palette.keyboardBg)
        backBtn.gravity = Gravity.CENTER; backBtn.setPadding(0, 0, 0, 0)
        clearBtn.gravity = Gravity.CENTER; clearBtn.setPadding(0, 0, 0, 0)
        lockBtn.gravity = Gravity.CENTER; lockBtn.setPadding(0, 0, 0, 0)
        backspaceBtn.gravity = Gravity.CENTER; backspaceBtn.setPadding(0, 0, 0, 0)
        lockSlot.addView(lockBtn, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(backBtn, LinearLayout.LayoutParams(dp(60), LayoutParams.MATCH_PARENT))
        addView(View(context), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(clearBtn, LinearLayout.LayoutParams(dp(60), LayoutParams.MATCH_PARENT))
        addView(View(context), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(lockSlot, LinearLayout.LayoutParams(dp(60), LayoutParams.MATCH_PARENT))
        addView(View(context), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(backspaceBtn, LinearLayout.LayoutParams(dp(60), LayoutParams.MATCH_PARENT))
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.keyLabelSecondary)
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabelSecondary)
        setOnClickListener { onClick() }
    }

    private class LockDrawable(private val density: Float) : Drawable() {
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
        override fun getIntrinsicWidth() = (60 * density).toInt()
        override fun getIntrinsicHeight() = (46 * density).toInt()
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
        private val iconBoxPx = (22 * density).toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun tint(color: Int) { paint.color = color; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            val b = bounds
            render(canvas, paint, b.exactCenterX(), b.exactCenterY(), iconBoxPx * sFactor)
        }
        override fun getIntrinsicWidth() = (60 * density).toInt()
        override fun getIntrinsicHeight() = (46 * density).toInt()
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
