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
import androidx.core.graphics.withClip
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.layout.Layouts
import kotlin.math.abs
import kotlin.math.roundToInt

enum class BarFunction { BRAND, EMOJI, LAYOUT, EDIT, CLIPBOARD }

class CandidateView(context: Context) : View(context) {

    var onPick: (Int) -> Unit = {}
    var onFunction: (BarFunction) -> Unit = {}
    var onExpand: () -> Unit = {}
    var onCollapse: () -> Unit = {}
    var onCollapseExpanded: () -> Unit = {}
    var onDictGate: (() -> Unit)? = null

    private var items: List<String> = emptyList()
    private var composing: String = ""
    private var gateActive = false
    private var gateLabel = ""
    private var gateFailed = false
    private var expanded = false
    private val hitRects = ArrayList<RectF>()
    private var hitCount = 0
    private var contentWidth = 0f
    private var scrollX = 0f
    private var contentTransitions = 0

    private val functions = listOf(BarFunction.BRAND, BarFunction.EMOJI, BarFunction.EDIT, BarFunction.LAYOUT, BarFunction.CLIPBOARD)
    private val funcRects = ArrayList<RectF>().also { l -> repeat(functions.size) { l.add(RectF()) } }
    private val collapseRect = RectF()
    private val iconCentersX = FloatArray(functions.size + 1)
    private enum class PressKind { CANDIDATE, FUNCTION, EXPAND, COLLAPSE }
    private data class PressTarget(val kind: PressKind, val index: Int = -1)
    private var pressedTarget: PressTarget? = null
    private var visualPressedTarget: PressTarget? = null
    private val pressFeedback = Motion.PressFeedback(this)

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
    private val gatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.candidateFirst
        textSize = sp(18f)
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
    private val toolbarBounds = RectF()
    private val toolbarClipPath = Path()

    fun applyPalette(p: ImePalette) {
        palette = p
        textPaint.color = p.candidateText
        firstPaint.color = p.candidateFirst
        gatePaint.color = p.candidateFirst
        iconPaint.color = p.icon
        capsulePaint.color = p.keySurface
        sepPaint.color = p.separator
        invalidate()
    }

    fun setContent(candidates: List<String>, composingText: String, gate: Boolean = false) {
        if (candidates == items && composingText == composing && gate == gateActive) return
        val roleChanged = stripRole(items.isEmpty(), composing) != stripRole(candidates.isEmpty(), composingText)
        val visualChange = candidates != items || gate != gateActive
        gateActive = gate
        when {
            roleChanged -> {
                contentTransitions++
                Motion.coverThrough(this, palette.keyboardBg) { applyContent(candidates, composingText) }
            }
            visualChange -> applyContent(candidates, composingText)
            else -> composing = composingText
        }
    }

    fun setGateStatus(text: String, failed: Boolean = false) {
        if (text == gateLabel && failed == gateFailed) return
        gateLabel = text
        gateFailed = failed
        invalidate()
    }

    private fun applyContent(candidates: List<String>, composingText: String) {
        items = candidates.toList()
        composing = composingText
        fling.forceFinish()
        scrollX = 0f
        hitCount = 0
        contentWidth = 0f
        invalidate()
    }

    private fun stripRole(itemsEmpty: Boolean, composingText: String): Int =
        when {
            !itemsEmpty -> ROLE_CANDIDATES
            composingText.isEmpty() -> ROLE_FUNCTIONS
            else -> ROLE_CANDIDATES
        }

    internal fun contentTransitionsForTest(): Int = contentTransitions

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
    internal fun taskbarPressRadiusDpForTest(): Float = ImeShapes.toolbarFeedbackRadiusDp
    internal fun keyPressRadiusDpForTest(): Float = ImeShapes.keyRadiusDp
    internal fun toolbarControlBoundsForTest(): List<RectF> = funcRects.map(::RectF) + RectF(collapseRect)
    internal fun toolbarPressHighlightBoundsForTest(): List<RectF> {
        val half = toolbarBounds.width() / (functions.size + 2) / 2f
        return iconCentersX.map { cx -> RectF(cx - half, toolbarBounds.top, cx + half, toolbarBounds.bottom) }
    }
    internal fun toolbarCapsuleBoundsForTest(): RectF = RectF(toolbarBounds)
    internal fun toolbarOuterRadiusForTest(): Float = toolbarOuterRadius()
    private fun expandWidth(): Float =
        if (width > 0) {
            minOf(
                (width * Layouts.NINE_SIDE_FRACTION).roundToInt().toFloat(),
                (Layouts.CANDIDATE_ACTION_WIDTH_DP * density).toInt().toFloat(),
            )
        } else {
            (Layouts.CANDIDATE_ACTION_WIDTH_DP * density).toInt().toFloat()
        }

    internal fun expandControlBoundsForTest(): RectF =
        RectF(width - expandWidth(), 0f, width.toFloat(), height.toFloat())
    internal fun toolbarChevronBoundsForTest(): RectF =
        Glyphs.chevronBounds(iconCentersX[functions.size], collapseRect.centerY(), 9f * density * CHEVRON_SCALE)
    internal fun candidateChevronBoundsForTest(): RectF {
        val control = expandControlBoundsForTest()
        return Glyphs.chevronBounds(control.centerX(), control.centerY(), 9f * density * CHEVRON_SCALE)
    }
    internal fun toolbarIconCentersForTest(): FloatArray = iconCentersX.copyOf()
    internal fun toolbarIconScaleForTest(f: BarFunction): Float = iconScale(f)
    internal fun toolbarFunctionsForTest(): List<BarFunction> = functions

    internal fun centerOfCandidateForTest(index: Int): Pair<Float, Float>? {
        if (index !in items.indices) return null
        layoutCellsThrough(index)
        val r = hitRects[index]
        return (r.centerX() - scrollX) to (height / 2f)
    }

    internal fun laidOutCellsForTest(): Int = hitCount

    private fun layoutNextCell() {
        val i = hitCount
        val cellW = (if (i == 0) firstPaint else textPaint).measureText(items[i]) + padding * 2
        hitRect(i).set(contentWidth, 0f, contentWidth + cellW, 0f)
        contentWidth += cellW
        hitCount = i + 1
    }

    private fun layoutCellsTo(contentX: Float) {
        while (hitCount < items.size && contentWidth < contentX) layoutNextCell()
    }

    private fun layoutCellsThrough(index: Int) {
        while (hitCount <= index && hitCount < items.size) layoutNextCell()
    }

    private fun maxScroll(): Float {
        val visibleW = width - expandWidth()
        layoutCellsTo(scrollX + visibleW * LAYOUT_AHEAD_SCREENS)
        return maxOf(0f, contentWidth - visibleW)
    }

    private fun candidateIndexAt(contentX: Float): Int {
        layoutCellsTo(contentX + 1f)
        var lo = 0
        var hi = hitCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (hitRects[mid].right <= contentX) lo = mid + 1 else hi = mid
        }
        return if (lo < hitCount && contentX >= hitRects[lo].left) lo else -1
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.keyboardBg)
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2

        if (items.isEmpty()) {
            when {
                gateActive -> drawGate(canvas, baseline)
                isFunctionMode() -> drawFunctions(canvas, baseline)
            }
            return
        }
        scrollX = scrollX.coerceIn(0f, maxScroll())

        val expandW = expandWidth()
        val visibleW = width - expandW
        canvas.save()
        canvas.clipRect(0f, 0f, visibleW, height.toFloat())
        val first = candidateIndexAt(scrollX).coerceAtLeast(0)
        for (i in first until hitCount) {
            val r = hitRects[i]
            if (r.left >= scrollX + visibleW) break
            r.bottom = height.toFloat()
            val left = r.left - scrollX
            drawPressLayer(canvas, PressKind.CANDIDATE, i, left, 4f * density, left + r.width(), height - 4f * density)
            canvas.drawText(items[i], left + padding, baseline, if (i == 0) firstPaint else textPaint)
            if (i != items.size - 1) {
                canvas.drawRect(r.right - scrollX, height * 0.25f, r.right - scrollX + density, height * 0.75f, sepPaint)
            }
        }
        canvas.restore()

        canvas.drawRect(visibleW, height * 0.25f, visibleW + density, height * 0.75f, sepPaint)
        drawPressLayer(canvas, PressKind.EXPAND, -1, visibleW, 4f * density, width.toFloat(), height - 4f * density)
        val chCx = visibleW + expandW / 2f; val chCy = height / 2f; val chS = 9f * density * CHEVRON_SCALE
        Glyphs.drawChevron(canvas, iconPaint, chCx, chCy, chS, down = !expanded)
    }

    private fun drawGate(canvas: Canvas, baseline: Float) {
        val text = gateLabel
        if (text.isEmpty()) return
        gatePaint.color = gateColor()
        val x = ((width - gatePaint.measureText(text)) / 2f).coerceAtLeast(padding)
        canvas.drawText(text, x, baseline, gatePaint)
    }

    private fun gateColor(): Int = if (gateFailed) palette.deletable else palette.candidateFirst

    private fun drawFunctions(canvas: Canvas, baseline: Float) {
        val capL = capMarginH
        val capR = width - capMarginH
        val capT = capMarginV
        val capB = height - capMarginV
        val rad = toolbarOuterRadius()
        toolbarBounds.set(capL, capT, capR, capB)
        capsulePaint.setShadowLayer(6f * density, 0f, 2f * density, palette.shadow)
        canvas.drawRoundRect(capL, capT, capR, capB, rad, rad, capsulePaint)
        capsulePaint.clearShadowLayer()

        val cy = (capT + capB) / 2f
        val areaL = capL
        val areaR = capR
        val gap = (areaR - areaL) / (functions.size + 2)
        val half = gap / 2f
        val s = 9f * density
        toolbarClipPath.reset()
        toolbarClipPath.addRoundRect(toolbarBounds, rad, rad, Path.Direction.CW)
        canvas.withClip(toolbarClipPath) {
            for ((i, f) in functions.withIndex()) {
                val cx = areaL + gap * (i + 1)
                iconCentersX[i] = cx
                val cellL = if (i == 0) areaL else areaL + gap * (i + 0.5f)
                funcRects[i].set(cellL, capT, areaL + gap * (i + 1.5f), capB)
                drawPressLayer(canvas, PressKind.FUNCTION, i, cx - half, capT, cx + half, capB)
                drawIcon(canvas, f, cx, cy, s)
            }
            val chevronCx = areaL + gap * (functions.size + 1)
            iconCentersX[functions.size] = chevronCx
            collapseRect.set(areaL + gap * (functions.size + 0.5f), capT, areaR, capB)
            drawPressLayer(canvas, PressKind.COLLAPSE, -1, chevronCx - half, capT, chevronCx + half, capB)
            Glyphs.drawChevron(canvas, iconPaint, chevronCx, cy, s * CHEVRON_SCALE, down = true)
        }
    }

    private fun toolbarOuterRadius(): Float =
        minOf((height - capMarginV * 2f) / 2f, ImeShapes.toolbarPillRadiusDp * density)

    private fun drawPressLayer(
        canvas: Canvas,
        kind: PressKind,
        index: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val pressed = visualPressedTarget
        val level = if (pressed?.kind == kind && pressed.index == index) pressFeedback.level else 0f
        if (level <= 0f || right <= left || bottom <= top) return
        pressPaint.color = Motion.stateLayerColor(palette.keyLabel, level, 0x22)
        tmpRect.set(left, top, right, bottom)
        val r = pressRadiusDp(kind) * density
        canvas.drawRoundRect(tmpRect, r, r, pressPaint)
    }

    private fun pressRadiusDp(kind: PressKind): Float = when (kind) {
        PressKind.FUNCTION, PressKind.COLLAPSE -> ImeShapes.toolbarFeedbackRadiusDp
        PressKind.CANDIDATE, PressKind.EXPAND -> ImeShapes.keyRadiusDp
    }

    private fun drawIcon(c: Canvas, f: BarFunction, cx: Float, cy: Float, s: Float) {
        val gs = s * iconScale(f)
        when (f) {
            BarFunction.BRAND -> Glyphs.drawBrandWeldedA(c, iconPaint, cx, cy, gs)
            BarFunction.LAYOUT -> Glyphs.drawKeyboard(c, iconPaint, cx, cy, gs)
            BarFunction.EMOJI -> Glyphs.drawEmoji(c, iconPaint, cx, cy, gs)
            BarFunction.EDIT -> Glyphs.drawEditCaret(c, iconPaint, cx, cy, gs)
            BarFunction.CLIPBOARD -> Glyphs.drawClipboard(c, iconPaint, cx, cy, gs)
        }
    }

    private fun iconScale(f: BarFunction): Float = when (f) {
        BarFunction.BRAND -> fitScale(BRAND_GLYPH_WIDTH, BRAND_GLYPH_HEIGHT)
        BarFunction.LAYOUT -> fitScale(LAYOUT_GLYPH_WIDTH, LAYOUT_GLYPH_HEIGHT)
        BarFunction.EMOJI -> fitScale(EMOJI_GLYPH_WIDTH, EMOJI_GLYPH_HEIGHT)
        BarFunction.EDIT -> fitScale(EDIT_GLYPH_WIDTH, EDIT_GLYPH_HEIGHT)
        BarFunction.CLIPBOARD -> fitScale(CLIPBOARD_GLYPH_WIDTH, CLIPBOARD_GLYPH_HEIGHT)
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
                setPressedTarget(targetAt(event.x, event.y))
            }
            MotionEvent.ACTION_MOVE -> {
                if (items.isNotEmpty()) {
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
                val downTarget = pressedTarget
                releasePressedTarget()
                if (dragging) { dragging = false; if (fling.fling(scrollX, maxScroll())) postInvalidateOnAnimation(); return true }
                if (fling.stopArmed) return true
                if (isGateMode()) { performClick(); onDictGate?.invoke(); return true }
                if (isFunctionMode()) {
                    val upTarget = toolbarTargetAt(event.x, event.y)
                    if (downTarget == upTarget) {
                        when (upTarget?.kind) {
                            PressKind.COLLAPSE -> { performClick(); onCollapse() }
                            PressKind.FUNCTION -> { performClick(); onFunction(functions[upTarget.index]) }
                            else -> Unit
                        }
                    }
                    return true
                }
                if (items.isNotEmpty() && (downTarget?.kind == PressKind.EXPAND || isExpandTarget(event.x, event.y))) {
                    if (downTarget?.kind == PressKind.EXPAND && isExpandTarget(event.x, event.y)) {
                        performClick(); if (expanded) onCollapseExpanded() else onExpand()
                    }
                    return true
                }
                val cx = event.x + scrollX
                val index = candidateIndexAt(cx)
                if (index >= 0) {
                    performClick()
                    onPick(index)
                }
            }
            MotionEvent.ACTION_CANCEL -> releasePressedTarget()
        }
        return true
    }

    private fun isFunctionMode(): Boolean = items.isEmpty() && composing.isEmpty()

    private fun isGateMode(): Boolean = gateActive && items.isEmpty()

    internal fun gateActiveForTest(): Boolean = gateActive

    internal fun gateTextColorForTest(): Int = gateColor()

    private fun targetAt(x: Float, y: Float): PressTarget? {
        if (isFunctionMode()) {
            return toolbarTargetAt(x, y)
        }
        if (items.isNotEmpty()) {
            if (isExpandTarget(x, y)) return PressTarget(PressKind.EXPAND)
            val cx = x + scrollX
            val index = candidateIndexAt(cx)
            if (index >= 0 && y >= 0f && y <= height) return PressTarget(PressKind.CANDIDATE, index)
        }
        return null
    }

    private fun toolbarTargetAt(x: Float, y: Float): PressTarget? {
        if (funcRects[0].contains(x, y)) return PressTarget(PressKind.FUNCTION, 0)
        if (collapseRect.contains(x, y)) return PressTarget(PressKind.COLLAPSE)
        if (!toolbarContains(x, y)) return null
        val idx = funcRects.indexOfFirst { it.contains(x, y) }
        return if (idx >= 0) PressTarget(PressKind.FUNCTION, idx) else null
    }

    private fun toolbarContains(x: Float, y: Float): Boolean {
        if (!toolbarBounds.contains(x, y)) return false
        val radius = toolbarOuterRadius()
        if (x >= toolbarBounds.left + radius && x < toolbarBounds.right - radius) return true
        if (y >= toolbarBounds.top + radius && y < toolbarBounds.bottom - radius) return true
        val cornerX = if (x < toolbarBounds.left + radius) toolbarBounds.left + radius else toolbarBounds.right - radius
        val cornerY = if (y < toolbarBounds.top + radius) toolbarBounds.top + radius else toolbarBounds.bottom - radius
        val dx = x - cornerX
        val dy = y - cornerY
        return dx * dx + dy * dy <= radius * radius
    }

    private fun isExpandTarget(x: Float, y: Float): Boolean =
        x >= width - expandWidth() && x < width && y >= 0f && y < height

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

    private companion object {
        private const val ROLE_CANDIDATES = 0
        private const val ROLE_FUNCTIONS = 1
        private const val LAYOUT_AHEAD_SCREENS = 8f

        private const val ICON_BOX = 1.64f
        private const val BRAND_GLYPH_WIDTH = 1.28f
        private const val BRAND_GLYPH_HEIGHT = 1.59f
        private const val LAYOUT_GLYPH_WIDTH = 1.40f
        private const val LAYOUT_GLYPH_HEIGHT = 1.40f
        private const val EMOJI_GLYPH_WIDTH = 1.64f
        private const val EMOJI_GLYPH_HEIGHT = 1.64f
        private const val EDIT_GLYPH_WIDTH = 1.00f
        private const val EDIT_GLYPH_HEIGHT = 1.64f
        private const val CLIPBOARD_GLYPH_WIDTH = 1.16f
        private const val CLIPBOARD_GLYPH_HEIGHT = 1.58f
        private const val CHEVRON_GLYPH_WIDTH = 1.40f
        private const val CHEVRON_GLYPH_HEIGHT = 0.76f
        private val CHEVRON_SCALE = fitScale(CHEVRON_GLYPH_WIDTH, CHEVRON_GLYPH_HEIGHT)

        private fun fitScale(w: Float, h: Float): Float = minOf(ICON_BOX / w, ICON_BOX / h)
    }
}
