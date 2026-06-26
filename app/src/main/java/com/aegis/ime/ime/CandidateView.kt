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
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Toolbar shortcuts shown on the idle candidate strip. */
enum class BarFunction(val glyph: String) {
    SETTINGS("⚙"), SWITCH_KBD("⌨"), EMOJI("☺"), EDIT("✎"), CLIPBOARD("📋"), NUMPAD("123")
}

/**
 * Candidate row above the keyboard. While composing it shows candidates — horizontally scrollable
 * (#3/C4), first candidate green (C2), with a fixed ⌄ at the right that opens the full grid (#3/C3,
 * via [onExpand]). When idle it shows the function toolbar (tap → [onFunction]). The pinyin preedit
 * lives in the separate [PreeditView] tab above, not here (C1).
 */
class CandidateView(context: Context) : View(context) {

    var onPick: (Int) -> Unit = {}
    var onFunction: (BarFunction) -> Unit = {}
    var onExpand: () -> Unit = {}
    var onCollapse: () -> Unit = {}

    private var items: List<String> = emptyList()
    private var composing: String = ""
    private val hitRects = ArrayList<RectF>() // content-space cell rects (reused pool)
    private var hitCount = 0
    private var contentWidth = 0f
    private var scrollX = 0f

    private val functions = BarFunction.entries
    private val funcRects = ArrayList<RectF>().also { l -> repeat(functions.size) { l.add(RectF()) } }
    private var showingFunctions = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downScroll = 0f
    private var dragging = false

    private fun hitRect(i: Int): RectF {
        while (hitRects.size <= i) hitRects.add(RectF())
        return hitRects[i]
    }

    private val density = resources.displayMetrics.density
    private val padding = 14f * density
    private val expandW = 40f * density

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202124.toInt()
        textSize = sp(18f)
    }
    private val firstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E7D32.toInt() // green highlight for the top candidate (C2)
        textSize = sp(18f)
    }
    private val funcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF455A64.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(18f)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0BEC5.toInt()
        textSize = sp(15f)
    }
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD5DADF.toInt() }
    private val expandBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F4F6.toInt() }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF607D8B.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(18f)
    }

    fun setContent(candidates: List<String>, composingText: String) {
        items = candidates
        composing = composingText
        scrollX = 0f
        layoutCells()
        invalidate()
    }

    private fun layoutCells() {
        hitCount = items.size
        var x = padding
        for ((i, item) in items.withIndex()) {
            val cellW = textPaint.measureText(item) + padding * 2
            hitRect(i).set(x, 0f, x + cellW, 0f) // bottom filled at draw (needs height)
            x += cellW
        }
        contentWidth = x
    }

    private fun maxScroll(): Float = maxOf(0f, contentWidth - (width - expandW))

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFFF2F4F6.toInt())
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2

        if (items.isEmpty()) {
            showingFunctions = composing.isEmpty()
            if (showingFunctions) drawFunctions(canvas, baseline)
            return
        }
        showingFunctions = false
        scrollX = scrollX.coerceIn(0f, maxScroll())

        // Candidates: clipped to the left of the fixed expand button, translated by the scroll offset.
        val visibleW = width - expandW
        canvas.save()
        canvas.clipRect(0f, 0f, visibleW, height.toFloat())
        for (i in 0 until hitCount) {
            val r = hitRects[i]
            r.bottom = height.toFloat()
            val left = r.left - scrollX
            canvas.drawText(items[i], left + padding, baseline, if (i == 0) firstPaint else textPaint)
            if (i != hitCount - 1) {
                canvas.drawRect(r.right - scrollX, height * 0.25f, r.right - scrollX + density, height * 0.75f, sepPaint)
            }
        }
        canvas.restore()

        // Fixed expand affordance at the right edge.
        canvas.drawRect(visibleW, 0f, width.toFloat(), height.toFloat(), expandBgPaint)
        canvas.drawRect(visibleW, height * 0.2f, visibleW + density, height * 0.8f, sepPaint)
        canvas.drawText("⌄", visibleW + expandW / 2f, baseline, chevronPaint)
    }

    private fun drawFunctions(canvas: Canvas, baseline: Float) {
        val cellW = 48f * density
        for ((i, f) in functions.withIndex()) {
            val left = i * cellW
            funcRects[i].set(left, 0f, left + cellW, height.toFloat())
            canvas.drawText(f.glyph, left + cellW / 2f, baseline, funcPaint)
        }
        // Right-edge collapse affordance (dual of the candidate expand ⌄).
        val visibleW = width - expandW
        canvas.drawRect(visibleW, height * 0.2f, visibleW + density, height * 0.8f, sepPaint)
        canvas.drawText("⌄", visibleW + expandW / 2f, baseline, chevronPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downScroll = scrollX
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!showingFunctions && items.isNotEmpty()) {
                    val dx = event.x - downX
                    if (!dragging && abs(dx) > touchSlop) dragging = true
                    if (dragging) {
                        scrollX = (downScroll - dx).coerceIn(0f, maxScroll())
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) { dragging = false; return true }
                if (showingFunctions) {
                    if (event.x >= width - expandW) { performClick(); onCollapse(); return true }
                    funcRects.indexOfFirst { it.contains(event.x, event.y) }
                        .takeIf { it >= 0 }?.let { performClick(); onFunction(functions[it]) }
                    return true
                }
                if (items.isNotEmpty() && event.x >= width - expandW) {
                    performClick(); onExpand(); return true
                }
                val cx = event.x + scrollX // screen → content coordinate
                for (i in 0 until hitCount) {
                    val r = hitRects[i]
                    if (cx >= r.left && cx < r.right) { performClick(); onPick(i); break }
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
