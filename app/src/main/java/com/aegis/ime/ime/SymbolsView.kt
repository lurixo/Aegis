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
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.SymbolCatalog

/**
 * Categorized symbols panel (D, reached from the keyboard ✎ pencil key). A left rail of category tabs
 * (常用 / 中文 / 英文 / 货币 / 网络 / 数学 / 箭头 / 角标 / 序号 / 音标 / 拼音) drives a scrollable grid.
 * Tapping a symbol commits it; by default it then returns to the keyboard (U3), unless 锁定 (P3) is on, in
 * which case the panel stays for continuous symbol entry. 常用 cells carry an origin badge (P2: 中/英/…).
 * Bottom bar = 返回 · 锁定 · ⌫. The "常用" tab is fed live from [recentProvider]; the rest from [SymbolCatalog].
 */
class SymbolsView(context: Context) : LinearLayout(context) {

    var onSymbol: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onBack: () -> Unit = {}
    /** Live "常用" feed (most-recently-used symbols, newest first). */
    var recentProvider: () -> List<String> = { emptyList() }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val titles: List<String> =
        listOf(SymbolCatalog.RECENT_TITLE) + SymbolCatalog.categories.map { it.title }
    private var selected = 0
    private var locked = false // P3: when on, tapping a symbol does NOT close the panel

    private var palette = ImePalette.STATIC_LIGHT
    private val rail = LinearLayout(context).apply { orientation = VERTICAL }
    private val railScroll = ScrollView(context).apply { addView(rail) }
    private val grid = GridLayout(context).apply {
        columnCount = COLUMNS
        val p = dp(4); setPadding(p, p, p, p)
    }
    private val backBtn = barButton("返回") { onBack() }
    private val lockBtn = barButton("锁定") { toggleLock() }       // P3
    private val backspaceBtn = barButton("⌫") { onBackspace() }
    private val bottomBarView = bottomBar()

    private companion object {
        const val COLUMNS = 7
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.panelBg)

        for ((i, t) in titles.withIndex()) rail.addView(railTab(i, t))

        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            railScroll.setBackgroundColor(palette.railBg)
            addView(railScroll, LayoutParams(dp(60), LayoutParams.MATCH_PARENT))
            addView(
                ScrollView(context).apply { addView(grid); isFillViewport = true },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottomBarView, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
        updateLockFace()
    }

    /** Rebuild the rail highlight + grid for the active category — call when the panel becomes visible. */
    fun refresh() = showCategory(selected)

    /** P3: clear the lock — call when (re)opening the panel so it always starts unlocked. */
    fun resetLock() { locked = false; updateLockFace() }

    /** F1: recolour from the Monet palette (active-tab accent now converges to primary). */
    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.panelBg)
        railScroll.setBackgroundColor(p.railBg)
        bottomBarView.setBackgroundColor(p.panelBg) // P6: 返回 bar = content background (was panelSubBg)
        (bottomBarView as LinearLayout).let { bar ->
            for (i in 0 until bar.childCount) (bar.getChildAt(i) as? TextView)?.setTextColor(p.keyLabelSecondary)
        }
        updateLockFace() // restore the lock-state colour after the bulk recolour
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
        val symbols = symbolsFor(index)
        if (symbols.isEmpty()) { grid.addView(emptySpan()); return }
        // P2: only the 常用 tab (index 0) shows an origin badge; the category tabs are already labelled.
        for (s in symbols) grid.addView(cell(s, badge = if (index == 0) badgeFor(s) else null))
    }

    private fun symbolsFor(index: Int): List<String> =
        if (index == 0) recentProvider() else SymbolCatalog.categories[index - 1].symbols

    /** P2: short origin badge for a 常用 symbol (中文→"中", 英文→"英", …); null if it's not in any category. */
    private fun badgeFor(symbol: String): String? = SymbolCatalog.categoryTitleOf(symbol)?.take(1)

    private fun railTab(index: Int, title: String): TextView = TextView(context).apply {
        text = title
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(13), 0, dp(13))
        isClickable = true
        setOnClickListener { showCategory(index) }
    }

    /**
     * One symbol key-tile (U11). [badge] (P2) draws a small origin tag at the bottom-right for 常用 cells.
     * U3/P3: a tap commits the symbol and — unless 锁定 is on — closes the panel back to the keyboard.
     */
    private fun cell(symbol: String, badge: String?): View {
        val tile = FrameLayout(context).apply {
            minimumHeight = dp(44)
            background = GradientDrawable().apply { setColor(palette.keySurface); cornerRadius = 8f * density }
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(palette.keyLabel)
                val pv = dp(10); setPadding(0, pv, 0, pv)
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        if (badge != null) tile.addView(
            TextView(context).apply {
                text = badge
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
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
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(16), dp(40), dp(16), dp(16))
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(0, COLUMNS, 1f)
            setGravity(Gravity.FILL_HORIZONTAL)
        }
    }

    private fun toggleLock() { locked = !locked; updateLockFace() }

    /** P3: the lock key shows its on/off state (filled 🔒 + accent when locked, 🔓 + muted when not). */
    private fun updateLockFace() {
        lockBtn.text = if (locked) "🔒锁定" else "🔓锁定"
        lockBtn.setTextColor(if (locked) palette.candidateFirst else palette.keyLabelSecondary)
        lockBtn.setTypeface(null, if (locked) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun bottomBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(palette.panelBg) // P6: same as the content background
        addView(backBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(lockBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)) // P3: 锁定 between 返回 and ⌫
        addView(backspaceBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(palette.keyLabelSecondary)
        isClickable = true
        setOnClickListener { onClick() }
    }
}
