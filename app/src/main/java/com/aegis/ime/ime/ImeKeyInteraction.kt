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

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.graphics.Outline
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ScrollView
import com.aegis.ime.ime.theme.ImeShapes

interface KeyHapticsAware {
    var hapticEnabled: Boolean
}

internal fun panelActionSlot(slot: FrameLayout, button: View): FrameLayout =
    slot.apply {
        addView(
            button,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
    }

internal class ImePanelTableGrid(context: Context, density: Float) : GridLayout(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }

    var separatorColor: Int
        get() = linePaint.color
        set(value) {
            linePaint.color = value
            invalidate()
        }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != View.VISIBLE) continue
            val right = child.right.toFloat()
            val bottom = child.bottom.toFloat()
            if (child.right < width) canvas.drawLine(right, child.top.toFloat(), right, bottom, linePaint)
            if (child.bottom < height) canvas.drawLine(child.left.toFloat(), bottom, right, bottom, linePaint)
        }
    }
}

internal class ImePanelTableViewport(context: Context, private val density: Float) : ScrollView(context) {
    private val radius = ImeShapes.cardRadiusDp * density
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val outlineRect = RectF()

    init {
        isVerticalScrollBarEnabled = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

    fun applyOutlineColor(color: Int) {
        outlinePaint.color = color
        invalidate()
    }

    internal fun outlineColorForTest(): Int = outlinePaint.color

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val half = density / 2f
        outlineRect.set(
            scrollX + half,
            scrollY + half,
            scrollX + width - half,
            scrollY + height - half,
        )
        canvas.drawRoundRect(outlineRect, radius, radius, outlinePaint)
    }
}

internal data class ImePanelGridMetrics(
    val columns: Int,
    val cellWidthPx: Int,
) {
    companion object {
        fun fit(availableWidthPx: Int, minimumCellWidthPx: Int, maximumColumns: Int): ImePanelGridMetrics {
            val available = availableWidthPx.coerceAtLeast(1)
            val minimum = minimumCellWidthPx.coerceAtLeast(1)
            val maximum = maximumColumns.coerceAtLeast(1)
            val columns = (available / minimum).coerceIn(1, maximum)
            return ImePanelGridMetrics(columns, available / columns)
        }
    }
}

internal data class ImePanelSurfaceMetrics(
    val faceHeightPx: Int,
    val faceInsetPx: Int,
    val gridCellHeightPx: Int,
    val gridSidePaddingPx: Int,
    val gridTopPaddingPx: Int,
    val minimumGridCellWidthPx: Int,
) {
    fun actionWidthPx(panelWidthPx: Int, columns: Int): Int = panelWidthPx / (columns + 1)

    fun fitGrid(panelWidthPx: Int, maximumColumns: Int): ImePanelGridMetrics =
        ImePanelGridMetrics.fit(
            panelWidthPx - actionWidthPx(panelWidthPx, maximumColumns),
            minimumGridCellWidthPx,
            maximumColumns,
        )

    fun outerWidth(cellWidthPx: Int, span: Int = 1): Int =
        cellWidthPx * span.coerceAtLeast(1)

    companion object {
        const val ACTION_WIDTH_DP = 60
        const val FACE_HEIGHT_DP = 45
        const val FACE_INSET_DP = 3
        const val GRID_SIDE_PADDING_DP = 4
        const val TOP_FACE_OFFSET_DP = 8
        const val MINIMUM_GRID_CELL_WIDTH_DP = 48
        const val ACTION_ICON_TO_TEXT = 1.05f

        fun actionIconPx(textSp: Float, density: Float): Float = textSp * density * ACTION_ICON_TO_TEXT

        fun resolve(density: Float, scaledDensity: Float = density): ImePanelSurfaceMetrics {
            fun dp(value: Int): Int = (value * density).toInt()
            val faceInsetPx = dp(FACE_INSET_DP)
            val glyphLinePx = (com.aegis.ime.ime.theme.ImeType.display * scaledDensity * 1.25f).toInt()
            val faceHeightPx = maxOf(dp(FACE_HEIGHT_DP), glyphLinePx)
            val topFaceOffsetPx = dp(TOP_FACE_OFFSET_DP)
            return ImePanelSurfaceMetrics(
                faceHeightPx = faceHeightPx,
                faceInsetPx = faceInsetPx,
                gridCellHeightPx = faceHeightPx + faceInsetPx * 2,
                gridSidePaddingPx = dp(GRID_SIDE_PADDING_DP),
                gridTopPaddingPx = (topFaceOffsetPx - faceInsetPx).coerceAtLeast(0),
                minimumGridCellWidthPx = dp(MINIMUM_GRID_CELL_WIDTH_DP),
            )
        }
    }
}

internal interface ImeKeySurface {
    val faceColor: Int
    val cornerRadiusPx: Float
    val faceCornerRadiusPx: Float
    fun faceBoundsForTest(width: Int, height: Int): RectF
}

class ImeKeyFeedback(
    private val view: View,
    faceColor: Int,
    stateColor: Int,
    faceInsetDp: Float = 3f,
    radiusDp: Float = ImeShapes.keyRadiusDp,
    faceInsetPxOverride: Float? = null,
    faceRadiusDp: Float = radiusDp,
) {
    private val density = view.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val faceInset = faceInsetPxOverride ?: faceInsetDp * density
    private val radius = radiusDp * density
    private val faceRadius = faceRadiusDp * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var face = faceColor
    private var stateColorValue = stateColor
    private var tracking = false
    private val press = Motion.PressFeedback(view) { view.invalidate() }
    private fun setFaceBounds(out: RectF, left: Int, top: Int, right: Int, bottom: Int) {
        out.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        out.inset(faceInset, faceInset)
    }
    private val surface = object : Drawable(), ImeKeySurface {
        override val faceColor: Int
            get() = face
        override val cornerRadiusPx: Float
            get() = radius
        override val faceCornerRadiusPx: Float
            get() = faceRadius

        override fun faceBoundsForTest(width: Int, height: Int): RectF = RectF().also {
            setFaceBounds(it, 0, 0, width, height)
        }

        override fun draw(canvas: Canvas) {
            setFaceBounds(rect, bounds.left, bounds.top, bounds.right, bounds.bottom)
            if (rect.width() <= 0f || rect.height() <= 0f) return
            paint.color = face
            canvas.drawRoundRect(rect, faceRadius, faceRadius, paint)
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

internal interface BackspaceBubbleSource {
    fun backspaceBubbleDirectionUp(): Boolean?
    fun backspaceBubbleArmed(): Boolean
    fun backspaceBubbleAnchor(): View
    fun bindBackspaceBubbleObserver(observer: Runnable)
}

class ImeBackspaceTouch(
    private val view: View,
    private val feedback: ImeKeyFeedback,
    density: Float,
    private val hapticsEnabled: () -> Boolean,
    onRepeat: () -> Unit,
    onSwipe: (Boolean) -> Unit,
    private val onBubbleChanged: () -> Unit = {},
) {
    private val gesture = BackspaceGesture(density).apply {
        this.onRepeat = onRepeat
        this.onSwipe = onSwipe
    }
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var pointerInside = false
    private var lastBubble: Pair<Boolean?, Boolean> = null to false

    var canSwipe: (Boolean) -> Boolean
        get() = gesture.canSwipe
        set(value) { gesture.canSwipe = value }

    var repeats: Boolean
        get() = gesture.repeats
        set(value) { gesture.repeats = value }

    fun bubbleDirectionUp(): Boolean? = gesture.swipeDirectionUp

    fun bubbleArmed(): Boolean = gesture.swipeArmed

    private fun notifyBubble() {
        val next = gesture.swipeDirectionUp to gesture.swipeArmed
        if (next != lastBubble) {
            lastBubble = next
            onBubbleChanged()
        }
    }

    init {
        view.setOnTouchListener { _, event -> onTouch(event) }
    }

    fun cancel() {
        gesture.cancel()
        notifyBubble()
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
                    notifyBubble()
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
        notifyBubble()
    }

    private fun settle(x: Float, y: Float, inside: Boolean): Boolean {
        pointerId = MotionEvent.INVALID_POINTER_ID
        pointerInside = false
        gesture.move(x, y, inside)
        val tap = gesture.finish()
        notifyBubble()
        feedback.release()
        return tap && inside
    }
}
