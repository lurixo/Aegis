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

/**
 * Toolbar shortcuts on the idle candidate strip (C2).
 * the bar keeps ONLY: A (brand/settings) · 表情 · 文字编辑 · 剪贴板·常用语 — plus the collapse ⌄ (separate).
 * The leftover ⌨ (9↔26 switch → the keyboard's startup setting) and 123 (numpad, still on the keyboards) are gone.
 */
enum class BarFunction { BRAND, EMOJI, EDIT, CLIPBOARD } // debug.17: glyphs are self-drawn (Glyphs.*), no char field

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
    private enum class PressKind { CANDIDATE, FUNCTION, EXPAND, COLLAPSE }
    private data class PressTarget(val kind: PressKind, val index: Int = -1)
    private var pressedTarget: PressTarget? = null
    private var visualPressedTarget: PressTarget? = null
    private val pressFeedback = Motion.PressFeedback(this)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downScroll = 0f
    private var dragging = false
    // debug.17 #66: the candidate strip's horizontal scroll now flings (was pure drag) via the SHARED helper —
    // a quick flick coasts instead of stopping dead at the finger lift.
    private val fling = FlingScroller(context)

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
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpRect = RectF()

    /** F1: push a new Monet palette and repaint. */
    fun applyPalette(p: ImePalette) {
        palette = p
        textPaint.color = p.candidateText
        firstPaint.color = p.candidateFirst
        iconPaint.color = p.icon
        capsulePaint.color = p.keySurface
        sepPaint.color = p.separator
        invalidate()
    }

    fun setContent(candidates: List<String>, composingText: String) {
        if (candidates == items && composingText == composing) return
        items = candidates.toList()
        composing = composingText
        fling.forceFinish() // debug.17 fix: kill any running fling so new content renders from offset 0, not the stale offset
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

    // debug.17 #66 fling test seams.
    internal fun scrollXForTest(): Float = scrollX
    internal fun maxScrollForTest(): Float = maxScroll()
    internal fun isFlingingForTest(): Boolean = !fling.isFinished
    internal fun flingVelocityForTest(): Float = fling.velocity()

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
            drawPressLayer(canvas, PressTarget(PressKind.CANDIDATE, i), left, 4f * density, left + r.width(), height - 4f * density)
            canvas.drawText(items[i], left + padding, baseline, if (i == 0) firstPaint else textPaint)
            if (i != hitCount - 1) {
                canvas.drawRect(r.right - scrollX, height * 0.25f, r.right - scrollX + density, height * 0.75f, sepPaint)
            }
        }
        canvas.restore()

        // Fixed expand/collapse affordance at the right edge (U14: ⌄ to expand, ⌃ once expanded).
        canvas.drawRect(visibleW, height * 0.25f, visibleW + density, height * 0.75f, sepPaint)
        drawPressLayer(canvas, PressTarget(PressKind.EXPAND), visibleW, 4f * density, width.toFloat(), height - 4f * density)
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
        // debug.17 E3: the toolbar keeps its stadium pill via [toolbarPillRadiusDp] (clamped to half-height),
        // while every other chip/capsule tightened to a rounded rectangle (chipRadiusDp 999→10).
        val rad = minOf((capB - capT) / 2f, ImeShapes.toolbarPillRadiusDp * density)
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
            drawPressLayer(canvas, PressTarget(PressKind.FUNCTION, i), funcRects[i].left, funcRects[i].top, funcRects[i].right, funcRects[i].bottom)
            drawIcon(canvas, f, cx, cy, s)
        }
        collapseRect.set(collapseL, capT, capR - edgePad, capB)
        // U-polish: divider tick at 50% of the capsule height, centred (consistent with the candidate ticks).
        val sepH = (capB - capT) * 0.25f
        canvas.drawRect(collapseL, cy - sepH, collapseL + density, cy + sepH, sepPaint)
        drawPressLayer(canvas, PressTarget(PressKind.COLLAPSE), collapseRect.left, collapseRect.top, collapseRect.right, collapseRect.bottom)
        drawChevronDown(canvas, collapseL + collapseW / 2f, cy, s)
    }

    private fun drawPressLayer(canvas: Canvas, target: PressTarget, left: Float, top: Float, right: Float, bottom: Float) {
        val level = if (target == visualPressedTarget) pressFeedback.level else 0f
        if (level <= 0f || right <= left || bottom <= top) return
        pressPaint.color = Motion.stateLayerColor(palette.keyLabel, level, 0x22)
        tmpRect.set(left, top, right, bottom)
        val r = ImeShapes.keyRadiusDp * density
        canvas.drawRoundRect(tmpRect, r, r, pressPaint)
    }

    /** Dispatch to a self-drawn [Glyphs] icon for each toolbar function (debug.17: all share the Glyphs family
     *  at the SAME ~1.5s box as the clipboard — the emoji was ~20% small inline, the brand/I-beam were local). */
    private fun drawIcon(c: Canvas, f: BarFunction, cx: Float, cy: Float, s: Float) {
        when (f) {
            BarFunction.BRAND -> Glyphs.drawBrandA(c, iconPaint, cx, cy, s)     // leading brand mark → settings
            BarFunction.EMOJI -> Glyphs.drawEmoji(c, iconPaint, cx, cy, s)      // 表情
            BarFunction.EDIT -> Glyphs.drawEditCaret(c, iconPaint, cx, cy, s)   // 文字编辑 (text I-beam)
            BarFunction.CLIPBOARD -> Glyphs.drawClipboard(c, iconPaint, cx, cy, s) // 剪贴板·常用语
        }
    }

    private fun drawChevronDown(c: Canvas, cx: Float, cy: Float, s: Float) {
        c.drawLine(cx - s * 0.5f, cy - s * 0.2f, cx, cy + s * 0.28f, iconPaint)
        c.drawLine(cx, cy + s * 0.28f, cx + s * 0.5f, cy - s * 0.2f, iconPaint)
    }

    private fun drawChevronUp(c: Canvas, cx: Float, cy: Float, s: Float) { // U-polish: expanded-state chevron
        c.drawLine(cx - s * 0.5f, cy + s * 0.2f, cx, cy - s * 0.28f, iconPaint)
        c.drawLine(cx, cy - s * 0.28f, cx + s * 0.5f, cy + s * 0.2f, iconPaint)
    }

    /** debug.17 #66: drive the horizontal momentum fling each frame (View.draw calls this automatically). */
    override fun computeScroll() {
        fling.computeOffset()?.let { scrollX = it.coerceIn(0f, maxScroll()); postInvalidateOnAnimation() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downScroll = scrollX
                dragging = false
                fling.onDown() // stop any running fling; a tap that halts it must not also pick a candidate
                setPressedTarget(targetAt(event.x, event.y))
            }
            MotionEvent.ACTION_MOVE -> {
                if (!showingFunctions && items.isNotEmpty()) {
                    fling.addSample(event.eventTime, event.x)
                    val dx = event.x - downX
                    if (!dragging && abs(dx) > touchSlop) dragging = true
                    if (dragging) {
                        releasePressedTarget()
                        scrollX = (downScroll - dx).coerceIn(0f, maxScroll())
                        invalidate()
                    }
                } else if (!dragging) {
                    setPressedTarget(targetAt(event.x, event.y))
                }
            }
            MotionEvent.ACTION_UP -> {
                releasePressedTarget()
                if (dragging) { dragging = false; if (fling.fling(scrollX, maxScroll())) postInvalidateOnAnimation(); return true }
                if (fling.stopArmed) return true // this tap only stopped a running fling — never a pick
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
            MotionEvent.ACTION_CANCEL -> releasePressedTarget()
        }
        return true
    }

    private fun isFunctionMode(): Boolean = items.isEmpty() && composing.isEmpty()

    private fun targetAt(x: Float, y: Float): PressTarget? {
        if (isFunctionMode()) {
            if (collapseRect.contains(x, y)) return PressTarget(PressKind.COLLAPSE)
            val idx = funcRects.indexOfFirst { it.contains(x, y) }
            return if (idx >= 0) PressTarget(PressKind.FUNCTION, idx) else null
        }
        if (items.isNotEmpty()) {
            if (x >= width - expandW) return PressTarget(PressKind.EXPAND)
            val cx = x + scrollX
            for (i in 0 until hitCount) {
                val r = hitRects[i]
                if (cx >= r.left && cx < r.right && y >= 0f && y <= height) return PressTarget(PressKind.CANDIDATE, i)
            }
        }
        return null
    }

    private fun setPressedTarget(target: PressTarget?) {
        if (pressedTarget == target) return
        pressedTarget = target
        if (target == null) {
            pressFeedback.release()
        } else {
            visualPressedTarget = target
            pressFeedback.press()
        }
        invalidate()
    }

    private fun releasePressedTarget() {
        pressedTarget = null
        pressFeedback.release()
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
