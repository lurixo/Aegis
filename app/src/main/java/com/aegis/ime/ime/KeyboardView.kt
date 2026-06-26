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
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
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

class KeyboardView(context: Context) : View(context) {

    var onKey: (Key) -> Unit = {}

    var onBackspaceSwipe: (Boolean) -> Unit = {}

    private var layout: KeyboardLayout = Layouts.forId(LayoutId.ALPHA, Lang.CN)
    private var shifted = false

    private val placed = ArrayList<Placed>()
    private var pressed: Key? = null

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
    private val gap = 6f * density
    private val radius = 13f * density

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF202124.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(20f)
        setShadowLayer(1.2f * density, 0f, 1f * density, 0x33000000)
    }
    private val specialLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF37474F.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(15f)
        setShadowLayer(1.2f * density, 0f, 1f * density, 0x33000000)
    }
    private val accentLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(20f)
        setShadowLayer(1.2f * density, 0f, 1f * density, 0x55000000)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF90A4AE.toInt()
        textAlign = Paint.Align.RIGHT
        textSize = sp(10f)
    }

    private val baseColor = 0xFFE6E9EF.toInt()
    private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradMatrix = Matrix()
    private val keyGradient =
        LinearGradient(0f, 0f, 0f, rowHeight, 0xFFFFFFFF.toInt(), 0xFFECEFF3.toInt(), Shader.TileMode.CLAMP)
    private val accentGradient =
        LinearGradient(0f, 0f, 0f, rowHeight, 0xFF7CC47F.toInt(), 0xFF57A35B.toInt(), Shader.TileMode.CLAMP)
    private val pressedGradient =
        LinearGradient(0f, 0f, 0f, rowHeight, 0xFFDCE0E6.toInt(), 0xFFE9ECF1.toInt(), Shader.TileMode.CLAMP)
    private val sepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD2D7DE.toInt(); strokeWidth = density }
    private val pressHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18000000 }

    private data class Placed(val rect: RectF, val key: Key, val groupId: Int = 0)

    fun setLayout(newLayout: KeyboardLayout, isShifted: Boolean) {
        layout = newLayout
        shifted = isShifted
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
        val cells = layout.cells
        if (cells != null) {
            val h = height.toFloat()
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

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(baseColor)
        if (placed.isEmpty()) relayout()

        val drawnGroups = HashSet<Int>()
        for (p in placed) {
            if (p.groupId > 0 && drawnGroups.add(p.groupId)) {
                val cells = placed.filter { it.groupId == p.groupId }.sortedBy { it.rect.top }
                drawNeumorphicShape(canvas, buildPeanut(cells))
                val l = cells.first().rect.left + 10 * density
                val r = cells.first().rect.right - 10 * density
                for (i in 1 until cells.size) {
                    val yMid = (cells[i - 1].rect.bottom + cells[i].rect.top) / 2f
                    canvas.drawLine(l, yMid, r, yMid, sepLinePaint)
                }
            }
        }

        for (p in placed) {
            if (p.groupId == 0) {
                drawNeumorphicKey(canvas, p.rect, p.key.accent, p.key == pressed)
            } else if (p.key == pressed) {
                canvas.drawRoundRect(p.rect, radius * 0.7f, radius * 0.7f, pressHighlight)
            }
            drawLabel(canvas, p)
        }
    }

    private val tmpBounds = RectF()

    private fun applyGradient(grad: LinearGradient, top: Float, h: Float) {
        gradMatrix.setScale(1f, h / rowHeight)
        gradMatrix.postTranslate(0f, top)
        grad.setLocalMatrix(gradMatrix)
        gradPaint.shader = grad
    }

    private fun drawNeumorphicKey(canvas: Canvas, rect: RectF, accent: Boolean, pressed: Boolean) {
        if (accent) { drawAccentKey(canvas, rect, pressed); return }
        if (!pressed) {
            val o = 3f * density
            val blur = 7f * density
            gradPaint.shader = null
            gradPaint.color = 0xFFFFFFFF.toInt()
            gradPaint.setShadowLayer(blur, o, o, 0x33586173)
            canvas.drawRoundRect(rect, radius, radius, gradPaint)
            gradPaint.setShadowLayer(blur, -o, -o, 0xCCFFFFFF.toInt())
            canvas.drawRoundRect(rect, radius, radius, gradPaint)
            gradPaint.clearShadowLayer()
        }
        applyGradient(if (pressed) pressedGradient else keyGradient, rect.top, rect.height())
        canvas.drawRoundRect(rect, radius, radius, gradPaint)
        gradPaint.shader = null
    }

    private fun drawAccentKey(canvas: Canvas, rect: RectF, pressed: Boolean) {
        val tall = rect.height() > rect.width() * 1.1f
        val rad = rect.height() / 2f
        fun shape(p: Paint) { if (tall) canvas.drawOval(rect, p) else canvas.drawRoundRect(rect, rad, rad, p) }
        if (!pressed) {
            val glow = if (tall) 0x9943A047.toInt() else 0xCC43A047.toInt()
            val glowBlur = if (tall) 13f * density else 15f * density
            gradPaint.shader = null
            gradPaint.color = 0xFF66BB6A.toInt()
            gradPaint.setShadowLayer(glowBlur, 0f, 3f * density, glow)
            shape(gradPaint)
            gradPaint.clearShadowLayer()
        }
        applyGradient(accentGradient, rect.top, rect.height())
        shape(gradPaint)
        gradPaint.shader = null
        if (pressed) { gradPaint.color = 0x18000000; shape(gradPaint) }
    }

    private fun drawNeumorphicShape(canvas: Canvas, path: Path) {
        val o = 3f * density
        val blur = 7f * density
        gradPaint.shader = null
        gradPaint.color = 0xFFFFFFFF.toInt()
        gradPaint.setShadowLayer(blur, o, o, 0x33586173)
        canvas.drawPath(path, gradPaint)
        gradPaint.setShadowLayer(blur, -o, -o, 0xCCFFFFFF.toInt())
        canvas.drawPath(path, gradPaint)
        gradPaint.clearShadowLayer()
        path.computeBounds(tmpBounds, true)
        applyGradient(keyGradient, tmpBounds.top, tmpBounds.height())
        canvas.drawPath(path, gradPaint)
        gradPaint.shader = null
    }

    private fun buildPeanut(cells: List<Placed>): Path {
        val extra = 7f * density
        val left = cells.first().rect.left
        val right = cells.first().rect.right
        val top = cells.first().rect.top
        val bottom = cells.last().rect.bottom
        val peanut = Path()
        for (c in cells) {
            val oval = Path().apply {
                addOval(left, c.rect.top - gap - extra, right, c.rect.bottom + gap + extra, Path.Direction.CW)
            }
            if (peanut.isEmpty) peanut.set(oval) else peanut.op(oval, Path.Op.UNION)
        }
        val envelope = Path().apply { addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW) }
        peanut.op(envelope, Path.Op.INTERSECT)
        return peanut
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
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
    }
}
