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
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.layout.EmojiCatalog

/**
 * Scrollable emoji panel (C8). A left rail of categories (黄脸 / 手势 / 旗帜) drives a grid of
 * tappable emoji over a bottom bar with a back-to-keyboard button and a code-point-aware backspace.
 * Curated common set from [EmojiCatalog] — no network.
 */
class EmojiView(context: Context) : LinearLayout(context), ResettablePanel {

    var onEmoji: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onBack: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT
    private var selected = 0
    private val rail = LinearLayout(context).apply { orientation = VERTICAL }
    private val railScroll = ScrollView(context).apply { addView(rail) }
    private val grid = GridLayout(context).apply {
        columnCount = COLUMNS
        val p = dp(4); setPadding(p, p, p, p)
    }
    private val gridScroll = ScrollView(context).apply { addView(grid); isFillViewport = true }
    private val bottomBarView = bottomBar()

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg) // P-A: panel floor == the strip/keyboard floor (no top seam)

        for ((i, c) in EmojiCatalog.categories.withIndex()) rail.addView(railTab(i, c.title))

        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            railScroll.setBackgroundColor(palette.railBg)
            addView(railScroll, LayoutParams(dp(60), LayoutParams.MATCH_PARENT))
            addView(gridScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottomBarView, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
        showCategory(0)
    }

    /** F1: recolour from the Monet palette. */
    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg) // P-A: see init
        railScroll.setBackgroundColor(p.railBg)
        bottomBarView.setBackgroundColor(p.keyboardBg) // P-A: 返回 bar = the unified floor
        (bottomBarView as LinearLayout).let { bar ->
            for (i in 0 until bar.childCount) (bar.getChildAt(i) as? TextView)?.setTextColor(p.keyLabelSecondary)
        }
        showCategory(selected)
    }

    /**
     * P7 (#19): on dismissal, fall back to the first category (黄脸) scrolled to the top, so reopening the
     * emoji panel never resumes on the last category / scroll position.
     */
    override fun resetToDefault() {
        showCategory(0)
        gridScroll.scrollTo(0, 0)
        railScroll.scrollTo(0, 0)
    }

    // P7 test seams.
    internal fun selectedCategoryForTest(): Int = selected
    internal fun openCategoryForTest(index: Int) = showCategory(index)

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
        for (e in EmojiCatalog.categories[index].emoji) grid.addView(emojiCell(e))
    }

    private fun railTab(index: Int, title: String): TextView = TextView(context).apply {
        text = title
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(0, dp(13), 0, dp(13))
        isClickable = true
        setOnClickListener { showCategory(index) }
    }

    private fun emojiCell(emoji: String): TextView = TextView(context).apply {
        text = emoji
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.display)
        val p = dp(8)
        setPadding(0, p, 0, p)
        isClickable = true
        setOnClickListener { onEmoji(emoji) }
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setGravity(Gravity.FILL_HORIZONTAL)
        }
    }

    private fun bottomBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(palette.keyboardBg) // P-A: same as the unified floor
        addView(barButton("返回") { onBack() }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(barButton("⌫") { onBackspace() }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.keyLabelSecondary)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private companion object {
        const val COLUMNS = 7
    }
}
