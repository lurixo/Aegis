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
        listOf(SymbolCatalog.RECENT_TITLE) + SymbolCatalog.categories.map { it.title }
    private var selected = 0
    private var locked = false
    private var showingUrlCompletions = false

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
    private val backBtn = barButton("返回") { onBack() }
    private val lockBtn = barButton("锁定") { toggleLock() }
    private val lockSlot = FrameLayout(context).apply {
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabelSecondary)
        setOnClickListener { toggleLock() }
    }
    private val lockGlyph = LockDrawable(density)
    private val backspaceGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }
    private val backspaceBtn = barButton("") { onBackspace() }
    private val bottomBarView = bottomBar()

    private val tilePool = ArrayList<FrameLayout>()
    private var emptySpanView: TextView? = null
    private val colorAnimators = HashMap<TextView, ValueAnimator>()
    private val symbolClick = View.OnClickListener { v ->
        val s = ((v as FrameLayout).getChildAt(0) as TextView).text.toString()
        onSymbol(s, originForCurrent(s)); if (!locked) onBack()
    }

    internal companion object {
        const val COLUMNS = 7
        const val WIDE_GLYPH_SCALE = 0.82f

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
        lockBtn.setCompoundDrawablesWithIntrinsicBounds(lockGlyph, null, null, null)
        lockBtn.compoundDrawablePadding = dp(2)
        backspaceBtn.setCompoundDrawablesWithIntrinsicBounds(null, null, backspaceGlyph, null)
        backspaceGlyph.tint(palette.keyLabelSecondary)

        for ((i, t) in titles.withIndex()) rail.addView(railTab(i, t))

        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            railScroll.setBackgroundColor(palette.railBg)
            addView(railScroll, LayoutParams(dp(60), LayoutParams.MATCH_PARENT))
            addView(gridScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottomBarView, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
        updateLockFace()
    }

    fun refresh() = showCategory(selected)

    fun resetLock() { locked = false; updateLockFace() }

    override fun resetToDefault() {
        resetLock()
        showCategory(0)
        gridScroll.scrollTo(0, 0)
        railScroll.scrollTo(0, 0)
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        railScroll.setBackgroundColor(p.railBg)
        bottomBarView.setBackgroundColor(p.keyboardBg)
        (bottomBarView as LinearLayout).let { bar ->
            for (i in 0 until bar.childCount) (bar.getChildAt(i) as? TextView)?.let {
                it.setTextColor(p.keyLabelSecondary)
                Motion.applyTapFeedback(it, p.keyLabelSecondary)
            }
        }
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

    private fun showCategory(index: Int) {
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
        bindGrid(index)
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
        val gap = dp(8)
        var row = netRow()
        var rowW = 0
        for (c in completions) {
            val chip = netChip(c)
            val w = measureW(chip) + gap
            if (rowW + w > maxRowW && row.childCount > 0) { netBar.addView(row); row = netRow(); rowW = 0 }
            row.addView(
                chip,
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = gap; topMargin = dp(4) },
            )
            rowW += w
        }
        if (row.childCount > 0) netBar.addView(row)
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
        if (selected == 0) recentOriginOf(symbol) else SymbolCatalog.categories.getOrNull(selected - 1)?.title

    private fun badgeFor(symbol: String): String? =
        (recentOriginOf(symbol) ?: SymbolCatalog.categoryTitleOf(symbol))?.take(1)

    private fun railTab(index: Int, title: String): TextView = TextView(context).apply {
        text = title
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(0, dp(13), 0, dp(13))
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
        bindGlyph(tile.getChildAt(0) as TextView, symbol)
        val badgeView = tile.getChildAt(1) as TextView
        if (badge != null) { badgeView.text = badge; badgeView.visibility = View.VISIBLE } else badgeView.visibility = View.GONE
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
        text = "最近使用的符号会显示在这里"
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

    private fun toggleLock() { locked = !locked; updateLockFace() }

    internal fun selectedCategoryForTest(): Int = selected
    internal fun lockedForTest(): Boolean = locked
    internal fun openCategoryForTest(index: Int) = showCategory(index)
    internal fun toggleLockForTest() = toggleLock()
    internal fun gridScrollYForTest(): Int = gridScroll.scrollY
    internal fun backBtnForTest(): TextView = backBtn
    internal fun backspaceBtnForTest(): TextView = backspaceBtn
    internal fun lockBtnForTest(): TextView = lockBtn
    internal fun lockSlotForTest(): View = lockSlot
    internal fun railTabForTest(index: Int): TextView = rail.getChildAt(index) as TextView

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
        lockBtn.text = "锁定"
        lockBtn.setTextColor(tint)
        Motion.applyTapFeedback(lockBtn, tint)
        Motion.applyTapFeedback(lockSlot, tint)
        lockBtn.setTypeface(null, if (locked) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun bottomBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(palette.keyboardBg)
        backBtn.gravity = Gravity.START or Gravity.CENTER_VERTICAL; backBtn.setPadding(dp(20), 0, 0, 0)
        backspaceBtn.gravity = Gravity.END or Gravity.CENTER_VERTICAL; backspaceBtn.setPadding(0, 0, dp(20), 0)
        lockSlot.addView(lockBtn, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(backBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(lockSlot, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(backspaceBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
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
            Glyphs.drawLock(canvas, paint, b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) * 0.48f, closed)
        }
        init { paint.strokeWidth = 2f * density }
        override fun getIntrinsicWidth() = (18 * density).toInt()
        override fun getIntrinsicHeight() = (18 * density).toInt()
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
