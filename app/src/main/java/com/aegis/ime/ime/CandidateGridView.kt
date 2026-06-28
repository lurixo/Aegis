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
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType

class CandidateGridView(context: Context) : LinearLayout(context) {

    var onPick: (Int) -> Unit = {}
    var onPickReading: (Int) -> Unit = {}
    var onClose: () -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onClear: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT
    private val readingColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val gridColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val rightColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val measurePaint = Paint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.title, resources.displayMetrics)
    }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(palette.keyboardBg)

        readingColumn.setBackgroundColor(palette.railBg)
        addView(
            ScrollView(context).apply { addView(readingColumn) },
            LayoutParams(dp(60), LayoutParams.MATCH_PARENT),
        )
        addView(
            ScrollView(context).apply { addView(gridColumn) },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        rightColumn.setBackgroundColor(palette.keyboardBg)
        rightColumn.addView(funcButton("返回") { onClose() }, funcLp())
        rightColumn.addView(funcButton("⌫") { onBackspace() }, funcLp())
        rightColumn.addView(funcButton("重输") { onClear() }, funcLp())
        addView(rightColumn, LayoutParams(dp(64), LayoutParams.MATCH_PARENT))
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        readingColumn.setBackgroundColor(p.railBg)
        rightColumn.setBackgroundColor(p.keyboardBg)
        for (i in 0 until rightColumn.childCount) (rightColumn.getChildAt(i) as? TextView)?.setTextColor(p.keyLabelSecondary)
        for (i in 0 until readingColumn.childCount) (readingColumn.getChildAt(i) as? TextView)?.setTextColor(p.preeditText)
    }

    private fun funcLp() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)

    private fun funcButton(label: String, onClick: () -> Unit): View = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.keyLabelSecondary)
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
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
                    setTextColor(palette.preeditText)
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
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
        setTextColor(palette.candidateText)
        isClickable = true
        setOnClickListener { onPick(index) }
    }
}
