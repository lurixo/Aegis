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

class CustomSymbolPanel(context: Context) : LinearLayout(context), ResettablePanel {

    var current: () -> List<String> = { emptyList() }
    var onAdd: (String) -> Unit = {}
    var onRemove: (String) -> Unit = {}
    var onPaste: () -> Unit = {}
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

    var backTitle: String = "‹ 自定义标点"
        set(v) { field = v; backText.text = v }
    var pasteLabel: String = "📋 粘贴符号"
        set(v) { field = v; pasteText.text = v }

    var addPalette: List<String> = SymbolCatalog.categories.flatMap { it.symbols }.distinct()
        set(v) { field = v; refresh() }

    init {
        orientation = VERTICAL
        setBackgroundColor(colors.panelBg)
        backText.setOnClickListener { onBack() }
        pasteText.setOnClickListener { onPaste() }
        headerBar.setBackgroundColor(colors.panelBg)
        headerBar.addView(backText)
        headerBar.addView(View(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headerBar.addView(pasteText)
        addView(headerBar)
        addView(sectionLabel("已添加（点击移除）"))
        addView(addedScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        addView(sectionLabel("点击添加"))
        addView(paletteScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun resetToDefault() {
        addedScroll.scrollTo(0, 0)
        paletteScroll.scrollTo(0, 0)
    }

    fun applyPalette(p: ImePalette) {
        colors = p
        setBackgroundColor(p.panelBg)
        headerBar.setBackgroundColor(p.panelBg)
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

    fun refresh() {
        val added = current()
        fillFlow(addedRows, added) { sym -> chip("$sym ✕", removable = true) { onRemove(sym) } }
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
        background = GradientDrawable().apply { setColor(this@CustomSymbolPanel.colors.keySurface); cornerRadius = ImeShapes.keyRadiusDp * density }
        isClickable = true
        setOnClickListener { onClick() }
    }
}
