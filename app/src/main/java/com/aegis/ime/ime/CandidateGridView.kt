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
import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Expanded candidate grid (C3): the full candidate list wrapped over multiple rows when
 * the user taps ⌄. Tapping a candidate reports its index via [onPick]; ⌃ collapses via [onClose].
 */
class CandidateGridView(context: Context) : LinearLayout(context) {

    var onPick: (Int) -> Unit = {}
    var onClose: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val rowsColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val measurePaint = Paint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFFF7F8FA.toInt())
        val bar = LinearLayout(context).apply {
            setBackgroundColor(0xFFE6E9ED.toInt())
            gravity = Gravity.CENTER_VERTICAL
            val collapse = TextView(context).apply {
                text = "⌃ 收起"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                isClickable = true
                setOnClickListener { onClose() }
            }
            addView(collapse, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { marginStart = dp(12) })
        }
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
        addView(ScrollView(context).apply { addView(rowsColumn) }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Rebuild the grid for [candidates], wrapping greedily to the screen width. */
    fun setCandidates(candidates: List<String>) {
        rowsColumn.removeAllViews()
        val maxRowW = resources.displayMetrics.widthPixels - dp(16)
        val cellPad = dp(14)
        var row = newRow()
        var rowW = 0
        for ((i, c) in candidates.withIndex()) {
            val cellW = (measurePaint.measureText(c) + cellPad * 2).toInt()
            if (rowW + cellW > maxRowW && row.childCount > 0) {
                rowsColumn.addView(row)
                row = newRow(); rowW = 0
            }
            row.addView(chip(c, i), LayoutParams(cellW, dp(46)))
            rowW += cellW
        }
        if (row.childCount > 0) rowsColumn.addView(row)
    }

    private fun newRow(): LinearLayout = LinearLayout(context).apply { orientation = HORIZONTAL }

    private fun chip(text: String, index: Int): View = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(0xFF202124.toInt())
        isClickable = true
        setOnClickListener { onPick(index) }
    }
}
