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

class CandidateGridView(context: Context) : LinearLayout(context) {

    var onPick: (Int) -> Unit = {}
    var onPickReading: (Int) -> Unit = {}
    var onClose: () -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onClear: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val readingColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val gridColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val measurePaint = Paint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
    }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(0xFFF7F8FA.toInt())

        readingColumn.setBackgroundColor(0xFFEFF1F5.toInt())
        addView(
            ScrollView(context).apply { addView(readingColumn) },
            LayoutParams(dp(60), LayoutParams.MATCH_PARENT),
        )
        addView(
            ScrollView(context).apply { addView(gridColumn) },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        val right = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(0xFFE6E9ED.toInt())
            addView(funcButton("返回") { onClose() }, funcLp())
            addView(funcButton("⌫") { onBackspace() }, funcLp())
            addView(funcButton("重输") { onClear() }, funcLp())
        }
        addView(right, LayoutParams(dp(64), LayoutParams.MATCH_PARENT))
    }

    private fun funcLp() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)

    private fun funcButton(label: String, onClick: () -> Unit): View = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(0xFF37474F.toInt())
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun setReadings(readings: List<String>) {
        readingColumn.removeAllViews()
        for ((i, r) in readings.withIndex()) {
            readingColumn.addView(
                TextView(context).apply {
                    text = r
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    setTextColor(0xFF1565C0.toInt())
                    isClickable = true
                    setOnClickListener { onPickReading(i) }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
    }

    fun setCandidates(candidates: List<String>) {
        gridColumn.removeAllViews()
        val maxRowW = resources.displayMetrics.widthPixels - dp(60 + 64 + 16)
        val cellPad = dp(14)
        var row = newRow()
        var rowW = 0
        for ((i, c) in candidates.withIndex()) {
            val cellW = (measurePaint.measureText(c) + cellPad * 2).toInt()
            if (rowW + cellW > maxRowW && row.childCount > 0) {
                gridColumn.addView(row)
                row = newRow(); rowW = 0
            }
            row.addView(chip(c, i), LayoutParams(cellW, dp(46)))
            rowW += cellW
        }
        if (row.childCount > 0) gridColumn.addView(row)
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
