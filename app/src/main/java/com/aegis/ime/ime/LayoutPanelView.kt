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
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType

enum class LayoutChoice { CN_NINE, CN_ALPHA, EN_ALPHA }

class LayoutPanelView(context: Context) : LinearLayout(context), ResettablePanel, CoversToolbar {

    var onPick: (LayoutChoice) -> Unit = {}
    var onBack: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val TITLE_SP = 16f
    private val ICON_BOX_DP = 34
    private val ICON_STROKE_DP = 2f
    private val ICON_RADIUS_DP = 7f
    private val CN_CHAR_SP = 18f
    private val EN_CHAR_SP = 13f
    private val BADGE_SP = 9f

    private var palette = ImePalette.STATIC_LIGHT
    private var active = LayoutChoice.CN_NINE
    private val backIcon = GlyphDrawable(dp(16), 0.56f, 2f * density) { c, p, x, y, s -> Glyphs.drawBack(c, p, x, y, s) }
    private val titleBtn: TextView
    private val cards: List<Card>
    private val cardRow: LinearLayout
    private val content: LinearLayout

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg)
        backIcon.applyTint(palette.keyLabel)

        titleBtn = TextView(context).apply {
            text = context.getString(R.string.layout_panel_title)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            setTextColor(palette.keyLabel)
            isClickable = true
            isFocusable = false
            setCompoundDrawablesWithIntrinsicBounds(backIcon, null, null, null)
            compoundDrawablePadding = dp(6)
            Motion.applyTapFeedback(this, palette.keyLabel)
            setOnClickListener { onBack() }
        }
        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))

        cards = listOf(
            Card(LayoutChoice.CN_NINE, context.getString(R.string.layout_nine), "拼", CN_CHAR_SP, "9"),
            Card(LayoutChoice.CN_ALPHA, context.getString(R.string.layout_alpha), "拼", CN_CHAR_SP, "26"),
            Card(LayoutChoice.EN_ALPHA, context.getString(R.string.layout_en), "EN", EN_CHAR_SP, "26"),
        )
        cardRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            for (card in cards) {
                addView(card.view, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                })
            }
        }
        content = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.TOP
            addView(cardRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(18)
            })
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        restyle()
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        titleBtn.setTextColor(p.keyLabel)
        backIcon.applyTint(p.keyLabel)
        Motion.applyTapFeedback(titleBtn, p.keyLabel)
        restyle()
    }

    fun setActiveChoice(choice: LayoutChoice) {
        active = choice
        restyle()
    }

    private fun restyle() {
        for (card in cards) card.applyStyle(card.choice == active)
    }

    override fun resetToDefault() {}

    internal fun cardViewForTest(choice: LayoutChoice): TextView = card(choice).view
    internal fun cardActiveForTest(choice: LayoutChoice): Boolean = card(choice).active
    internal fun iconTintForTest(choice: LayoutChoice): Int = card(choice).icon.tint
    internal fun badgeDigitsForTest(choice: LayoutChoice): String = card(choice).icon.badge
    internal fun iconCharForTest(choice: LayoutChoice): String = card(choice).icon.symbol
    internal fun titleButtonForTest(): TextView = titleBtn
    internal fun cardIconForTest(choice: LayoutChoice): Drawable = card(choice).icon
    internal fun cardRowTopForTest(): Int = content.top + cardRow.top

    private fun card(choice: LayoutChoice): Card = cards.first { it.choice == choice }

    private inner class Card(val choice: LayoutChoice, label: String, symbol: String, charSp: Float, badge: String) {
        var active = false
        val icon = CardIcon(symbol, charSp, badge)
        val view: TextView = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
            setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
            compoundDrawablePadding = dp(2)
            isClickable = true
            setOnClickListener { onPick(choice) }
        }

        fun applyStyle(isActive: Boolean) {
            active = isActive
            val tint = if (isActive) palette.accentBottom else palette.keyLabel
            view.setTextColor(tint)
            view.typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            icon.applyTint(tint)
            icon.applyFill(palette.keySurface)
            view.background = GradientDrawable().apply {
                setColor(palette.keySurface)
                cornerRadius = ImeShapes.cardRadiusDp * density
            }
            Motion.applyTapFeedback(view, tint, radiusDp = ImeShapes.cardRadiusDp)
        }
    }

    private inner class CardIcon(val symbol: String, charSp: Float, val badge: String) : Drawable() {
        private val overflow = dp(6).toFloat()
        private val boxSide = dp(ICON_BOX_DP).toFloat()
        private val stroke = ICON_STROKE_DP * density
        private val radius = ICON_RADIUS_DP * density
        private val badgePadX = dp(3).toFloat()
        private val badgePadY = dp(2).toFloat()
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeJoin = Paint.Join.ROUND
        }
        private val charPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD; textSize = sp(charSp)
        }
        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD; textSize = sp(BADGE_SP)
        }
        private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val charInk = Rect()
        private val badgeInk = Rect()
        var tint = 0
            private set

        init {
            charPaint.getTextBounds(symbol, 0, symbol.length, charInk)
            badgePaint.getTextBounds(badge, 0, badge.length, badgeInk)
        }

        fun applyTint(color: Int) {
            tint = color
            boxPaint.color = color
            charPaint.color = color
            badgePaint.color = color
            invalidateSelf()
        }

        fun applyFill(color: Int) {
            badgeFill.color = color
            invalidateSelf()
        }

        override fun getIntrinsicWidth() = (boxSide + overflow * 2f).toInt()
        override fun getIntrinsicHeight() = (boxSide + overflow * 2f).toInt()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val inset = stroke / 2f
            val left = b.left + overflow + inset
            val top = b.top + overflow + inset
            val right = b.left + overflow + boxSide - inset
            val bottom = b.top + overflow + boxSide - inset
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, boxPaint)
            canvas.drawText(
                symbol,
                (left + right) / 2f - charInk.exactCenterX(),
                (top + bottom) / 2f - charInk.exactCenterY(),
                charPaint,
            )
            val corner = radius * 0.2929f
            val halfW = badgeInk.width() / 2f + badgePadX
            val halfH = badgeInk.height() / 2f + badgePadY
            val cx = right - halfW
            val cy = bottom - corner
            canvas.drawRoundRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH, halfH, halfH, badgeFill)
            canvas.drawText(badge, cx - badgeInk.exactCenterX(), cy - badgeInk.exactCenterY(), badgePaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class GlyphDrawable(
        private val boxPx: Int,
        private val sFactor: Float,
        strokePx: Float,
        private val render: (Canvas, Paint, Float, Float, Float) -> Unit,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = strokePx
        }
        fun applyTint(color: Int) { paint.color = color; invalidateSelf() }
        override fun getIntrinsicWidth() = boxPx
        override fun getIntrinsicHeight() = boxPx
        override fun draw(canvas: Canvas) {
            val b = bounds
            render(canvas, paint, b.exactCenterX(), b.exactCenterY(), boxPx * sFactor)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
