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
    private val collapseRect = RectF()
    private var showingFunctions = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downScroll = 0f
    private var dragging = false

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // soft shadow for the floating toolbar capsule

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

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202124.toInt()
        textSize = sp(18f)
    }
    private val firstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2E7D32.toInt() // green highlight for the top candidate (C2)
        textSize = sp(18f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF455A64.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val icon123Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF455A64.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(14f)
    }
    private val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD5DADF.toInt() }
    private val expandBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9ECF1.toInt() }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF607D8B.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(18f)
    }
    // Leading brand badge in slot 1. DEVIATION: the brand
    // glyph is an "S"; we ship our own Aegis "A" so we are not stamping a third-party mark.
    private val brandTilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF57A35B.toInt() }
    private val brandTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textSize = sp(13f)
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
        canvas.drawColor(0xFFE9ECF1.toInt())
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

    /** Floating rounded-capsule toolbar with self-drawn linear icons. */
    private fun drawFunctions(canvas: Canvas, baseline: Float) {
        val capL = capMarginH
        val capR = width - capMarginH
        val capT = capMarginV
        val capB = height - capMarginV
        val rad = (capB - capT) / 2f
        capsulePaint.setShadowLayer(6f * density, 0f, 2f * density, 0x22000000)
        canvas.drawRoundRect(capL, capT, capR, capB, rad, rad, capsulePaint)
        capsulePaint.clearShadowLayer()

        val cy = (capT + capB) / 2f
        val collapseW = 34f * density
        val areaL = capL + 10f * density
        val areaR = capR - collapseW
        val slot = (areaR - areaL) / functions.size
        val s = 9f * density
        for ((i, f) in functions.withIndex()) {
            val cx = areaL + slot * (i + 0.5f)
            funcRects[i].set(areaL + slot * i, capT, areaL + slot * (i + 1), capB)
            drawIcon(canvas, f, cx, cy, s)
        }
        collapseRect.set(areaR, capT, capR, capB)
        // thin "|" divider before the collapse chevron.
        canvas.drawRect(areaR, cy - s * 0.8f, areaR + density, cy + s * 0.8f, sepPaint)
        drawChevronDown(canvas, capR - collapseW / 2f, cy, s)
    }

    /** Dispatch to a self-drawn linear icon for each toolbar function (no font assets). */
    private fun drawIcon(c: Canvas, f: BarFunction, cx: Float, cy: Float, s: Float) {
        when (f) {
            BarFunction.SETTINGS -> drawBrand(c, cx, cy, s) // leading brand mark
            BarFunction.SWITCH_KBD -> { // keyboard outline + key rows
                val w = s * 0.95f; val h = s * 0.62f
                c.drawRoundRect(cx - w, cy - h, cx + w, cy + h, 3f * density, 3f * density, iconPaint)
                c.drawLine(cx - w * 0.55f, cy - h * 0.25f, cx + w * 0.55f, cy - h * 0.25f, iconPaint)
                c.drawLine(cx - w * 0.55f, cy + h * 0.25f, cx + w * 0.2f, cy + h * 0.25f, iconPaint)
            }
            BarFunction.EMOJI -> { // smiley
                c.drawCircle(cx, cy, s * 0.7f, iconPaint)
                val eye = 1.4f * density
                iconPaint.style = Paint.Style.FILL
                c.drawCircle(cx - s * 0.28f, cy - s * 0.15f, eye, iconPaint)
                c.drawCircle(cx + s * 0.28f, cy - s * 0.15f, eye, iconPaint)
                iconPaint.style = Paint.Style.STROKE
                c.drawArc(cx - s * 0.4f, cy - s * 0.1f, cx + s * 0.4f, cy + s * 0.35f, 20f, 140f, false, iconPaint)
            }
            BarFunction.EDIT -> { // text I-beam cursor ⟨I⟩
                c.drawLine(cx, cy - s * 0.75f, cx, cy + s * 0.75f, iconPaint)
                c.drawLine(cx - s * 0.32f, cy - s * 0.75f, cx + s * 0.32f, cy - s * 0.75f, iconPaint)
                c.drawLine(cx - s * 0.32f, cy + s * 0.75f, cx + s * 0.32f, cy + s * 0.75f, iconPaint)
            }
            // Slots 5/6: clipboard history + numeric pad, drawn as designed linear icons.
            BarFunction.CLIPBOARD -> { // clipboard board + clip + lines
                val w = s * 0.55f; val h = s * 0.78f
                c.drawRoundRect(cx - w, cy - h + s * 0.18f, cx + w, cy + h, 2f * density, 2f * density, iconPaint)
                c.drawRoundRect(cx - s * 0.26f, cy - h - s * 0.02f, cx + s * 0.26f, cy - h + s * 0.28f, 1.5f * density, 1.5f * density, iconPaint)
                c.drawLine(cx - w * 0.5f, cy - h * 0.1f, cx + w * 0.5f, cy - h * 0.1f, iconPaint)
                c.drawLine(cx - w * 0.5f, cy + h * 0.3f, cx + w * 0.5f, cy + h * 0.3f, iconPaint)
            }
            BarFunction.NUMPAD -> // numeric label
                c.drawText("123", cx, cy - (icon123Paint.descent() + icon123Paint.ascent()) / 2, icon123Paint)
        }
    }

    /** Leading brand badge: a rounded-square tile with the Aegis "A" (see [brandTilePaint] note). */
    private fun drawBrand(c: Canvas, cx: Float, cy: Float, s: Float) {
        val half = s * 0.95f
        val r = 4f * density
        c.drawRoundRect(cx - half, cy - half, cx + half, cy + half, r, r, brandTilePaint)
        c.drawText("A", cx, cy - (brandTextPaint.descent() + brandTextPaint.ascent()) / 2, brandTextPaint)
    }

    private fun drawChevronDown(c: Canvas, cx: Float, cy: Float, s: Float) {
        c.drawLine(cx - s * 0.5f, cy - s * 0.2f, cx, cy + s * 0.28f, iconPaint)
        c.drawLine(cx, cy + s * 0.28f, cx + s * 0.5f, cy - s * 0.2f, iconPaint)
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
                    if (collapseRect.contains(event.x, event.y)) { performClick(); onCollapse(); return true }
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
