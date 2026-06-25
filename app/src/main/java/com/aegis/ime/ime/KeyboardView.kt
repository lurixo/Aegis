package com.aegis.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
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

    private var layout: KeyboardLayout = Layouts.forId(LayoutId.ALPHA, Lang.CN)
    private var shifted = false

    private val placed = ArrayList<Placed>()
    private var pressed: Key? = null

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
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF78909C.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(10f)
    }

    private data class Placed(val rect: RectF, val key: Key)

    fun setLayout(newLayout: KeyboardLayout, isShifted: Boolean) {
        layout = newLayout
        shifted = isShifted
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = layout.rows.size
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
        val paint = if (display.length > 1 && p.key.action != KeyAction.COMMIT) specialLabelPaint else labelPaint
        if (p.key.sub != null) {
            val mainBaseline = cy - 3 * density
            canvas.drawText(display, cx, mainBaseline - (paint.descent() + paint.ascent()) / 2, paint)
            canvas.drawText(p.key.sub, cx, cy + 13 * density, subPaint)
        } else {
            canvas.drawText(display, cx, cy - (paint.descent() + paint.ascent()) / 2, paint)
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
                pressed = keyAt(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val k = keyAt(event.x, event.y)
                if (k !== pressed) {
                    pressed = k
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val k = keyAt(event.x, event.y)
                pressed = null
                invalidate()
                if (k != null) {
                    performClick()
                    onKey(k)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
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
}
