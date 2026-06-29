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
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
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
    private var shifted = false
    private var shiftLocked = false
    private var lang = Lang.CN

    private var lastShiftTapTime = 0L
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()

    private val placed = ArrayList<Placed>()
    private var pressed: Key? = null

    private var scrollColumn: ScrollColumn? = null
    private val scrollRegion = RectF()
    private var scrollCellH = 0f
    private var scrollY = 0f
    private var scrollPressedIndex = -1
    private var inScrollDown = false
    private var scrollDownY = 0f
    private var scrollLastY = 0f
    private var scrolling = false
    private val tmpRect = RectF()
    private val scrollSlop = 6f * resources.displayMetrics.density
    private val scroller = OverScroller(context)
    private val minFlingVel = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxFlingVel = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val sampleT = LongArray(VELOCITY_SAMPLES)
    private val sampleY = FloatArray(VELOCITY_SAMPLES)
    private var sampleHead = 0
    private var sampleCount = 0
    private var flingStopArmed = false

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var downKey: Key? = null
    private var downPlaced: Placed? = null
    private var downX = 0f
    private var downY = 0f
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

    private fun isRepeatable(key: Key) =
        key.action == KeyAction.BACKSPACE || key.action == KeyAction.SPACE || key.action == KeyAction.ENTER ||
            (lang == Lang.EN && isAlphaLetter(key))

    private fun isAlphaLetter(key: Key) =
        layout.id == LayoutId.ALPHA && key.action == KeyAction.COMMIT &&
            key.label.length == 1 && key.label[0] in 'a'..'z'

    private val density = resources.displayMetrics.density
    private val rowHeight = 52f * density
    private val shortPageRowExtra = 7f * density
    private val gap = 6f * density
    private val keyRadius = ImeShapes.keyRadiusDp * density

    private var palette = ImePalette.STATIC_LIGHT

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(20f) }
    private val specialLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabelSecondary; textAlign = Paint.Align.CENTER; textSize = sp(15f) }
    private val boldLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(18f); typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val shiftActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentBottom; textAlign = Paint.Align.CENTER; textSize = sp(20f) }
    private val accentLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentLabel; textAlign = Paint.Align.CENTER; textSize = sp(20f) }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyHint; textAlign = Paint.Align.RIGHT; textSize = sp(10f) }
    private val langActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabelSecondary; textAlign = Paint.Align.CENTER; textSize = sp(17f) }
    private val langSmallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyHint; textAlign = Paint.Align.RIGHT; textSize = sp(11f) }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keySurface }
    private val keyOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density; color = palette.separator }
    private val sepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.separator; strokeWidth = density }
    private val pressHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(palette.keyLabel, 0x22) }
    private val scrollTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.railBg }
    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(palette.icon, 0x55) }
    private val scrollLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(17f) }

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
        keyOutlinePaint.color = p.separator
        sepLinePaint.color = p.separator
        pressHighlight.color = withAlpha(p.keyLabel, 0x22)
        scrollTrackPaint.color = p.railBg
        scrollbarPaint.color = withAlpha(p.icon, 0x55)
        scrollLabelPaint.color = p.keyLabel
        invalidate()
    }

    private fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha shl 24)

    private data class Placed(val rect: RectF, val key: Key, val groupId: Int = 0)

    fun setLayout(newLayout: KeyboardLayout, isShifted: Boolean, isLocked: Boolean, language: Lang) {
        val sameColumn = newLayout.scrollColumn?.items?.map { it.label } == layout.scrollColumn?.items?.map { it.label }
        layout = newLayout
        shifted = isShifted
        shiftLocked = isLocked
        lang = language
        scrollColumn = newLayout.scrollColumn
        if (!sameColumn) scrollY = 0f
        if (width > 0) relayout()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = layout.rowCount
        val rh = if (rows == 4) rowHeight + shortPageRowExtra else rowHeight
        val height = (rows * rh + (rows + 1) * gap).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        placed.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val sc = layout.scrollColumn
        scrollColumn = sc
        if (sc != null && h > 0) {
            scrollRegion.set(sc.x * w + gap, sc.y * h + gap, (sc.x + sc.w) * w - gap, (sc.y + sc.h) * h - gap)
            val visible = (sc.h / sc.cellHFrac).roundToInt().coerceAtLeast(1)
            scrollCellH = scrollRegion.height() / visible
            clampScroll()
        }
        val cells = layout.cells
        if (cells != null) {
            for (pk in cells) {
                placed.add(
                    Placed(
                        RectF(pk.x * w + gap, pk.y * h + gap, (pk.x + pk.w) * w - gap, (pk.y + pk.h) * h - gap),
                        pk.key, pk.groupId,
                    ),
                )
            }
            return
        }
        val rh = (h - (layout.rowCount + 1) * gap) / layout.rowCount
        var top = gap
        for (rowItem in layout.rows) {
            val totalWeight = rowItem.keys.sumOf { it.weight.toDouble() }.toFloat()
            val usable = w - 2 * gap - (rowItem.keys.size - 1) * gap
            var left = gap
            for (key in rowItem.keys) {
                val keyW = usable * (key.weight / totalWeight)
                placed.add(Placed(RectF(left, top, left + keyW, top + rh), key))
                left += keyW + gap
            }
            top += rh + gap
        }
    }

    private fun maxScroll(): Float {
        val sc = scrollColumn ?: return 0f
        return maxOf(0f, sc.items.size * scrollCellH - scrollRegion.height())
    }

    private fun clampScroll() {
        scrollY = scrollY.coerceIn(0f, maxScroll())
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat()
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
            if (i == scrollPressedIndex) {
                tmpRect.set(scrollRegion.left, top, scrollRegion.right, bottom)
                canvas.drawRoundRect(tmpRect, keyRadius * 0.6f, keyRadius * 0.6f, pressHighlight)
            }
            val label = displayLabel(key)
            paint.textSize = baseTextSize
            val w = paint.measureText(label)
            if (w > avail && avail > 0f) paint.textSize = (baseTextSize * avail / w).coerceAtLeast(minTextSize)
            canvas.drawText(label, scrollRegion.centerX(), (top + bottom) / 2f - (paint.descent() + paint.ascent()) / 2, paint)
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
            drawKey(canvas, p.rect, p.key.accent, p.key == pressed)
            drawLabel(canvas, p)
        }

        drawScrollColumn(canvas)
    }

    private fun drawKey(canvas: Canvas, rect: RectF, accent: Boolean, pressed: Boolean) {
        if (accent) {
            fillPaint.color = palette.accentBottom
            canvas.drawRoundRect(rect, keyRadius, keyRadius, fillPaint)
            if (pressed) canvas.drawRoundRect(rect, keyRadius, keyRadius, pressHighlight)
            return
        }
        fillPaint.color = if (pressed) palette.keySurfacePressed else palette.keySurface
        canvas.drawRoundRect(rect, keyRadius, keyRadius, fillPaint)
        canvas.drawRoundRect(rect, keyRadius, keyRadius, keyOutlinePaint)
    }

    private fun drawLabel(canvas: Canvas, p: Placed) {
        if (p.key.action == KeyAction.TOGGLE_LANG) { drawLangToggle(canvas, p.rect); return }
        if (p.key.action == KeyAction.SHIFT) { drawShift(canvas, p.rect); return }
        val cx = p.rect.centerX()
        val cy = p.rect.centerY()
        val display = displayLabel(p.key)
        val paint = when {
            p.key.accent -> accentLabelPaint
            p.key.bold -> boldLabelPaint
            display.length > 1 && p.key.action != KeyAction.COMMIT -> specialLabelPaint
            p.key.action == KeyAction.SHOW_SYMBOLS -> specialLabelPaint
            else -> labelPaint
        }
        canvas.drawText(display, cx, cy - (paint.descent() + paint.ascent()) / 2, paint)
        if (p.key.sub != null) {
            canvas.drawText(p.key.sub, p.rect.right - 6 * density, p.rect.top + 15 * density, subPaint)
        }
    }

    private fun drawLangToggle(canvas: Canvas, rect: RectF) {
        val active = if (lang == Lang.CN) "中" else "英"
        val small = if (lang == Lang.CN) "英" else "中"
        val baseline = rect.centerY() - (langActivePaint.descent() + langActivePaint.ascent()) / 2
        canvas.drawText(active, rect.centerX(), baseline, langActivePaint)
        canvas.drawText(small, rect.right - 5 * density, rect.bottom - 6 * density, langSmallPaint)
    }

    private fun drawShift(canvas: Canvas, rect: RectF) {
        val glyph = if (shiftLocked) "⬆︎" else "⇧"
        val paint = if (shifted) shiftActivePaint else labelPaint
        canvas.drawText(glyph, rect.centerX(), rect.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
    }

    internal fun shiftRenderState(): String = if (shiftLocked) "LOCK" else if (shifted) "ONCE" else "OFF"

    internal fun centerOfActionForTest(action: KeyAction): Pair<Float, Float>? {
        if (placed.isEmpty()) relayout()
        val p = placed.firstOrNull { it.key.action == action } ?: return null
        return p.rect.centerX() to p.rect.centerY()
    }

    private fun displayLabel(key: Key): String {
        if (shifted && key.action == KeyAction.COMMIT && key.label.length == 1 && key.label[0] in 'a'..'z') {
            return key.label.uppercase()
        }
        if (key.label == "✎") return "✎︎"
        return key.label
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            inScrollDown = scrollColumn != null && scrollRegion.contains(event.x, event.y)
        }
        if (inScrollDown) return handleScrollTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downPlaced = placedAt(event.x, event.y)
                downKey = downPlaced?.key
                pressed = downKey
                downX = event.x; downY = event.y
                repeating = false; swiped = false; vSwipeDir = 0
                downKey?.let { if (isRepeatable(it)) repeatHandler.postDelayed(repeatRunnable, REPEAT_DELAY_MS) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dk = downKey
                when {
                    dk != null && dk.action == KeyAction.BACKSPACE -> {
                        val dy = event.y - downY
                        if (!swiped && abs(dy) > swipeThreshold && abs(dy) > abs(event.x - downX)) {
                            swiped = true
                            repeatHandler.removeCallbacks(repeatRunnable)
                        }
                    }
                    dk != null && lang == Lang.EN && isAlphaLetter(dk) -> {
                        val dy = event.y - downY
                        if (!swiped && abs(dy) > swipeThreshold && abs(dy) > abs(event.x - downX)) {
                            swiped = true
                            vSwipeDir = if (dy < 0) -1 else 1
                            repeatHandler.removeCallbacks(repeatRunnable)
                        } else if (!swiped) {
                            val k = currentTarget(event.x, event.y)
                            if (k !== pressed) {
                                pressed = k
                                if (k !== downKey) repeatHandler.removeCallbacks(repeatRunnable)
                                invalidate()
                            }
                        }
                    }
                    else -> {
                        val k = currentTarget(event.x, event.y)
                        if (k !== pressed) {
                            pressed = k
                            if (k !== downKey) repeatHandler.removeCallbacks(repeatRunnable)
                            invalidate()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                repeatHandler.removeCallbacks(repeatRunnable)
                val dk = downKey
                pressed = null
                invalidate()
                when {
                    dk != null && dk.action == KeyAction.BACKSPACE && swiped && !repeating ->
                        onBackspaceSwipe(event.y - downY < 0)
                    dk != null && lang == Lang.EN && isAlphaLetter(dk) && swiped && !repeating -> {
                        performClick()
                        if (vSwipeDir < 0 && dk.sub != null) onKey(Key(dk.sub, output = dk.sub, direct = true))
                        else onKey(dk)
                    }
                    !repeating ->
                        currentTarget(event.x, event.y)?.let { performClick(); emitKey(it, event.eventTime) }
                }
                downKey = null
                downPlaced = null
            }
            MotionEvent.ACTION_CANCEL -> {
                repeatHandler.removeCallbacks(repeatRunnable)
                pressed = null
                downKey = null
                downPlaced = null
                invalidate()
            }
        }
        return true
    }

    private fun handleScrollTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inScrollDown = true; scrolling = false
                flingStopArmed = !scroller.isFinished
                if (flingStopArmed) scroller.forceFinished(true)
                sampleCount = 0; sampleHead = 0
                scrollDownY = event.y; scrollLastY = event.y
                scrollPressedIndex = if (flingStopArmed) -1 else scrollIndexAt(event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                addVelocitySample(event.eventTime, event.y)
                if (!scrolling && abs(event.y - scrollDownY) > scrollSlop) { scrolling = true; scrollPressedIndex = -1 }
                if (scrolling) {
                    scrollY += scrollLastY - event.y
                    clampScroll(); invalidate()
                }
                scrollLastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val col = scrollColumn
                if (scrolling) {
                    val vy = flingVelocity()
                    if (col != null && abs(vy) > minFlingVel && maxScroll() > 0f) {
                        scroller.fling(0, scrollY.toInt(), 0, (-vy).toInt(), 0, 0, 0, maxScroll().toInt())
                        postInvalidateOnAnimation()
                    }
                } else if (col != null && !flingStopArmed) {
                    val idx = scrollIndexAt(event.y)
                    if (idx >= 0 && idx == scrollPressedIndex) { performClick(); onKey(col.items[idx]) }
                }
                scrollPressedIndex = -1; inScrollDown = false; scrolling = false; flingStopArmed = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                scrollPressedIndex = -1; inScrollDown = false; scrolling = false; flingStopArmed = false
                invalidate()
            }
        }
        return true
    }

    internal fun scrollOffsetForTest(): Float = scrollY
    internal fun maxScrollForTest(): Float = maxScroll()
    internal fun isFlingingForTest(): Boolean = !scroller.isFinished
    internal fun flingFinalForTest(): Float = scroller.finalY.toFloat()

    private fun addVelocitySample(t: Long, y: Float) {
        sampleT[sampleHead] = t; sampleY[sampleHead] = y
        sampleHead = (sampleHead + 1) % VELOCITY_SAMPLES
        if (sampleCount < VELOCITY_SAMPLES) sampleCount++
    }

    private fun flingVelocity(): Float {
        if (sampleCount < 2) return 0f
        val newest = (sampleHead - 1 + VELOCITY_SAMPLES) % VELOCITY_SAMPLES
        val tNew = sampleT[newest]; val yNew = sampleY[newest]
        var ref = newest
        for (k in 1 until sampleCount) {
            val idx = (newest - k + VELOCITY_SAMPLES) % VELOCITY_SAMPLES
            ref = idx
            if (tNew - sampleT[idx] >= VELOCITY_WINDOW_MS) break
        }
        val dt = (tNew - sampleT[ref]).toFloat()
        if (dt <= 0f) return 0f
        return ((yNew - sampleY[ref]) / dt * 1000f).coerceIn(-maxFlingVel, maxFlingVel)
    }

    internal fun flingVelocityForTest(): Float = flingVelocity()

    private fun placedAt(x: Float, y: Float): Placed? {
        var nearest: Placed? = null
        var best = Float.MAX_VALUE
        for (p in placed) {
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
        val cap = rowHeight
        return if (best <= cap * cap) nearest else null
    }

    private fun currentTarget(x: Float, y: Float): Key? {
        val dp = downPlaced ?: return placedAt(x, y)?.key
        val t = 0.5f * dp.rect.width()
        val dx = x - downX
        val dy = y - downY
        return if (dx * dx + dy * dy <= t * t) dp.key else placedAt(x, y)?.key ?: dp.key
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
        const val VELOCITY_SAMPLES = 12
        const val VELOCITY_WINDOW_MS = 100L
    }
}
