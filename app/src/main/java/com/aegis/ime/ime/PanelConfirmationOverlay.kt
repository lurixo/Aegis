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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType
import kotlin.math.abs

internal class PanelConfirmationOverlay(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private var confirmAction: TextView? = null
    private var cancelAction: TextView? = null
    private var cardView: View? = null
    private var outsidePointerId = MotionEvent.INVALID_POINTER_ID
    private var outsideDownX = 0f
    private var outsideDownY = 0f
    private var outsideTap = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    init {
        visibility = View.GONE
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun show(title: String, confirm: String, cancel: String, palette: ImePalette, onConfirm: () -> Unit) {
        Motion.reset(this)
        resetOutsideGesture()
        setOnClickListener(null)
        isClickable = false
        clearActions()
        cardView = null
        removeAllViews()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            minimumWidth = dp(260)
            setPadding(0, dp(6), 0, dp(6))
            background = GradientDrawable().apply {
                setColor(palette.keySurface)
                cornerRadius = ImeShapes.cardRadiusDp * density
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
        }
        card.addView(TextView(context).apply {
            text = title
            gravity = Gravity.CENTER
            setTextColor(palette.keyLabel)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
            setPadding(dp(20), dp(12), dp(20), dp(8))
        })
        val confirmView = action(confirm, palette) {
            dismiss()
            onConfirm()
        }
        confirmAction = confirmView
        val cancelView = action(cancel, palette, ::dismiss)
        cancelAction = cancelView
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), dp(6))
            addView(confirmView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(cancelView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
        }
        card.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        cardView = card
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            val margin = dp(24)
            leftMargin = margin
            rightMargin = margin
        })
        setOnClickListener { dismiss() }
        visibility = View.VISIBLE
        bringToFront()
        Motion.showNow(card)
    }

    fun dismiss() {
        resetOutsideGesture()
        if (visibility != View.VISIBLE) {
            dismissImmediately()
            return
        }
        setOnClickListener(null)
        isClickable = false
        clearActions()
        cardView = null
        Motion.hideNow(this) { removeAllViews() }
    }

    internal fun dismissImmediately() {
        resetOutsideGesture()
        setOnClickListener(null)
        isClickable = false
        clearActions()
        cardView = null
        Motion.reset(this)
        removeAllViews()
        visibility = View.GONE
    }

    internal fun confirmForTest(): Boolean = confirmAction?.performClick() ?: false
    internal fun cancelForTest(): Boolean = cancelAction?.performClick() ?: false
    internal fun confirmActionForTest(): View? = confirmAction
    internal fun cancelActionForTest(): View? = cancelAction
    internal fun cardForTest(): View? = cardView
    internal fun outsideGestureActiveForTest(): Boolean = outsidePointerId != MotionEvent.INVALID_POINTER_ID

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetOutsideGesture()
                if (visibility == View.VISIBLE && outsideCard(event.x, event.y)) {
                    outsidePointerId = event.getPointerId(0)
                    outsideDownX = event.x
                    outsideDownY = event.y
                    outsideTap = true
                    return true
                }
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> if (outsidePointerId != MotionEvent.INVALID_POINTER_ID) return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (outsidePointerId == MotionEvent.INVALID_POINTER_ID) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(outsidePointerId)
                if (index < 0) {
                    resetOutsideGesture()
                    return true
                }
                val x = event.getX(index)
                val y = event.getY(index)
                if (abs(x - outsideDownX) > touchSlop || abs(y - outsideDownY) > touchSlop || !outsideCard(x, y)) {
                    outsideTap = false
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                outsideTap = false
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(outsidePointerId)
                val dismiss = index >= 0 && outsideTap && outsideCard(event.getX(index), event.getY(index))
                resetOutsideGesture()
                if (dismiss) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                resetOutsideGesture()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == outsidePointerId) {
                    val replacement = (0 until event.pointerCount).firstOrNull { it != event.actionIndex }
                    if (replacement == null) {
                        resetOutsideGesture()
                    } else {
                        outsidePointerId = event.getPointerId(replacement)
                        outsideDownX = event.getX(replacement)
                        outsideDownY = event.getY(replacement)
                        outsideTap = false
                    }
                }
                return true
            }
        }
        return true
    }

    private fun outsideCard(x: Float, y: Float): Boolean {
        val card = cardView ?: return true
        return x < card.left || x >= card.right || y < card.top || y >= card.bottom
    }

    private fun resetOutsideGesture() {
        outsidePointerId = MotionEvent.INVALID_POINTER_ID
        outsideTap = false
    }

    private fun clearActions() {
        confirmAction?.setOnClickListener(null)
        cancelAction?.setOnClickListener(null)
        confirmAction?.isClickable = false
        cancelAction?.isClickable = false
        confirmAction = null
        cancelAction = null
    }

    private fun action(label: String, palette: ImePalette, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(palette.keyLabel)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        minimumHeight = dp(48)
        minimumWidth = dp(64)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        Motion.applyTapFeedback(this, palette.keyLabel)
        setOnClickListener { onClick() }
    }
}
