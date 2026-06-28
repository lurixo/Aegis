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

import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.ime.theme.ImeShapes
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.layout.SymbolCatalog

/**
 * 自定义 punctuation panel (A3): edit the user's custom 9-key marks one symbol at a time ("逐符可定义").
 * Tap a palette symbol to ADD it to the column; tap an added symbol (✕) to REMOVE it. The host wires
 * [onAdd]/[onRemove] to the persistent store and [current] to read it back; [onBack] closes the panel.
 */
class CustomSymbolPanel(context: Context) : LinearLayout(context), ResettablePanel {

    var current: () -> List<String> = { emptyList() }
    var onAdd: (String) -> Unit = {}
    var onRemove: (String) -> Unit = {}
    var onPaste: () -> Unit = {} // U13: add a symbol from the system clipboard (one aegis doesn't ship)
    var onBack: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private var colors = ImePalette.STATIC_LIGHT
    private val addedRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val paletteRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val addedScroll = ScrollView(context).apply { addView(addedRows) }
    private val paletteScroll = ScrollView(context).apply { addView(paletteRows) }
    private val headerBar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val backText = TextView(context).apply {
        text = "‹ 自定义标点"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        setTextColor(colors.keyLabel)
    }
    private val pasteText = TextView(context).apply {
        text = "📋 粘贴符号"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        setTextColor(colors.keyLabel)
    }
    private val sectionLabels = mutableListOf<TextView>()

    /** Header title — defaults to the A3 punctuation panel; the I2 operator panel overrides it ("‹ 自定义运算符"). */
    var backTitle: String = "‹ 自定义标点"
        set(v) { field = v; backText.text = v }
    /** Paste-button label — overridden by the operator panel ("📋 粘贴运算符"). */
    var pasteLabel: String = "📋 粘贴符号"
        set(v) { field = v; pasteText.text = v }

    /** The add-suggestion palette: defaults to the full [SymbolCatalog] (punctuation); the operator panel
     *  supplies an operator-only set so it never offers URL/ordinal tokens or the built-in defaults. */
    var addPalette: List<String> = SymbolCatalog.categories.flatMap { it.symbols }.distinct()
        set(v) { field = v; refresh() }

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.keyboardBg) // P-A: panel floor == the strip/keyboard floor (no top seam)
        backText.setOnClickListener { onBack() }
        pasteText.setOnClickListener { onPaste() }
        headerBar.setBackgroundColor(colors.keyboardBg) // P-A: 返回 row on the unified floor
        headerBar.addView(backText)
        headerBar.addView(View(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headerBar.addView(pasteText)
        addView(headerBar)
        addView(sectionLabel("已添加（点击移除）"))
        addView(addedScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        addView(sectionLabel("点击添加"))
        addView(paletteScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** P7 (#19): on dismissal, scroll both columns back to the top so reopening starts at the first marks
     *  rather than the user's last scroll position. (Added/palette contents are data, refreshed on open.) */
    override fun resetToDefault() {
        addedScroll.scrollTo(0, 0)
        paletteScroll.scrollTo(0, 0)
    }

    /** F1: recolour from the Monet palette (chips re-coloured by the following refresh()). */
    fun applyPalette(p: ImePalette) {
        colors = p
        setBackgroundColor(p.keyboardBg) // P-A: see init
        headerBar.setBackgroundColor(p.keyboardBg) // P-A: 返回 row on the unified floor
        backText.setTextColor(p.keyLabel)
        pasteText.setTextColor(p.keyLabel)
        sectionLabels.forEach { it.setTextColor(p.keyLabelSecondary) }
        refresh()
    }

    private fun sectionLabel(text: String): View = TextView(context).apply {
        this.text = text
        setTextColor(colors.keyLabelSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.caption)
        setPadding(dp(12), dp(6), dp(12), dp(4))
        sectionLabels.add(this)
    }

    /** Rebuild both sections from the live store + palette. */
    fun refresh() {
        val added = current()
        fillFlow(addedRows, added) { sym -> chip("$sym ✕", removable = true) { onRemove(sym) } }
        // Palette excludes already-added marks so each can be added once.
        fillFlow(paletteRows, addPalette.filter { it !in added }) { sym -> chip(sym, removable = false) { onAdd(sym) } }
    }

    private fun fillFlow(container: LinearLayout, items: List<String>, make: (String) -> View) {
        container.removeAllViews()
        val maxRowW = resources.displayMetrics.widthPixels - dp(16)
        var row = newRow(); var rowW = 0
        val cellW = dp(56)
        for (sym in items) {
            if (rowW + cellW > maxRowW && row.childCount > 0) { container.addView(row); row = newRow(); rowW = 0 }
            row.addView(make(sym), LayoutParams(cellW, dp(44)).apply { marginEnd = dp(4); topMargin = dp(4) })
            rowW += cellW + dp(4)
        }
        if (row.childCount > 0) container.addView(row)
    }

    private fun newRow(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun chip(label: String, removable: Boolean, onClick: () -> Unit): View = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (removable) ImeType.body else ImeType.title)
        setTextColor(if (removable) colors.deletable else colors.keyLabel)
        background = GradientDrawable().apply { setColor(this@CustomSymbolPanel.colors.keySurface); cornerRadius = ImeShapes.keyRadiusDp * density } // U-polish: round like every other tile
        isClickable = true
        setOnClickListener { onClick() }
    }
}
