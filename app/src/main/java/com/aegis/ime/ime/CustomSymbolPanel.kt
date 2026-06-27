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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 自定义 punctuation panel (A3): edit the user's custom 9-key marks one symbol at a time ("逐符可定义").
 * Tap a palette symbol to ADD it to the column; tap an added symbol (✕) to REMOVE it. The host wires
 * [onAdd]/[onRemove] to the persistent store and [current] to read it back; [onBack] closes the panel.
 */
class CustomSymbolPanel(context: Context) : LinearLayout(context) {

    var current: () -> List<String> = { emptyList() }
    var onAdd: (String) -> Unit = {}
    var onRemove: (String) -> Unit = {}
    var onBack: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val addedRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val paletteRows = LinearLayout(context).apply { orientation = VERTICAL }

    /** A broad set of extra marks the user can promote into their column (not already in the fixed list). */
    private val palette = listOf(
        "、", "·", "—", "～", "‘", "’", "“", "”", "（", "）", "《", "》", "【", "】", "「", "」",
        "%", "&", "*", "#", "/", "\\", "|", "+", "=", "<", ">", "^", "￥", "$", "€", "°",
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFFF2F4F7.toInt())
        addView(header())
        addView(sectionLabel("已添加（点击移除）"))
        addView(ScrollView(context).apply { addView(addedRows) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        addView(sectionLabel("点击添加"))
        addView(ScrollView(context).apply { addView(paletteRows) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun header(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xFFE6E9ED.toInt())
        val back = TextView(context).apply {
            text = "‹ 自定义标点"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { onBack() }
        }
        addView(back)
    }

    private fun sectionLabel(text: String): View = TextView(context).apply {
        this.text = text
        setTextColor(0xFF78909C.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(12), dp(6), dp(12), dp(4))
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
        setTextColor(if (removable) 0xFFD32F2F.toInt() else 0xFF202124.toInt())
        setBackgroundColor(0xFFFFFFFF.toInt())
        isClickable = true
        setOnClickListener { onClick() }
    }
}
