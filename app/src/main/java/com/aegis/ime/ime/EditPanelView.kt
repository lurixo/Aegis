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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.KeyAction
import kotlin.math.roundToInt

enum class EditAction(val keyAction: KeyAction? = null) {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    START_SELECT,
    UNDO,
    DELETE(KeyAction.BACKSPACE),
    TAB,
    FORWARD_DELETE,
    COPY,
    CUT,
    SELECT_ALL,
    HOME,
    END,
    PASTE,
    BACK,
}

class EditPanelView(context: Context) :
    LinearLayout(context), ResettablePanel, CoversToolbar, KeyHapticsAware, BackspaceBubbleSource {

    var onAction: (EditAction) -> Unit = {}
    var onBackspaceSwipe: (Boolean) -> Unit = {}
    var backspaceSwipeAvailable: (Boolean) -> Boolean
        get() = backspaceTouch.canSwipe
        set(value) { backspaceTouch.canSwipe = value }
    override var hapticEnabled = false

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun px(value: Float) = dp(value).roundToInt()
    private var palette = ImePalette.STATIC_LIGHT
    private val actionViews = linkedMapOf<EditAction, View>()
    private val actionFeedback = mutableMapOf<EditAction, ImeKeyFeedback>()
    private val keyViews = linkedMapOf<EditAction, EditKey>()
    private val arrowIcons = mutableMapOf<EditAction, GlyphDrawable>()
    private val tintAnimators = mutableMapOf<TextView, ValueAnimator>()
    private val directions = setOf(EditAction.UP, EditAction.DOWN, EditAction.LEFT, EditAction.RIGHT)
    private var selectionTinted = false
    private var selecting = false
    private var undoAvailable = false
    private var backspaceBubbleObserver: Runnable? = null
    private val backControl: PanelHeaderBackControl
    private val titleBar: LinearLayout
    private val actionBody: ActionLayout
    private val actionScroll: ScrollView
    private val backspaceTouch: ImeBackspaceTouch
    private val selectBtn: EditKey

    init {
        orientation = VERTICAL
        clipChildren = false
        setBackgroundColor(palette.keyboardBg)
        backControl = PanelBackButton.control(context, context.getString(R.string.edit_title), palette.keyLabel) {
            onAction(EditAction.BACK)
        }.apply {
            isFocusable = false
        }
        actionViews[EditAction.BACK] = backControl
        titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(backControl, LayoutParams(LayoutParams.WRAP_CONTENT, (PanelBackButton.HIT_DP * density).toInt()))
        }
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, (56 * density).toInt()))
        actionBody = ActionLayout()
        actionScroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                actionBody.minimumHeight = if (MeasureSpec.getSize(heightMeasureSpec) < px(144f)) px(216f) else 0
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(actionBody, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(actionScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        fun arrow(action: EditAction, description: Int, direction: Glyphs.Arrow) {
            val icon = glyph(9f, 2.25f) { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, direction) }
            arrowIcons[action] = icon
            addKey(action, "", icon).contentDescription = context.getString(description)
        }
        arrow(EditAction.UP, R.string.edit_move_up, Glyphs.Arrow.UP)
        arrow(EditAction.LEFT, R.string.edit_move_left, Glyphs.Arrow.LEFT)
        arrow(EditAction.RIGHT, R.string.edit_move_right, Glyphs.Arrow.RIGHT)
        arrow(EditAction.DOWN, R.string.edit_move_down, Glyphs.Arrow.DOWN)
        selectBtn = addKey(EditAction.START_SELECT, context.getString(R.string.edit_select), glyph(9f) { c, p, x, y, s ->
            p.strokeCap = Paint.Cap.BUTT
            p.pathEffect = DashPathEffect(floatArrayOf(s / 3f, s * 2.5f / 9f), 0f)
            c.drawRoundRect(x - s, y - s * 2f / 3f, x + s, y + s * 2f / 3f, s * 2f / 9f, s * 2f / 9f, p)
            p.pathEffect = null
            p.strokeCap = Paint.Cap.ROUND
        })
        for ((action, label, start) in listOf(
            Triple(EditAction.HOME, R.string.edit_text_start, true),
            Triple(EditAction.END, R.string.edit_text_end, false),
        )) addKey(action, context.getString(label), glyph(8f) { c, p, x, y, s ->
            c.save()
            c.rotate(90f, x, y)
            Glyphs.drawArrowToEdge(c, p, x, y, s, start)
            c.restore()
        })
        addKey(EditAction.TAB, context.getString(R.string.edit_tab), glyph(9f) { c, p, x, y, s ->
            c.drawLine(x - s, y, x + s * 4f / 9f, y, p)
            c.drawLine(x - s / 9f, y - s * 5f / 9f, x + s * 4f / 9f, y, p)
            c.drawLine(x - s / 9f, y + s * 5f / 9f, x + s * 4f / 9f, y, p)
            c.drawLine(x + s, y - s * 2f / 3f, x + s, y + s * 2f / 3f, p)
        })
        addKey(EditAction.FORWARD_DELETE, context.getString(R.string.edit_forward_delete), glyph(10f) { c, p, x, y, s ->
            c.save()
            c.scale(-1f, 1f, x, y)
            Glyphs.drawBackspace(c, p, x, y, s)
            c.restore()
        })
        addKey(EditAction.UNDO, context.getString(R.string.edit_undo), glyph(10f) { c, p, x, y, s ->
            Glyphs.drawUndo(c, p, x, y, s)
        })
        val delete = addKey(EditAction.DELETE, context.getString(R.string.edit_delete), glyph(10f) { c, p, x, y, s ->
            Glyphs.drawBackspace(c, p, x, y, s)
        })
        backspaceTouch = ImeBackspaceTouch(
            delete, requireNotNull(actionFeedback[EditAction.DELETE]), density,
            { hapticEnabled }, { onAction(EditAction.DELETE) }, { onBackspaceSwipe(it) },
            { backspaceBubbleObserver?.run() },
        )
        addKey(EditAction.SELECT_ALL, context.getString(R.string.edit_select_all), glyph(10.8f) { c, p, x, y, s ->
            Glyphs.drawSelectAll(c, p, x, y, s)
        })
        addKey(EditAction.COPY, context.getString(R.string.edit_copy), glyph(9f) { c, p, x, y, s ->
            Glyphs.drawCopy(c, p, x, y, s)
        })
        addKey(EditAction.CUT, context.getString(R.string.edit_cut), glyph(11f) { c, p, x, y, s ->
            Glyphs.drawCut(c, p, x, y, s)
        })
        addKey(EditAction.PASTE, context.getString(R.string.edit_paste), glyph(10.5f) { c, p, x, y, s ->
            Glyphs.drawClipboard(c, p, x, y, s)
        })
        renderSelectingLabel()
        setUndoAvailable(false)
        applyPalette(palette)
    }

    private fun glyph(sizeDp: Float, strokeDp: Float = 2f, render: (Canvas, Paint, Float, Float, Float) -> Unit) =
        GlyphDrawable(px(24f), sizeDp / 24f, dp(strokeDp), 0f, render)

    private fun addKey(action: EditAction, label: String, icon: GlyphDrawable): EditKey {
        val key = EditKey(label, icon).apply {
            isClickable = true
            isFocusable = false
            setOnClickListener { if (isEnabled) onAction(action) }
        }
        keyViews[action] = key
        actionViews[action] = key
        actionFeedback[action] = ImeKeyFeedback(key, palette.keySurface, palette.keyLabel, faceInsetDp = 0f, radiusDp = 10f).also {
            if (action != EditAction.DELETE) it.bind { hapticEnabled }
        }
        actionBody.addView(key)
        return key
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        backControl.applyTint(p.keyLabel)
        for (action in keyViews.keys) updateActionColors(action)
        applySelectionTint(selectionTinted, animate = false)
        actionBody.invalidate()
    }

    private fun updateActionColors(action: EditAction) {
        val key = keyViews.getValue(action)
        val active = action == EditAction.START_SELECT && selecting
        val tint = when {
            active -> palette.accentLabel
            action in directions && selecting -> palette.accentBottom
            action in setOf(EditAction.COPY, EditAction.CUT) && !selectionTinted -> palette.disabled
            action == EditAction.UNDO && !undoAvailable -> palette.disabled
            else -> palette.keyLabel
        }
        key.setTextColor(tint)
        key.icon.applyTint(tint)
        actionFeedback.getValue(action).update(if (active) palette.accentBottom else palette.keySurface, tint)
        key.invalidate()
    }

    fun setHasSelection(has: Boolean) = applySelectionTint(has, animate = true)

    fun setUndoAvailable(available: Boolean) {
        undoAvailable = available
        val key = keyViews.getValue(EditAction.UNDO)
        if (key.isEnabled != available || key.isClickable != available) {
            actionFeedback.getValue(EditAction.UNDO).reset()
        }
        key.isEnabled = available
        key.isClickable = available
        updateActionColors(EditAction.UNDO)
    }

    private fun applySelectionTint(has: Boolean, animate: Boolean) {
        selectionTinted = has
        val tint = if (has) palette.keyLabel else palette.disabled
        for (action in listOf(EditAction.COPY, EditAction.CUT)) {
            val key = keyViews.getValue(action)
            val changed = key.isEnabled != has || key.isClickable != has
            actionFeedback.getValue(action).apply {
                if (changed) reset()
                update(palette.keySurface, tint)
            }
            key.isEnabled = has
            key.isClickable = has
            tintAnimators.remove(key)?.cancel()
            if (animate) {
                Motion.crossfadeColor(key, key.currentTextColor, tint) {
                    key.setTextColor(it)
                    key.icon.applyTint(it)
                }?.let { tintAnimators[key] = it }
            } else {
                key.setTextColor(tint)
                key.icon.applyTint(tint)
            }
            key.invalidate()
        }
    }

    fun setSelecting(selecting: Boolean) {
        this.selecting = selecting
        renderSelectingLabel()
        updateActionColors(EditAction.START_SELECT)
        for (action in directions) updateActionColors(action)
    }

    private fun renderSelectingLabel() {
        selectBtn.text = context.getString(R.string.edit_select)
        selectBtn.contentDescription = context.getString(if (selecting) R.string.edit_end_select else R.string.edit_start_select)
        selectBtn.isSelected = selecting
    }

    override fun resetToDefault() {
        resetActionFeedback()
        setSelecting(false)
        actionScroll.scrollTo(0, 0)
        actionScroll.fling(0)
    }

    override fun onDetachedFromWindow() {
        resetActionFeedback()
        super.onDetachedFromWindow()
    }

    private fun resetActionFeedback() {
        backspaceTouch.cancel()
        for ((action, feedback) in actionFeedback) if (action != EditAction.DELETE) feedback.reset()
    }

    override fun bindBackspaceBubbleObserver(observer: Runnable) { backspaceBubbleObserver = observer }
    override fun backspaceBubbleDirectionUp(): Boolean? = backspaceTouch.bubbleDirectionUp()
    override fun backspaceBubbleArmed(): Boolean = backspaceTouch.bubbleArmed()
    override fun backspaceBubbleAnchor(): View = actionViews.getValue(EditAction.DELETE)
    internal fun selectingLabelForTest(): CharSequence = selectBtn.text
    internal fun selectionTintAnimatingForTest(): Boolean = tintAnimators.values.any { it.isRunning }
    internal fun actionViewForTest(action: EditAction): View? = actionViews[action]
    internal fun actionFeedbackLevelForTest(action: EditAction): Float? = actionFeedback[action]?.levelForTest()
    internal fun titleBarForTest(): View = titleBar
    internal fun actionViewportForTest(): View = actionScroll
    internal fun actionContentCanScrollForTest(): Boolean = actionScroll.canScrollVertically(-1) || actionScroll.canScrollVertically(1)
    internal fun scrollActionIntoViewForTest(action: EditAction) {
        val view = actionViews.getValue(action)
        val bounds = Rect(0, 0, view.width, view.height)
        actionBody.offsetDescendantRectToMyCoords(view, bounds)
        actionScroll.requestChildRectangleOnScreen(actionBody, bounds, true)
    }
    internal fun arrowLastDrawCenterForTest(action: EditAction): Pair<Float, Float>? = arrowIcons[action]?.lastDrawCenterForTest()

    private inner class EditKey(label: String, val icon: GlyphDrawable) : TextView(context) {
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var inline = false
        private var nominalSp = 14f
        private var nominalIconDp = 24f

        init {
            text = label
            maxLines = 1
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, nominalSp)
            if (label.isEmpty()) {
                foreground = icon
                foregroundGravity = Gravity.CENTER
            }
        }

        fun configure(horizontal: Boolean, compactSelect: Boolean) {
            nominalSp = if (compactSelect) 12f else 14f
            nominalIconDp = if (compactSelect) 18f else 24f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, nominalSp)
            if (text.isNotEmpty()) {
                val box = px(nominalIconDp)
                if (icon.bounds.width() != box || inline != horizontal) {
                    icon.setBounds(0, 0, box, box)
                    setCompoundDrawables(if (horizontal) icon else null, if (horizontal) null else icon, null, null)
                }
                compoundDrawablePadding = if (horizontal) px(6f) else px(4f)
            }
            inline = horizontal
            setPadding(px(2f), 0, px(2f), if (text.isNotEmpty() && !horizontal) px(2f) else 0)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val nominal = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, nominalSp, resources.displayMetrics)
            val labelWidth = if (text.isEmpty()) 0f else paint.measureText(text.toString()) * nominal / paint.textSize
            val available = (width - compoundPaddingLeft - compoundPaddingRight - px(1f)).coerceAtLeast(1)
            val floor = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, if (labelWidth > available) maxOf(floor, nominal * available / labelWidth) else nominal)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (text.isNotEmpty() && !inline) {
                val iconSpace = (measuredHeight - requireNotNull(layout).height).coerceAtLeast(0)
                val box = minOf(px(nominalIconDp), iconSpace)
                val bottomPadding = if (iconSpace >= box + px(6f)) px(2f) else 0
                val gap = minOf(px(4f), (iconSpace - box - bottomPadding).coerceAtLeast(0))
                if (icon.bounds.height() != box || compoundDrawablePadding != gap || paddingBottom != bottomPadding) {
                    icon.setBounds(0, 0, box, box)
                    setCompoundDrawables(null, icon, null, null)
                    compoundDrawablePadding = gap
                    setPadding(px(2f), 0, px(2f), bottomPadding)
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            icon.verticalOffsetPx = if (text.isNotEmpty() && !inline) {
                ((height - compoundPaddingTop - compoundPaddingBottom - (layout?.height ?: 0)) / 2f).coerceAtLeast(0f)
            } else 0f
            super.onDraw(canvas)
        }

        override fun draw(canvas: Canvas) {
            if (isEnabled) {
                shadowPaint.color = palette.shadow
                canvas.drawRoundRect(0f, dp(1f), width.toFloat(), height + dp(1f), dp(10f), dp(10f), shadowPaint)
            }
            super.draw(canvas)
        }
    }

    private inner class ActionLayout : ViewGroup(context) {
        private val targets = linkedMapOf<EditAction, Rect>()
        private val tray = RectF()
        private val trayPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            setWillNotDraw(false)
            clipChildren = false
            clipToPadding = false
        }

        private fun target(action: EditAction, x: Float, y: Float, w: Float, h: Float) {
            targets[action] = Rect(px(x), px(y), px(x + w), px(y + h))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                maxOf(minimumHeight, px(144f))
            } else {
                maxOf(minimumHeight, MeasureSpec.getSize(heightMeasureSpec))
            }
            val w = width / density
            val h = height / density
            val landscape = w >= 520f && h + titleBar.layoutParams.height / density < 260f
            targets.clear()
            if (landscape) layoutLandscape(w, h) else layoutPortrait(w, h)
            for ((action, key) in keyViews) {
                val r = targets.getValue(action)
                val short = r.height() < px(52f)
                key.configure(
                    landscape && short && r.width() >= px(68f) && action !in directions && action != EditAction.START_SELECT,
                    short && action == EditAction.START_SELECT,
                )
                key.measure(MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(width, height)
        }

        private fun layoutPortrait(w: Float, h: Float) {
            val t = ((w - 320f) / 91f).coerceIn(0f, 1f)
            val edge = 6f + 2f * t
            val blockGap = 8f + 4f * t
            val left = 170f + 31f * t
            val compact = h < 232f
            val compactSpacing = ((h - 144f) / 18f).coerceIn(0f, 1f)
            val gap = if (compact) 4f * compactSpacing else 6f
            val top = if (compact) 4f * compactSpacing else 6f
            val bottom = if (compact) 2f * compactSpacing else 4f
            val row = (h - top - bottom - 3f * gap) / 4f
            val rightX = edge + left + blockGap
            val right = w - edge - rightX
            val columnGap = 6f
            val column = (right - columnGap) / 2f
            val trayHeight = row * 3f + gap * 2f
            val inner = 5f + t
            val navGap = if (compact) gap else inner
            val padY = minOf(inner, ((trayHeight - 108f - 2f * navGap) / 2f).coerceAtLeast(0f))
            val navW = (left - 2f * inner - 2f * navGap) / 3f
            val navH = (trayHeight - 2f * padY - 2f * navGap) / 3f
            tray.set(dp(edge), dp(top), dp(edge + left), dp(top + trayHeight))
            target(EditAction.UP, edge + inner + navW + navGap, top + padY, navW, navH)
            target(EditAction.LEFT, edge + inner, top + padY + navH + navGap, navW, navH)
            target(EditAction.START_SELECT, edge + inner + navW + navGap, top + padY + navH + navGap, navW, navH)
            target(EditAction.RIGHT, edge + inner + 2f * (navW + navGap), top + padY + navH + navGap, navW, navH)
            target(EditAction.DOWN, edge + inner + navW + navGap, top + padY + 2f * (navH + navGap), navW, navH)
            val edgeGap = 6f + t
            val edgeW = (left - edgeGap) / 2f
            val lastY = top + 3f * (row + gap)
            target(EditAction.HOME, edge, lastY, edgeW, row)
            target(EditAction.END, edge + edgeW + edgeGap, lastY, edgeW, row)
            target(EditAction.TAB, rightX, top, column, row)
            target(EditAction.DELETE, rightX + column + columnGap, top, column, row)
            target(EditAction.UNDO, rightX, top + row + gap, column, row)
            target(EditAction.FORWARD_DELETE, rightX + column + columnGap, top + row + gap, column, row)
            target(EditAction.SELECT_ALL, rightX, top + 2f * (row + gap), column, row)
            target(EditAction.COPY, rightX + column + columnGap, top + 2f * (row + gap), column, row)
            target(EditAction.CUT, rightX, lastY, column, row)
            target(EditAction.PASTE, rightX + column + columnGap, lastY, column, row)
        }

        private fun layoutLandscape(w: Float, h: Float) {
            val blockGap = 12f
            val top = 4f
            val contentH = h - top - 2f
            val gap = minOf(6f, ((contentH - 144f) / 3f).coerceAtLeast(0f))
            val row = (contentH - 3f * gap) / 4f
            val left = minOf(236f, (w - 16f) * 0.4f)
            val column = minOf(128f, (w - 16f - left - blockGap - gap) / 2f)
            val total = left + blockGap + 2f * column + gap
            val edge = (w - total) / 2f
            val navW = (left - 8f - 2f * gap) / 3f
            val navH = (contentH - 8f - 2f * gap) / 3f
            tray.set(dp(edge), dp(top), dp(edge + left), dp(top + contentH))
            fun nav(action: EditAction, columnIndex: Int, rowIndex: Int) =
                target(action, edge + 4f + columnIndex * (navW + gap), top + 4f + rowIndex * (navH + gap), navW, navH)
            nav(EditAction.UP, 1, 0)
            nav(EditAction.LEFT, 0, 1)
            nav(EditAction.START_SELECT, 1, 1)
            nav(EditAction.RIGHT, 2, 1)
            nav(EditAction.DOWN, 1, 2)
            nav(EditAction.HOME, 0, 2)
            nav(EditAction.END, 2, 2)
            val rightX = edge + left + blockGap
            for ((index, pair) in listOf(
                EditAction.TAB to EditAction.DELETE,
                EditAction.UNDO to EditAction.FORWARD_DELETE,
                EditAction.SELECT_ALL to EditAction.COPY,
                EditAction.CUT to EditAction.PASTE,
            ).withIndex()) {
                target(pair.first, rightX, top + index * (row + gap), column, row)
                target(pair.second, rightX + column + gap, top + index * (row + gap), column, row)
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for ((action, key) in keyViews) targets.getValue(action).let { key.layout(it.left, it.top, it.right, it.bottom) }
        }

        override fun onDraw(canvas: Canvas) {
            trayPaint.color = ColorUtils.blendARGB(palette.keyboardBg, palette.keyLabel, 0.055f)
            canvas.drawRoundRect(tray, dp(16f), dp(16f), trayPaint)
        }
    }

    internal class GlyphDrawable(
        private val boxPx: Int,
        private val sFactor: Float,
        private val strokePx: Float,
        private val leftExtent: Float,
        private val render: (Canvas, Paint, Float, Float, Float) -> Unit,
    ) : Drawable() {
        internal var verticalOffsetPx = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = strokePx
        }
        fun applyTint(color: Int) { paint.color = color; invalidateSelf() }
        fun leftInkInset(): Float = boxPx / 2f - boxPx * sFactor * leftExtent - strokePx / 2f
        internal fun glyphSizeForTest(): Float = boxPx * sFactor
        internal fun tintForTest(): Int = paint.color
        fun lastDrawCenterForTest(): Pair<Float, Float>? {
            val x = lastCenterX
            val y = lastCenterY
            return if (x.isNaN() || y.isNaN()) null else x to y
        }
        override fun getIntrinsicWidth() = boxPx
        override fun getIntrinsicHeight() = boxPx
        override fun draw(canvas: Canvas) {
            val b = bounds
            lastCenterX = b.exactCenterX()
            lastCenterY = b.exactCenterY() + verticalOffsetPx
            render(canvas, paint, lastCenterX, lastCenterY, minOf(boxPx.toFloat(), b.width().toFloat(), b.height().toFloat()) * sFactor)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        private var lastCenterX = Float.NaN
        private var lastCenterY = Float.NaN
    }
}
