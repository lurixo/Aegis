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
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType

open class PreeditView(context: Context) : View(context) {

    private var text: String = ""
    private var shownText: String = ""
    private var model: PreeditModel? = null
    private val density = resources.displayMetrics.density
    private val pad = 6f * density
    private val candPad = 14f * density
    private val doneLabel = context.getString(R.string.panel_back)
    private var leftInset = 0f
    private var rightInset = 0f
    private val tab = RectF()
    private var downX = Float.NaN
    private var downY = Float.NaN

    var onTap: () -> Unit = {}
    var onCaret: (Int) -> Unit = {}
    var onEditDone: () -> Unit = {}

    private var palette = ImePalette.STATIC_LIGHT

    private val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.keySurface
        setShadowLayer(5f * density, 0f, 2f * density, palette.shadow)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.preeditText
        textSize = sp(16f)
    }
    private val editTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.preeditText
        textSize = sp(20f)
    }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.accentBottom
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.accentBottom
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.keyLabel
        textSize = sp(ImeType.body)
        textAlign = Paint.Align.CENTER
    }
    private val doneWidth: Float get() = donePaint.measureText(doneLabel) + candPad * 2

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    fun applyPalette(p: ImePalette) {
        palette = p
        tabPaint.color = p.keySurface
        tabPaint.setShadowLayer(5f * density, 0f, 2f * density, p.shadow)
        textPaint.color = p.preeditText
        editTextPaint.color = p.preeditText
        caretPaint.color = p.accentBottom
        underlinePaint.color = p.accentBottom
        donePaint.color = p.keyLabel
        invalidate()
    }

    fun setModel(m: PreeditModel?) {
        val was = model
        model = m
        if (was == null && m == null) return
        invalidate()
    }

    fun isEditing(): Boolean = model != null

    internal fun editingModelForTest(): PreeditModel? = model

    private fun editingModel(): PreeditModel? = model?.takeIf { it.text == shownText }

    private fun layoutTab() {
        if (shownText.isEmpty()) { tab.setEmpty(); return }
        val r = ImeShapes.cardRadiusDp * density
        val left = leftInset + candPad - pad
        if (editingModel() != null) {
            val right = (width - rightInset - candPad + pad).coerceAtLeast(left + doneWidth + pad * 2)
            tab.set(left, 0f, right, height.toFloat() + r)
        } else {
            val w = textPaint.measureText(shownText) + pad * 2
            tab.set(left, 0f, left + w, height.toFloat() + r)
        }
    }

    private fun textOffset(m: PreeditModel): Float {
        val caretX = editTextPaint.measureText(m.text, 0, m.displayCaret())
        val visible = tab.right - doneWidth - tab.left - pad * 2
        return (caretX - visible).coerceAtLeast(0f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || shownText.isEmpty()) return false
        layoutTab()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!tab.contains(event.x, event.y)) return false
                playImeTapFeedback()
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val slop = 24f * density
                val moved = downX.isNaN() || kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop
                downX = Float.NaN
                downY = Float.NaN
                if (moved || !tab.contains(event.x, event.y)) return true
                val m = editingModel()
                when {
                    m == null -> onTap()
                    event.x >= tab.right - doneWidth -> onEditDone()
                    else -> onCaret(m.rawIndexForDisplay(nearestBoundary(m, event.x)))
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downX = Float.NaN
                downY = Float.NaN
                return true
            }
        }
        return true
    }

    private fun nearestBoundary(m: PreeditModel, x: Float): Int {
        val textX = tab.left + pad - textOffset(m)
        var best = m.editableFrom
        var bestDist = Float.MAX_VALUE
        for (p in m.editableFrom..m.text.length) {
            val bx = textX + editTextPaint.measureText(m.text, 0, p)
            val d = kotlin.math.abs(bx - x)
            if (d < bestDist) { bestDist = d; best = p }
        }
        return best
    }

    internal fun boundaryXForTest(position: Int): Float {
        layoutTab()
        val offset = editingModel()?.let(::textOffset) ?: 0f
        return tab.left + pad - offset + editTextPaint.measureText(shownText, 0, position.coerceIn(0, shownText.length))
    }

    internal fun doneLeftForTest(): Float { layoutTab(); return tab.right - doneWidth }

    internal fun doneLabelForTest(): String = doneLabel

    internal fun doneTextColorForTest(): Int = donePaint.color

    fun tabBounds(): RectF { layoutTab(); return RectF(tab) }

    fun setText(s: String) {
        if (s == text) return
        val appearing = text.isEmpty() && s.isNotEmpty()
        val disappearing = text.isNotEmpty() && s.isEmpty()
        text = s
        when {
            appearing -> {
                shownText = s
                Motion.showNow(this)
            }
            disappearing -> Motion.hideNow(this, endVisibility = VISIBLE) {
                shownText = ""
                invalidate()
            }
            else -> {
                shownText = s
                invalidate()
            }
        }
    }

    internal fun shownTextForTest(): String = shownText

    internal fun tabLeftForTest(leftInsetPx: Float): Float = leftInsetPx + candPad - pad

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (text.isEmpty() && shownText.isNotEmpty()) {
            shownText = ""
            invalidate()
        }
    }

    fun setLeftInset(px: Float) {
        if (px == leftInset) return
        leftInset = px
        invalidate()
    }

    fun setRightInset(px: Float) {
        if (px == rightInset) return
        rightInset = px
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        layoutTab()
        if (shownText.isEmpty()) return
        val r = ImeShapes.cardRadiusDp * density
        val m = editingModel()
        if (m != null) { drawEditing(canvas, m, r); return }
        canvas.drawRoundRect(tab, r, r, tabPaint)
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(shownText, tab.left + pad, baseline, textPaint)
    }

    private fun drawEditing(canvas: Canvas, m: PreeditModel, r: Float) {
        val left = tab.left
        val right = tab.right
        canvas.drawRoundRect(tab, r, r, tabPaint)
        val textX = left + pad - textOffset(m)
        val baseline = height / 2f - (editTextPaint.descent() + editTextPaint.ascent()) / 2
        val textRight = right - doneWidth
        canvas.save()
        canvas.clipRect(left, 0f, textRight, height.toFloat())
        canvas.drawText(m.text, textX, baseline, editTextPaint)
        val underlineY = baseline + editTextPaint.descent() * 0.6f
        for (range in m.lockedRanges) {
            if (range.isEmpty()) continue
            val x0 = textX + editTextPaint.measureText(m.text, 0, range.first)
            val x1 = textX + editTextPaint.measureText(m.text, 0, range.last + 1)
            canvas.drawLine(x0, underlineY, x1, underlineY, underlinePaint)
        }
        val caretAt = m.displayCaret()
        val afterApostrophe = caretAt > 0 && m.text[caretAt - 1] == '\''
        val caretX = textX + editTextPaint.measureText(m.text, 0, caretAt) + if (afterApostrophe) caretPaint.strokeWidth else 0f
        val caretTop = baseline + editTextPaint.ascent()
        val caretBottom = baseline + editTextPaint.descent()
        canvas.drawLine(caretX, caretTop, caretX, caretBottom, caretPaint)
        canvas.restore()
        val doneBaseline = height / 2f - (donePaint.descent() + donePaint.ascent()) / 2
        canvas.drawText(doneLabel, right - doneWidth / 2f, doneBaseline, donePaint)
    }
}
