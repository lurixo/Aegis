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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import kotlin.math.abs

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

internal class ImePanelFaceGrid(context: Context, density: Float) : GridLayout(context) {
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ImeShapes.gridLinePx(density) }

    var ruleColor: Int
        get() = rulePaint.color
        set(value) {
            rulePaint.color = value
            invalidate()
        }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val half = rulePaint.strokeWidth / 2f
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != View.VISIBLE || child.background !is ImeKeySurface) continue
            val right = child.right - half
            val bottom = child.bottom - half
            if (child.right < width) {
                canvas.drawLine(right, child.top.toFloat(), right, child.bottom.toFloat(), rulePaint)
            }
            canvas.drawLine(child.left.toFloat(), bottom, child.right.toFloat(), bottom, rulePaint)
        }
    }
}

internal class ImePanelFrame(context: Context, density: Float) : FrameLayout(context) {
    private val radius = ImeShapes.cardRadiusDp * density
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ImeShapes.gridLinePx(density) }
    private val outlineRect = RectF()
    private val clip = Path()

    var outlineColor: Int
        get() = outlinePaint.color
        set(value) {
            outlinePaint.color = value
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)
        val half = outlinePaint.strokeWidth / 2f
        outlineRect.set(half, half, width - half, height - half)
        canvas.drawRoundRect(outlineRect, radius, radius, outlinePaint)
    }
}

internal class ImePanelViewport(context: Context) : ScrollView(context) {
    init {
        isVerticalScrollBarEnabled = false
    }
}

internal class ImePanelPager(context: Context, private val current: View, private val peek: View) : FrameLayout(context) {
    private val flingVelocity = PanelPageGesture.FLING_VELOCITY_DP * resources.displayMetrics.density
    private val gesture = PanelPageGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), flingVelocity)
    private val velocity = FlingScroller(context)
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var dragging = false
    private var offset = 0f
    private var peekPage = -1
    private var settle: ValueAnimator? = null

    var pageCount: () -> Int = { 1 }
    var selectedPage: () -> Int = { 0 }
    var canPage: () -> Boolean = { true }
    var onBindPeek: (Int) -> Unit = {}
    var onOffset: (Float) -> Unit = {}
    var onPageSelected: (Int) -> Unit = {}

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        addView(current, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(peek, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        peek.visibility = View.GONE
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settle?.end()
                begin(ev)
            }
            MotionEvent.ACTION_MOVE -> if (drag(ev)) return true
            MotionEvent.ACTION_POINTER_DOWN -> if (dragging) return true
            MotionEvent.ACTION_POINTER_UP -> if (pointerUp(ev)) return true
            MotionEvent.ACTION_UP -> if (dragging) {
                release(ev)
                return true
            } else {
                gesture.cancel()
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) {
                gesture.cancel()
                dragging = false
                settleTo(0, fling = false)
                return true
            } else {
                gesture.cancel()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    fun abort() {
        settle?.let {
            settle = null
            it.cancel()
        }
        gesture.cancel()
        dragging = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        rest()
    }

    private fun begin(ev: MotionEvent) {
        dragging = false
        peekPage = -1
        pointerId = ev.getPointerId(0)
        val page = selectedPage()
        val count = pageCount()
        if (count > 1 && canPage()) {
            gesture.begin(ev.x, ev.y, width.toFloat(), hasPrevious = page > 0, hasNext = page < count - 1)
        } else {
            gesture.cancel()
        }
        velocity.forceFinish()
        velocity.addSample(ev.eventTime, ev.x)
    }

    private fun drag(ev: MotionEvent): Boolean {
        val index = ev.findPointerIndex(pointerId)
        if (index < 0) return dragging
        val x = ev.getX(index)
        velocity.addSample(ev.eventTime, x)
        if (!dragging) {
            if (!canPage()) {
                gesture.cancel()
                return false
            }
            if (!gesture.move(x, ev.getY(index))) return false
            dragging = true
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            parent?.requestDisallowInterceptTouchEvent(true)
        } else {
            gesture.move(x, ev.getY(index))
        }
        show(gesture.offset)
        return true
    }

    private fun pointerUp(ev: MotionEvent): Boolean {
        if (ev.getPointerId(ev.actionIndex) != pointerId) return dragging
        if (!dragging) {
            gesture.cancel()
            pointerId = MotionEvent.INVALID_POINTER_ID
            return false
        }
        val next = if (ev.actionIndex == 0) 1 else 0
        pointerId = ev.getPointerId(next)
        gesture.rebase(ev.getX(next))
        velocity.forceFinish()
        velocity.addSample(ev.eventTime, ev.getX(next))
        return true
    }

    private fun release(ev: MotionEvent) {
        val index = ev.findPointerIndex(pointerId)
        if (index >= 0) {
            velocity.addSample(ev.eventTime, ev.getX(index))
            gesture.move(ev.getX(index), ev.getY(index))
            show(gesture.offset)
        }
        val speed = velocity.velocity()
        val step = gesture.release(speed)
        dragging = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        settleTo(step, fling = abs(speed) >= flingVelocity)
    }

    private fun settleTo(step: Int, fling: Boolean) {
        val target = -step * width.toFloat()
        if (!isAttachedToWindow || !Motion.enabled() || offset == target) {
            land(step)
            return
        }
        settle = ValueAnimator.ofFloat(offset, target).apply {
            duration = Motion.PAGE_SETTLE
            interpolator = if (fling) Motion.STANDARD_DECEL else Motion.STANDARD
            addUpdateListener { show(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (settle !== animation) return
                    land(step)
                }
            })
            start()
        }
    }

    private fun land(step: Int) {
        settle = null
        val page = selectedPage() + step
        if (step != 0 && page in 0 until pageCount()) onPageSelected(page)
        rest()
    }

    private fun rest() {
        offset = 0f
        current.translationX = 0f
        peek.translationX = 0f
        peek.visibility = View.GONE
        onOffset(0f)
    }

    private fun show(value: Float) {
        offset = value
        current.translationX = value
        val side = if (value < 0f) 1 else if (value > 0f) -1 else 0
        val page = selectedPage() + side
        if (side != 0 && page in 0 until pageCount()) {
            if (peekPage != page) {
                peekPage = page
                onBindPeek(page)
            }
            peek.translationX = value + side * width
            peek.visibility = View.VISIBLE
        } else if (peek.visibility == View.VISIBLE) {
            peek.visibility = View.INVISIBLE
        }
        onOffset(if (width > 0) -value / width else 0f)
    }

    override fun onDetachedFromWindow() {
        abort()
        super.onDetachedFromWindow()
    }

    internal fun offsetForTest(): Float = offset
    internal fun draggingForTest(): Boolean = dragging
    internal fun settlingForTest(): Boolean = settle != null
}

internal class ImePanelCategoryRail(context: Context, density: Float) : LinearLayout(context) {
    private val underlineHeight = 4f * density
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val underline = RectF()

    init {
        orientation = HORIZONTAL
    }

    var underlineColor: Int
        get() = underlinePaint.color
        set(value) {
            underlinePaint.color = value
            invalidate()
        }

    var selectedIndex = 0
        set(value) {
            field = value
            invalidate()
        }

    var pageOffset = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun dispatchDraw(canvas: Canvas) {
        val child = getChildAt(selectedIndex)
        if (child != null && child.visibility == View.VISIBLE) canvas.drawRect(underlineUnder(child), underlinePaint)
        super.dispatchDraw(canvas)
    }

    internal fun underlineBoundsForTest(): RectF? = getChildAt(selectedIndex)?.let { RectF(underlineUnder(it)) }

    private fun underlineUnder(child: View): RectF {
        var left = child.left.toFloat()
        var right = child.right.toFloat()
        val toward = when {
            pageOffset > 0f -> getChildAt(selectedIndex + 1)
            pageOffset < 0f -> getChildAt(selectedIndex - 1)
            else -> null
        }
        if (toward != null && toward.visibility == View.VISIBLE) {
            val progress = abs(pageOffset).coerceAtMost(1f)
            left += (toward.left - left) * progress
            right += (toward.right - right) * progress
        }
        underline.set(left, height - underlineHeight, right, height.toFloat())
        return underline
    }
}

internal class ImePanelCategoryBar(context: Context, density: Float) : HorizontalScrollView(context) {
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ImeShapes.gridLinePx(density) }

    init {
        isHorizontalScrollBarEnabled = false
    }

    var ruleColor: Int
        get() = rulePaint.color
        set(value) {
            rulePaint.color = value
            invalidate()
        }

    fun reveal(index: Int) {
        val content = getChildAt(0) as? ViewGroup ?: return
        val child = content.getChildAt(index) ?: return
        val span = width - paddingLeft - paddingRight
        if (span <= 0) return
        val target = when {
            child.left < scrollX -> child.left
            child.right > scrollX + span -> child.right - span
            else -> return
        }.coerceIn(0, (content.width - span).coerceAtLeast(0))
        if (target == scrollX) return
        if (isAttachedToWindow && Motion.enabled()) smoothScrollTo(target, 0) else scrollTo(target, 0)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val y = rulePaint.strokeWidth / 2f
        canvas.drawLine(scrollX.toFloat(), y, (scrollX + width).toFloat(), y, rulePaint)
    }
}

internal class ImePanelActionColumn(context: Context, density: Float) : LinearLayout(context) {
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ImeShapes.gridLinePx(density) }

    init {
        orientation = VERTICAL
    }

    fun applyPalette(p: ImePalette) {
        setBackgroundColor(p.functionSurface)
        rulePaint.color = p.gridLine
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val half = rulePaint.strokeWidth / 2f
        canvas.drawLine(half, 0f, half, height.toFloat(), rulePaint)
        for (index in 0 until childCount - 1) {
            val y = getChildAt(index).bottom - half
            canvas.drawLine(0f, y, width.toFloat(), y, rulePaint)
        }
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
    faceInsetDp: Float = DEFAULT_FACE_INSET_DP,
    radiusDp: Float = ImeShapes.keyRadiusDp,
    faceInsetPxOverride: Float? = null,
    faceRadiusDp: Float = radiusDp,
    faceInsetLeftPx: Float? = null,
    faceInsetRightPx: Float? = null,
) {
    private val density = view.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val faceInset = faceInsetPxOverride ?: faceInsetDp * density
    private val faceInsetLeft = faceInsetLeftPx ?: faceInset
    private val faceInsetRight = faceInsetRightPx ?: faceInset
    private val radius = radiusDp * density
    private val faceRadius = faceRadiusDp * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var face = faceColor
    private var stateColorValue = stateColor
    private var tracking = false
    private val press = Motion.PressFeedback(view) { view.invalidate() }
    private fun setFaceBounds(out: RectF, left: Int, top: Int, right: Int, bottom: Int) {
        out.set(left + faceInsetLeft, top + faceInset, right - faceInsetRight, bottom - faceInset)
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
        if (!view.isEnabled) return
        tracking = true
        view.isPressed = true
        press.press()
        view.playImeKeyFeedback(hapticsEnabled)
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

    companion object {
        const val DEFAULT_FACE_INSET_DP = 3f
    }
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
