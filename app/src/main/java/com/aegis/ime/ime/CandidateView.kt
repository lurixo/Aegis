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
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import kotlin.math.abs

enum class BarFunction { BRAND, EMOJI, EDIT, CLIPBOARD }

class CandidateView(context: Context) : View(context) {

    var onPick: (Int) -> Unit = {}
    var onFunction: (BarFunction) -> Unit = {}
    var onExpand: () -> Unit = {}
    var onCollapse: () -> Unit = {}
    var onCollapseExpanded: () -> Unit = {}

    private var items: List<String> = emptyList()
    private var composing: String = ""
    private var expanded = false
    private val hitRects = ArrayList<RectF>()
    private var hitCount = 0
    private var contentWidth = 0f
    private var scrollX = 0f

    private val functions = BarFunction.entries
    private val funcRects = ArrayList<RectF>().also { l -> repeat(functions.size) { l.add(RectF()) } }
    private val collapseRect = RectF()
    private var showingFunctions = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downScroll = 0f
    private var dragging = false
    private val fling = FlingScroller(context)

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    private fun hitRect(i: Int): RectF {
        while (hitRects.size <= i) hitRects.add(RectF())
        return hitRects[i]
    }

    private val density = resources.displayMetrics.density
    private val padding = 14f * density
    private val expandW = 40f * density
    private val capMarginH = 8f * density
    private val capMarginV = 5f * density

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private var palette = ImePalette.STATIC_LIGHT

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.candidateText
        textSize = sp(18f)
    }
    private val firstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.candidateFirst
        textSize = sp(18f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.icon
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keySurface }
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.separator }
    private val expandBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.railBg }

    fun applyPalette(p: ImePalette) {
        palette = p
        textPaint.color = p.candidateText
        firstPaint.color = p.candidateFirst
        iconPaint.color = p.icon
        capsulePaint.color = p.keySurface
        sepPaint.color = p.separator
        expandBgPaint.color = p.railBg
        invalidate()
    }

    fun setContent(candidates: List<String>, composingText: String) {
        items = candidates
        composing = composingText
        scrollX = 0f
        layoutCells()
        invalidate()
    }

    internal fun itemCount(): Int = items.size

    fun setExpanded(value: Boolean) {
        if (value == expanded) return
        expanded = value
        invalidate()
    }

    internal fun chevronGlyph(): String = if (expanded) "⌃" else "⌄"

    internal fun scrollXForTest(): Float = scrollX
    internal fun maxScrollForTest(): Float = maxScroll()
    internal fun isFlingingForTest(): Boolean = !fling.isFinished
    internal fun flingVelocityForTest(): Float = fling.velocity()

    private fun layoutCells() {
        hitCount = items.size
        var x = 0f
        for ((i, item) in items.withIndex()) {
            val cellW = (if (i == 0) firstPaint else textPaint).measureText(item) + padding * 2
            hitRect(i).set(x, 0f, x + cellW, 0f)
            x += cellW
        }
        contentWidth = x
    }

    private fun maxScroll(): Float = maxOf(0f, contentWidth - (width - expandW))

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.keyboardBg)
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2

        if (items.isEmpty()) {
            showingFunctions = composing.isEmpty()
            if (showingFunctions) drawFunctions(canvas, baseline)
            return
        }
        showingFunctions = false
        scrollX = scrollX.coerceIn(0f, maxScroll())

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

        canvas.drawRect(visibleW, 0f, width.toFloat(), height.toFloat(), expandBgPaint)
        canvas.drawRect(visibleW, height * 0.25f, visibleW + density, height * 0.75f, sepPaint)
        val chCx = visibleW + expandW / 2f; val chCy = height / 2f; val chS = 9f * density
        if (expanded) drawChevronUp(canvas, chCx, chCy, chS) else drawChevronDown(canvas, chCx, chCy, chS)
    }

    private fun drawFunctions(canvas: Canvas, baseline: Float) {
        val capL = capMarginH
        val capR = width - capMarginH
        val capT = capMarginV
        val capB = height - capMarginV
        val rad = minOf((capB - capT) / 2f, ImeShapes.toolbarPillRadiusDp * density)
        capsulePaint.setShadowLayer(6f * density, 0f, 2f * density, palette.shadow)
        canvas.drawRoundRect(capL, capT, capR, capB, rad, rad, capsulePaint)
        capsulePaint.clearShadowLayer()

        val cy = (capT + capB) / 2f
        val edgePad = 10f * density
        val collapseW = expandW
        val areaL = capL + edgePad
        val collapseL = capR - edgePad - collapseW
        val areaR = collapseL
        val slot = (areaR - areaL) / functions.size
        val s = 9f * density
        for ((i, f) in functions.withIndex()) {
            val cx = areaL + slot * (i + 0.5f)
            funcRects[i].set(areaL + slot * i, capT, areaL + slot * (i + 1), capB)
            drawIcon(canvas, f, cx, cy, s)
        }
        collapseRect.set(collapseL, capT, capR - edgePad, capB)
        val sepH = (capB - capT) * 0.25f
        canvas.drawRect(collapseL, cy - sepH, collapseL + density, cy + sepH, sepPaint)
        drawChevronDown(canvas, collapseL + collapseW / 2f, cy, s)
    }

    private fun drawIcon(c: Canvas, f: BarFunction, cx: Float, cy: Float, s: Float) {
        when (f) {
            BarFunction.BRAND -> Glyphs.drawBrandA(c, iconPaint, cx, cy, s)
            BarFunction.EMOJI -> Glyphs.drawEmoji(c, iconPaint, cx, cy, s)
            BarFunction.EDIT -> Glyphs.drawEditCaret(c, iconPaint, cx, cy, s)
            BarFunction.CLIPBOARD -> Glyphs.drawClipboard(c, iconPaint, cx, cy, s)
        }
    }

    private fun drawChevronDown(c: Canvas, cx: Float, cy: Float, s: Float) {
        c.drawLine(cx - s * 0.5f, cy - s * 0.2f, cx, cy + s * 0.28f, iconPaint)
        c.drawLine(cx, cy + s * 0.28f, cx + s * 0.5f, cy - s * 0.2f, iconPaint)
    }

    private fun drawChevronUp(c: Canvas, cx: Float, cy: Float, s: Float) {
        c.drawLine(cx - s * 0.5f, cy + s * 0.2f, cx, cy - s * 0.28f, iconPaint)
        c.drawLine(cx, cy - s * 0.28f, cx + s * 0.5f, cy + s * 0.2f, iconPaint)
    }

    override fun computeScroll() {
        fling.computeOffset()?.let { scrollX = it.coerceIn(0f, maxScroll()); postInvalidateOnAnimation() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downScroll = scrollX
                dragging = false
                fling.onDown()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!showingFunctions && items.isNotEmpty()) {
                    fling.addSample(event.eventTime, event.x)
                    val dx = event.x - downX
                    if (!dragging && abs(dx) > touchSlop) dragging = true
                    if (dragging) {
                        scrollX = (downScroll - dx).coerceIn(0f, maxScroll())
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) { dragging = false; if (fling.fling(scrollX, maxScroll())) postInvalidateOnAnimation(); return true }
                if (fling.stopArmed) return true
                if (showingFunctions) {
                    if (collapseRect.contains(event.x, event.y)) { performClick(); onCollapse(); return true }
                    funcRects.indexOfFirst { it.contains(event.x, event.y) }
                        .takeIf { it >= 0 }?.let { performClick(); onFunction(functions[it]) }
                    return true
                }
                if (items.isNotEmpty() && event.x >= width - expandW) {
                    performClick(); if (expanded) onCollapseExpanded() else onExpand(); return true
                }
                val cx = event.x + scrollX
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
