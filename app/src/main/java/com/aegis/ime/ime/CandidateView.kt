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
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.aegis.ime.ime.theme.ImePalette
import kotlin.math.abs

/**
 * Toolbar shortcuts on the idle candidate strip (C2).
 * the bar keeps ONLY: A (brand/settings) · 表情 · 文字编辑 · 剪贴板·常用语 — plus the collapse ⌄ (separate).
 * The leftover ⌨ (9↔26 switch → the keyboard's startup setting) and 123 (numpad, still on the keyboards) are gone.
 */
enum class BarFunction(val glyph: String) {
    BRAND("A"), EMOJI("☺"), EDIT("✎"), CLIPBOARD("📋")
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
    var onCollapseExpanded: () -> Unit = {} // U14: collapse the A2 grid via the (now flipped) right chevron

    private var items: List<String> = emptyList()
    private var composing: String = ""
    // U14: true while the A2 expanded grid is open — flips the right chevron (⌄→⌃) and makes tapping it
    // collapse the grid instead of (re)expanding it.
    private var expanded = false
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

    // F1: Monet palette (default = static light = previous look); the IME service pushes the live one.
    private var palette = ImePalette.STATIC_LIGHT

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.candidateText
        textSize = sp(18f)
    }
    private val firstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.candidateFirst // highlight for the top candidate (C2)
        textSize = sp(18f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD // U-polish: a weight cue too, not colour-only
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

    /** F1: push a new Monet palette and repaint. */
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

    /** Number of candidates currently rendered in the strip (test hook, U1 regression guard). */
    internal fun itemCount(): Int = items.size

    /** U14: tell the strip whether the A2 expanded grid is open, so the chevron flips + toggles collapse. */
    fun setExpanded(value: Boolean) {
        if (value == expanded) return
        expanded = value
        invalidate()
    }

    /** U14 test seam: the chevron currently drawn at the right edge (⌃ when expanded, else ⌄). */
    internal fun chevronGlyph(): String = if (expanded) "⌃" else "⌄"

    private fun layoutCells() {
        hitCount = items.size
        var x = 0f // ★U: start flush-left so the first candidate sits ~one padding (14dp) from the edge,
        // not the doubled ~28dp it had when the layout itself also started at `padding`.
        for ((i, item) in items.withIndex()) {
            // U-polish: measure cell 0 with the (bold) firstPaint so the bold top candidate still fits its cell.
            val cellW = (if (i == 0) firstPaint else textPaint).measureText(item) + padding * 2
            hitRect(i).set(x, 0f, x + cellW, 0f) // bottom filled at draw (needs height)
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

        // Fixed expand/collapse affordance at the right edge (U14: ⌄ to expand, ⌃ once expanded).
        canvas.drawRect(visibleW, 0f, width.toFloat(), height.toFloat(), expandBgPaint)
        canvas.drawRect(visibleW, height * 0.25f, visibleW + density, height * 0.75f, sepPaint)
        // U-polish: self-drawn chevron (same stroke weight/centring as the toolbar's) instead of a font glyph.
        val chCx = visibleW + expandW / 2f; val chCy = height / 2f; val chS = 9f * density
        if (expanded) drawChevronUp(canvas, chCx, chCy, chS) else drawChevronDown(canvas, chCx, chCy, chS)
    }

    /** Floating rounded-capsule toolbar with self-drawn linear icons. */
    private fun drawFunctions(canvas: Canvas, baseline: Float) {
        val capL = capMarginH
        val capR = width - capMarginH
        val capT = capMarginV
        val capB = height - capMarginV
        val rad = (capB - capT) / 2f
        capsulePaint.setShadowLayer(6f * density, 0f, 2f * density, palette.shadow) // U-polish: shadow via token (theme-aware)
        canvas.drawRoundRect(capL, capT, capR, capB, rad, rad, capsulePaint)
        capsulePaint.clearShadowLayer()

        val cy = (capT + capB) / 2f
        val edgePad = 10f * density                 // U-polish: equal inset on BOTH ends so the cluster centres
        val collapseW = expandW                     // U-polish: match the candidate-mode expand button (was 34dp)
        val areaL = capL + edgePad
        val collapseL = capR - edgePad - collapseW  // chevron group inset from the right edge too (symmetry)
        val areaR = collapseL
        val slot = (areaR - areaL) / functions.size
        val s = 9f * density
        for ((i, f) in functions.withIndex()) {
            val cx = areaL + slot * (i + 0.5f)
            funcRects[i].set(areaL + slot * i, capT, areaL + slot * (i + 1), capB)
            drawIcon(canvas, f, cx, cy, s)
        }
        collapseRect.set(collapseL, capT, capR - edgePad, capB)
        // U-polish: divider tick at 50% of the capsule height, centred (consistent with the candidate ticks).
        val sepH = (capB - capT) * 0.25f
        canvas.drawRect(collapseL, cy - sepH, collapseL + density, cy + sepH, sepPaint)
        drawChevronDown(canvas, collapseL + collapseW / 2f, cy, s)
    }

    /** Dispatch to a self-drawn linear icon for each toolbar function (no font assets). */
    private fun drawIcon(c: Canvas, f: BarFunction, cx: Float, cy: Float, s: Float) {
        when (f) {
            BarFunction.BRAND -> drawBrand(c, cx, cy, s) // leading brand mark → settings
            BarFunction.EMOJI -> { // smiley — U-polish: circle 0.7s->0.6s so its optical box matches the others
                c.drawCircle(cx, cy, s * 0.6f, iconPaint)
                val eye = s * 0.16f // U-polish: relative to s (was abs 1.4dp) so it tracks the icon size
                iconPaint.style = Paint.Style.FILL
                c.drawCircle(cx - s * 0.24f, cy - s * 0.13f, eye, iconPaint)
                c.drawCircle(cx + s * 0.24f, cy - s * 0.13f, eye, iconPaint)
                iconPaint.style = Paint.Style.STROKE
                c.drawArc(cx - s * 0.34f, cy - s * 0.08f, cx + s * 0.34f, cy + s * 0.3f, 20f, 140f, false, iconPaint)
            }
            BarFunction.EDIT -> { // text I-beam cursor ⟨I⟩ — U-polish: serifs 0.32s->0.5s so it isn't a thin sliver
                c.drawLine(cx, cy - s * 0.75f, cx, cy + s * 0.75f, iconPaint)
                c.drawLine(cx - s * 0.5f, cy - s * 0.75f, cx + s * 0.5f, cy - s * 0.75f, iconPaint)
                c.drawLine(cx - s * 0.5f, cy + s * 0.75f, cx + s * 0.5f, cy + s * 0.75f, iconPaint)
            }
            BarFunction.CLIPBOARD -> { // clipboard board + clip + lines (剪贴板·常用语 panel)
                val w = s * 0.58f; val h = s * 0.78f // U-polish: w 0.55->0.58s to even the optical box
                c.drawRoundRect(cx - w, cy - h + s * 0.18f, cx + w, cy + h, s * 0.22f, s * 0.22f, iconPaint)
                c.drawRoundRect(cx - s * 0.26f, cy - h - s * 0.02f, cx + s * 0.26f, cy - h + s * 0.28f, s * 0.17f, s * 0.17f, iconPaint)
                c.drawLine(cx - w * 0.5f, cy - h * 0.1f, cx + w * 0.5f, cy - h * 0.1f, iconPaint)
                c.drawLine(cx - w * 0.5f, cy + h * 0.3f, cx + w * 0.5f, cy + h * 0.3f, iconPaint)
            }
        }
    }

    /**
     * Leading brand mark: the Aegis "A" (U4). Drawn as ONE round-joined path so the apex reads as a clean
     * peak (not two crossed lines), and SIZED to the same ~1.5s height as the other toolbar icons (the old
     * 2s height made it visibly larger). DEVIATION: the brand glyph is our "A" (not an "S").
     */
    private val brandPath = Path()
    private fun drawBrand(c: Canvas, cx: Float, cy: Float, s: Float) {
        val h = s * 0.78f                 // match emoji/edit/clipboard height (~1.5s tall), not 2s
        val apexY = cy - h; val baseY = cy + h
        val legX = s * 0.6f
        brandPath.reset()
        brandPath.moveTo(cx - legX, baseY)
        brandPath.lineTo(cx, apexY)       // up to the rounded-join apex
        brandPath.lineTo(cx + legX, baseY)
        c.drawPath(brandPath, iconPaint)
        val t = 0.58f                     // crossbar a little below the middle
        val ly = apexY + (baseY - apexY) * t
        c.drawLine(cx - legX * t, ly, cx + legX * t, ly, iconPaint)
    }

    private fun drawChevronDown(c: Canvas, cx: Float, cy: Float, s: Float) {
        c.drawLine(cx - s * 0.5f, cy - s * 0.2f, cx, cy + s * 0.28f, iconPaint)
        c.drawLine(cx, cy + s * 0.28f, cx + s * 0.5f, cy - s * 0.2f, iconPaint)
    }

    private fun drawChevronUp(c: Canvas, cx: Float, cy: Float, s: Float) { // U-polish: expanded-state chevron
        c.drawLine(cx - s * 0.5f, cy + s * 0.2f, cx, cy - s * 0.28f, iconPaint)
        c.drawLine(cx, cy - s * 0.28f, cx + s * 0.5f, cy + s * 0.2f, iconPaint)
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
                    performClick(); if (expanded) onCollapseExpanded() else onExpand(); return true
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
