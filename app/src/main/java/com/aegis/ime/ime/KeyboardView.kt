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

/**
 * Self-drawn (View + Canvas) typing grid — the perf-sensitive surface deliberately kept off
 * Compose. Lays keys out by weight, hit-tests touches and reports
 * the tapped [Key] via [onKey].
 */
class KeyboardView(context: Context) : View(context) {

    var onKey: (Key) -> Unit = {}

    /** Backspace vertical swipe (issue #5): true = up (delete all), false = down (restore). */
    var onBackspaceSwipe: (Boolean) -> Unit = {}

    private var layout: KeyboardLayout = Layouts.forId(LayoutId.ALPHA, Lang.CN)
    private var shifted = false
    private var lang = Lang.CN

    private val placed = ArrayList<Placed>()
    private var pressed: Key? = null

    // A3: the scrollable left column (pinyin combos while composing / punctuation at rest).
    private var scrollColumn: ScrollColumn? = null
    private val scrollRegion = RectF()
    private var scrollCellH = 0f
    private var scrollY = 0f
    private var scrollPressedIndex = -1
    private var inScrollDown = false
    private var scrollDownY = 0f
    private var scrollStartY = 0f
    private var scrolling = false
    private val tmpRect = RectF()
    // A3: start scrolling after only a small drag so the list FOLLOWS the finger (the 24dp backspace-swipe
    // threshold felt like "滑不动 / 不跟手"); once started it tracks 1:1.
    private val scrollSlop = 6f * resources.displayMetrics.density

    // Long-press key repeat (#8) + backspace swipe (#5).
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var downKey: Key? = null
    private var downPlaced: Placed? = null
    private var downX = 0f
    private var downY = 0f
    private var repeating = false
    private var swiped = false
    private var vSwipeDir = 0 // B2 26-key letter flick: -1 = up (symbol), +1 = down (letter), 0 = none
    private val swipeThreshold = 24f * resources.displayMetrics.density
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val k = downKey ?: return
            repeating = true
            onKey(k)
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    // B2: a long-press on a 26-key English letter repeats it ("长按连续输入字母"); functional keys repeat too.
    private fun isRepeatable(key: Key) =
        key.action == KeyAction.BACKSPACE || key.action == KeyAction.SPACE || key.action == KeyAction.ENTER ||
            (lang == Lang.EN && isAlphaLetter(key))

    /** A 26-key letter key (single a–z label, COMMIT) — the target of the B2 swipe / long-press gestures. */
    private fun isAlphaLetter(key: Key) =
        layout.id == LayoutId.ALPHA && key.action == KeyAction.COMMIT &&
            key.label.length == 1 && key.label[0] in 'a'..'z'

    private val density = resources.displayMetrics.density
    private val rowHeight = 52f * density
    private val gap = 6f * density
    private val keyRadius = ImeShapes.keyRadiusDp * density // F2: rounded-rect keys (≤16dp, never pill)

    // F1: all colours come from the Monet palette (default = static light = the previous hand-tuned look).
    // AegisInputMethodService pushes the live, dark-aware palette via [applyPalette].
    private var palette = ImePalette.STATIC_LIGHT

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // kept; cheap (redraw only on key press)
    }

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    // F2: flat MD3 text — no neumorphic emboss shadow.
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(20f) }
    private val specialLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabelSecondary; textAlign = Paint.Align.CENTER; textSize = sp(15f) }
    private val accentLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentLabel; textAlign = Paint.Align.CENTER; textSize = sp(20f) }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyHint; textAlign = Paint.Align.RIGHT; textSize = sp(10f) }
    // B3 中英字号: active language large & centred, inactive one small in the bottom-right corner.
    private val langActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabelSecondary; textAlign = Paint.Align.CENTER; textSize = sp(17f) }
    private val langSmallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyHint; textAlign = Paint.Align.RIGHT; textSize = sp(11f) }

    // F2: flat MD3 tonal key surface — solid fill + thin outline for separation (no dual-shadow/gradient).
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keySurface }
    private val keyOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density; color = palette.separator }
    private val sepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.separator; strokeWidth = density }
    private val pressHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(palette.keyLabel, 0x22) }
    // A3 scroll column: a tonal track + a slim scrollbar thumb when the list overflows.
    private val scrollTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.railBg }
    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(palette.icon, 0x55) }
    private val scrollLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(17f) }

    /** F1: push a new (Monet, dark-aware) palette; re-colours every Paint and repaints. */
    fun applyPalette(p: ImePalette) {
        palette = p
        labelPaint.color = p.keyLabel
        specialLabelPaint.color = p.keyLabelSecondary
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

    fun setLayout(newLayout: KeyboardLayout, isShifted: Boolean, language: Lang) {
        // A3: reset the left column to the top whenever its CONTENT changes (new syllable / rest↔compose),
        // but keep the scroll offset on a pure re-render of the same list.
        val sameColumn = newLayout.scrollColumn?.items?.map { it.label } == layout.scrollColumn?.items?.map { it.label }
        layout = newLayout
        shifted = isShifted
        lang = language
        scrollColumn = newLayout.scrollColumn
        if (!sameColumn) scrollY = 0f
        // All four layouts have the same row count, so swapping between them leaves the measured
        // height unchanged and onSizeChanged never fires — relay out here so the new keys (and their
        // hit rects) take effect immediately instead of redrawing the stale layout.
        if (width > 0) relayout()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = layout.rowCount
        val height = (rows * rowHeight + (rows + 1) * gap).toInt()
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
        // A3 scrollable left column — computed first since the 9-key layout also carries `cells`.
        val sc = layout.scrollColumn
        scrollColumn = sc
        if (sc != null && h > 0) {
            scrollRegion.set(sc.x * w + gap, sc.y * h + gap, (sc.x + sc.w) * w - gap, (sc.y + sc.h) * h - gap)
            val visible = (sc.h / sc.cellHFrac).roundToInt().coerceAtLeast(1)
            scrollCellH = scrollRegion.height() / visible
            clampScroll()
        }
        // Fractional-cell layout (9-key): keys carry explicit rectangles for merged / spanning cells.
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
        var top = gap
        for (rowItem in layout.rows) {
            val totalWeight = rowItem.keys.sumOf { it.weight.toDouble() }.toFloat()
            val usable = w - 2 * gap - (rowItem.keys.size - 1) * gap
            var left = gap
            for (key in rowItem.keys) {
                val keyW = usable * (key.weight / totalWeight)
                placed.add(Placed(RectF(left, top, left + keyW, top + rowHeight), key))
                left += keyW + gap
            }
            top += rowHeight + gap
        }
    }

    private fun clampScroll() {
        val sc = scrollColumn ?: return
        val maxScroll = maxOf(0f, sc.items.size * scrollCellH - scrollRegion.height())
        scrollY = scrollY.coerceIn(0f, maxScroll)
    }

    /** Index of the scroll-column item under [y] (accounting for the scroll offset), or -1. */
    private fun scrollIndexAt(y: Float): Int {
        val sc = scrollColumn ?: return -1
        if (scrollCellH <= 0f || y < scrollRegion.top || y > scrollRegion.bottom) return -1
        val idx = ((y - scrollRegion.top + scrollY) / scrollCellH).toInt()
        return if (idx in sc.items.indices) idx else -1
    }

    /** A3 left column: clean vertical list (track + separators), clipped + translated by the scroll. */
    private fun drawScrollColumn(canvas: Canvas) {
        val sc = scrollColumn ?: return
        if (scrollRegion.isEmpty || scrollCellH <= 0f || sc.items.isEmpty()) return
        canvas.drawRoundRect(scrollRegion, keyRadius, keyRadius, scrollTrackPaint)
        canvas.save()
        canvas.clipRect(scrollRegion)
        val paint = scrollLabelPaint // uniform size for every row (reference look)
        for ((i, key) in sc.items.withIndex()) {
            val top = scrollRegion.top - scrollY + i * scrollCellH
            val bottom = top + scrollCellH
            if (bottom < scrollRegion.top || top > scrollRegion.bottom) continue // off-screen
            if (i == scrollPressedIndex) {
                tmpRect.set(scrollRegion.left, top, scrollRegion.right, bottom)
                canvas.drawRoundRect(tmpRect, keyRadius * 0.6f, keyRadius * 0.6f, pressHighlight)
            }
            canvas.drawText(displayLabel(key), scrollRegion.centerX(), (top + bottom) / 2f - (paint.descent() + paint.ascent()) / 2, paint)
            if (i < sc.items.size - 1 && bottom < scrollRegion.bottom) {
                canvas.drawLine(scrollRegion.left + 6 * density, bottom, scrollRegion.right - 6 * density, bottom, sepLinePaint)
            }
        }
        canvas.restore()
        // Slim scrollbar thumb when the list overflows the region.
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

        // Flat MD3 keys (F2: no peanut/oval/neumorphic — the 9-key left column is the A3 scroll strip below).
        for (p in placed) {
            drawKey(canvas, p.rect, p.key.accent, p.key == pressed)
            drawLabel(canvas, p)
        }

        // A3 scrollable left column (pinyin combos / punctuation), drawn over its own region.
        drawScrollColumn(canvas)
    }

    /** F2: a flat MD3 tonal key — accent = solid primary fill; normal = tonal fill + thin outline. */
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
        val cx = p.rect.centerX()
        val cy = p.rect.centerY()
        val display = displayLabel(p.key)
        val paint = when {
            p.key.accent -> accentLabelPaint
            display.length > 1 && p.key.action != KeyAction.COMMIT -> specialLabelPaint
            else -> labelPaint
        }
        canvas.drawText(display, cx, cy - (paint.descent() + paint.ascent()) / 2, paint)
        // 26-key super-script symbol at the top-right corner.
        if (p.key.sub != null) {
            canvas.drawText(p.key.sub, p.rect.right - 6 * density, p.rect.top + 15 * density, subPaint)
        }
    }

    /**
     * B3 中英 toggle: draw the ACTIVE input language large & centred and the inactive one small in the
     * bottom-right corner — 中文态 shrinks "英", 英文态 shrinks "中".
     */
    private fun drawLangToggle(canvas: Canvas, rect: RectF) {
        val active = if (lang == Lang.CN) "中" else "英"
        val small = if (lang == Lang.CN) "英" else "中"
        val baseline = rect.centerY() - (langActivePaint.descent() + langActivePaint.ascent()) / 2
        canvas.drawText(active, rect.centerX(), baseline, langActivePaint)
        canvas.drawText(small, rect.right - 5 * density, rect.bottom - 6 * density, langSmallPaint)
    }

    private fun displayLabel(key: Key): String {
        if (shifted && key.action == KeyAction.COMMIT && key.label.length == 1 && key.label[0] in 'a'..'z') {
            return key.label.uppercase()
        }
        return key.label
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A3: a gesture that starts in the left scroll column is handled as scroll/pick, fully isolated
        // from key presses (so it never triggers the keyboard's tap / backspace-swipe paths). A fresh DOWN
        // outside the region clears any stuck latch (defensive: a lost UP/CANCEL must not swallow the tap).
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
                        // Vertical drag on backspace = a swipe gesture, not a key press.
                        val dy = event.y - downY
                        if (!swiped && abs(dy) > swipeThreshold && abs(dy) > abs(event.x - downX)) {
                            swiped = true
                            repeatHandler.removeCallbacks(repeatRunnable)
                        }
                    }
                    dk != null && lang == Lang.EN && isAlphaLetter(dk) -> {
                        // B2 (英文26键 only): a deliberate vertical flick on a letter selects symbol (up) /
                        // letter (down); a horizontal slide still retargets to the neighbour (★V slide-to-correct).
                        // Gated to EN so it never flushes a half-typed CN pinyin buffer.
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
                    // !repeating: a long-press that already auto-fired must not ALSO emit a swipe/tap on lift.
                    dk != null && dk.action == KeyAction.BACKSPACE && swiped && !repeating ->
                        onBackspaceSwipe(event.y - downY < 0)
                    dk != null && lang == Lang.EN && isAlphaLetter(dk) && swiped && !repeating -> {
                        // B2: up-flick commits the super-script symbol straight to the editor (direct);
                        // down-flick commits the letter itself.
                        performClick()
                        if (vSwipeDir < 0 && dk.sub != null) onKey(Key(dk.sub, output = dk.sub, direct = true))
                        else onKey(dk)
                    }
                    !repeating ->
                        currentTarget(event.x, event.y)?.let { performClick(); onKey(it) }
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

    /**
     * A3 left scroll column: a vertical drag past the swipe threshold scrolls the list; a tap (no
     * scroll) on the item the finger went down on picks it. Isolated from the keyboard's key handling.
     */
    private fun handleScrollTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inScrollDown = true; scrolling = false
                scrollDownY = event.y; scrollStartY = scrollY
                scrollPressedIndex = scrollIndexAt(event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - scrollDownY
                if (!scrolling && abs(dy) > scrollSlop) { scrolling = true; scrollPressedIndex = -1 }
                if (scrolling) { scrollY = scrollStartY - dy; clampScroll(); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val col = scrollColumn
                if (!scrolling && col != null) {
                    val idx = scrollIndexAt(event.y)
                    if (idx >= 0 && idx == scrollPressedIndex) { performClick(); onKey(col.items[idx]) }
                }
                scrollPressedIndex = -1; inScrollDown = false; scrolling = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                scrollPressedIndex = -1; inScrollDown = false; scrolling = false
                invalidate()
            }
        }
        return true
    }

    /**
     * ★V follow-finger hit-test: exact containment first, else snap to the NEAREST key (by clamped
     * edge distance, capped at ~one key height) so taps landing in the inter-key gaps are no longer
     * dropped. Kills the 6dp row / 12dp 9-key dead bands behind "indeterminate aim / no response".
     */
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
        val cap = rowHeight // don't snap taps that fall well outside the keyboard
        return if (best <= cap * cap) nearest else null
    }

    /**
     * ★V "commit the key the finger went DOWN on": a normal tap yields [downKey] even if the finger
     * micro-rolls before lift (the old code committed whatever was under the finger at ACTION_UP, so a
     * tiny slide off key A produced neighbor B). Only a deliberate slide past half the pressed key's
     * width retargets to the key now under the finger (preserves slide-to-correct).
     */
    private fun currentTarget(x: Float, y: Float): Key? {
        val dp = downPlaced ?: return placedAt(x, y)?.key
        val t = 0.5f * dp.rect.width()
        val dx = x - downX
        val dy = y - downY
        return if (dx * dx + dy * dy <= t * t) dp.key else placedAt(x, y)?.key ?: dp.key
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val REPEAT_DELAY_MS = 400L    // hold this long before auto-repeat starts
        const val REPEAT_INTERVAL_MS = 55L  // then fire this often
    }
}
