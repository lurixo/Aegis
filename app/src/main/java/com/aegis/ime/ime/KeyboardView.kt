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
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
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
import com.aegis.ime.ui.LetterCase

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
    private var scrollCellH = 0f
    private var scrollY = 0f
    private var scrollPressedIndex = -1
    private var scrollVisualPressedIndex = -1
    private val scrollPress = Motion.PressFeedback(this)
    private var inScrollDown = false
    private var scrollDownY = 0f
    private var scrollLastY = 0f
    private var scrolling = false
    private val tmpRect = RectF()
    private val scrollSlop = 6f * resources.displayMetrics.density
    private val fling = FlingScroller(context)

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var downKey: Key? = null
    private var downPlaced: Placed? = null
    private var downX = 0f
    private var downY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var repeating = false
    private var swiped = false
    private var vSwipeDir = 0
    private val swipeThreshold = 24f * resources.displayMetrics.density
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val k = downKey ?: return
            repeating = true
            onKey(k)
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private val longPressRunnable = Runnable {
        val dk = downKey ?: return@Runnable
        val dp = downPlaced ?: return@Runnable
        if (lang != Lang.EN || !isAlphaLetter(dk)) return@Runnable
        hidePreview()
        caseBoxKey = dk
        previewRect.set(dp.rect)
        caseBoxActive = true
        caseBoxMoved = false
        caseBoxSelected = -1
        previewFeedback.press()
        invalidate()
    }

    private fun cancelKeyHold() {
        repeatHandler.removeCallbacks(repeatRunnable)
        repeatHandler.removeCallbacks(longPressRunnable)
    }

    private fun isRepeatable(key: Key) = key.action == KeyAction.BACKSPACE

    private fun isAlphaLetter(key: Key) =
        layout.id == LayoutId.ALPHA && key.action == KeyAction.COMMIT &&
            key.label.length == 1 && key.label[0] in 'a'..'z'

    private fun isNineDigit(key: Key) =
        layout.id == LayoutId.NINE && key.action == KeyAction.COMMIT

    private val density = resources.displayMetrics.density
    private val rowHeight = 52f * density
    private val snapCap = rowHeight * 0.5f
    private val retargetHysteresis = 4f * density
    private val shortPageRowExtra = 2f * density
    private val gap = 6f * density
    private val keyRadius = ImeShapes.keyRadiusDp * density

    var hapticEnabled = false
    var previewNineEnabled = false
    var previewAlphaEnabled = false
    var caseMode: LetterCase = LetterCase.AUTO
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    private var previewKey: Key? = null
    private val previewRect = RectF()
    private val previewFeedback = Motion.PressFeedback(this)
    private var caseBoxKey: Key? = null
    private var caseBoxActive = false
    private var caseBoxSelected = -1
    private var caseBoxMoved = false
    private val caseBoxSlop = 12f * density

    private var palette = ImePalette.STATIC_LIGHT


    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(20f); typeface = android.graphics.Typeface.DEFAULT }
    private val specialLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabelSecondary; textAlign = Paint.Align.CENTER; textSize = sp(15f); typeface = android.graphics.Typeface.DEFAULT }
    private val boldLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(18f); typeface = android.graphics.Typeface.DEFAULT }
    private val shiftActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentBottom; textAlign = Paint.Align.CENTER; textSize = sp(20f) }
    private val accentLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentLabel; textAlign = Paint.Align.CENTER; textSize = sp(20f); typeface = android.graphics.Typeface.DEFAULT }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyHint; textAlign = Paint.Align.RIGHT; textSize = sp(10f); typeface = android.graphics.Typeface.DEFAULT }
    private val langActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabelSecondary; textAlign = Paint.Align.CENTER; textSize = sp(17f); typeface = android.graphics.Typeface.DEFAULT }
    private val langSmallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyHint; textAlign = Paint.Align.RIGHT; textSize = sp(11f); typeface = android.graphics.Typeface.DEFAULT }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keySurface }
    private val sepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.separator; strokeWidth = density }
    private val pressHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(palette.keyLabel, 0x22) }
    private val scrollTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.railBg }
    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(palette.icon, 0x55) }
    private val scrollLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.LEFT; textSize = sp(17f); typeface = android.graphics.Typeface.DEFAULT }
    private val inkBounds = android.graphics.Rect()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val previewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keySurface }
    private val previewOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density; color = palette.separator }
    private val previewLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(30f); typeface = android.graphics.Typeface.DEFAULT }

    fun applyPalette(p: ImePalette) {
        palette = p
        labelPaint.color = p.keyLabel
        specialLabelPaint.color = p.keyLabelSecondary
        boldLabelPaint.color = p.keyLabel
        shiftActivePaint.color = p.accentBottom
        accentLabelPaint.color = p.accentLabel
        subPaint.color = p.keyHint
        langActivePaint.color = p.keyLabelSecondary
        langSmallPaint.color = p.keyHint
        sepLinePaint.color = p.separator
        pressHighlight.color = Motion.withAlpha(p.keyLabel, 0x22)
        scrollTrackPaint.color = p.railBg
        scrollbarPaint.color = withAlpha(p.icon, 0x55)
        scrollLabelPaint.color = p.keyLabel
        previewFillPaint.color = p.keySurface
        previewOutlinePaint.color = p.separator
        previewLabelPaint.color = p.keyLabel
        invalidate()
    }

    private fun withAlpha(argb: Int, alpha: Int): Int = Motion.withAlpha(argb, alpha)

    private data class Placed(val rect: RectF, val key: Key, val groupId: Int = 0, val hitRect: RectF? = null)

    fun setLayout(newLayout: KeyboardLayout, isShifted: Boolean, isLocked: Boolean, language: Lang) {
        if (newLayout == layout && isShifted == shifted && isLocked == shiftLocked && language == lang) return
        layoutApplies++
        val sameColumn = newLayout.scrollColumn?.items?.map { it.label } == layout.scrollColumn?.items?.map { it.label }
        val modeChanged = newLayout.id != layout.id
        layout = newLayout
        shifted = isShifted
        shiftLocked = isLocked
        lang = language
        scrollColumn = newLayout.scrollColumn
        if (!sameColumn) { fling.forceFinish(); scrollY = 0f }
        if (width > 0) relayout()
        requestLayout()
        invalidate()
        if (modeChanged && width > 0) { modeSwitches++ }
    }

    internal fun modeSwitchesForTest(): Int = modeSwitches

    internal fun layoutAppliesForTest(): Int = layoutApplies

    internal fun rowCountForSizing(): Int = layout.rowCount
    internal fun usesFractionalCellsForSizing(): Boolean = layout.cells != null && layout.id != LayoutId.ALPHA

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
        val horizontalGap = effectiveHorizontalGap(w, if (compactGrid || gapGeometry.id == LayoutId.ALPHA) gap / 2f else gap, gapGeometry)
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
                sc.x * w + horizontalGap,
                sc.y * h + verticalGap,
                (sc.x + sc.w) * w - horizontalGap,
                (sc.y + sc.h) * h - verticalGap,
            )
            val visible = (sc.h / sc.cellHFrac).roundToInt().coerceAtLeast(1)
            scrollCellH = scrollRegion.height() / visible
            clampScroll()
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
                val hitTop = if (layout.id == LayoutId.ALPHA) top - verticalGap / 2f else pk.y * h
                val hitBottom = if (layout.id == LayoutId.ALPHA) bottom + verticalGap / 2f else (pk.y + pk.h) * h
                placed.add(
                    Placed(
                        RectF(
                            pk.x * w + horizontalGap,
                            top,
                            (pk.x + pk.w) * w - horizontalGap,
                            bottom,
                        ),
                        pk.key, pk.groupId,
                        RectF(pk.x * w, hitTop, (pk.x + pk.w) * w, hitBottom),
                    ),
                )
            }
            return
        }
        if (layout.id == LayoutId.NUMBER || layout.id == LayoutId.SYMBOL) {
            val faceGap = horizontalGap * 2f
            val ordinaryWidth = w / 10f - faceGap
            val availableFaceHeight = (h - (verticalRows + 1) * verticalGap) / verticalRows
            val faceHeight = minOf(rowHeight, availableFaceHeight)
            var top = (h - layout.rowCount * faceHeight - (layout.rowCount - 1) * verticalGap) / 2f
            for (rowItem in layout.rows) {
                val finalRow = rowItem.keys.any { it.action == KeyAction.SPACE }
                val widths = if (finalRow) {
                    val nonSpaceWidth = rowItem.keys
                        .filter { it.action != KeyAction.SPACE }
                        .sumOf { (ordinaryWidth * it.weight).toDouble() }
                        .toFloat()
                    val spaceWidth = w - horizontalGap * 2f - faceGap * (rowItem.keys.size - 1) - nonSpaceWidth
                    rowItem.keys.map { if (it.action == KeyAction.SPACE) spaceWidth else ordinaryWidth * it.weight }
                } else {
                    rowItem.keys.map { ordinaryWidth * it.weight }
                }
                val rowWidth = widths.sum() + faceGap * (rowItem.keys.size - 1)
                var left = (w - rowWidth) / 2f
                for ((key, keyWidth) in rowItem.keys.zip(widths)) {
                    val rect = RectF(left, top, left + keyWidth, top + faceHeight)
                    val hitRect = RectF(
                        rect.left - horizontalGap,
                        rect.top - verticalGap / 2f,
                        rect.right + horizontalGap,
                        rect.bottom + verticalGap / 2f,
                    )
                    placed.add(Placed(rect, key, hitRect = hitRect))
                    left += keyWidth + faceGap
                }
                top += faceHeight + verticalGap
            }
            return
        }
        val rh = (h - (layout.rowCount + 1) * verticalGap) / layout.rowCount
        var top = verticalGap
        for (rowItem in layout.rows) {
            val totalWeight = rowItem.keys.sumOf { it.weight.toDouble() }.toFloat()
            val usable = w - 2 * horizontalGap - (rowItem.keys.size - 1) * horizontalGap
            var left = horizontalGap
            for (key in rowItem.keys) {
                val keyW = usable * (key.weight / totalWeight)
                placed.add(Placed(RectF(left, top, left + keyW, top + rh), key))
                left += keyW + horizontalGap
            }
            top += rh + verticalGap
        }
    }

    private fun effectiveHorizontalGap(viewWidth: Float, maximumGap: Float, geometry: KeyboardLayout): Float {
        if (viewWidth <= 0f) return 0f
        val minimumFace = 20f * density
        var allowed = maximumGap
        val cells = geometry.cells
        if (cells != null) {
            val minimumFraction = cells.minOfOrNull { it.w } ?: 1f
            allowed = min(allowed, (minimumFraction * viewWidth - minimumFace) / 2f)
        } else {
            for (row in geometry.rows) {
                if (row.keys.isEmpty()) continue
                val totalWeight = row.keys.sumOf { it.weight.toDouble() }.toFloat()
                val minimumWeight = row.keys.minOf { it.weight }
                val gapCount = row.keys.size + 1
                allowed = min(
                    allowed,
                    (viewWidth - minimumFace * totalWeight / minimumWeight) / gapCount,
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

    override fun computeScroll() {
        fling.computeOffset()?.let {
            scrollY = it
            clampScroll()
            postInvalidateOnAnimation()
        }
    }

    private fun scrollIndexAt(y: Float): Int {
        val sc = scrollColumn ?: return -1
        if (scrollCellH <= 0f || y < scrollRegion.top || y > scrollRegion.bottom) return -1
        val idx = ((y - scrollRegion.top + scrollY) / scrollCellH).toInt()
        return if (idx in sc.items.indices) idx else -1
    }

    private fun drawScrollColumn(canvas: Canvas) {
        val sc = scrollColumn ?: return
        if (scrollRegion.isEmpty || scrollCellH <= 0f || sc.items.isEmpty()) return
        canvas.drawRoundRect(scrollRegion, keyRadius, keyRadius, scrollTrackPaint)
        canvas.save()
        canvas.clipRect(scrollRegion)
        val paint = scrollLabelPaint
        val baseTextSize = paint.textSize
        val avail = scrollRegion.width() - 12f * density
        val minTextSize = 11f * density
        for ((i, key) in sc.items.withIndex()) {
            val top = scrollRegion.top - scrollY + i * scrollCellH
            val bottom = top + scrollCellH
            if (bottom < scrollRegion.top || top > scrollRegion.bottom) continue
            val pressLevel = if (i == scrollVisualPressedIndex) scrollPress.level else 0f
            if (pressLevel > 0f) {
                tmpRect.set(scrollRegion.left, top, scrollRegion.right, bottom)
                pressHighlight.color = Motion.stateLayerColor(palette.keyLabel, pressLevel)
                canvas.drawRoundRect(tmpRect, keyRadius * 0.6f, keyRadius * 0.6f, pressHighlight)
            }
            val label = displayLabel(key)
            paint.textSize = baseTextSize
            val w = paint.measureText(label)
            if (w > avail && avail > 0f) paint.textSize = (baseTextSize * avail / w).coerceAtLeast(minTextSize)
            paint.getTextBounds(label, 0, label.length, inkBounds)
            val cellCx = scrollRegion.centerX()
            val cellCy = (top + bottom) / 2f
            canvas.drawText(label, cellCx - inkBounds.exactCenterX(), cellCy - inkBounds.exactCenterY(), paint)
            if (i < sc.items.size - 1 && bottom < scrollRegion.bottom) {
                canvas.drawLine(scrollRegion.left + 6 * density, bottom, scrollRegion.right - 6 * density, bottom, sepLinePaint)
            }
        }
        paint.textSize = baseTextSize
        canvas.restore()
        val contentH = sc.items.size * scrollCellH
        val trackH = scrollRegion.height()
        if (contentH > trackH + 0.5f) {
            val thumbH = maxOf(18f * density, trackH * trackH / contentH)
            val thumbTop = scrollRegion.top + (scrollY / (contentH - trackH)) * (trackH - thumbH)
            val right = scrollRegion.right - 2f * density
            tmpRect.set(right - 2.5f * density, thumbTop, right, thumbTop + thumbH)
            canvas.drawRoundRect(tmpRect, 2f * density, 2f * density, scrollbarPaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.keyboardBg)
        if (placed.isEmpty()) relayout()

        for (p in placed) {
            val pressLevel = if (p.key == visualPressed) keyPress.level else 0f
            drawKey(canvas, p.rect, p.key.accent, pressLevel)
            drawLabel(canvas, p)
        }

        drawScrollColumn(canvas)

        drawPreview(canvas)
    }

    private fun drawPreview(canvas: Canvas) {
        if (caseBoxActive) { drawCaseBox(canvas); return }
        val key = previewKey ?: return
        val level = previewFeedback.level
        if (level <= 0f) return
        val bw = previewRect.width() * 1.32f
        val bh = previewRect.height() * 1.12f
        val cx = previewRect.centerX().coerceIn(bw / 2f, width - bw / 2f)
        var top = previewRect.top - bh - 4f * density
        if (top < 0f) top = 0f
        tmpRect.set(cx - bw / 2f, top, cx + bw / 2f, top + bh)
        val alpha = (255 * level).toInt().coerceIn(0, 255)
        val scale = 0.86f + 0.14f * level
        canvas.save()
        canvas.scale(scale, scale, tmpRect.centerX(), tmpRect.bottom)
        previewFillPaint.alpha = alpha
        canvas.drawRoundRect(tmpRect, keyRadius, keyRadius, previewFillPaint)
        previewOutlinePaint.alpha = alpha
        canvas.drawRoundRect(tmpRect, keyRadius, keyRadius, previewOutlinePaint)
        previewFillPaint.alpha = 255
        previewOutlinePaint.alpha = 255
        previewLabelPaint.alpha = alpha
        drawFittedPreviewLabel(canvas, displayLabel(key), tmpRect)
        previewLabelPaint.alpha = 255
        canvas.restore()
    }

    private fun drawFittedPreviewLabel(canvas: Canvas, label: String, box: RectF) {
        if (label.isEmpty()) return
        val base = previewLabelPaint.textSize
        val avail = box.width() - 12f * density
        val w = previewLabelPaint.measureText(label)
        if (w > avail && avail > 0f) previewLabelPaint.textSize = (base * avail / w).coerceAtLeast(14f * density)
        canvas.drawText(
            label,
            box.centerX(),
            box.centerY() - (previewLabelPaint.descent() + previewLabelPaint.ascent()) / 2f,
            previewLabelPaint,
        )
        previewLabelPaint.textSize = base
    }

    private fun drawCaseBox(canvas: Canvas) {
        val key = caseBoxKey ?: return
        val level = previewFeedback.level
        if (level <= 0f) return
        val cellW = caseBoxCellW()
        val cellH = previewRect.height() * 1.12f
        val left = caseBoxLeft()
        var top = previewRect.top - cellH - 4f * density
        if (top < 0f) top = 0f
        val alpha = (255 * level).toInt().coerceIn(0, 255)
        val scale = 0.86f + 0.14f * level
        val labels = caseBoxLabels(key)
        canvas.save()
        canvas.scale(scale, scale, left + cellW * 1.5f, top + cellH)
        for (i in 0..2) {
            val cellLeft = left + i * cellW
            tmpRect.set(cellLeft, top, cellLeft + cellW, top + cellH)
            previewFillPaint.color = if (i == caseBoxSelected) palette.accentBottom else palette.keySurface
            previewFillPaint.alpha = alpha
            canvas.drawRoundRect(tmpRect, keyRadius, keyRadius, previewFillPaint)
            previewOutlinePaint.alpha = alpha
            canvas.drawRoundRect(tmpRect, keyRadius, keyRadius, previewOutlinePaint)
            previewLabelPaint.color = if (i == caseBoxSelected) palette.accentLabel else palette.keyLabel
            previewLabelPaint.alpha = alpha
            drawFittedPreviewLabel(canvas, labels[i], tmpRect)
        }
        previewFillPaint.color = palette.keySurface
        previewFillPaint.alpha = 255
        previewOutlinePaint.alpha = 255
        previewLabelPaint.color = palette.keyLabel
        previewLabelPaint.alpha = 255
        canvas.restore()
    }

    private fun drawKey(canvas: Canvas, rect: RectF, accent: Boolean, pressLevel: Float) {
        if (accent) {
            fillPaint.color = palette.accentBottom
            canvas.drawRoundRect(rect, keyRadius, keyRadius, fillPaint)
            if (pressLevel > 0f) {
                pressHighlight.color = Motion.stateLayerColor(palette.keyLabel, pressLevel)
                canvas.drawRoundRect(rect, keyRadius, keyRadius, pressHighlight)
            }
            return
        }
        fillPaint.color = palette.keySurface
        canvas.drawRoundRect(rect, keyRadius, keyRadius, fillPaint)
        if (pressLevel > 0f) {
            pressHighlight.color = Motion.stateLayerColor(palette.keyLabel, pressLevel)
            canvas.drawRoundRect(rect, keyRadius, keyRadius, pressHighlight)
        }
    }

    private fun drawLabel(canvas: Canvas, p: Placed) {
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
            p.key.bold -> boldLabelPaint
            display.length > 1 && p.key.action != KeyAction.COMMIT -> specialLabelPaint
            else -> labelPaint
        }
        val baseTextSize = paint.textSize
        if (display.length > 1) {
            val avail = p.rect.width() - 14f * density
            val w = paint.measureText(display)
            if (w > avail && avail > 0f) paint.textSize = (baseTextSize * avail / w).coerceAtLeast(11f * density)
        }
        canvas.drawText(display, cx, cy - (paint.descent() + paint.ascent()) / 2, paint)
        paint.textSize = baseTextSize
        if (p.key.sub != null) {
            canvas.drawText(p.key.sub, p.rect.right - 6 * density, p.rect.top + 15 * density, subPaint)
        }
    }

    private fun drawLangToggle(canvas: Canvas, rect: RectF) {
        val cn = context.getString(R.string.lang_cn)
        val en = context.getString(R.string.lang_en)
        val active = if (lang == Lang.CN) cn else en
        val small = if (lang == Lang.CN) en else cn
        val baseline = rect.centerY() - (langActivePaint.descent() + langActivePaint.ascent()) / 2
        canvas.drawText(active, rect.centerX(), baseline, langActivePaint)
        canvas.drawText(small, rect.right - 5 * density, rect.bottom - 6 * density, langSmallPaint)
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

    internal fun shiftRenderState(): String = if (shiftLocked) "LOCK" else if (shifted) "ONCE" else "OFF"

    internal fun previewLabelForTest(): String? = previewKey?.let { displayLabel(it) }
    internal fun previewActiveForTest(): Boolean = previewKey != null

    internal fun displayLabelForTest(key: Key): String = displayLabel(key)

    internal fun keyLabelPaintsUseNormalWeightForTest(): Boolean = listOf(
        labelPaint,
        specialLabelPaint,
        boldLabelPaint,
        accentLabelPaint,
        subPaint,
        langActivePaint,
        langSmallPaint,
        scrollLabelPaint,
        previewLabelPaint,
    ).all { it.typeface === android.graphics.Typeface.DEFAULT }

    internal fun enterGlyphBoundsForTest(): RectF? {
        val rect = boundsOfActionForTest(KeyAction.ENTER) ?: return null
        return Glyphs.enterBounds(rect.centerX(), rect.centerY(), functionalGlyphScale(rect))
    }

    internal fun caseBoxActiveForTest(): Boolean = caseBoxActive
    internal fun caseBoxLabelsForTest(): List<String>? = caseBoxKey?.let { caseBoxLabels(it) }
    internal fun caseBoxSelectedForTest(): Int = caseBoxSelected

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
            return when (caseMode) {
                LetterCase.UPPER -> key.label.uppercase()
                LetterCase.LOWER -> key.label.lowercase()
                LetterCase.AUTO -> if (shifted) key.label.uppercase() else key.label
            }
        }
        if (isNineLetterBlock(key)) {
            return when (caseMode) {
                LetterCase.UPPER -> key.label.uppercase()
                LetterCase.LOWER -> key.label.lowercase()
                LetterCase.AUTO -> key.label
            }
        }
        return key.label
    }

    private fun isNineLetterBlock(key: Key): Boolean =
        key.action == KeyAction.COMMIT && key.label.length > 1 && key.label.all { it in 'A'..'Z' } &&
            key.output.length == 1 && key.output[0] in '2'..'9'

    private fun caseBoxLabels(key: Key): List<String> =
        listOf(key.label.uppercase(), key.sub ?: "", key.label.lowercase())

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            inScrollDown = scrollColumn != null && scrollRegion.contains(event.x, event.y)
        }
        if (inScrollDown) return handleScrollTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                beginPrimary(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val newIdx = event.actionIndex
                if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    val ai = event.findPointerIndex(activePointerId)
                    if (ai >= 0) finishPrimary(event.getX(ai), event.getY(ai), event.eventTime)
                    else finishPrimary(downX, downY, event.eventTime)
                }
                activePointerId = event.getPointerId(newIdx)
                beginPrimary(event.getX(newIdx), event.getY(newIdx))
            }
            MotionEvent.ACTION_MOVE -> {
                val ai = event.findPointerIndex(activePointerId)
                if (ai >= 0) handlePrimaryMove(event.getX(ai), event.getY(ai))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val ai = event.actionIndex
                    finishPrimary(event.getX(ai), event.getY(ai), event.eventTime)
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    val ai = event.findPointerIndex(activePointerId)
                    if (ai >= 0) finishPrimary(event.getX(ai), event.getY(ai), event.eventTime)
                    else finishPrimary(downX, downY, event.eventTime)
                }
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPrimary()
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    private fun beginPrimary(x: Float, y: Float) {
        downPlaced = placedAt(x, y)
        downKey = downPlaced?.key
        setPressedKey(downKey)
        downX = x; downY = y
        repeating = false; swiped = false; vSwipeDir = 0
        caseBoxActive = false; caseBoxKey = null; caseBoxSelected = -1; caseBoxMoved = false
        val dp = downPlaced
        val dk = downKey
        if (dk != null && dp != null) {
            if (isRepeatable(dk)) repeatHandler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
            else if (lang == Lang.EN && isAlphaLetter(dk)) repeatHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showPreview(dk, dp.rect)
        } else {
            hidePreview()
        }
    }

    private fun isPreviewable(key: Key) =
        key.action == KeyAction.COMMIT || key.action == KeyAction.SEGMENT

    private fun previewEnabledForCurrentLayout(): Boolean = when (layout.id) {
        LayoutId.NINE, LayoutId.NUMPAD -> previewNineEnabled
        LayoutId.ALPHA, LayoutId.NUMBER, LayoutId.SYMBOL -> previewAlphaEnabled
    }

    private fun showPreview(key: Key, rect: RectF) {
        if (!previewEnabledForCurrentLayout() || !isPreviewable(key)) { hidePreview(); return }
        previewKey = key
        previewRect.set(rect)
        previewFeedback.press()
        invalidate()
    }

    private fun hidePreview() {
        if (previewKey == null) return
        previewKey = null
        previewFeedback.reset()
        invalidate()
    }

    private fun clearCaseBox() {
        if (!caseBoxActive && caseBoxKey == null) return
        caseBoxActive = false
        caseBoxKey = null
        caseBoxSelected = -1
        caseBoxMoved = false
        previewFeedback.reset()
        invalidate()
    }

    private fun caseBoxCellW(): Float = previewRect.width() * 1.15f
    private fun caseBoxLeft(): Float {
        val boxW = caseBoxCellW() * 3f
        return previewRect.centerX().coerceIn(boxW / 2f, width - boxW / 2f) - boxW / 2f
    }

    private fun caseBoxSelectionAt(x: Float): Int =
        ((x - caseBoxLeft()) / caseBoxCellW()).toInt().coerceIn(0, 2)

    private fun handlePrimaryMove(x: Float, y: Float) {
        val dk = downKey
        when {
            caseBoxActive -> {
                if (abs(x - downX) > caseBoxSlop || abs(y - downY) > caseBoxSlop) caseBoxMoved = true
                val newSel = if (caseBoxMoved) caseBoxSelectionAt(x) else -1
                if (newSel != caseBoxSelected) { caseBoxSelected = newSel; invalidate() }
            }
            dk != null && dk.action == KeyAction.BACKSPACE -> {
                val dy = y - downY
                if (!swiped && abs(dy) > swipeThreshold && abs(dy) > abs(x - downX)) {
                    swiped = true
                    cancelKeyHold()
                }
            }
            dk != null && lang == Lang.EN && isAlphaLetter(dk) -> {
                val dy = y - downY
                if (!swiped && abs(dy) > swipeThreshold && abs(dy) > abs(x - downX)) {
                    swiped = true
                    vSwipeDir = if (dy < 0) -1 else 1
                    cancelKeyHold()
                    hidePreview()
                } else if (!swiped) {
                    val k = currentTarget(x, y)
                    if (k !== pressed) {
                        setPressedKey(k)
                        if (k !== downKey) { cancelKeyHold(); hidePreview() }
                    }
                }
            }
            dk != null && isNineDigit(dk) -> {
                val dx = x - downX
                val dy = y - downY
                if (abs(dy) > abs(dx)) {
                    if (pressed !== downKey) setPressedKey(downKey)
                } else {
                    val k = currentTarget(x, y)
                    if (k !== pressed) {
                        setPressedKey(k)
                        if (k !== downKey) { cancelKeyHold(); hidePreview() }
                    }
                }
            }
            else -> {
                val k = currentTarget(x, y)
                if (k !== pressed) {
                    setPressedKey(k)
                    if (k !== downKey) { cancelKeyHold(); hidePreview() }
                }
            }
        }
    }

    private fun finishPrimary(x: Float, y: Float, eventTime: Long) {
        cancelKeyHold()
        val dk = downKey
        val stickyPressed = pressed
        hidePreview()
        releasePressedKey()
        when {
            dk != null && caseBoxActive -> {
                performClick()
                when (caseBoxSelected) {
                    0 -> onKey(Key(dk.label.uppercase(), output = dk.label.uppercase(), direct = true))
                    1 -> dk.sub?.let { s -> onKey(Key(s, output = s, direct = true)) } ?: emitKey(dk, eventTime)
                    2 -> onKey(Key(dk.label.lowercase(), output = dk.label.lowercase(), direct = true))
                    else -> emitKey(dk, eventTime)
                }
            }
            dk != null && dk.action == KeyAction.BACKSPACE && swiped && !repeating ->
                onBackspaceSwipe(y - downY < 0)
            dk != null && lang == Lang.EN && isAlphaLetter(dk) && swiped && !repeating -> {
                performClick()
                if (vSwipeDir < 0 && dk.sub != null) onKey(Key(dk.sub, output = dk.sub, direct = true))
                else onKey(dk)
            }
            dk != null && isNineDigit(dk) && !repeating -> {
                performClick(); emitKey(stickyPressed ?: dk, eventTime)
            }
            !repeating ->
                currentTarget(x, y)?.let { performClick(); emitKey(it, eventTime) }
        }
        downKey = null
        downPlaced = null
        clearCaseBox()
    }

    private fun cancelPrimary() {
        cancelKeyHold()
        hidePreview()
        clearCaseBox()
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

    private fun handleScrollTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inScrollDown = true; scrolling = false
                fling.onDown()
                scrollDownY = event.y; scrollLastY = event.y
                scrollPressedIndex = if (fling.stopArmed) -1 else scrollIndexAt(event.y)
                scrollVisualPressedIndex = scrollPressedIndex
                if (scrollPressedIndex >= 0) scrollPress.press() else scrollPress.release()
                showScrollPreview()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                fling.addSample(event.eventTime, event.y)
                if (!scrolling && abs(event.y - scrollDownY) > scrollSlop) {
                    scrolling = true; scrollPressedIndex = -1; scrollPress.release()
                    hidePreview()
                }
                if (scrolling) {
                    scrollY += scrollLastY - event.y
                    clampScroll(); invalidate()
                }
                scrollLastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val col = scrollColumn
                if (scrolling) {
                    if (col != null && fling.fling(scrollY, maxScroll())) postInvalidateOnAnimation()
                } else if (col != null && !fling.stopArmed) {
                    val idx = scrollIndexAt(event.y)
                    if (idx >= 0 && idx == scrollPressedIndex) { performClick(); onKey(col.items[idx]) }
                }
                scrollPressedIndex = -1; inScrollDown = false; scrolling = false
                scrollPress.release()
                hidePreview()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                scrollPressedIndex = -1; inScrollDown = false; scrolling = false
                scrollPress.release()
                hidePreview()
                invalidate()
            }
        }
        return true
    }

    private fun showScrollPreview() {
        val sc = scrollColumn ?: return
        val idx = scrollPressedIndex
        if (idx !in sc.items.indices || scrollCellH <= 0f) return
        val top = scrollRegion.top - scrollY + idx * scrollCellH
        tmpRect.set(scrollRegion.left, top, scrollRegion.right, top + scrollCellH)
        showPreview(sc.items[idx], tmpRect)
    }

    internal fun scrollOffsetForTest(): Float = scrollY
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

    private fun currentTarget(x: Float, y: Float): Key? {
        val dp = downPlaced ?: return placedAt(x, y)?.key
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

    private companion object {
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
        const val LONG_PRESS_MS = 300L
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

    fun computeOffset(): Float? = if (scroller.computeScrollOffset()) scroller.currY.toFloat() else null

    val isFinished: Boolean get() = scroller.isFinished

    fun finalOffset(): Float = scroller.finalY.toFloat()

    private companion object {
        const val SAMPLES = 12
        const val WINDOW_MS = 100L
    }
}
