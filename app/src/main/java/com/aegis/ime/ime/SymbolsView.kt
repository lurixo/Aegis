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

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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

    var onSymbol: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onBack: () -> Unit = {}
    var recentProvider: () -> List<String> = { emptyList() }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

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
    private val lockGlyph = LockDrawable(density)
    private val backspaceGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }
    private val backspaceBtn = barButton("") { onBackspace() }
    private val bottomBarView = bottomBar()

    private companion object {
        const val COLUMNS = 7
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg)
        lockBtn.setCompoundDrawablesWithIntrinsicBounds(lockGlyph, null, null, null)
        lockBtn.compoundDrawablePadding = dp(4)
        backspaceBtn.setCompoundDrawablesWithIntrinsicBounds(backspaceGlyph, null, null, null)
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
            for (i in 0 until bar.childCount) (bar.getChildAt(i) as? TextView)?.setTextColor(p.keyLabelSecondary)
        }
        backspaceGlyph.tint(p.keyLabelSecondary)
        updateLockFace()
        showCategory(selected)
    }

    private fun showCategory(index: Int) {
        selected = index
        for (i in 0 until rail.childCount) {
            val tab = rail.getChildAt(i) as TextView
            val on = i == index
            tab.setTextColor(if (on) palette.candidateFirst else palette.keyLabelSecondary)
            tab.setBackgroundColor(if (on) palette.keySurface else 0x00000000)
            tab.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        grid.removeAllViews()
        netBar.removeAllViews()
        val symbols = symbolsFor(index)
        if (symbols.isEmpty()) { netBar.visibility = View.GONE; grid.addView(emptySpan()); return }
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
        for (s in symbols) if (s !in urlCompletions) grid.addView(cell(s, badge = if (index == 0) badgeFor(s) else null))
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
        setOnClickListener { onSymbol(symbol); if (!locked) onBack() }
    }

    private fun measureW(v: View): Int {
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        v.measure(unspec, unspec)
        return v.measuredWidth
    }

    private fun isUrlLike(s: String): Boolean = s.any { it == '/' || it == ':' || it == '.' }

    private fun symbolsFor(index: Int): List<String> =
        if (index == 0) recentProvider() else SymbolCatalog.categories[index - 1].symbols

    private fun badgeFor(symbol: String): String? = SymbolCatalog.categoryTitleOf(symbol)?.take(1)

    private fun railTab(index: Int, title: String): TextView = TextView(context).apply {
        text = title
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(0, dp(13), 0, dp(13))
        isClickable = true
        setOnClickListener { showCategory(index) }
    }

    private fun cell(symbol: String, badge: String?): View {
        val tile = FrameLayout(context).apply {
            minimumHeight = dp(44)
            background = GradientDrawable().apply { setColor(palette.keySurface); cornerRadius = ImeShapes.keyRadiusDp * density }
            isClickable = true
            setOnClickListener { onSymbol(symbol); if (!locked) onBack() }
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setGravity(Gravity.FILL_HORIZONTAL)
                val m = dp(3); setMargins(m, m, m, m)
            }
        }
        tile.addView(
            TextView(context).apply {
                text = symbol
                gravity = Gravity.CENTER
                maxLines = 1
                if (symbol.length > 1) {
                    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 9, ImeType.display.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
                } else {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.display)
                }
                setTextColor(palette.keyLabel)
                val pv = dp(10); setPadding(0, pv, 0, pv)
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        if (badge != null) tile.addView(
            TextView(context).apply {
                text = badge
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.caption)
                setTextColor(palette.keyLabelSecondary)
                setPadding(0, 0, dp(4), dp(2))
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END),
        )
        return tile
    }

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

    private fun updateLockFace() {
        val tint = if (locked) palette.candidateFirst else palette.keyLabelSecondary
        lockGlyph.closed = locked
        lockGlyph.tint(tint)
        lockBtn.text = "锁定"
        lockBtn.setTextColor(tint)
        lockBtn.setTypeface(null, if (locked) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun bottomBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(palette.keyboardBg)
        backBtn.gravity = Gravity.START or Gravity.CENTER_VERTICAL; backBtn.setPadding(dp(20), 0, 0, 0)
        backspaceBtn.gravity = Gravity.END or Gravity.CENTER_VERTICAL; backspaceBtn.setPadding(0, 0, dp(20), 0)
        addView(backBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(lockBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(backspaceBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.keyLabelSecondary)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private class LockDrawable(private val density: Float) : Drawable() {
        var closed = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        fun tint(color: Int) { paint.color = color; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            val b = bounds
            Glyphs.drawLock(canvas, paint, b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) * 0.52f, closed)
        }
        init { paint.strokeWidth = 2f * density }
        override fun getIntrinsicWidth() = (22 * density).toInt()
        override fun getIntrinsicHeight() = (22 * density).toInt()
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
