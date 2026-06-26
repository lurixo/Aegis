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
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.KeyboardLayout
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts

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

    private val placed = ArrayList<Placed>()
    private var pressed: Key? = null

    // Long-press key repeat (#8) + backspace swipe (#5).
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var downKey: Key? = null
    private var downX = 0f
    private var downY = 0f
    private var repeating = false
    private var swiped = false
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
        key.action == KeyAction.BACKSPACE || key.action == KeyAction.SPACE || key.action == KeyAction.ENTER

    private val density = resources.displayMetrics.density
    private val rowHeight = 52f * density
    private val gap = 5f * density
    private val radius = 7f * density

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202124.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(20f)
    }
    private val specialLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF37474F.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(15f)
    }
    private val accentLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(20f)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF90A4AE.toInt()
        textAlign = Paint.Align.RIGHT
        textSize = sp(10f)
    }

    private data class Placed(val rect: RectF, val key: Key)

    fun setLayout(newLayout: KeyboardLayout, isShifted: Boolean) {
        layout = newLayout
        shifted = isShifted
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
        // Fractional-cell layout (9-key): keys carry explicit rectangles for merged / spanning cells.
        val cells = layout.cells
        if (cells != null) {
            val h = height.toFloat()
            for (pk in cells) {
                placed.add(
                    Placed(
                        RectF(pk.x * w + gap, pk.y * h + gap, (pk.x + pk.w) * w - gap, (pk.y + pk.h) * h - gap),
                        pk.key,
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

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFFE2E6EA.toInt())
        if (placed.isEmpty()) relayout()
        for (p in placed) {
            val special = p.key.action != KeyAction.COMMIT && p.key.action != KeyAction.SPACE
            keyPaint.color = when {
                p.key.accent -> if (p.key == pressed) 0xFF4CAF50.toInt() else 0xFF66BB6A.toInt()
                p.key == pressed -> 0xFFB0BEC5.toInt()
                special -> 0xFFCDD5DB.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
            canvas.drawRoundRect(p.rect, radius, radius, keyPaint)
            drawLabel(canvas, p)
        }
    }

    private fun drawLabel(canvas: Canvas, p: Placed) {
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

    private fun displayLabel(key: Key): String {
        if (shifted && key.action == KeyAction.COMMIT && key.label.length == 1 && key.label[0] in 'a'..'z') {
            return key.label.uppercase()
        }
        return key.label
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downKey = keyAt(event.x, event.y)
                pressed = downKey
                downX = event.x; downY = event.y
                repeating = false; swiped = false
                downKey?.let { if (isRepeatable(it)) repeatHandler.postDelayed(repeatRunnable, REPEAT_DELAY_MS) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dk = downKey
                if (dk != null && dk.action == KeyAction.BACKSPACE) {
                    // Vertical drag on backspace = a swipe gesture, not a key press.
                    val dy = event.y - downY
                    if (!swiped && abs(dy) > swipeThreshold && abs(dy) > abs(event.x - downX)) {
                        swiped = true
                        repeatHandler.removeCallbacks(repeatRunnable)
                    }
                } else {
                    val k = keyAt(event.x, event.y)
                    if (k !== pressed) {
                        pressed = k
                        if (k !== downKey) repeatHandler.removeCallbacks(repeatRunnable)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                repeatHandler.removeCallbacks(repeatRunnable)
                val dk = downKey
                pressed = null
                invalidate()
                if (dk != null && dk.action == KeyAction.BACKSPACE && swiped) {
                    onBackspaceSwipe(event.y - downY < 0)
                } else if (!repeating) {
                    keyAt(event.x, event.y)?.let { performClick(); onKey(it) }
                }
                downKey = null
            }
            MotionEvent.ACTION_CANCEL -> {
                repeatHandler.removeCallbacks(repeatRunnable)
                pressed = null
                downKey = null
                invalidate()
            }
        }
        return true
    }

    private fun keyAt(x: Float, y: Float): Key? =
        placed.firstOrNull { it.rect.contains(x, y) }?.key

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val REPEAT_DELAY_MS = 400L    // hold this long before auto-repeat starts
        const val REPEAT_INTERVAL_MS = 55L  // then fire this often
    }
}
