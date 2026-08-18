// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.ime

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.aegis.ime.ime.theme.ImeShapes

interface KeyHapticsAware {
    var hapticEnabled: Boolean
}

internal interface ImeKeySurface {
    val faceColor: Int
}

class ImeKeyFeedback(
    private val view: View,
    faceColor: Int,
    stateColor: Int,
    faceInsetDp: Float = 3f,
    radiusDp: Float = ImeShapes.keyRadiusDp,
) {
    private val density = view.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val faceInset = faceInsetDp * density
    private val radius = radiusDp * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var face = faceColor
    private var stateColorValue = stateColor
    private var tracking = false
    private val press = Motion.PressFeedback(view) { view.invalidate() }
    private val surface = object : Drawable(), ImeKeySurface {
        override val faceColor: Int
            get() = face

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            rect.inset(faceInset, faceInset)
            if (rect.width() <= 0f || rect.height() <= 0f) return
            paint.color = face
            canvas.drawRoundRect(rect, radius, radius, paint)
            val level = press.level
            if (level > 0f) {
                paint.color = Motion.stateLayerColor(stateColorValue, level)
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    init {
        view.background = surface
    }

    fun bind(hapticsEnabled: () -> Boolean) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(hapticsEnabled())
                MotionEvent.ACTION_MOVE -> move(inside(event.x, event.y))
                MotionEvent.ACTION_UP -> releaseBeforeDefaultClick()
                MotionEvent.ACTION_CANCEL -> cancel()
            }
            false
        }
    }

    fun update(faceColor: Int, stateColor: Int) {
        face = faceColor
        stateColorValue = stateColor
        surface.invalidateSelf()
        view.invalidate()
    }

    fun begin(hapticsEnabled: Boolean) {
        tracking = true
        view.isPressed = true
        press.press()
        if (hapticsEnabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun move(inside: Boolean) {
        if (inside == tracking) return
        tracking = inside
        view.isPressed = inside
        if (inside) press.press() else press.release()
    }

    fun release() {
        tracking = false
        view.isPressed = false
        press.release()
    }

    private fun releaseBeforeDefaultClick() {
        tracking = false
        press.release()
    }

    fun cancel() {
        tracking = false
        view.isPressed = false
        press.cancel()
    }

    fun reset() {
        tracking = false
        view.isPressed = false
        press.reset()
    }

    fun inside(x: Float, y: Float): Boolean =
        x >= -touchSlop && y >= -touchSlop && x < view.width + touchSlop && y < view.height + touchSlop

    internal fun levelForTest(): Float = press.level
    internal fun drawableForTest(): Drawable = surface
}

class ImeBackspaceTouch(
    private val view: View,
    private val feedback: ImeKeyFeedback,
    density: Float,
    private val hapticsEnabled: () -> Boolean,
    onRepeat: () -> Unit,
    onSwipe: (Boolean) -> Unit,
) {
    private val gesture = BackspaceGesture(density).apply {
        this.onRepeat = onRepeat
        this.onSwipe = onSwipe
    }
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var pointerInside = false

    init {
        view.setOnTouchListener { _, event -> onTouch(event) }
    }

    fun cancel() {
        gesture.cancel()
        feedback.reset()
        pointerId = MotionEvent.INVALID_POINTER_ID
        pointerInside = false
        view.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.parent?.requestDisallowInterceptTouchEvent(true)
                begin(event.getPointerId(0), event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val tracked = event.findPointerIndex(pointerId)
                if (tracked >= 0 && settle(event.getX(tracked), event.getY(tracked), pointerInside)) view.performClick()
                val index = event.actionIndex
                begin(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    pointerInside = feedback.inside(x, y)
                    feedback.move(pointerInside)
                    gesture.move(x, y, pointerInside)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) == pointerId && settle(event.getX(index), event.getY(index), pointerInside)) {
                    view.performClick()
                }
            }
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(pointerId)
                val x = if (index >= 0) event.getX(index) else event.x
                val y = if (index >= 0) event.getY(index) else event.y
                val inside = feedback.inside(x, y)
                if (settle(x, y, inside)) view.performClick()
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun begin(id: Int, x: Float, y: Float) {
        pointerId = id
        pointerInside = true
        feedback.begin(hapticsEnabled())
        gesture.begin(x, y)
    }

    private fun settle(x: Float, y: Float, inside: Boolean): Boolean {
        pointerId = MotionEvent.INVALID_POINTER_ID
        pointerInside = false
        gesture.move(x, y, inside)
        val tap = gesture.finish(y)
        feedback.release()
        return tap && inside
    }
}
