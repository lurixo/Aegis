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
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import kotlin.math.abs

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
    private val rightColumn = FrameLayout(context)
    private val backspaceGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }
    private val measurePaint = Paint().apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.title, resources.displayMetrics)
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var renderedCandidates: List<String>? = null
    private var renderedCandidateWidth = 0
    private var renderedReadings: List<String>? = null
    private var renderedSelected = Int.MIN_VALUE
    private var candidateRebuilds = 0
    private var readingRebuilds = 0

    init {
        orientation = HORIZONTAL
        setBackgroundColor(palette.keyboardBg)

        readingColumn.setBackgroundColor(palette.keyboardBg)
        addView(
            ScrollView(context).apply { addView(readingColumn) },
            LayoutParams(dp(60), LayoutParams.MATCH_PARENT),
        )
        addView(
            ScrollView(context).apply { addView(gridColumn) },
            LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
        )
        rightColumn.setBackgroundColor(palette.keyboardBg)
        rightColumn.addView(funcButton("返回") { onClose() }, rowAlignedLp(0))
        rightColumn.addView(
            backspaceButton().apply {
                setCompoundDrawablesWithIntrinsicBounds(null, backspaceGlyph, null, null)
                backspaceGlyph.tint(palette.keyLabelSecondary)
            },
            centeredLp(),
        )
        rightColumn.addView(funcButton("重输") { onClear() }, rowAlignedLp(4))
        addView(rightColumn, LayoutParams(dp(64), LayoutParams.MATCH_PARENT))
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        readingColumn.setBackgroundColor(p.keyboardBg)
        rightColumn.setBackgroundColor(p.keyboardBg)
        for (i in 0 until rightColumn.childCount) (rightColumn.getChildAt(i) as? TextView)?.setTextColor(p.keyLabelSecondary)
        backspaceGlyph.tint(p.keyLabelSecondary)
        for (i in 0 until readingColumn.childCount) (readingColumn.getChildAt(i) as? TextView)?.setTextColor(p.preeditText)
        renderedCandidates = null
        renderedReadings = null
    }

    private fun rowAlignedLp(rowIndex: Int) =
        FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46), Gravity.TOP).apply { topMargin = dp(46 * rowIndex) }

    private fun centeredLp() = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46), Gravity.CENTER)

    private fun funcButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.keyLabelSecondary)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun backspaceButton(): TextView {
        var downX = 0f
        var downY = 0f
        return funcButton("") { onBackspace() }.apply {
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val upSwipe = downY - e.y > touchSlop && abs(e.x - downX) <= dp(28)
                        if (upSwipe) {
                            onClear()
                        } else {
                            v.performClick()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
    }

    fun setReadings(readings: List<String>, selected: Int = -1) {
        if (readings == renderedReadings && selected == renderedSelected) return
        renderedReadings = readings.toList()
        renderedSelected = selected
        readingRebuilds++
        readingColumn.removeAllViews()
        for ((i, r) in readings.withIndex()) {
            val on = i == selected
            readingColumn.addView(
                TextView(context).apply {
                    text = r
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
                    setTextColor(if (on) palette.candidateFirst else palette.preeditText)
                    setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    isClickable = true
                    setOnClickListener { onPickReading(i) }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }
    }

    fun setCandidates(candidates: List<String>) {
        val maxRowW = resources.displayMetrics.widthPixels - dp(60 + 64 + 16)
        if (candidates == renderedCandidates && maxRowW == renderedCandidateWidth) return
        renderedCandidates = candidates.toList()
        renderedCandidateWidth = maxRowW
        candidateRebuilds++
        gridColumn.removeAllViews()
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

    internal fun candidateRebuildsForTest(): Int = candidateRebuilds
    internal fun readingRebuildsForTest(): Int = readingRebuilds
    internal fun returnButtonForTest(): TextView = rightColumn.getChildAt(0) as TextView
    internal fun backspaceButtonForTest(): TextView = rightColumn.getChildAt(1) as TextView
    internal fun clearButtonForTest(): TextView = rightColumn.getChildAt(2) as TextView
    internal fun selectedReadingBackgroundForTest(index: Int): Drawable? =
        (readingColumn.getChildAt(index) as? TextView)?.background

    private class IconDrawable(
        private val density: Float,
        private val sFactor: Float,
        private val render: (Canvas, Paint, Float, Float, Float) -> Unit,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun tint(color: Int) { paint.color = color; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            val b = bounds
            render(canvas, paint, b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) * sFactor)
        }
        override fun getIntrinsicWidth() = (22 * density).toInt()
        override fun getIntrinsicHeight() = (22 * density).toInt()
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
