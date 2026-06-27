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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.layout.SymbolCatalog

/**
 * Categorized symbols panel (D, reached from the keyboard ✎ pencil key). A left rail of category tabs
 * (常用 / 中文 / 英文 / 网络 / 数学 / 箭头 / 角标 / 序号 / 音标 / 拼音) drives a scrollable grid; tapping a
 * symbol commits it and keeps the panel open for fast multi-symbol entry. Bottom bar = 返回 + ⌫. The
 * "常用" tab is fed live from [recentProvider] (the usage store), the rest from [SymbolCatalog].
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

    private val rail = LinearLayout(context).apply { orientation = VERTICAL }
    private val grid = GridLayout(context).apply {
        columnCount = COLUMNS
        val p = dp(4); setPadding(p, p, p, p)
    }

    private companion object {
        const val COLUMNS = 7
        const val ACCENT = 0xFFF5821F.toInt()
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFFF7F8FA.toInt())

        for ((i, t) in titles.withIndex()) rail.addView(railTab(i, t))

        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(
                ScrollView(context).apply { addView(rail); setBackgroundColor(0xFFEFF1F4.toInt()) },
                LayoutParams(dp(60), LayoutParams.MATCH_PARENT),
            )
            addView(
                ScrollView(context).apply { addView(grid); isFillViewport = true },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottomBar(), LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
    }

    /** Rebuild the rail highlight + grid for the active category — call when the panel becomes visible. */
    fun refresh() = showCategory(selected)

    private fun showCategory(index: Int) {
        selected = index
        for (i in 0 until rail.childCount) {
            val tab = rail.getChildAt(i) as TextView
            val on = i == index
            tab.setTextColor(if (on) ACCENT else 0xFF455A64.toInt())
            tab.setBackgroundColor(if (on) 0xFFFFFFFF.toInt() else 0x00000000)
            tab.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        grid.removeAllViews()
        val symbols = symbolsFor(index)
        if (symbols.isEmpty()) { grid.addView(emptySpan()); return }
        for (s in symbols) grid.addView(cell(s))
    }

    private fun symbolsFor(index: Int): List<String> =
        if (index == 0) recentProvider() else SymbolCatalog.categories[index - 1].symbols

    private fun railTab(index: Int, title: String): TextView = TextView(context).apply {
        text = title
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(13), 0, dp(13))
        isClickable = true
        setOnClickListener { showCategory(index) }
    }

    private fun cell(symbol: String): TextView = TextView(context).apply {
        text = symbol
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setTextColor(0xFF202124.toInt())
        val p = dp(8); setPadding(0, p, 0, p)
        isClickable = true
        setOnClickListener {
            onSymbol(symbol)
            if (selected == 0) showCategory(0) // 常用 tab: re-render so the just-used glyph bumps to the front
        }
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setGravity(Gravity.FILL_HORIZONTAL)
        }
    }

    private fun emptySpan(): TextView = TextView(context).apply {
        text = "最近使用的符号会显示在这里"
        gravity = Gravity.CENTER
        setTextColor(0xFF9AA0A6.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(16), dp(40), dp(16), dp(16))
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(0, COLUMNS, 1f)
            setGravity(Gravity.FILL_HORIZONTAL)
        }
    }

    private fun bottomBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(0xFFE6E9ED.toInt())
        addView(barButton("返回") { onBack() }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(barButton("⌫") { onBackspace() }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isClickable = true
        setOnClickListener { onClick() }
    }
}
