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
    private var shiftLocked = false // I4: caps-lock (persistent) vs one-shot — drives the solid-arrow glyph
    private var lang = Lang.CN

    // I4: a second tap on the shift key within the double-tap window promotes one-shot → caps lock.
    private var lastShiftTapTime = 0L
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()

    private val placed = ArrayList<Placed>()
    private var pressed: Key? = null

    // A3: the scrollable left column (pinyin combos while composing / punctuation at rest).
    private var scrollColumn: ScrollColumn? = null
    private val scrollRegion = RectF()
    private var scrollCellH = 0f
    private var scrollY = 0f
    private var scrollPressedIndex = -1
    private var inScrollDown = false
    private var scrollDownY = 0f // where the gesture went down (for the slop threshold)
    private var scrollLastY = 0f // I5: previous touch-Y — the drag consumes incremental deltas (true 1:1,
    // and reversing off the top/bottom clamp moves immediately instead of through an overshoot dead zone)
    private var scrolling = false
    private val tmpRect = RectF()
    // A3: start scrolling after only a small drag so the list FOLLOWS the finger (the 24dp backspace-swipe
    // threshold felt like "滑不动 / 不跟手"); once started it tracks 1:1.
    private val scrollSlop = 6f * resources.displayMetrics.density
    // U7/U17 fling: a quick flick must carry the list to the bottom in ONE gesture (the old drag-only scroll
    // was bounded by finger travel within the short ~4-cell region, so one gesture could not reach the bottom). Momentum
    // via OverScroller; velocity self-computed from the last two MOVE samples so it's deterministic + testable
    // (Robolectric's VelocityTracker shadow reports nothing).
    private val scroller = OverScroller(context)
    private val minFlingVel = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxFlingVel = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    // I5: fling velocity is estimated over a short TIME WINDOW of recent MOVE samples (like VelocityTracker),
    // NOT just the last two points — two points are dominated by the finger's final micro-motion, so a
    // decelerating lift gave ~0 (no fling → "要滑很长才到底") and a jittery last sample gave a huge spike
    // (overshoot → "滑一点点就到底"). A ring buffer + window makes the momentum match the actual flick speed.
    private val sampleT = LongArray(VELOCITY_SAMPLES)
    private val sampleY = FloatArray(VELOCITY_SAMPLES)
    private var sampleHead = 0 // next write slot (ring)
    private var sampleCount = 0
    private var flingStopArmed = false // this DOWN halted a running fling → its UP must NOT pick (no mis-touch)

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
    // I3/numpad-align: the 4-row pages (9-key + numpad/number/symbol) get a small per-row bump so they share
    // ONE height and switching between them (e.g. 9-key ⇄ 123) never resizes the IME; the 5-row 26-key keeps
    // the base. (Supersedes the nine-only I3 bump — same +7dp on the 9-key, now generalized.)
    private val shortPageRowExtra = 7f * density
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
    // I6: bold primary label for the 9-key 分词 / @# keys so they read as prominently as the letter keys.
    private val boldLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(18f); typeface = android.graphics.Typeface.DEFAULT_BOLD }
    // I4: the shift glyph when one-shot/locked — accent colour makes the active state obvious on a normal key.
    private val shiftActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentBottom; textAlign = Paint.Align.CENTER; textSize = sp(20f) }
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
        // A3: reset the left column to the top whenever its CONTENT changes (new syllable / rest↔compose),
        // but keep the scroll offset on a pure re-render of the same list.
        val sameColumn = newLayout.scrollColumn?.items?.map { it.label } == layout.scrollColumn?.items?.map { it.label }
        layout = newLayout
        shifted = isShifted
        shiftLocked = isLocked // I4: drives the solid (locked) vs hollow (one-shot/off) shift glyph
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
        // All 4-row keyboards (9-key, numpad, number, symbol) share one taller height; the 5-row 26-key
        // keeps the base. So 9-key⇄123 and any text⇄number/symbol switch never resizes the IME window.
        // (NINE is rowCount==4, so it still gets the same +7dp as the nine-only I3 — superset.)
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
        // Derive the row height from the MEASURED height so the rows fill it exactly — onMeasure bumps the
        // 4-row pages (number/symbol), and using a constant rowHeight here would leave a dead band at the
        // bottom of those pages. This formula reproduces onMeasure's per-row height for every rowCount.
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

    /** Farthest the list can scroll (0 when it fits) — the fling's lower clamp = "the bottom". */
    private fun maxScroll(): Float {
        val sc = scrollColumn ?: return 0f
        return maxOf(0f, sc.items.size * scrollCellH - scrollRegion.height())
    }

    private fun clampScroll() {
        scrollY = scrollY.coerceIn(0f, maxScroll())
    }

    /** Drive the momentum fling each frame; View.draw() calls this automatically. */
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
        }
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
        if (p.key.action == KeyAction.SHIFT) { drawShift(canvas, p.rect); return } // I4: stateful arrow glyph
        val cx = p.rect.centerX()
        val cy = p.rect.centerY()
        val display = displayLabel(p.key)
        val paint = when {
            p.key.accent -> accentLabelPaint
            p.key.bold -> boldLabelPaint // I6: 分词 / @# at the prominent primary weight
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

    /**
     * I4 shift key, three visually distinct states:
     *  OFF  → hollow up-arrow ⇧ in the normal label colour;
     *  ONCE → hollow up-arrow ⇧ in the accent colour (temporarily armed — "临时态");
     *  LOCK → SOLID up-arrow ⬆ in the accent colour (caps lock — the "实心箭头").
     */
    private fun drawShift(canvas: Canvas, rect: RectF) {
        // U+2B06 is emoji-presentation-capable; the U+FE0E text selector forces a flat glyph so it keeps the
        // accent colour (a color-emoji ⬆ would ignore shiftActivePaint) and reads as the "实心箭头".
        val glyph = if (shiftLocked) "⬆︎" else "⇧"
        val paint = if (shifted) shiftActivePaint else labelPaint
        canvas.drawText(glyph, rect.centerX(), rect.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
    }

    /** I4 test seam: the shift key's current visual state (OFF / ONCE / LOCK). */
    internal fun shiftRenderState(): String = if (shiftLocked) "LOCK" else if (shifted) "ONCE" else "OFF"

    /** Test seam: the on-screen centre of the first key with [action] (for robust tap targeting). */
    internal fun centerOfActionForTest(action: KeyAction): Pair<Float, Float>? {
        if (placed.isEmpty()) relayout()
        val p = placed.firstOrNull { it.key.action == action } ?: return null
        return p.rect.centerX() to p.rect.centerY()
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

    /**
     * A3 left scroll column: a vertical drag past the swipe threshold scrolls the list; a tap (no
     * scroll) on the item the finger went down on picks it. Isolated from the keyboard's key handling.
     */
    private fun handleScrollTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inScrollDown = true; scrolling = false
                // U7/U17: a touch landing on a moving list STOPS the fling and that tap must not select
                // anything (so flicking then tapping to halt never mis-commits a combo/punctuation).
                flingStopArmed = !scroller.isFinished
                if (flingStopArmed) scroller.forceFinished(true)
                sampleCount = 0; sampleHead = 0 // velocity is measured from MOVE samples only (a single fast
                // MOVE off the DOWN point is not a flick — needs ≥2 MOVEs, as before)
                scrollDownY = event.y; scrollLastY = event.y
                scrollPressedIndex = if (flingStopArmed) -1 else scrollIndexAt(event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                addVelocitySample(event.eventTime, event.y)
                if (!scrolling && abs(event.y - scrollDownY) > scrollSlop) { scrolling = true; scrollPressedIndex = -1 }
                if (scrolling) {
                    // 1:1 drag via INCREMENTAL deltas: content moves exactly as far as the finger, and a
                    // clamp at the top/bottom is applied to the accumulated offset (not absorbed into an
                    // anchor) so reversing direction tracks the finger immediately — no overscroll dead zone.
                    scrollY += scrollLastY - event.y
                    clampScroll(); invalidate()
                }
                scrollLastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val col = scrollColumn
                if (scrolling) {
                    // Hand off the finger's velocity to a momentum fling so one flick can reach the bottom.
                    val vy = flingVelocity()
                    if (col != null && abs(vy) > minFlingVel && maxScroll() > 0f) {
                        // scrollY grows as the finger moves UP (dy<0), so fling velocity in scroll-space = -vy.
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

    // U7/U17 test seams (Robolectric drives MotionEvents; the OverScroller computes its final target
    // synchronously in fling(), so "reaches the bottom in one gesture" is checkable without a frame clock).
    internal fun scrollOffsetForTest(): Float = scrollY
    internal fun maxScrollForTest(): Float = maxScroll()
    internal fun isFlingingForTest(): Boolean = !scroller.isFinished
    internal fun flingFinalForTest(): Float = scroller.finalY.toFloat()

    /** I5: record one (time, y) touch sample into the ring buffer used for the windowed fling velocity. */
    private fun addVelocitySample(t: Long, y: Float) {
        sampleT[sampleHead] = t; sampleY[sampleHead] = y
        sampleHead = (sampleHead + 1) % VELOCITY_SAMPLES
        if (sampleCount < VELOCITY_SAMPLES) sampleCount++
    }

    /**
     * I5: finger velocity (px/s, screen-Y) measured over the last [VELOCITY_WINDOW_MS] of samples — the
     * displacement from the newest sample back to the oldest one still inside the window, divided by their
     * time span. This averages out the final-sample jitter that made the old two-point estimate swing
     * between "no fling" and "overshoot", so the momentum reflects the real flick speed. 0 with <2 samples.
     */
    private fun flingVelocity(): Float {
        if (sampleCount < 2) return 0f
        val newest = (sampleHead - 1 + VELOCITY_SAMPLES) % VELOCITY_SAMPLES
        val tNew = sampleT[newest]; val yNew = sampleY[newest]
        var ref = newest
        for (k in 1 until sampleCount) {
            val idx = (newest - k + VELOCITY_SAMPLES) % VELOCITY_SAMPLES
            ref = idx
            if (tNew - sampleT[idx] >= VELOCITY_WINDOW_MS) break // far enough back: spans the window
        }
        val dt = (tNew - sampleT[ref]).toFloat()
        if (dt <= 0f) return 0f
        return ((yNew - sampleY[ref]) / dt * 1000f).coerceIn(-maxFlingVel, maxFlingVel)
    }

    /** I5 test seam: the windowed fling velocity the next UP would use (px/s, screen-Y). */
    internal fun flingVelocityForTest(): Float = flingVelocity()

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

    /**
     * I4: emit a tapped key, promoting a quick second tap on the SHIFT key to caps lock. The first tap
     * fires SHIFT (→ one-shot); a second SHIFT tap within the double-tap window fires SHIFT_LOCK (→ lock).
     * Any other key, or a slow tap, resets the window. Timing comes from the MotionEvent so it is testable.
     */
    private fun emitKey(key: Key, eventTime: Long) {
        if (key.action == KeyAction.SHIFT) {
            if (lastShiftTapTime != 0L && eventTime - lastShiftTapTime <= doubleTapMs) {
                lastShiftTapTime = 0L // consume — a third quick tap starts a fresh window, not another lock
                onKey(Key(key.label, action = KeyAction.SHIFT_LOCK))
            } else {
                lastShiftTapTime = eventTime
                onKey(key)
            }
            return
        }
        lastShiftTapTime = 0L // any non-shift tap breaks a pending double-tap
        onKey(key)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val REPEAT_DELAY_MS = 400L    // hold this long before auto-repeat starts
        const val REPEAT_INTERVAL_MS = 55L  // then fire this often
        // I5 windowed fling velocity: keep up to N samples, measure speed over the last ~window ms.
        const val VELOCITY_SAMPLES = 12
        const val VELOCITY_WINDOW_MS = 100L
    }
}
