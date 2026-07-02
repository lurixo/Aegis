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

/**
 * Categorized symbols panel (D, reached from the keyboard ✎ pencil key). A left rail of category tabs
  * Chinese IME behavior note.
  * Chinese IME behavior note.
  * Chinese IME behavior note.
  * Chinese IME behavior note.
 */
class SymbolsView(context: Context) : LinearLayout(context), ResettablePanel {

    var onSymbol: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onBack: () -> Unit = {}
    /** Chinese IME behavior note. */
    var recentProvider: () -> List<String> = { emptyList() }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val titles: List<String> =
        listOf(SymbolCatalog.RECENT_TITLE) + SymbolCatalog.categories.map { it.title }
    private var selected = 0
    private var locked = false // P3: when on, tapping a symbol does NOT close the panel
    // Chinese IME behavior note.
    // Chinese IME behavior note.
    private var showingUrlCompletions = false

    private var palette = ImePalette.STATIC_LIGHT
    private val rail = LinearLayout(context).apply { orientation = VERTICAL }
    private val railScroll = ScrollView(context).apply { addView(rail) }
    private val grid = GridLayout(context).apply {
        columnCount = COLUMNS
        val p = dp(4); setPadding(p, p, p, p)
    }
    // Chinese IME behavior note.
    // Chinese IME behavior note.
    // gridHolder so the single GONE↔VISIBLE swap keeps every all-glyph category pixel-identical.
    private val netBar = LinearLayout(context).apply { orientation = VERTICAL; visibility = View.GONE }
    private val gridHolder = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(netBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val gridScroll = ScrollView(context).apply { addView(gridHolder); isFillViewport = true }
    private val backBtn = barButton("返回") { onBack() }
    private val lockBtn = barButton("锁定") { toggleLock() }       // P3
    private val lockSlot = FrameLayout(context).apply {
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabelSecondary)
        setOnClickListener { toggleLock() }
    }
    private val lockGlyph = LockDrawable(density)                 // P-C: self-drawn monochrome lock (was emoji)
    private val backspaceGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) } // debug.17: ⌫ self-drawn, no char
    private val backspaceBtn = barButton("") { onBackspace() }
    private val bottomBarView = bottomBar()

    private companion object {
        const val COLUMNS = 7
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg) // P-A: panel floor == the strip/keyboard floor (no top seam)
        lockBtn.setCompoundDrawablesWithIntrinsicBounds(lockGlyph, null, null, null) // Chinese IME behavior note.
        lockBtn.compoundDrawablePadding = dp(2)
        // debug.18 (item14): ⌫ glyph as the END (right) compound drawable, not LEFT — a left drawable anchors to
        // the button's left edge so the gravity-END + right-padding below were ineffective (⌫ floated mid-bar).
        // Chinese IME behavior note.
        // Chinese IME behavior note.
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

    /** Rebuild the rail highlight + grid for the active category — call when the panel becomes visible. */
    fun refresh() = showCategory(selected)

    /** P3: clear the lock — call when (re)opening the panel so it always starts unlocked. */
    fun resetLock() { locked = false; updateLockFace() }

    /**
      * Chinese IME behavior note.
     * at the top — so reopening never lands on the last category / scroll position.
     */
    override fun resetToDefault() {
        resetLock()
        showCategory(0)
        gridScroll.scrollTo(0, 0)
        railScroll.scrollTo(0, 0)
    }

    /** F1: recolour from the Monet palette (active-tab accent now converges to primary). */
    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg) // P-A: see init
        railScroll.setBackgroundColor(p.railBg)
        bottomBarView.setBackgroundColor(p.keyboardBg) // Chinese IME behavior note.
        (bottomBarView as LinearLayout).let { bar ->
            for (i in 0 until bar.childCount) (bar.getChildAt(i) as? TextView)?.let {
                it.setTextColor(p.keyLabelSecondary)
                Motion.applyTapFeedback(it, p.keyLabelSecondary)
            }
        }
        backspaceGlyph.tint(p.keyLabelSecondary)
        updateLockFace() // restore the lock-state colour after the bulk recolour
        showCategory(selected)
    }

    private fun showCategory(index: Int) {
        selected = index
        for (i in 0 until rail.childCount) {
            val tab = rail.getChildAt(i) as TextView
            val on = i == index
            tab.setTextColor(if (on) palette.candidateFirst else palette.keyLabelSecondary)
            tab.background = railTabBackground(on)
            tab.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            Motion.applyTapFeedback(tab, if (on) palette.candidateFirst else palette.keyLabelSecondary, radiusDp = ImeShapes.chipRadiusDp)
        }
        grid.removeAllViews()
        netBar.removeAllViews()
        val symbols = symbolsFor(index)
        if (symbols.isEmpty()) { netBar.visibility = View.GONE; grid.addView(emptySpan()); return }
        // P5 + debug.16: only URL completions render as content-sized chips so they NEVER truncate in the single-
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        // straight to the editor on tap, NOT advertised as URL completions. Single glyphs keep the unchanged grid
        // path, so every all-glyph category stays pixel-identical.
        val isNet = index != 0 && SymbolCatalog.categories.getOrNull(index - 1)?.id == "net"
        val completions = symbols.filter { it.length > 1 }
        // debug.17 A: ONLY genuine URL completions ride the content-sized chip bar — and ONLY the url-like ones,
        // even on a tab that also holds a non-url multi-char token. Everything else (single glyphs AND non-url
        // Chinese IME behavior note.
        // auto-shrinks to fit), in natural catalogue order — so arcsin never becomes a wide tile that breaks the
        // Chinese IME behavior note.
        val urlCompletions = if (completions.isNotEmpty() && (isNet || completions.any { isUrlLike(it) }))
            completions.filter { isUrlLike(it) } else emptyList()
        showingUrlCompletions = urlCompletions.isNotEmpty()
        if (showingUrlCompletions) {
            netBar.visibility = View.VISIBLE
            // Chinese IME behavior note.
            addCompletionChips(urlCompletions)
        } else {
            netBar.visibility = View.GONE
        }
        // Chinese IME behavior note.
        for (s in symbols) if (s !in urlCompletions) grid.addView(cell(s, badge = if (index == 0) badgeFor(s) else null))
    }

    /** P5: lay the multi-char completions out as full, single-line chips, wrapping to a new row instead of
     *  truncating. Reuses [cell]'s tap contract (P3 lock aware) via [netChip]. */
    private fun addCompletionChips(completions: List<String>) {
        val maxRowW = resources.displayMetrics.widthPixels - dp(60) - dp(16) // minus the left rail + row padding
        val gap = dp(8)
        var row = netRow()
        var rowW = 0
        for (c in completions) {
            val chip = netChip(c)
            val w = measureW(chip) + gap
            if (rowW + w > maxRowW && row.childCount > 0) { netBar.addView(row); row = netRow(); rowW = 0 }
            row.addView(
                // WRAP height + minHeight (like cell()) so the chip grows at large font scale instead of clipping
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

    /**
     *  single-line with ellipsize off, so a long completion can never wrap mid-token or clip. */
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
        setOnClickListener { onSymbol(symbol); if (!locked) onBack() }
    }

    private fun measureW(v: View): Int {
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        v.measure(unspec, unspec)
        return v.measuredWidth
    }

    /**
      * Chinese IME behavior note.
     *  grid as ordinary cells, not the url chip bar. */
    private fun isUrlLike(s: String): Boolean = s.any { it == '/' || it == ':' || it == '.' }

    private fun symbolsFor(index: Int): List<String> =
        if (index == 0) recentProvider() else SymbolCatalog.categories[index - 1].symbols

    /** Chinese IME behavior note. */
    private fun badgeFor(symbol: String): String? = SymbolCatalog.categoryTitleOf(symbol)?.take(1)

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

    /**
      * Chinese IME behavior note.
      * Chinese IME behavior note.
     */
    private fun cell(symbol: String, badge: String?): View {
        val tile = FrameLayout(context).apply {
            minimumHeight = dp(44)
            background = GradientDrawable().apply { setColor(palette.keySurface); cornerRadius = ImeShapes.keyRadiusDp * density }
            isClickable = true
            Motion.applyTapFeedback(this, palette.keyLabel)
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
                    // Chinese IME behavior note.
                    // cell, so it never truncates and never needs a wide tile that breaks the grid.
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

    // P7 test seams (read state / drive it to a non-default position before asserting the reset).
    internal fun selectedCategoryForTest(): Int = selected
    internal fun lockedForTest(): Boolean = locked
    internal fun openCategoryForTest(index: Int) = showCategory(index)
    internal fun toggleLockForTest() = toggleLock()
    internal fun gridScrollYForTest(): Int = gridScroll.scrollY
    // Chinese IME behavior note.
    internal fun backBtnForTest(): TextView = backBtn
    internal fun backspaceBtnForTest(): TextView = backspaceBtn
    internal fun lockBtnForTest(): TextView = lockBtn
    internal fun lockSlotForTest(): View = lockSlot
    internal fun railTabForTest(index: Int): TextView = rail.getChildAt(index) as TextView

    // Chinese IME behavior note.
    // Chinese IME behavior note.
    internal fun netBarVisibleForTest(): Boolean = showingUrlCompletions
    /** Whether the chip (net) bar is showing at all — currently only URL completions ever populate it. */
    internal fun chipBarVisibleForTest(): Boolean = netBar.visibility == View.VISIBLE
    internal fun gridCellCountForTest(): Int = grid.childCount
    /** Chinese IME behavior note. */
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
            if (child is LinearLayout) { // a chip row (netBar now holds only chip rows — no caption header)
                for (j in 0 until child.childCount) (child.getChildAt(j) as? TextView)?.let { out.add(it.text.toString()) }
            }
        }
        return out
    }

    /** P3/P-C: the lock key shows its on/off state via the self-drawn padlock (closed + accent when locked,
     *  open + muted when not) — a monochrome glyph that tracks the palette, not a multi-colour emoji. */
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
        setBackgroundColor(palette.keyboardBg) // P-A: same as the unified floor
        // Chinese IME behavior note.
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

    /**
     *  by [updateLockFace]. Self-drawn (Glyphs.drawLock) so it stays monochrome and theme-correct in dark mode. */
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
        init { paint.strokeWidth = 2f * density } // the bar's stroke weight (matches EmojiView's lock)
        override fun getIntrinsicWidth() = (18 * density).toInt()
        override fun getIntrinsicHeight() = (18 * density).toInt()
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** debug.17: a palette-tinted [Drawable] drawing one self-drawn [Glyphs] icon (the bar's ⌫), so it stops
     *  using a font character and matches the lock's monochrome-stroke language. */
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
