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
import android.content.Context
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
class CustomSymbolPanel(context: Context) : LinearLayout(context) {

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
    private val headerBar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val backText = TextView(context).apply {
        text = "‹ 自定义标点"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        setTextColor(0xFF202124.toInt())
    }
    private val pasteText = TextView(context).apply {
        text = "📋 粘贴符号"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        setTextColor(0xFF202124.toInt())
    }
    private val sectionLabels = mutableListOf<TextView>()

    /** U13: the full symbol set (every [SymbolCatalog] category, de-duplicated) so the user can promote ANY
     *  symbol into their column — plus the 粘贴 button for marks aegis doesn't ship. */
    private val palette = SymbolCatalog.categories.flatMap { it.symbols }.distinct()

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.panelBg)
        backText.setOnClickListener { onBack() }
        pasteText.setOnClickListener { onPaste() }
        headerBar.setBackgroundColor(colors.panelSubBg)
        headerBar.addView(backText)
        headerBar.addView(View(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headerBar.addView(pasteText)
        addView(headerBar)
        addView(sectionLabel("已添加（点击移除）"))
        addView(ScrollView(context).apply { addView(addedRows) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        addView(sectionLabel("点击添加"))
        addView(ScrollView(context).apply { addView(paletteRows) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** F1: recolour from the Monet palette (chips re-coloured by the following refresh()). */
    fun applyPalette(p: ImePalette) {
        colors = p
        setBackgroundColor(p.panelBg)
        headerBar.setBackgroundColor(p.panelSubBg)
        backText.setTextColor(p.keyLabel)
        pasteText.setTextColor(p.keyLabel)
        sectionLabels.forEach { it.setTextColor(p.keyLabelSecondary) }
        refresh()
    }

    private fun sectionLabel(text: String): View = TextView(context).apply {
        this.text = text
        setTextColor(colors.keyLabelSecondary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(12), dp(6), dp(12), dp(4))
        sectionLabels.add(this)
    }

    /** Rebuild both sections from the live store + palette. */
    fun refresh() {
        val added = current()
        fillFlow(addedRows, added) { sym -> chip("$sym ✕", removable = true) { onRemove(sym) } }
        // Palette excludes already-added marks so each can be added once.
        fillFlow(paletteRows, palette.filter { it !in added }) { sym -> chip(sym, removable = false) { onAdd(sym) } }
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
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (removable) 15f else 18f)
        setTextColor(if (removable) colors.deletable else colors.keyLabel)
        setBackgroundColor(colors.keySurface)
        isClickable = true
        setOnClickListener { onClick() }
    }
}
