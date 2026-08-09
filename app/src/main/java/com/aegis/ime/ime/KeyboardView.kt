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

import com.aegis.ime.R

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.KeyboardLayout
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.layout.ScrollColumn
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes

class KeyboardView(context: Context) : View(context) {

    var onKey: (Key) -> Unit = {}

    var onBackspaceSwipe: (Boolean) -> Unit = {}

    private var layout: KeyboardLayout = Layouts.forId(LayoutId.ALPHA, Lang.CN)
    private var modeSwitches = 0
    private var layoutApplies = 0
    private var shifted = false
    private var shiftLocked = false
    private var lang = Lang.CN

    private var lastShiftTapTime = 0L
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()

    private val placed = ArrayList<Placed>()
    private var pressed: Key? = null
    private var visualPressed: Key? = null
    private val keyPress = Motion.PressFeedback(this)

    private var scrollColumn: ScrollColumn? = null
    private val scrollRegion = RectF()
    private val scrollTouch = RectF()
    private var scrollCellH = 0f
    private var scrollAccentIndex = -1
    private var pendingAccentReveal = false
    private var scrollY = 0f
    private var scrollPressedIndex = -1
    private var scrollVisualPressedIndex = -1
    private val scrollPress = Motion.PressFeedback(this)
    private var scrollPointerId = MotionEvent.INVALID_POINTER_ID
    private var scrollDownY = 0f
    private var scrollLastY = 0f
    private var scrolling = false
    private val tmpRect = RectF()
    private val scrollTrackPath = Path()
    private val scrollPressPath = Path()
    private val scrollSlop = 6f * resources.displayMetrics.density
    private val fling = FlingScroller(context)
    private val scrollbarFade = ScrollbarFade()
    private val scrollbarTick = Runnable { invalidate() }

    private var downKey: Key? = null
    private var downPlaced: Placed? = null
    private var downInEdgeInset = false
    private var downX = 0f
    private var downY = 0f
    private var downEventTime = 0L
    private var retargetUnlocked = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private val backspace = BackspaceGesture(resources.displayMetrics.density).apply {
        onRepeat = { downKey?.let { key -> onKey(key) } }
        onSwipe = { up -> onBackspaceSwipe(up) }
    }

    private fun isRepeatable(key: Key) = key.action == KeyAction.BACKSPACE

    private val density = resources.displayMetrics.density
    private val rowHeight = 52f * density
    private val snapCap = rowHeight * 0.5f
    private val retargetHysteresis = 4f * density
    private val retargetDistance = 24f * density
    private val shortPageRowExtra = 2f * density
    private val gap = KEY_GAP_DP * density
    private val edgeInset = ImeShapes.edgeInsetDp * density
    private val keyRadius = ImeShapes.keyRadiusDp * density

    private var palette = ImePalette.STATIC_LIGHT


    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(20f); typeface = android.graphics.Typeface.DEFAULT }
    private val specialLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabelSecondary; textAlign = Paint.Align.CENTER; textSize = sp(20f); typeface = android.graphics.Typeface.DEFAULT }
    private val shiftActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentBottom; textAlign = Paint.Align.CENTER; textSize = sp(20f) }
    private val accentLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentLabel; textAlign = Paint.Align.CENTER; textSize = sp(20f); typeface = android.graphics.Typeface.DEFAULT }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keySub; textAlign = Paint.Align.CENTER; textSize = sp(11f); typeface = android.graphics.Typeface.DEFAULT }
    private val langLabel = ImeSplitLabel(density, sp(20f), sp(17f)).apply { applyColors(palette.keyLabelSecondary, palette.keyHint) }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keySurface }
    private val keyEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.shadow }
    private val spaceMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.gridLine; strokeWidth = ImeShapes.gridLinePx(density) }
    private val pressHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(palette.keyLabel, 0x22) }
    private val scrollTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.functionSurface }
    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(palette.icon, SCROLLBAR_ALPHA) }
    private val scrollLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.LEFT; textSize = sp(17f); typeface = android.graphics.Typeface.DEFAULT }
    private val inkBounds = android.graphics.Rect()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    fun applyPalette(p: ImePalette) {
        palette = p
        labelPaint.color = p.keyLabel
        specialLabelPaint.color = p.keyLabelSecondary
        shiftActivePaint.color = p.accentBottom
        accentLabelPaint.color = p.accentLabel
        subPaint.color = p.keySub
        langLabel.applyColors(p.keyLabelSecondary, p.keyHint)
        sepLinePaint.color = p.gridLine
        pressHighlight.color = Motion.withAlpha(p.keyLabel, 0x22)
        keyEdgePaint.color = p.shadow
        scrollTrackPaint.color = p.functionSurface
        scrollbarPaint.color = withAlpha(p.icon, SCROLLBAR_ALPHA)
        scrollLabelPaint.color = p.keyLabel
        invalidate()
    }

    private fun withAlpha(argb: Int, alpha: Int): Int = Motion.withAlpha(argb, alpha)

    private data class Placed(val rect: RectF, val key: Key, val groupId: Int = 0, val hitRect: RectF? = null)

    fun setLayout(newLayout: KeyboardLayout, isShifted: Boolean, isLocked: Boolean, language: Lang) {
        if (newLayout == layout && isShifted == shifted && isLocked == shiftLocked && language == lang) return
        val faceSwap = newLayout.id != layout.id || language != lang
        if (faceSwap) cancelPrimary()
        val snap = if (faceSwap && width > 0) Motion.snapshot(this, palette.keyboardBg) else null
        layoutApplies++
        val sameColumn = newLayout.scrollColumn?.items?.map { it.label } == layout.scrollColumn?.items?.map { it.label }
        val accentIndex = newLayout.scrollColumn?.items?.indexOfFirst { it.accent } ?: -1
        if (accentIndex >= 0 && (!sameColumn || accentIndex != scrollAccentIndex)) pendingAccentReveal = true
        scrollAccentIndex = accentIndex
        val modeChanged = newLayout.id != layout.id
        val sizingChanged = newLayout.rowCount != layout.rowCount ||
            usesFractionalCells(newLayout) != usesFractionalCells(layout)
        layout = newLayout
        shifted = isShifted
        shiftLocked = isLocked
        lang = language
        scrollColumn = newLayout.scrollColumn
        if (!sameColumn) { fling.forceFinish(); scrollY = 0f; scrollbarFade.hide() }
        if (width > 0) relayout()
        if (sizingChanged || width <= 0) requestLayout()
        invalidate()
        if (modeChanged && width > 0) { modeSwitches++ }
        if (snap != null) Motion.coverWith(this, snap)
    }

    internal fun modeSwitchesForTest(): Int = modeSwitches

    internal fun layoutAppliesForTest(): Int = layoutApplies

    private fun usesFractionalCells(l: KeyboardLayout): Boolean = l.cells != null && l.id != LayoutId.ALPHA

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = layout.rowCount
        val rh = if (rows == 4) rowHeight + shortPageRowExtra else rowHeight
        val desiredHeight = (rows * rh + (rows + 1) * gap).toInt()

        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        placed.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val compactGrid = layout.id == LayoutId.ALPHA || layout.id == LayoutId.NINE || layout.id == LayoutId.NUMPAD
        val gapGeometry = if (layout.id == LayoutId.NUMBER || layout.id == LayoutId.SYMBOL) {
            Layouts.forId(LayoutId.ALPHA, lang)
        } else {
            layout
        }
        val constrainedPage = (layout.id == LayoutId.NUMBER || layout.id == LayoutId.SYMBOL) &&
            height < LandscapeDockSizing.preferredKeyboardHeight(layout.rowCount, density)
        val verticalRows = if (constrainedPage) gapGeometry.rowCount else layout.rowCount
        val maximumGap = if (compactGrid || gapGeometry.id == LayoutId.ALPHA) gap / 2f else gap
        val horizontalGap = effectiveHorizontalGap((w - 2f * edgeInset).coerceAtLeast(0f), maximumGap, gapGeometry)
        val outer = (edgeInset - horizontalGap).coerceAtLeast(0f)
        val span = (w - 2f * outer).coerceAtLeast(0f)
        val fractionalVertical = layout.cells != null && layout.id != LayoutId.ALPHA
        val availableVerticalGap = LandscapeDockSizing.effectiveVerticalGap(
            height,
            verticalRows,
            density,
            fractionalRows = fractionalVertical,
        )
        val verticalGap = if (layout.id == LayoutId.NINE || layout.id == LayoutId.NUMPAD) {
            minOf(gap / 2f, availableVerticalGap / 2f)
        } else {
            availableVerticalGap
        }
        val sc = layout.scrollColumn
        scrollColumn = sc
        if (sc != null && h > 0) {
            scrollRegion.set(
                outer + sc.x * span + horizontalGap,
                sc.y * h + verticalGap,
                outer + (sc.x + sc.w) * span - horizontalGap,
                (sc.y + sc.h) * h - verticalGap,
            )
            scrollTouch.set(
                maxOf(edgeInset, outer + sc.x * span),
                scrollRegion.top,
                minOf(w - edgeInset, outer + (sc.x + sc.w) * span),
                scrollRegion.bottom,
            )
            scrollCellH = scrollCellHeight(sc, h, verticalGap)
            clampScroll()
            if (pendingAccentReveal) {
                revealScrollIndex(scrollAccentIndex)
                pendingAccentReveal = false
            }
        }
        val cells = layout.cells
        if (cells != null) {
            val alphaFaceHeight = if (layout.id == LayoutId.ALPHA) {
                (h - (layout.rowCount + 1) * verticalGap) / layout.rowCount
            } else {
                0f
            }
            for (pk in cells) {
                val top = if (layout.id == LayoutId.ALPHA) {
                    val row = (pk.y * layout.rowCount).roundToInt()
                    verticalGap + row * (alphaFaceHeight + verticalGap)
                } else {
                    pk.y * h + verticalGap
                }
                val bottom = if (layout.id == LayoutId.ALPHA) top + alphaFaceHeight else (pk.y + pk.h) * h - verticalGap
                val rowIndex = (pk.y * layout.rowCount).roundToInt()
                val hitTop = when {
                    layout.id != LayoutId.ALPHA -> pk.y * h
                    rowIndex == 0 -> 0f
                    else -> top - verticalGap / 2f
                }
                val hitBottom = when {
                    layout.id != LayoutId.ALPHA -> (pk.y + pk.h) * h
                    rowIndex == layout.rowCount - 1 -> h
                    else -> bottom + verticalGap / 2f
                }
                placed.add(
                    Placed(
                        RectF(
                            outer + pk.x * span + horizontalGap,
                            top,
                            outer + (pk.x + pk.w) * span - horizontalGap,
                            bottom,
                        ),
                        pk.key, pk.groupId,
                        RectF(
                            maxOf(edgeInset, outer + pk.x * span),
                            hitTop,
                            minOf(w - edgeInset, outer + (pk.x + pk.w) * span),
                            hitBottom,
                        ),
                    ),
                )
            }
            return
        }
        if (layout.id == LayoutId.NUMBER || layout.id == LayoutId.SYMBOL) {
            val faceGap = horizontalGap * 2f
            val availableFaceHeight = (h - (verticalRows + 1) * verticalGap) / verticalRows
            val faceHeight = minOf(rowHeight, availableFaceHeight)
            var top = (h - layout.rowCount * faceHeight - (layout.rowCount - 1) * verticalGap) / 2f
            for (rowItem in layout.rows) {
                val finalRow = rowItem.keys.any { it.action == KeyAction.SPACE }
                fun rowWidths(total: Float): List<Float> {
                    val ordinaryWidth = total / 10f - faceGap
                    if (!finalRow) return rowItem.keys.map { ordinaryWidth * it.weight }
                    val nonSpaceWidth = rowItem.keys
                        .filter { it.action != KeyAction.SPACE }
                        .sumOf { (ordinaryWidth * it.weight).toDouble() }
                        .toFloat()
                    val spaceWidth = total - horizontalGap * 2f - faceGap * (rowItem.keys.size - 1) - nonSpaceWidth
                    return rowItem.keys.map { if (it.action == KeyAction.SPACE) spaceWidth else ordinaryWidth * it.weight }
                }
                val widths = rowWidths(span)
                val keyGap = (span - horizontalGap * 2f - widths.sum()) / (rowItem.keys.size - 1)
                var left = outer + horizontalGap
                var hitLeft = maxOf(edgeInset, left)
                for ((index, key) in rowItem.keys.withIndex()) {
                    val rect = RectF(left, top, left + widths[index], top + faceHeight)
                    val hitRight = if (index == rowItem.keys.lastIndex) minOf(w - edgeInset, rect.right) else rect.right + keyGap / 2f
                    val hitRect = RectF(hitLeft, rect.top - verticalGap / 2f, hitRight, rect.bottom + verticalGap / 2f)
                    placed.add(Placed(rect, key, hitRect = hitRect))
                    left += widths[index] + keyGap
                    hitLeft = hitRight
                }
                top += faceHeight + verticalGap
            }
            return
        }
        val rh = (h - (layout.rowCount + 1) * verticalGap) / layout.rowCount
        var top = verticalGap
        for (rowItem in layout.rows) {
            val totalWeight = rowItem.keys.sumOf { it.weight.toDouble() }.toFloat()
            val usable = span - 2 * horizontalGap - (rowItem.keys.size - 1) * horizontalGap
            var left = outer + horizontalGap
            for (key in rowItem.keys) {
                val keyW = usable * (key.weight / totalWeight)
                placed.add(Placed(RectF(left, top, left + keyW, top + rh), key))
                left += keyW + horizontalGap
            }
            top += rh + verticalGap
        }
    }

    private fun effectiveHorizontalGap(contentWidth: Float, maximumGap: Float, geometry: KeyboardLayout): Float {
        if (contentWidth <= 0f) return 0f
        val minimumFace = 20f * density + FACE_FLOOR_ROUNDING_PX
        var allowed = maximumGap
        val cells = geometry.cells
        if (cells != null) {
            val minimumFraction = cells.minOfOrNull { it.w } ?: 1f
            if (minimumFraction < 1f) {
                allowed = min(allowed, (minimumFraction * contentWidth - minimumFace) / (2f * (1f - minimumFraction)))
            }
        } else {
            for (row in geometry.rows) {
                if (row.keys.size < 2) continue
                val totalWeight = row.keys.sumOf { it.weight.toDouble() }.toFloat()
                val minimumWeight = row.keys.minOf { it.weight }
                allowed = min(
                    allowed,
                    (contentWidth - minimumFace * totalWeight / minimumWeight) / (row.keys.size - 1),
                )
            }
        }
        return allowed.coerceIn(0f, maximumGap)
    }

    private fun maxScroll(): Float {
        val sc = scrollColumn ?: return 0f
        return maxOf(0f, sc.items.size * scrollCellH - scrollRegion.height())
    }

    private fun clampScroll() {
        scrollY = scrollY.coerceIn(0f, maxScroll())
    }

    private fun revealScrollIndex(index: Int) {
        val sc = scrollColumn ?: return
        if (index !in sc.items.indices || scrollCellH <= 0f) return
        val top = index * scrollCellH
        val bottom = top + scrollCellH
        val window = scrollRegion.height()
        if (top < scrollY) scrollY = top else if (bottom > scrollY + window) scrollY = bottom - window
        clampScroll()
    }

    override fun computeScroll() {
        fling.computeOffset()?.let {
            scrollY = it
            clampScroll()
            scrollbarFade.scrolled(SystemClock.uptimeMillis())
            postInvalidateOnAnimation()
        }
    }

    private fun scrollIndexAt(y: Float): Int {
        val sc = scrollColumn ?: return -1
        if (scrollCellH <= 0f || y < scrollRegion.top || y > scrollRegion.bottom) return -1
        val idx = ((y - scrollRegion.top + scrollY) / scrollCellH).toInt()
        return if (idx in sc.items.indices) idx else -1
    }

    private fun scrollLabelMinTextSize(): Float = SCROLL_LABEL_MIN_DP * density

    private fun fittedScrollLabelTextSize(label: String, baseTextSize: Float): Float {
        val avail = scrollRegion.width() - SCROLL_LABEL_INSET_DP * density
        scrollLabelPaint.textSize = baseTextSize
        val w = scrollLabelPaint.measureText(label)
        if (w <= avail || avail <= 0f) return baseTextSize
        return (baseTextSize * avail / w).coerceAtLeast(scrollLabelMinTextSize())
    }

    private fun drawScrollColumn(canvas: Canvas) {
        val sc = scrollColumn ?: return
        if (scrollRegion.isEmpty || scrollCellH <= 0f || sc.items.isEmpty()) return
        canvas.drawRoundRect(scrollRegion, keyRadius, keyRadius, scrollTrackPaint)
        canvas.save()
        canvas.clipRect(scrollRegion)
        val paint = scrollLabelPaint
        val baseTextSize = paint.textSize
        val baseColor = paint.color
        for ((i, key) in sc.items.withIndex()) {
            val top = scrollRegion.top - scrollY + i * scrollCellH
            val bottom = top + scrollCellH
            if (bottom < scrollRegion.top || top > scrollRegion.bottom) continue
            val pressLevel = if (i == scrollVisualPressedIndex) scrollPress.level else 0f
            if (pressLevel > 0f) {
                tmpRect.set(scrollRegion.left, top, scrollRegion.right, bottom)
                pressHighlight.color = Motion.stateLayerColor(palette.keyLabel, pressLevel)
                scrollTrackPath.reset()
                scrollTrackPath.addRoundRect(scrollRegion, keyRadius, keyRadius, Path.Direction.CW)
                scrollPressPath.reset()
                scrollPressPath.addRect(tmpRect, Path.Direction.CW)
                scrollPressPath.op(scrollTrackPath, Path.Op.INTERSECT)
                canvas.drawPath(scrollPressPath, pressHighlight)
            }
            val label = displayLabel(key)
            paint.color = if (key.accent) palette.lockedReading else baseColor
            paint.textSize = fittedScrollLabelTextSize(label, baseTextSize)
            paint.getTextBounds(label, 0, label.length, inkBounds)
            val cellCx = scrollRegion.centerX()
            val cellCy = (top + bottom) / 2f
            canvas.drawText(label, cellCx - inkBounds.exactCenterX(), cellCy - inkBounds.exactCenterY(), paint)
            if (i < sc.items.size - 1 && bottom < scrollRegion.bottom) {
                val ruleY = bottom.roundToInt() - sepLinePaint.strokeWidth / 2f
                canvas.drawLine(scrollRegion.left + 6 * density, ruleY, scrollRegion.right - 6 * density, ruleY, sepLinePaint)
            }
        }
        paint.textSize = baseTextSize
        paint.color = baseColor
        canvas.restore()
        val contentH = sc.items.size * scrollCellH
        val trackH = scrollRegion.height()
        val now = SystemClock.uptimeMillis()
        val alpha = scrollbarFade.alphaAt(now)
        if (contentH > trackH + 0.5f && alpha > 0f) {
            val thumbH = maxOf(18f * density, trackH * trackH / contentH)
            val thumbTop = scrollRegion.top + (scrollY / (contentH - trackH)) * (trackH - thumbH)
            val right = scrollRegion.right - 2f * density
            tmpRect.set(right - 2.5f * density, thumbTop, right, thumbTop + thumbH)
            scrollbarPaint.alpha = (SCROLLBAR_ALPHA * alpha).roundToInt()
            canvas.drawRoundRect(tmpRect, 2f * density, 2f * density, scrollbarPaint)
        }
        removeCallbacks(scrollbarTick)
        scrollbarFade.nextTickDelayMs(now)?.let { delay ->
            if (delay <= 0L) postOnAnimation(scrollbarTick) else postDelayed(scrollbarTick, delay)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.keyboardBg)
        if (placed.isEmpty()) relayout()

        drawContent(canvas)
    }

    private fun drawContent(canvas: Canvas) {
        for (p in placed) {
            val pressLevel = if (p.key == visualPressed) keyPress.level else 0f
            drawKey(canvas, p.rect, p.key.accent, p.key.rail, pressLevel)
            drawLabel(canvas, p)
        }

        drawScrollColumn(canvas)
    }

    private fun drawKey(canvas: Canvas, rect: RectF, accent: Boolean, rail: Boolean, pressLevel: Float) {
        tmpRect.set(rect)
        tmpRect.offset(0f, density)
        canvas.drawRoundRect(tmpRect, keyRadius, keyRadius, keyEdgePaint)
        if (accent) {
            fillPaint.color = palette.accentBottom
            canvas.drawRoundRect(rect, keyRadius, keyRadius, fillPaint)
            if (pressLevel > 0f) {
                pressHighlight.color = Motion.stateLayerColor(palette.keyLabel, pressLevel)
                canvas.drawRoundRect(rect, keyRadius, keyRadius, pressHighlight)
            }
            return
        }
        fillPaint.color = if (rail) palette.functionSurface else palette.keySurface
        canvas.drawRoundRect(rect, keyRadius, keyRadius, fillPaint)
        if (pressLevel > 0f) {
            pressHighlight.color = Motion.stateLayerColor(palette.keyLabel, pressLevel)
            canvas.drawRoundRect(rect, keyRadius, keyRadius, pressHighlight)
        }
    }

    private fun drawLabel(canvas: Canvas, p: Placed) {
        if (p.key.action == KeyAction.SPACE && !p.key.rail) { drawSpaceMarker(canvas, p.rect); return }
        if (p.key.action == KeyAction.TOGGLE_LANG) { drawLangToggle(canvas, p.rect); return }
        if (p.key.action == KeyAction.SHIFT) { drawShift(canvas, p.rect); return }
        if (p.key.action == KeyAction.BACKSPACE) { drawKeyGlyph(canvas, p.rect, palette.keyLabel) { c, pt, x, y, s -> Glyphs.drawBackspace(c, pt, x, y, s) }; return }
        if (p.key.action == KeyAction.ENTER) {
            drawKeyGlyph(canvas, p.rect, palette.accentLabel, functionalGlyphScale(p.rect)) { c, pt, x, y, s ->
                Glyphs.drawEnter(c, pt, x, y, s)
            }
            return
        }
        val cx = p.rect.centerX()
        val cy = p.rect.centerY()
        val display = displayLabel(p.key)
        val paint = when {
            p.key.accent -> accentLabelPaint
            display.length > 1 && p.key.action != KeyAction.COMMIT -> specialLabelPaint
            else -> labelPaint
        }
        val scale = labelScale(p.rect)
        val baseTextSize = paint.textSize
        paint.textSize = baseTextSize * scale
        if (p.key.action == KeyAction.SHOW_SYMBOLS && layout.id == LayoutId.ALPHA && display.length >= 5) {
            drawStackedLabel(canvas, p.rect, display, paint, scale)
            paint.textSize = baseTextSize
            return
        }
        if (display.length > 1) {
            val avail = p.rect.width() - 4f * density
            val w = paint.measureText(display)
            if (w > avail && avail > 0f) paint.textSize = (paint.textSize * avail / w).coerceAtLeast(11f * density * scale)
            val face = p.rect.width() - 2f * density
            val fw = paint.measureText(display)
            if (fw > face && face > 0f) paint.textSize = paint.textSize * face / fw
        }
        val labelDrop = if (p.key.sub != null) 7f * density * scale else 0f
        val onAlpha = layout.id == LayoutId.ALPHA
        val inkCentred = if (onAlpha) INK_CENTERED_GLYPHS else KEYPAD_INK_CENTERED_GLYPHS
        val horizontalInkQwertyPunctuation =
            onAlpha && lang == Lang.CN && p.key.direct &&
                display.length == 1 && display[0] in INK_CENTERED_GLYPHS
        if (horizontalInkQwertyPunctuation) {
            val baseAlign = paint.textAlign
            paint.textAlign = Paint.Align.LEFT
            paint.getTextBounds(display, 0, display.length, inkBounds)
            canvas.drawText(
                display,
                cx - inkBounds.exactCenterX(),
                cy + labelDrop - (paint.descent() + paint.ascent()) / 2,
                paint,
            )
            paint.textAlign = baseAlign
        } else if (display.length == 1 && display[0] in inkCentred) {
            val baseAlign = paint.textAlign
            paint.textAlign = Paint.Align.LEFT
            paint.getTextBounds(display, 0, display.length, inkBounds)
            canvas.drawText(display, cx - inkBounds.exactCenterX(), cy + labelDrop - inkBounds.exactCenterY(), paint)
            paint.textAlign = baseAlign
        } else {
            canvas.drawText(display, cx, cy + labelDrop - (paint.descent() + paint.ascent()) / 2, paint)
        }
        paint.textSize = baseTextSize
        if (p.key.sub != null) {
            val subBaseTextSize = subPaint.textSize
            subPaint.textSize = subBaseTextSize * scale
            val sub = p.key.sub
            if (sub.codePointCount(0, sub.length) == 1) {
                val baseAlign = subPaint.textAlign
                subPaint.textAlign = Paint.Align.LEFT
                subPaint.getTextBounds(sub, 0, sub.length, inkBounds)
                val subX = if (layout.id == LayoutId.NINE) {
                    p.rect.left + 8f * density * scale - inkBounds.left
                } else {
                    cx - inkBounds.exactCenterX()
                }
                canvas.drawText(
                    sub,
                    subX,
                    p.rect.top + 11f * density * scale -
                        if (onAlpha) (subPaint.descent() + subPaint.ascent()) / 2f else inkBounds.exactCenterY(),
                    subPaint,
                )
                subPaint.textAlign = baseAlign
            } else {
                canvas.drawText(sub, cx, p.rect.top + 15 * density * scale, subPaint)
            }
            subPaint.textSize = subBaseTextSize
        }
    }

    private fun drawStackedLabel(canvas: Canvas, rect: RectF, display: String, paint: Paint, scale: Float) {
        val head = display.substring(0, display.length / 2)
        val tail = display.substring(display.length / 2)
        val avail = rect.width() - 4f * density
        val w = maxOf(paint.measureText(head), paint.measureText(tail))
        if (w > avail && avail > 0f) paint.textSize = (paint.textSize * avail / w).coerceAtLeast(11f * density * scale)
        val face = rect.width() - 2f * density
        val fw = maxOf(paint.measureText(head), paint.measureText(tail))
        if (fw > face && face > 0f) paint.textSize = paint.textSize * face / fw
        val step = (paint.descent() - paint.ascent()) / 2f
        val baseline = rect.centerY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(head, rect.centerX(), baseline - step, paint)
        canvas.drawText(tail, rect.centerX(), baseline + step, paint)
    }

    private fun drawSpaceMarker(canvas: Canvas, rect: RectF) {
        val w = rect.width() * SPACE_MARKER_FRACTION
        val h = 2f * density
        tmpRect.set(rect.centerX() - w / 2f, rect.centerY() - h / 2f, rect.centerX() + w / 2f, rect.centerY() + h / 2f)
        spaceMarkerPaint.color = palette.keySub
        canvas.drawRoundRect(tmpRect, h / 2f, h / 2f, spaceMarkerPaint)
    }

    private fun labelScale(rect: RectF): Float = min(1f, rect.height() / rowHeight)

    private fun drawLangToggle(canvas: Canvas, rect: RectF) {
        langLabel.draw(
            canvas,
            rect,
            context.getString(R.string.lang_cn),
            context.getString(R.string.lang_en),
            lang == Lang.CN,
            labelScale(rect),
        )
    }

    private fun drawShift(canvas: Canvas, rect: RectF) {
        drawKeyGlyph(canvas, rect, if (shifted) palette.accentBottom else palette.keyLabel) { c, pt, x, y, s ->
            Glyphs.drawShift(c, pt, x, y, s, locked = shiftLocked)
        }
    }

    private inline fun drawKeyGlyph(
        canvas: Canvas,
        rect: RectF,
        color: Int,
        scale: Float = minOf(rect.width(), rect.height()) * 0.24f,
        draw: (Canvas, Paint, Float, Float, Float) -> Unit,
    ) {
        iconPaint.color = color
        draw(canvas, iconPaint, rect.centerX(), rect.centerY(), scale)
    }

    private fun functionalGlyphScale(fallback: RectF): Float {
        val rect = placed.firstOrNull { it.key.action == KeyAction.BACKSPACE }?.rect ?: fallback
        return minOf(rect.width(), rect.height()) * 0.24f
    }

    internal fun langLabelForTest(): ImeSplitLabel = langLabel
    internal fun langPlacementForTest(): ImeSplitLabel.Placement? = boundsOfActionForTest(KeyAction.TOGGLE_LANG)?.let {
        langLabel.layout(it, context.getString(R.string.lang_cn), context.getString(R.string.lang_en), lang == Lang.CN, labelScale(it))
    }
    internal fun langLeadingActiveForTest(): Boolean = lang == Lang.CN

    internal fun shiftRenderState(): String = if (shiftLocked) "LOCK" else if (shifted) "ONCE" else "OFF"

    internal fun displayLabelForTest(key: Key): String = displayLabel(key)

    internal fun scrollLabelMinTextSizeForTest(): Float = scrollLabelMinTextSize()

    internal fun scrollLabelTextSizeForTest(label: String): Float {
        val base = scrollLabelPaint.textSize
        val fitted = fittedScrollLabelTextSize(label, base)
        scrollLabelPaint.textSize = base
        return fitted
    }

    internal fun scrollLabelWidthForTest(label: String): Float {
        val base = scrollLabelPaint.textSize
        scrollLabelPaint.textSize = fittedScrollLabelTextSize(label, base)
        val width = scrollLabelPaint.measureText(label)
        scrollLabelPaint.textSize = base
        return width
    }

    internal fun centerOfActionForTest(action: KeyAction): Pair<Float, Float>? {
        if (placed.isEmpty()) relayout()
        val p = placed.firstOrNull { it.key.action == action } ?: return null
        return p.rect.centerX() to p.rect.centerY()
    }

    internal fun centerOfLabelForTest(label: String): Pair<Float, Float>? {
        if (placed.isEmpty()) relayout()
        val p = placed.firstOrNull { it.key.label == label } ?: return null
        return p.rect.centerX() to p.rect.centerY()
    }

    internal fun boundsOfActionForTest(action: KeyAction): RectF? {
        if (placed.isEmpty()) relayout()
        return placed.firstOrNull { it.key.action == action }?.rect?.let(::RectF)
    }

    internal fun boundsOfLabelForTest(label: String): RectF? {
        if (placed.isEmpty()) relayout()
        return placed.firstOrNull { it.key.label == label }?.rect?.let(::RectF)
    }

    internal fun keyBoundsForTest(): List<Pair<Key, RectF>> {
        if (placed.isEmpty()) relayout()
        return placed.map { it.key to RectF(it.rect) }
    }

    internal fun keyHitBoundsForTest(): List<Pair<Key, RectF>> {
        if (placed.isEmpty()) relayout()
        return placed.map { it.key to RectF(it.hitRect ?: it.rect) }
    }

    internal fun keyAtForTest(x: Float, y: Float): Key? {
        if (placed.isEmpty()) relayout()
        return placedAt(x, y)?.key
    }

    internal fun minimumKeyWidthForTest(): Float {
        if (placed.isEmpty()) relayout()
        return placed.minOfOrNull { it.rect.width() } ?: 0f
    }

    private fun displayLabel(key: Key): String {
        key.labelRes?.let { return context.getString(it) }
        if (key.action == KeyAction.COMMIT && key.label.length == 1 && key.label[0] in 'a'..'z') {
            return if (shifted) key.label.uppercase() else key.label
        }
        if (isNineLetterBlock(key)) {
            return key.label
        }
        return key.label
    }

    private fun isNineLetterBlock(key: Key): Boolean =
        key.action == KeyAction.COMMIT && key.label.length > 1 && key.label.all { it in 'A'..'Z' } &&
            key.output.length == 1 && key.output[0] in '2'..'9'

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (scrollColumn != null && scrollTouch.contains(event.x, event.y)) {
                    scrollPointerId = event.getPointerId(0)
                    beginScroll(event.y)
                } else {
                    activePointerId = event.getPointerId(0)
                    beginPrimary(event.x, event.y, event.eventTime)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val newIdx = event.actionIndex
                val x = event.getX(newIdx)
                val y = event.getY(newIdx)
                if (scrollColumn != null && scrollTouch.contains(x, y)) {
                    if (scrollPointerId == MotionEvent.INVALID_POINTER_ID) {
                        finishActivePrimary(event)
                        scrollPointerId = event.getPointerId(newIdx)
                        beginScroll(y)
                    }
                } else if (!inEdgeInset(x)) {
                    finishActivePrimary(event)
                    activePointerId = event.getPointerId(newIdx)
                    beginPrimary(x, y, event.eventTime)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val si = event.findPointerIndex(scrollPointerId)
                if (si >= 0) moveScroll(event.getY(si), event.eventTime)
                val ai = event.findPointerIndex(activePointerId)
                if (ai >= 0) handlePrimaryMove(event.getX(ai), event.getY(ai), event.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                if (id == scrollPointerId) {
                    endScroll(event.getY(event.actionIndex))
                    scrollPointerId = MotionEvent.INVALID_POINTER_ID
                } else if (id == activePointerId) {
                    val ai = event.actionIndex
                    finishPrimary(event.getX(ai), event.getY(ai), event.eventTime)
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
            }
            MotionEvent.ACTION_UP -> {
                if (scrollPointerId != MotionEvent.INVALID_POINTER_ID) {
                    val si = event.findPointerIndex(scrollPointerId)
                    endScroll(if (si >= 0) event.getY(si) else event.y)
                    scrollPointerId = MotionEvent.INVALID_POINTER_ID
                }
                if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    val ai = event.findPointerIndex(activePointerId)
                    if (ai >= 0) finishPrimary(event.getX(ai), event.getY(ai), event.eventTime)
                    else finishPrimary(downX, downY, event.eventTime)
                }
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> {
                if (scrollPointerId != MotionEvent.INVALID_POINTER_ID) cancelScroll()
                scrollPointerId = MotionEvent.INVALID_POINTER_ID
                cancelPrimary()
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    private fun finishActivePrimary(event: MotionEvent) {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return
        val ai = event.findPointerIndex(activePointerId)
        if (ai >= 0) finishPrimary(event.getX(ai), event.getY(ai), event.eventTime)
        else finishPrimary(downX, downY, event.eventTime)
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun inEdgeInset(x: Float): Boolean = x < edgeInset || x >= width - edgeInset

    private fun heldBounds(p: Placed): RectF = RectF(p.hitRect ?: p.rect).apply {
        if (left <= edgeInset) left = 0f
        if (right >= width - edgeInset) right = width.toFloat()
    }

    private fun beginPrimary(x: Float, y: Float, eventTime: Long) {
        downPlaced = placedAt(x, y)
        downInEdgeInset = downPlaced == null && inEdgeInset(x)
        downKey = downPlaced?.key
        setPressedKey(downKey)
        downX = x; downY = y
        downEventTime = eventTime; retargetUnlocked = false
        backspace.cancel()
        val dp = downPlaced
        val dk = downKey
        if (dk != null && dp != null) {
            if (isRepeatable(dk)) backspace.begin(x, y)
        }
    }

    private fun handlePrimaryMove(x: Float, y: Float, eventTime: Long) {
        maybeUnlockRetarget(x, y, eventTime)
        val dk = downKey
        when {
            dk != null && dk.action == KeyAction.BACKSPACE -> {
                val bounds = downPlaced?.let(::heldBounds)
                backspace.move(x, y, bounds == null || bounds.contains(x, y))
            }
            else -> {
                val k = currentTarget(x, y)
                if (k !== pressed) {
                    setPressedKey(k)
                }
            }
        }
    }

    private fun finishPrimary(x: Float, y: Float, eventTime: Long) {
        maybeUnlockRetarget(x, y, eventTime)
        val dk = downKey
        releasePressedKey()
        when {
            dk != null && dk.action == KeyAction.BACKSPACE -> {
                val bounds = downPlaced?.let(::heldBounds)
                backspace.move(x, y, bounds == null || bounds.contains(x, y))
                if (backspace.finish()) currentTarget(x, y)?.let { performClick(); emitKey(it, eventTime) }
            }
            else ->
                currentTarget(x, y)?.let { performClick(); emitKey(it, eventTime) }
        }
        downKey = null
        downPlaced = null
    }

    private fun cancelPrimary() {
        backspace.cancel()
        releasePressedKey()
        downKey = null
        downPlaced = null
    }

    private fun setPressedKey(key: Key?) {
        pressed = key
        if (key == null) {
            keyPress.release()
        } else {
            visualPressed = key
            keyPress.press()
        }
        invalidate()
    }

    private fun releasePressedKey() {
        pressed = null
        keyPress.release()
        invalidate()
    }

    private fun beginScroll(y: Float) {
        scrolling = false
        fling.onDown()
        scrollDownY = y; scrollLastY = y
        scrollPressedIndex = if (fling.stopArmed) -1 else scrollIndexAt(y)
        scrollVisualPressedIndex = scrollPressedIndex
        if (scrollPressedIndex >= 0) scrollPress.press() else scrollPress.release()
        invalidate()
    }

    private fun moveScroll(y: Float, eventTime: Long) {
        fling.addSample(eventTime, y)
        if (!scrolling && abs(y - scrollDownY) > scrollSlop) {
            scrolling = true; scrollPressedIndex = -1; scrollPress.release()
        }
        if (scrolling) {
            val before = scrollY
            scrollY += scrollLastY - y
            clampScroll()
            if (scrollY != before) scrollbarFade.scrolled(SystemClock.uptimeMillis())
            invalidate()
        }
        scrollLastY = y
    }

    private fun endScroll(y: Float) {
        val col = scrollColumn
        if (scrolling) {
            if (col != null && fling.fling(scrollY, maxScroll())) postInvalidateOnAnimation()
        } else if (col != null && !fling.stopArmed) {
            val idx = scrollIndexAt(y)
            if (idx >= 0 && idx == scrollPressedIndex) { performClick(); onKey(col.items[idx]) }
        }
        scrollPressedIndex = -1; scrolling = false
        scrollPress.release()
        invalidate()
    }

    private fun cancelScroll() {
        scrollPressedIndex = -1; scrolling = false
        scrollPress.release()
        invalidate()
    }

    internal fun scrollOffsetForTest(): Float = scrollY
    internal fun scrollbarAlphaForTest(): Float = scrollbarFade.alphaAt(SystemClock.uptimeMillis())
    internal fun scrollRegionForTest(): RectF = RectF(scrollRegion)
    internal fun scrollTouchForTest(): RectF = RectF(scrollTouch)
    internal fun scrollCellHeightForTest(): Float = scrollCellH
    internal fun scrollColumnKeysForTest(): List<Key> = scrollColumn?.items ?: emptyList()
    internal fun maxScrollForTest(): Float = maxScroll()
    internal fun isFlingingForTest(): Boolean = !fling.isFinished
    internal fun flingFinalForTest(): Float = fling.finalOffset()

    internal fun flingVelocityForTest(): Float = fling.velocity()

    private fun placedAt(x: Float, y: Float): Placed? {
        var nearest: Placed? = null
        var best = Float.MAX_VALUE
        var explicitHits = false
        for (p in placed) {
            val hitRect = p.hitRect
            if (hitRect != null) {
                explicitHits = true
                if (hitRect.contains(x, y)) return p
                continue
            }
            if (p.rect.contains(x, y)) return p
            val dx = when {
                x < p.rect.left -> p.rect.left - x
                x > p.rect.right -> x - p.rect.right
                else -> 0f
            }
            val dy = when {
                y < p.rect.top -> p.rect.top - y
                y > p.rect.bottom -> y - p.rect.bottom
                else -> 0f
            }
            val d = dx * dx + dy * dy
            if (d < best) { best = d; nearest = p }
        }

        if (explicitHits) return null

        val cap = snapCap
        val boundedCap = minOf(cap, (nearest?.rect?.height() ?: 0f) * 0.5f)
        return if (best <= boundedCap * boundedCap) nearest else null
    }

    private fun maybeUnlockRetarget(x: Float, y: Float, eventTime: Long) {
        if (retargetUnlocked) return
        val dp = downPlaced ?: return
        val m = retargetHysteresis
        val hitRect = dp.hitRect ?: dp.rect
        val insideDownKey = x >= hitRect.left - m && x <= hitRect.right + m &&
            y >= hitRect.top - m && y <= hitRect.bottom + m
        if (insideDownKey) return
        val deliberate = eventTime - downEventTime >= RETARGET_HOLD_MS ||
            hypot(x - downX, y - downY) >= retargetDistance
        if (deliberate) retargetUnlocked = true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(scrollbarTick)
        backspace.cancel()
        super.onDetachedFromWindow()
    }

    private fun currentTarget(x: Float, y: Float): Key? {
        val dp = downPlaced ?: return if (downInEdgeInset) null else placedAt(x, y)?.key
        if (!retargetUnlocked) return dp.key
        val m = retargetHysteresis
        val hitRect = dp.hitRect ?: dp.rect
        val insideDownKey = x >= hitRect.left - m && x <= hitRect.right + m &&
            y >= hitRect.top - m && y <= hitRect.bottom + m
        return if (insideDownKey) dp.key else placedAt(x, y)?.key ?: dp.key
    }

    private fun emitKey(key: Key, eventTime: Long) {
        if (key.action == KeyAction.SHIFT) {
            if (lastShiftTapTime != 0L && eventTime - lastShiftTapTime <= doubleTapMs) {
                lastShiftTapTime = 0L
                onKey(Key(key.label, action = KeyAction.SHIFT_LOCK))
            } else {
                lastShiftTapTime = eventTime
                onKey(key)
            }
            return
        }
        lastShiftTapTime = 0L
        onKey(key)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    internal companion object {
        const val KEY_GAP_DP = 6f
        private const val FACE_FLOOR_ROUNDING_PX = 0.001f

        fun scrollCellHeight(column: ScrollColumn, keyboardHeight: Float, verticalGap: Float): Float {
            val visible = (column.h / column.cellHFrac).roundToInt().coerceAtLeast(1)
            return (column.h * keyboardHeight - 2f * verticalGap) / visible
        }

        const val RETARGET_HOLD_MS = 120L
        const val INK_CENTERED_GLYPHS = "，。"
        const val KEYPAD_INK_CENTERED_GLYPHS = "，。,."
        const val SCROLL_LABEL_INSET_DP = 12f
        const val SCROLLBAR_ALPHA = 0x55
        const val SPACE_MARKER_FRACTION = 0.34f
        const val SCROLL_LABEL_MIN_DP = 11f
    }
}

class FlingScroller(context: Context) {
    private val scroller = OverScroller(context)
    private val minVel = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxVel = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val sampleT = LongArray(SAMPLES)
    private val samplePos = FloatArray(SAMPLES)
    private var head = 0
    private var count = 0

    var stopArmed = false
        private set

    fun onDown() {
        stopArmed = !scroller.isFinished
        if (stopArmed) scroller.forceFinished(true)
        count = 0; head = 0
    }

    fun forceFinish() {
        scroller.forceFinished(true)
        stopArmed = false
        count = 0; head = 0
    }

    fun addSample(t: Long, pos: Float) {
        sampleT[head] = t; samplePos[head] = pos
        head = (head + 1) % SAMPLES
        if (count < SAMPLES) count++
    }

    fun velocity(): Float {
        if (count < 2) return 0f
        val newest = (head - 1 + SAMPLES) % SAMPLES
        val tNew = sampleT[newest]; val pNew = samplePos[newest]
        var ref = newest
        for (k in 1 until count) {
            val idx = (newest - k + SAMPLES) % SAMPLES
            ref = idx
            if (tNew - sampleT[idx] >= WINDOW_MS) break
        }
        val dt = (tNew - sampleT[ref]).toFloat()
        if (dt <= 0f) return 0f
        return ((pNew - samplePos[ref]) / dt * 1000f).coerceIn(-maxVel, maxVel)
    }

    fun fling(start: Float, max: Float): Boolean {
        val v = velocity()
        if (kotlin.math.abs(v) <= minVel || max <= 0f) return false
        scroller.fling(0, start.toInt(), 0, (-v).toInt(), 0, 0, 0, max.toInt())
        return true
    }

    fun predictFinalOffset(start: Float): Float {
        val v = velocity()
        if (kotlin.math.abs(v) <= minVel) return start
        scroller.fling(0, start.toInt(), 0, (-v).toInt(), 0, 0, 0, Int.MAX_VALUE)
        val end = scroller.finalY.toFloat()
        scroller.forceFinished(true)
        return end
    }

    fun computeOffset(): Float? = if (scroller.computeScrollOffset()) scroller.currY.toFloat() else null

    val isFinished: Boolean get() = scroller.isFinished

    fun finalOffset(): Float = scroller.finalY.toFloat()

    private companion object {
        const val SAMPLES = 12
        const val WINDOW_MS = 100L
    }
}
