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
import android.view.HapticFeedbackConstants
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
import com.aegis.ime.ui.LetterCase

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
    private var modeSwitches = 0 // live layout-id changes that ran the MD3 mode-switch fade
    private var shifted = false
    private var shiftLocked = false // I4: caps-lock (persistent) vs one-shot — drives the solid-arrow glyph
    private var lang = Lang.CN

    // I4: a second tap on the shift key within the double-tap window promotes one-shot → caps lock.
    private var lastShiftTapTime = 0L
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()

    private val placed = ArrayList<Placed>()
    private var pressed: Key? = null
    private var visualPressed: Key? = null
    private val keyPress = Motion.PressFeedback(this)

    // A3: the scrollable left column (pinyin combos while composing / punctuation at rest).
    private var scrollColumn: ScrollColumn? = null
    private val scrollRegion = RectF()
    private var scrollCellH = 0f
    private var scrollY = 0f
    private var scrollPressedIndex = -1
    private var scrollVisualPressedIndex = -1
    private val scrollPress = Motion.PressFeedback(this)
    private var inScrollDown = false
    private var scrollDownY = 0f // where the gesture went down (for the slop threshold)
    private var scrollLastY = 0f // I5: previous touch-Y — the drag consumes incremental deltas (true 1:1,
    // and reversing off the top/bottom clamp moves immediately instead of through an overshoot dead zone)
    private var scrolling = false
    private val tmpRect = RectF()
    // A3: start scrolling after only a small drag so the list FOLLOWS the finger (the 24dp backspace-swipe
    // Chinese IME behavior note.
    private val scrollSlop = 6f * resources.displayMetrics.density
    // U7/U17/I5 fling: a quick flick must carry the A3 reading column to the bottom in ONE gesture. The momentum
    // + windowed-velocity logic now lives in the SHARED [FlingScroller] (debug.17 #66) — reused by CandidateView.
    private val fling = FlingScroller(context)

    // Long-press key repeat (#8) + backspace swipe (#5).
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var downKey: Key? = null
    private var downPlaced: Placed? = null
    private var downX = 0f
    private var downY = 0f
    // ② Multi-touch: the pointer id that currently owns the key gesture (press / swipe / repeat / retarget).
    // A single active pointer keeps every rich gesture intact; a SECOND finger landing before the first lifts
    // rolls the current key through (commits it, type-through) and hands the gesture to the new finger — so
    // fast rolling never drops a key or misattributes it (the old code ignored POINTER_DOWN/UP entirely).
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
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

    // ③ long-press on a 26-key EN letter (held still past [LONG_PRESS_MS]) opens the case/symbol box: the single
    // press preview is replaced by three cells and the gesture enters "box mode" (all further moves pick a cell).
    // A fast flick or a slide off the key before the timer fires cancels it, so the existing flick / retarget win.
    private val longPressRunnable = Runnable {
        val dk = downKey ?: return@Runnable
        val dp = downPlaced ?: return@Runnable
        if (lang != Lang.EN || !isAlphaLetter(dk)) return@Runnable
        hidePreview() // the 3-cell box replaces the single bubble
        caseBoxKey = dk
        previewRect.set(dp.rect)
        caseBoxActive = true
        caseBoxMoved = false
        caseBoxSelected = -1
        previewFeedback.press() // EXISTING Motion vocabulary drives the box's scale/alpha appear
        invalidate()
    }

    /** Cancel any pending auto-repeat AND any pending long-press box timer (removing a non-posted runnable is a no-op). */
    private fun cancelKeyHold() {
        repeatHandler.removeCallbacks(repeatRunnable)
        repeatHandler.removeCallbacks(longPressRunnable)
    }

    // ③ mis-touch tightening: auto-repeat is restricted to BACKSPACE only — the one hold-to-repeat every IME
    // (Gboard / Sogou / iOS) provides. Letters, digits and symbols (COMMIT) no longer repeat: a slightly-long
    // press used to flood "aaaa" / "2222", a common mis-fire that no mainstream soft keyboard produces. SPACE
    // and ENTER are likewise not repeated (holding them to spam spaces / newlines is never intended, and hold
    // gestures there read as deliberate, not as a repeat request). This reverses the debug.18 ④ over-broad rule.
    private fun isRepeatable(key: Key) = key.action == KeyAction.BACKSPACE

    /** A 26-key letter key (single a–z label, COMMIT) — the target of the B2 swipe / long-press gestures. */
    private fun isAlphaLetter(key: Key) =
        layout.id == LayoutId.ALPHA && key.action == KeyAction.COMMIT &&
            key.label.length == 1 && key.label[0] in 'a'..'z'

    /** ④ A 9-key T9 digit key (COMMIT in the NINE grid: the 8 ABC…WXYZ blocks) — the target whose vertical
     *  swipe must resolve to a single click on the pressed key, not a drift to a neighbour / the function row. */
    private fun isNineDigit(key: Key) =
        layout.id == LayoutId.NINE && key.action == KeyAction.COMMIT

    private val density = resources.displayMetrics.density
    private val rowHeight = 52f * density
    // ③ mis-touch tightening. snapCap: farthest a tap may sit from a key and still snap to it — half a key height,
    // enough to bridge the 6dp/12dp inter-key gaps but not a full key away into the gutter / nav-bar region.
    private val snapCap = rowHeight * 0.5f
    // retargetHysteresis: how far PAST the pressed key's edge the finger must move before a slide retargets to
    // the neighbour, so a finger resting on the shared seam never flickers between the two keys.
    private val retargetHysteresis = 4f * density
    // I3/numpad-align: the 4-row pages (9-key + numpad/number/symbol) get a small per-row bump so they share
    // ONE height and switching between them (e.g. 9-key ⇄ 123) never resizes the IME; the 5-row 26-key keeps
    // the base. debug.17 C (F3): trimmed 7→2dp/row so the 9-key (and its 4-row siblings) sit ~20dp lower overall
    // — they read less tall/chunky — while still sharing one height (no resize on a 9-key⇄123 switch).
    private val shortPageRowExtra = 2f * density
    private val gap = 6f * density
    private val keyRadius = ImeShapes.keyRadiusDp * density // F2: rounded-rect keys (≤16dp, never pill)

    // ⑤ touch-feedback toggles (pushed from prefs via InputView, hot-applied). Haptics defaults OFF (opt-in,
    // respects the system haptic master). See KeyFeedbackCards.
    var hapticEnabled = false
    // ① The magnified press preview is split per keyboard WORLD, each defaulting OFF: the 9-key (T9 + its
    // NUMPAD number pad) and the 26-key (qwerty + its NUMBER / SYMBOL pages). A sub-page inherits the toggle of
    // the keyboard it is reached from, so every layout id maps to exactly one switch. See [previewEnabledForCurrentLayout].
    var previewNineEnabled = false
    var previewAlphaEnabled = false
    // ② Letter-case display setting (AUTO = follow shift / always UPPER / always LOWER). Affects only the
    // on-key + preview label via [displayLabel]; never the committed character or the shift logic.
    var caseMode: LetterCase = LetterCase.AUTO
    // ⑤ The magnified press-preview bubble: the currently-pressed previewable key + its on-screen rect, plus a
    // Motion.PressFeedback (EXISTING Motion vocabulary — Motion.kt untouched) driving its scale/alpha appear.
    private var previewKey: Key? = null
    private val previewRect = RectF()
    private val previewFeedback = Motion.PressFeedback(this)
    // ③ 26-key long-press case/symbol box: three cells [UPPER][key.sub][lower] above the held EN letter. The
    // finger slides left/middle/right (past [caseBoxSlop]) to pick a cell; no slide = the normal letter.
    private var caseBoxKey: Key? = null
    private var caseBoxActive = false
    private var caseBoxSelected = -1 // 0 = upper, 1 = symbol, 2 = lower; -1 = none (no slide → default lower)
    private var caseBoxMoved = false
    private val caseBoxSlop = 12f * density

    // F1: all colours come from the Monet palette (default = static light = the previous hand-tuned look).
    // AegisInputMethodService pushes the live, dark-aware palette via [applyPalette].
    private var palette = ImePalette.STATIC_LIGHT

    // No software layer (jank fix): the keyboard draws only flat fills/strokes — no setShadowLayer/BlurMaskFilter
    // anywhere here (unlike CandidateView/PreeditView, whose soft shadows genuinely need one). A software layer
    // forced the WHOLE keyboard to re-rasterize into a CPU bitmap on every invalidate() — and press-highlight,
    // preview bubble, case box and scroll-column drag all invalidate — so it was pure per-frame re-raster cost
    // with no visual payoff. Drawing straight to the hardware canvas (default LAYER_TYPE_NONE) is cheaper here.

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    // F2: flat MD3 text — no neumorphic emboss shadow.
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(20f) }
    private val specialLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabelSecondary; textAlign = Paint.Align.CENTER; textSize = sp(15f) }
    // Chinese IME behavior note.
    private val boldLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(18f); typeface = android.graphics.Typeface.DEFAULT_BOLD }
    // I4: the shift glyph when one-shot/locked — accent colour makes the active state obvious on a normal key.
    private val shiftActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentBottom; textAlign = Paint.Align.CENTER; textSize = sp(20f) }
    private val accentLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentLabel; textAlign = Paint.Align.CENTER; textSize = sp(20f) }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyHint; textAlign = Paint.Align.RIGHT; textSize = sp(10f) }
    // Chinese IME behavior note.
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
    // ④ LEFT align (was CENTER): drawScrollColumn positions each mark by its real INK box so it is optically
    // centred; with LEFT align the draw origin is computed directly from getTextBounds on both axes.
    private val scrollLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.LEFT; textSize = sp(17f) }
    private val inkBounds = android.graphics.Rect() // ④ reused ink-measurement rect for the scroll column
    // debug.17: STROKE paint for the self-drawn key glyphs (⌫ / ⇧ / ✎) — colour is set per draw (state-aware).
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    // ⑤ press-preview bubble: an elevated key surface + outline + a magnified label.
    private val previewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keySurface }
    private val previewOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density; color = palette.separator }
    private val previewLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.keyLabel; textAlign = Paint.Align.CENTER; textSize = sp(30f) }

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

    private data class Placed(val rect: RectF, val key: Key, val groupId: Int = 0)

    fun setLayout(newLayout: KeyboardLayout, isShifted: Boolean, isLocked: Boolean, language: Lang) {
        // A3: reset the left column to the top whenever its CONTENT changes (new syllable / rest↔compose),
        // but keep the scroll offset on a pure re-render of the same list.
        val sameColumn = newLayout.scrollColumn?.items?.map { it.label } == layout.scrollColumn?.items?.map { it.label }
        // MD3 mode-switch (9键↔26键↔数字↔符号): an incoming fade-through. Gated to a real layout-id change on an
        // already-laid-out keyboard, so the per-keystroke re-renders (same id) and the first cold show (width==0,
        // handled by the panel/keyboard reveal) never fade — only a genuine mode change does. Alpha only: the
        // content + hit rects are applied synchronously below FIRST, so touch stays exact through the fade.
        val modeChanged = newLayout.id != layout.id
        layout = newLayout
        shifted = isShifted
        shiftLocked = isLocked // I4: drives the solid (locked) vs hollow (one-shot/off) shift glyph
        lang = language
        scrollColumn = newLayout.scrollColumn
        // debug.17 fix: on a content change, kill any running fling BEFORE resetting the offset, so the next
        // computeScroll frame renders the new column from 0 instead of restoring the stale fling offset.
        if (!sameColumn) { fling.forceFinish(); scrollY = 0f }
        // All four layouts have the same row count, so swapping between them leaves the measured
        // height unchanged and onSizeChanged never fires — relay out here so the new keys (and their
        // hit rects) take effect immediately instead of redrawing the stale layout.
        if (width > 0) relayout()
        requestLayout()
        invalidate()
        if (modeChanged && width > 0) { modeSwitches++; Motion.fadeIn(this, Motion.MODE_SWITCH) }
    }

    /** Test seam: how many live keyboard mode switches have run the MD3 fade (same-id re-renders must NOT bump it). */
    internal fun modeSwitchesForTest(): Int = modeSwitches

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = layout.rowCount
        // All 4-row keyboards (9-key, numpad, number, symbol) share one taller height; the 5-row 26-key
        // keeps the base. So 9-key⇄123 and any text⇄number/symbol switch never resizes the IME window.
        // (NINE is rowCount==4, so it still gets the same +2dp/row short-page bump as the nine-only I3 — superset.)
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
        fling.computeOffset()?.let {
            scrollY = it
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
        val paint = scrollLabelPaint // reference size; an over-wide label shrinks to fit (debug.16 item5)
        val baseTextSize = paint.textSize
        val avail = scrollRegion.width() - 12f * density // padding each side so the glyph never touches the edge
        val minTextSize = 11f * density
        for ((i, key) in sc.items.withIndex()) {
            val top = scrollRegion.top - scrollY + i * scrollCellH
            val bottom = top + scrollCellH
            if (bottom < scrollRegion.top || top > scrollRegion.bottom) continue // off-screen
            val pressLevel = if (i == scrollVisualPressedIndex) scrollPress.level else 0f
            if (pressLevel > 0f) {
                tmpRect.set(scrollRegion.left, top, scrollRegion.right, bottom)
                pressHighlight.color = Motion.stateLayerColor(palette.keyLabel, pressLevel)
                canvas.drawRoundRect(tmpRect, keyRadius * 0.6f, keyRadius * 0.6f, pressHighlight)
            }
            // debug.16 item5: the left column is narrow; keep the reference size unless a label is wider than the
            // column (e.g. a 6-letter syllable zhuang/shuang/chuang) — then shrink it (clamped) so it shows in
            // full instead of being clipped to the column edge. The widened column (Layouts.NINE_LEFT_U) means
            // this normally never triggers; it is the belt-and-suspenders guard for large font scales.
            val label = displayLabel(key)
            paint.textSize = baseTextSize
            val w = paint.measureText(label)
            if (w > avail && avail > 0f) paint.textSize = (baseTextSize * avail / w).coerceAtLeast(minTextSize)
            // ④ INK centring on BOTH axes. getTextBounds gives the glyph's real ink box (relative to a LEFT-align
            // origin at the baseline); place its centre on the cell centre. Font line-box centring (the old
            // (descent+ascent)/2) pushed full-width CJK punctuation (，。？！ — ink sits in the lower-left of the
            // em square) visibly down-left; ink centring makes every mark optically centred in its cell. Same
            // code path draws the 9-key pinyin column AND the numpad operator column, so both are fixed at once.
            paint.getTextBounds(label, 0, label.length, inkBounds)
            val cellCx = scrollRegion.centerX()
            val cellCy = (top + bottom) / 2f
            canvas.drawText(label, cellCx - inkBounds.exactCenterX(), cellCy - inkBounds.exactCenterY(), paint)
            if (i < sc.items.size - 1 && bottom < scrollRegion.bottom) {
                canvas.drawLine(scrollRegion.left + 6 * density, bottom, scrollRegion.right - 6 * density, bottom, sepLinePaint)
            }
        }
        paint.textSize = baseTextSize // restore (the paint is shared across draws)
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
            val pressLevel = if (p.key == visualPressed) keyPress.level else 0f
            drawKey(canvas, p.rect, p.key.accent, pressLevel)
            drawLabel(canvas, p)
        }

        // A3 scrollable left column (pinyin combos / punctuation), drawn over its own region.
        drawScrollColumn(canvas)

        // ⑤ magnified press preview, drawn last so it sits over the keys.
        drawPreview(canvas)
    }

    /**
     * ⑤ The enlarged press-preview bubble: an elevated key surface centred above the pressed key, holding a
     * magnified copy of its label, scaling/fading in via the EXISTING Motion.PressFeedback level. Positioned
     * ABOVE the key; for a top-row key whose bubble would clip past the view top it is clamped to sit flush
     * with the top edge instead (self-drawn, so it stays inside the KeyboardView — no PopupWindow).
     */
    private fun drawPreview(canvas: Canvas) {
        if (caseBoxActive) { drawCaseBox(canvas); return } // ③ the long-press box replaces the single bubble
        val key = previewKey ?: return
        val level = previewFeedback.level
        if (level <= 0f) return
        val bw = previewRect.width() * 1.32f
        val bh = previewRect.height() * 1.12f
        val cx = previewRect.centerX().coerceIn(bw / 2f, width - bw / 2f)
        var top = previewRect.top - bh - 4f * density
        if (top < 0f) top = 0f // top row: clamp flush to the view top rather than clipping past it
        tmpRect.set(cx - bw / 2f, top, cx + bw / 2f, top + bh)
        val alpha = (255 * level).toInt().coerceIn(0, 255)
        val scale = 0.86f + 0.14f * level
        canvas.save()
        canvas.scale(scale, scale, tmpRect.centerX(), tmpRect.bottom)
        previewFillPaint.alpha = alpha
        canvas.drawRoundRect(tmpRect, keyRadius, keyRadius, previewFillPaint)
        previewOutlinePaint.alpha = alpha
        canvas.drawRoundRect(tmpRect, keyRadius, keyRadius, previewOutlinePaint)
        previewFillPaint.alpha = 255 // restore the shared paint's opacity
        previewOutlinePaint.alpha = 255
        previewLabelPaint.alpha = alpha
        // ① fit the label: a 9-key block ("ABC" … "WXYZ"), 分词 or a punctuation mark is wider than a single
        // 26-key letter — shrink it to the bubble instead of clipping, so the whole block shows (与键面一致).
        drawFittedPreviewLabel(canvas, displayLabel(key), tmpRect)
        previewLabelPaint.alpha = 255
        canvas.restore()
    }

    /** ① Draw [label] centred in [box], shrinking the shared preview paint if the label overruns the bubble. */
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
        previewLabelPaint.textSize = base // restore (the paint is shared across draws)
    }

    /**
     * ③ The 26-key long-press box: three cells [UPPER][key.sub symbol][lower] above the held letter. The
     * currently-selected cell (chosen by the finger's x once it slides past the slop) is filled with the accent
     * colour; with no slide nothing is highlighted and a lift commits the normal letter. Self-drawn (no PopupWindow),
     * scaling/fading in via the EXISTING Motion.PressFeedback level — Motion.kt untouched.
     */
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
        previewFillPaint.color = palette.keySurface // restore shared paints
        previewFillPaint.alpha = 255
        previewOutlinePaint.alpha = 255
        previewLabelPaint.color = palette.keyLabel
        previewLabelPaint.alpha = 255
        canvas.restore()
    }

    /** F2: a flat MD3 tonal key — accent = solid primary fill; normal = tonal fill + thin outline. */
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
        canvas.drawRoundRect(rect, keyRadius, keyRadius, keyOutlinePaint)
    }

    private fun drawLabel(canvas: Canvas, p: Placed) {
        if (p.key.action == KeyAction.TOGGLE_LANG) { drawLangToggle(canvas, p.rect); return }
        if (p.key.action == KeyAction.SHIFT) { drawShift(canvas, p.rect); return } // I4: stateful arrow glyph
        // debug.17: ⌫ is a self-drawn Glyph (no font character impersonating an icon, no FE0E hack).
        if (p.key.action == KeyAction.BACKSPACE) { drawKeyGlyph(canvas, p.rect, palette.keyLabel) { c, pt, x, y, s -> Glyphs.drawBackspace(c, pt, x, y, s) }; return }
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        val cx = p.rect.centerX()
        val cy = p.rect.centerY()
        val display = displayLabel(p.key)
        val paint = when {
            p.key.accent -> accentLabelPaint
            p.key.bold -> boldLabelPaint // Chinese IME behavior note.
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
      * Chinese IME behavior note.
      * Chinese IME behavior note.
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
      * Chinese IME behavior note.
      * Chinese IME behavior note.
     */
    private fun drawShift(canvas: Canvas, rect: RectF) {
        // debug.17: self-drawn Glyphs.drawShift (no font ⇧/⬆ char). OFF/ONCE = hollow arrow (accent when armed);
        // LOCK = the same arrow with a caps-lock underline bar — all in the same monochrome-stroke language.
        drawKeyGlyph(canvas, rect, if (shifted) palette.accentBottom else palette.keyLabel) { c, pt, x, y, s ->
            Glyphs.drawShift(c, pt, x, y, s, locked = shiftLocked)
        }
    }

    /** debug.17: paint a self-drawn [Glyphs] key icon centred in [rect] at a consistent box (the iconPaint owns
     *  stroke width/cap; colour set per call). s = ~0.24 of the key's short side. */
    private inline fun drawKeyGlyph(canvas: Canvas, rect: RectF, color: Int, draw: (Canvas, Paint, Float, Float, Float) -> Unit) {
        iconPaint.color = color
        draw(canvas, iconPaint, rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) * 0.24f)
    }

    /** I4 test seam: the shift key's current visual state (OFF / ONCE / LOCK). */
    internal fun shiftRenderState(): String = if (shiftLocked) "LOCK" else if (shifted) "ONCE" else "OFF"

    /** ⑤ test seams: the magnified preview's current label (null when hidden) and whether it is armed. */
    internal fun previewLabelForTest(): String? = previewKey?.let { displayLabel(it) }
    internal fun previewActiveForTest(): Boolean = previewKey != null

    /** ② test seam: the case-aware label a key would draw (on-key face + preview bubble share this). */
    internal fun displayLabelForTest(key: Key): String = displayLabel(key)

    /** ③ case-box test seams: whether the box is open, its three cell labels, and the selected index (-1 = none). */
    internal fun caseBoxActiveForTest(): Boolean = caseBoxActive
    internal fun caseBoxLabelsForTest(): List<String>? = caseBoxKey?.let { caseBoxLabels(it) }
    internal fun caseBoxSelectedForTest(): Int = caseBoxSelected

    /** Test seam: the on-screen centre of the first key with [action] (for robust tap targeting). */
    internal fun centerOfActionForTest(action: KeyAction): Pair<Float, Float>? {
        if (placed.isEmpty()) relayout()
        val p = placed.firstOrNull { it.key.action == action } ?: return null
        return p.rect.centerX() to p.rect.centerY()
    }

    /** ② Test seam: the on-screen centre of the first key whose label == [label] (multi-touch targeting). */
    internal fun centerOfLabelForTest(label: String): Pair<Float, Float>? {
        if (placed.isEmpty()) relayout()
        val p = placed.firstOrNull { it.key.label == label } ?: return null
        return p.rect.centerX() to p.rect.centerY()
    }

    /**
     * ② The label drawn on a key face and in the preview bubble. The letter-case setting decides the case of the
     * displayed LETTERS — DISPLAY ONLY: the committed character, the T9 digit [Key.output] and the shift logic are
     * all untouched (so "always uppercase" never corrupts CN pinyin, which types lowercase):
     *  - a single a–z COMMIT letter (26-key face): AUTO follows shift (lowercase at rest, uppercase when shifted —
     *    the original behaviour), UPPER shows it always uppercase, LOWER always lowercase;
     *  - a 9-key T9 letter block ("ABC"/"DEF"/… — authored uppercase, emits a digit, see [isNineLetterBlock]):
     *    UPPER shows the block uppercase (ABC), LOWER shows it lowercase (abc), AUTO keeps the authored uppercase
     *    block (the original 9-key look).
     * Every other (non-letter) key is returned verbatim.
     */
    private fun displayLabel(key: Key): String {
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
                LetterCase.AUTO -> key.label // authored uppercase block — the original 9-key look
            }
        }
        return key.label // debug.17: ✎ / ⌫ no longer drawn as text — drawLabel renders them via Glyphs
    }

    /**
     * ② A 9-key T9 letter block: a COMMIT key whose face is 2+ authored A–Z letters ("ABC"…"WXYZ") that emits a
     * single T9 digit (2–9) — the eight middle cells of [com.aegis.ime.layout.Layouts.nine]. This is the exact,
     * self-contained shape (no digit key, no control key, no 26-key single letter qualifies), so the letter-case
     * setting cases the WHOLE block for DISPLAY only while its [Key.output] digit / the decoder stay untouched.
     */
    private fun isNineLetterBlock(key: Key): Boolean =
        key.action == KeyAction.COMMIT && key.label.length > 1 && key.label.all { it in 'A'..'Z' } &&
            key.output.length == 1 && key.output[0] in '2'..'9'

    /** ③ The three case-box cell labels for [key]: [UPPER, key.sub-or-"", lower]. */
    private fun caseBoxLabels(key: Key): List<String> =
        listOf(key.label.uppercase(), key.sub ?: "", key.label.lowercase())

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A3: a gesture that starts in the left scroll column is a single-touch scroll/pick, fully isolated
        // from key presses (it never triggers the keyboard's tap / backspace-swipe paths and owns the whole
        // event stream until its UP/CANCEL). A fresh DOWN outside the region clears any stuck latch (defensive:
        // a lost UP/CANCEL must not swallow the tap). A held-scroll-finger + a simultaneous key-tap is a known
        // pre-existing limitation of that single-touch model — properly supporting it would need a second
        // tracked pointer in the scroll path, out of scope here; the multi-touch key ROLLING the ② work targets
        // happens on the key grid (inScrollDown false), handled by the per-pointer branches below.
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
                // ② A second finger landed before the first lifted (rolling). Commit the CURRENT active key
                // (type-through) at the active pointer's last position, then hand the gesture to the new finger
                // so its key is tracked from its own down point — neither key is dropped. Only finish when there
                // IS an active gesture: if activePointerId is INVALID (the active finger already lifted while
                // another finger still rests), downKey/downX/downY are STALE and finishing would spuriously
                // re-emit the previous key — so we skip straight to beginning the new finger.
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
                // Only the active pointer drives the gesture; other fingers (already rolled through) are inert.
                val ai = event.findPointerIndex(activePointerId)
                if (ai >= 0) handlePrimaryMove(event.getX(ai), event.getY(ai))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // If the ACTIVE finger lifted, commit its key. A non-active finger (already rolled through when
                // the active one landed) is inert — ignoring its lift is what prevents a double/misattributed emit.
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

    /** ② Begin (or re-begin, on a roll) the key gesture for the active pointer that went down at [x],[y]. */
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
            // ③ a 26-key EN letter held still opens the case/symbol box (mutually exclusive with auto-repeat,
            // which is BACKSPACE-only). A flick / slide-off before the timer fires cancels it (see cancelKeyHold).
            else if (lang == Lang.EN && isAlphaLetter(dk)) repeatHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            // ⑤ tactile + visual press feedback, each gated by its own setting.
            if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showPreview(dk, dp.rect)
        } else {
            hidePreview()
        }
    }

    /** ① Content keys get an enlarged preview: every COMMIT key PLUS the 9-key 分词 (SEGMENT) key — a
     *  frequently-tapped composing action the user asked to confirm. Every OTHER functional key
     *  (space / backspace / shift / enter / symbols / lang-toggle / 123 / @# / 重输 …) stays exempt. */
    private fun isPreviewable(key: Key) =
        key.action == KeyAction.COMMIT || key.action == KeyAction.SEGMENT

    /** ① The press-preview toggle governing the CURRENT layout: the 9-key world (T9 + its NUMPAD) reads the
     *  9-key switch; the 26-key world (qwerty + its NUMBER / SYMBOL pages) reads the 26-key switch. */
    private fun previewEnabledForCurrentLayout(): Boolean = when (layout.id) {
        LayoutId.NINE, LayoutId.NUMPAD -> previewNineEnabled
        LayoutId.ALPHA, LayoutId.NUMBER, LayoutId.SYMBOL -> previewAlphaEnabled
    }

    /** ⑤ Arm the magnified preview for [key] over its [rect] (no-op when the layout's toggle is off or the key
     *  is not a content key). Shared by the key grid AND the left scroll column (① punctuation / operators). */
    private fun showPreview(key: Key, rect: RectF) {
        if (!previewEnabledForCurrentLayout() || !isPreviewable(key)) { hidePreview(); return }
        previewKey = key
        previewRect.set(rect)
        previewFeedback.press() // EXISTING Motion vocabulary drives the scale/alpha appear
        invalidate()
    }

    /** ⑤ Retract the preview (release / cancel / slide off the pressed key). Clears [previewKey] and resets the
     *  feedback level SYNCHRONOUSLY: drawPreview early-returns on a null key, so an animated release() could
     *  never render its fade anyway — reset() avoids ~PRESS_OUT of no-op repaint frames. */
    private fun hidePreview() {
        if (previewKey == null) return
        previewKey = null
        previewFeedback.reset()
        invalidate()
    }

    /** ③ Retract the long-press case box (release / cancel / new gesture). Clears its state SYNCHRONOUSLY. */
    private fun clearCaseBox() {
        if (!caseBoxActive && caseBoxKey == null) return
        caseBoxActive = false
        caseBoxKey = null
        caseBoxSelected = -1
        caseBoxMoved = false
        previewFeedback.reset()
        invalidate()
    }

    /** ③ Case-box geometry (shared by [drawCaseBox] and [caseBoxSelectionAt] so hit-test == render). */
    private fun caseBoxCellW(): Float = previewRect.width() * 1.15f
    private fun caseBoxLeft(): Float {
        val boxW = caseBoxCellW() * 3f
        return previewRect.centerX().coerceIn(boxW / 2f, width - boxW / 2f) - boxW / 2f
    }

    /** ③ Which of the three cells the finger's [x] is over (0 = upper, 1 = symbol, 2 = lower). */
    private fun caseBoxSelectionAt(x: Float): Int =
        ((x - caseBoxLeft()) / caseBoxCellW()).toInt().coerceIn(0, 2)

    /** ② Active-pointer move to [x],[y]: case-box selection, backspace/letter vertical-swipe detection,
     *  9-key vertical-as-click pinning, or follow-finger retarget. */
    private fun handlePrimaryMove(x: Float, y: Float) {
        val dk = downKey
        when {
            caseBoxActive -> {
                // ③ box mode: the finger slides left / middle / right (past the slop) to pick a cell; a
                // no-slide lift commits the normal letter. Vertical / horizontal are treated the same — the
                // cell is chosen by x once any slide is registered (so sliding up into the box selects too).
                if (abs(x - downX) > caseBoxSlop || abs(y - downY) > caseBoxSlop) caseBoxMoved = true
                val newSel = if (caseBoxMoved) caseBoxSelectionAt(x) else -1
                if (newSel != caseBoxSelected) { caseBoxSelected = newSel; invalidate() }
            }
            dk != null && dk.action == KeyAction.BACKSPACE -> {
                // Vertical drag on backspace = a swipe gesture, not a key press.
                val dy = y - downY
                if (!swiped && abs(dy) > swipeThreshold && abs(dy) > abs(x - downX)) {
                    swiped = true
                    cancelKeyHold()
                }
            }
            dk != null && lang == Lang.EN && isAlphaLetter(dk) -> {
                // A vertical flick on a 26-key letter commits its super-script symbol (up) or the letter
                // (down); a horizontal slide still retargets to the neighbour (★V slide-to-correct).
                // Gated to EN so it never flushes a half-typed CN pinyin buffer. A fast flick (before the
                // ③ long-press timer fires) cancels the box; a slow hold opens it (handled above).
                val dy = y - downY
                if (!swiped && abs(dy) > swipeThreshold && abs(dy) > abs(x - downX)) {
                    swiped = true
                    vSwipeDir = if (dy < 0) -1 else 1
                    cancelKeyHold()
                    hidePreview() // ⑤ a flick is not a plain press → drop the preview
                } else if (!swiped) {
                    val k = currentTarget(x, y)
                    if (k !== pressed) {
                        setPressedKey(k)
                        if (k !== downKey) { cancelKeyHold(); hidePreview() }
                    }
                }
            }
            dk != null && isNineDigit(dk) -> {
                // ④ 9-key digit: a vertical drag = a single click on the pressed key. Pin the pressed key so a
                // vertical swipe never drifts to a neighbour (up 5→2) or the function row (down); a horizontal
                // slide to a neighbour still retargets ("point at the right key", 指哪打哪).
                val dx = x - downX
                val dy = y - downY
                if (abs(dy) > abs(dx)) {
                    if (pressed !== downKey) setPressedKey(downKey) // re-pin after any earlier horizontal drift
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
                    // ⑤ slid off the pressed key → the preview would now mislead (it shows the down key); hide it.
                    if (k !== downKey) { cancelKeyHold(); hidePreview() }
                }
            }
        }
    }

    /** ② Commit the active pointer's gesture at its release point [x],[y] (tap / backspace-swipe / letter-flick /
     *  ③ case-box pick / ④ 9-key vertical-as-click). */
    private fun finishPrimary(x: Float, y: Float, eventTime: Long) {
        cancelKeyHold()
        val dk = downKey
        // ④ the 9-key vertical-as-click target: [pressed] is pinned to the down key on a vertical drag and set
        // to the retargeted neighbour on a horizontal slide. Capture it before releasePressedKey() nulls it.
        val stickyPressed = pressed
        hidePreview() // ⑤ retract the press preview on release
        releasePressedKey()
        when {
            // !repeating: a long-press that already auto-fired must not ALSO emit a swipe/tap on lift.
            dk != null && caseBoxActive -> {
                // ③ commit the selected cell: upper / symbol / lower (all direct); no slide → the normal letter.
                performClick()
                when (caseBoxSelected) {
                    0 -> onKey(Key(dk.label.uppercase(), output = dk.label.uppercase(), direct = true))
                    1 -> dk.sub?.let { s -> onKey(Key(s, output = s, direct = true)) } ?: emitKey(dk, eventTime)
                    2 -> onKey(Key(dk.label.lowercase(), output = dk.label.lowercase(), direct = true))
                    else -> emitKey(dk, eventTime) // no slide → normal letter (respects shift / case setting)
                }
            }
            dk != null && dk.action == KeyAction.BACKSPACE && swiped && !repeating ->
                onBackspaceSwipe(y - downY < 0)
            dk != null && lang == Lang.EN && isAlphaLetter(dk) && swiped && !repeating -> {
                // B2: up-flick commits the super-script symbol straight to the editor (direct);
                // down-flick commits the letter itself.
                performClick()
                if (vSwipeDir < 0 && dk.sub != null) onKey(Key(dk.sub, output = dk.sub, direct = true))
                else onKey(dk)
            }
            dk != null && isNineDigit(dk) && !repeating -> {
                // ④ commit the PINNED key (down key on a vertical drag; retargeted neighbour on a horizontal slide),
                // never the key the finger happened to drift onto vertically.
                performClick(); emitKey(stickyPressed ?: dk, eventTime)
            }
            !repeating ->
                currentTarget(x, y)?.let { performClick(); emitKey(it, eventTime) }
        }
        downKey = null
        downPlaced = null
        clearCaseBox()
    }

    /** ② Abandon the active gesture without emitting (ACTION_CANCEL). */
    private fun cancelPrimary() {
        cancelKeyHold()
        hidePreview() // ⑤ retract the press preview on cancel
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

    /**
     * A3 left scroll column: a vertical drag past the swipe threshold scrolls the list; a tap (no
     * scroll) on the item the finger went down on picks it. Isolated from the keyboard's key handling.
     */
    private fun handleScrollTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inScrollDown = true; scrolling = false
                // U7/U17: a touch landing on a moving list STOPS the fling (FlingScroller.onDown) and that tap
                // must not select anything (so flicking then tapping to halt never mis-commits a combo).
                fling.onDown()
                scrollDownY = event.y; scrollLastY = event.y
                scrollPressedIndex = if (fling.stopArmed) -1 else scrollIndexAt(event.y)
                scrollVisualPressedIndex = scrollPressedIndex
                if (scrollPressedIndex >= 0) scrollPress.press() else scrollPress.release()
                // ① the left scroll column (9-key punctuation / numpad operators) now previews the pressed cell,
                // gated by the same per-layout toggle + isPreviewable rule as the key grid (so 自定义 / composing
                // combos stay exempt). showPreview copies the rect, so the shared tmpRect is safe to reuse.
                showScrollPreview()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                fling.addSample(event.eventTime, event.y)
                if (!scrolling && abs(event.y - scrollDownY) > scrollSlop) {
                    scrolling = true; scrollPressedIndex = -1; scrollPress.release()
                    hidePreview() // a drag became a scroll → the pressed-cell preview would mislead
                }
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
                    if (col != null && fling.fling(scrollY, maxScroll())) postInvalidateOnAnimation()
                } else if (col != null && !fling.stopArmed) {
                    val idx = scrollIndexAt(event.y)
                    if (idx >= 0 && idx == scrollPressedIndex) { performClick(); onKey(col.items[idx]) }
                }
                scrollPressedIndex = -1; inScrollDown = false; scrolling = false
                scrollPress.release()
                hidePreview() // ① retract the pressed-cell preview on pick / release
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

    /** ① Arm the press preview for the currently-pressed scroll-column cell (no-op when nothing is pressed;
     *  showPreview applies the per-layout toggle + isPreviewable gate). */
    private fun showScrollPreview() {
        val sc = scrollColumn ?: return
        val idx = scrollPressedIndex
        if (idx !in sc.items.indices || scrollCellH <= 0f) return
        val top = scrollRegion.top - scrollY + idx * scrollCellH
        tmpRect.set(scrollRegion.left, top, scrollRegion.right, top + scrollCellH)
        showPreview(sc.items[idx], tmpRect)
    }

    // U7/U17 test seams (Robolectric drives MotionEvents; the OverScroller computes its final target
    // synchronously in fling(), so "reaches the bottom in one gesture" is checkable without a frame clock).
    internal fun scrollOffsetForTest(): Float = scrollY
    internal fun maxScrollForTest(): Float = maxScroll()
    internal fun isFlingingForTest(): Boolean = !fling.isFinished
    internal fun flingFinalForTest(): Float = fling.finalOffset()

    /** I5 test seam: the windowed fling velocity the next UP would use (px/s, screen-Y) — via [FlingScroller]. */
    internal fun flingVelocityForTest(): Float = fling.velocity()

    /**
     * ★V follow-finger hit-test: exact containment first, else snap to the NEAREST key (by clamped edge
     * distance) so taps landing in the inter-key gaps are no longer dropped. Kills the 6dp row / 12dp 9-key
     * dead bands behind "indeterminate aim / no response".
     *
     * ③ mis-touch tightening: the snap cap is HALF a key height (was a full key height, 52dp). The gaps it must bridge are
     * 6dp (26-key) / 12dp (9-key), so ~26dp still bridges them with margin; but a tap that lands more than
     * half a key away from every key — deep in the gutter, or below the bottom row toward the nav bar — is now
     * DROPPED instead of being pulled onto the nearest edge key (the old full-key cap fired those mis-touches).
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
        val cap = snapCap // ③ half a key height (see field): tighter than the old full-key snap
        return if (best <= cap * cap) nearest else null
    }

    /**
     * ★V "commit the key the finger went DOWN on": a normal tap yields [downKey] even if the finger
     * micro-rolls before lift (the old code committed whatever was under the finger at ACTION_UP, so a tiny
     * slide off key A produced neighbour B).
     *
     * ③ mis-touch tightening: retargeting is now BOUNDARY-based, not distance-based. The committed key stays [downKey]
     * until the finger actually LEAVES that key's rectangle (by more than a small hysteresis margin) — then it
     * retargets to the key now under the finger (preserves slide-to-correct). The old "half the key's width"
     * radius let a slide that never left the pressed key still flip to a neighbour near the shared edge; a
     * boundary test matches the physical intuition "you're on the key you're over" and adds a hysteresis band
     * so a finger resting exactly on the seam does not flicker between the two keys.
     */
    private fun currentTarget(x: Float, y: Float): Key? {
        val dp = downPlaced ?: return placedAt(x, y)?.key
        val m = retargetHysteresis
        val insideDownKey = x >= dp.rect.left - m && x <= dp.rect.right + m &&
            y >= dp.rect.top - m && y <= dp.rect.bottom + m
        return if (insideDownKey) dp.key else placedAt(x, y)?.key ?: dp.key
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
        // ③ hold a 26-key EN letter this long (still) to open the case/symbol box. Shorter than REPEAT_DELAY_MS
        // (a menu should feel snappier) yet far above a normal tap's ~<150ms, so a plain tap never opens it.
        const val LONG_PRESS_MS = 300L
    }
}

/**
 * debug.17 #66: a reusable 1-D momentum scroller shared by every SELF-DRAWN scroll surface (the keyboard's A3
 * reading column — vertical — and the candidate strip — horizontal). The caller owns the offset and tracks the
 * finger 1:1; a flick hands off to an [OverScroller] fling whose velocity is SELF-COMPUTED over a short time
 * window of recent samples — deterministic + unit-testable (Robolectric's VelocityTracker shadow reports
 * nothing) and robust to the final-sample jitter that swung a 2-point estimate between "no fling" and
 * "overshoot". A DOWN landing on a moving list STOPS the fling and arms [stopArmed] so that tap is not a pick.
 */
class FlingScroller(context: Context) {
    private val scroller = OverScroller(context)
    private val minVel = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxVel = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val sampleT = LongArray(SAMPLES)
    private val samplePos = FloatArray(SAMPLES)
    private var head = 0
    private var count = 0

    /** True when the most recent [onDown] halted a running fling — the caller must NOT treat that tap as a pick. */
    var stopArmed = false
        private set

    /** DOWN: stop any running fling (arming [stopArmed]) and reset the velocity window. */
    fun onDown() {
        stopArmed = !scroller.isFinished
        if (stopArmed) scroller.forceFinished(true)
        count = 0; head = 0
    }

    /**
     * Cancel any running fling and clear all state (idempotent). Call when the caller resets its scroll offset
     * out from under the scroller — e.g. new content rendered at offset 0 — so the next [computeOffset] frame
     * does NOT restore the stale fling offset over the reset. Unlike [onDown] this is NOT a tap, so it clears
     * [stopArmed] rather than arming it.
     */
    fun forceFinish() {
        scroller.forceFinished(true)
        stopArmed = false
        count = 0; head = 0
    }

    /** MOVE: record one (eventTime, finger-position) sample for the windowed velocity. */
    fun addSample(t: Long, pos: Float) {
        sampleT[head] = t; samplePos[head] = pos
        head = (head + 1) % SAMPLES
        if (count < SAMPLES) count++
    }

    /**
     * Finger velocity (px/s) from the newest sample back to the oldest one still inside the last [WINDOW_MS],
     * divided by their time span — averaging out final-sample jitter. 0 with < 2 samples.
     */
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

    /**
     * UP: if the windowed velocity clears the min-fling threshold and there is room ([max] > 0), start a
     * momentum fling from [start] over [0, max]. The offset grows as the finger moves toward smaller positions,
     * so the scroll-space velocity is the NEGATIVE of the finger velocity. Returns true if a fling started.
     */
    fun fling(start: Float, max: Float): Boolean {
        val v = velocity()
        if (kotlin.math.abs(v) <= minVel || max <= 0f) return false
        scroller.fling(0, start.toInt(), 0, (-v).toInt(), 0, 0, 0, max.toInt())
        return true
    }

    /** Per-frame (call from View.computeScroll): the new offset while the fling animates, else null. */
    fun computeOffset(): Float? = if (scroller.computeScrollOffset()) scroller.currY.toFloat() else null

    val isFinished: Boolean get() = scroller.isFinished

    /** Test seam: where a running fling will finally settle (px). */
    fun finalOffset(): Float = scroller.finalY.toFloat()

    private companion object {
        const val SAMPLES = 12      // I5: ring-buffer size
        const val WINDOW_MS = 100L  // I5: velocity is measured over the last ~100ms of samples
    }
}
