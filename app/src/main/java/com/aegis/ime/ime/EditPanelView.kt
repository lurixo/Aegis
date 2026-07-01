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

import com.aegis.ime.ime.theme.ImePalette
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Actions the text-editing panel reports back to the IME service. */
enum class EditAction { UP, DOWN, LEFT, RIGHT, START_SELECT, DELETE, COPY, CUT, SELECT_ALL, HOME, END, PASTE, BACK }

/**
 * Text-editing panel (issue #4): a cursor D-pad with a center "开始选择" toggle,
 * a right column 删除/复制/剪切 (复制/剪切 disabled without a selection), and a bottom row 段首/全选/段尾/粘贴.
 * Pure UI — the service maps [EditAction]s onto the InputConnection.
 *
 * debug.16 (#55): every key now carries a self-drawn [Glyphs] outline icon in the IME's monochrome-stroke
 * language — the D-pad is hollow arrows, 粘贴 reuses the copy-bar clipboard glyph, and the back chevron "‹"
 * matches the clipboard panel — instead of mixed text/emoji markers.
 */
class EditPanelView(context: Context) : LinearLayout(context), ResettablePanel {

    var onAction: (EditAction) -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    /** debug.18 (item13): the 文字编辑 title text size; the back chevron is sized to match its visual height. */
    private val TITLE_SP = 16f

    private var palette = ImePalette.STATIC_LIGHT
    private val copyBtn: TextView
    private val cutBtn: TextView
    private val selectBtn: TextView
    private val copyIcon: GlyphDrawable
    private val cutIcon: GlyphDrawable
    private val icons = mutableListOf<GlyphDrawable>() // tinted as a group on applyPalette (copy/cut re-tinted by state)
    private val actionViews = mutableMapOf<EditAction, View>()
    private val arrowIcons = mutableMapOf<EditAction, GlyphDrawable>()
    private val titleBar: LinearLayout

    /** F1: recolour from the Monet palette (every button text → onSurface; disabled copy/cut stays muted). */
    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg) // P-A: see init
        recolor(this)
        for (g in icons) g.applyTint(p.keyLabel)
        setHasSelection(copyBtn.isEnabled) // re-mutes the disabled copy/cut text + icons
    }

    private fun recolor(v: View) {
        if (v.hasOnClickListeners()) Motion.applyTapFeedback(v, palette.keyLabel)
        when (v) {
            is TextView -> {
                v.setTextColor(palette.keyLabel)
            }
            is ViewGroup -> for (i in 0 until v.childCount) recolor(v.getChildAt(i))
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg) // P-A: panel floor == the strip/keyboard floor (no top seam)

        // Title bar: keep the back chevron in the same outline style as the panel icons, but size its box near
        // the right-side action label height so it does not read heavier than the controls beside the D-pad.
        titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                textBtn("文字编辑", EditAction.BACK, sp = TITLE_SP).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
                    setCompoundDrawablesWithIntrinsicBounds(
                        icon(16, 0.56f) { c, p, x, y, s -> Glyphs.drawBack(c, p, x, y, s) },
                        null,
                        null,
                        null,
                    )
                    compoundDrawablePadding = dp(6)
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
            )
        }
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))

        // Middle: D-pad (left) + delete/copy/cut column (right). Arrows = hollow Glyphs.drawArrow (debug.16 item6).
        val mid = LinearLayout(context).apply { orientation = HORIZONTAL }
        val dpad = LinearLayout(context).apply { orientation = VERTICAL }
        selectBtn = textBtn("开始选择", EditAction.START_SELECT, sp = 16f)
        dpad.addView(dpadRow(null, arrowBtn(EditAction.UP, Glyphs.Arrow.UP), null), rowLp())
        dpad.addView(dpadRow(arrowBtn(EditAction.LEFT, Glyphs.Arrow.LEFT), selectBtn, arrowBtn(EditAction.RIGHT, Glyphs.Arrow.RIGHT)), rowLp())
        dpad.addView(dpadRow(null, arrowBtn(EditAction.DOWN, Glyphs.Arrow.DOWN), null), rowLp())
        mid.addView(dpad, LayoutParams(0, LayoutParams.MATCH_PARENT, 3f))

        copyIcon = icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawCopy(c, p, x, y, s) }
        cutIcon = icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawCut(c, p, x, y, s) }
        copyBtn = iconBtn("复制", EditAction.COPY, copyIcon)
        cutBtn = iconBtn("剪切", EditAction.CUT, cutIcon)
        val rightCol = LinearLayout(context).apply { orientation = VERTICAL }
        rightCol.addView(iconBtn("删除", EditAction.DELETE, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }), rowLp())
        rightCol.addView(copyBtn, rowLp())
        rightCol.addView(cutBtn, rowLp())
        // debug.17 B: a gutter column so the action column (删除/复制/剪切) sits at the far right, its right margin
        // mirroring the left content's left margin (was weight 2 centred → too far from the right edge). dpad keeps
        // weight 3, so its 3 arrows do not move; the right action column drops to weight 1 in the last 1/5.
        mid.addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        mid.addView(rightCol, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(mid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom row — 段首/段尾 (paragraph edges, debug.16 item1), 全选, and 粘贴 with the copy-bar clipboard glyph.
        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL }
        bottom.addView(iconBtn("段首", EditAction.HOME, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawParagraphEdge(c, p, x, y, s, toStart = true) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(iconBtn("全选", EditAction.SELECT_ALL, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawSelectAll(c, p, x, y, s) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(iconBtn("段尾", EditAction.END, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawParagraphEdge(c, p, x, y, s, toStart = false) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bottom.addView(spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)) // debug.17 B: gutter so 粘贴 lines up under the action column at the far right
        bottom.addView(iconBtn("粘贴", EditAction.PASTE, icon(22, 0.34f) { c, p, x, y, s -> Glyphs.drawClipboard(c, p, x, y, s) }), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))

        setHasSelection(false)
    }

    /** Enable 复制/剪切 only when there is a selection — text AND icon are muted when off. */
    fun setHasSelection(has: Boolean) {
        val tint = if (has) palette.keyLabel else palette.disabled
        for (b in listOf(copyBtn, cutBtn)) {
            b.isEnabled = has
            b.setTextColor(tint)
            Motion.applyTapFeedback(b, tint)
        }
        copyIcon.applyTint(tint); cutIcon.applyTint(tint)
    }

    fun setSelecting(selecting: Boolean) {
        selectBtn.text = if (selecting) "结束选择" else "开始选择"
    }

    /** P7 (#19): on dismissal, drop selection mode so the panel reopens showing "开始选择". The D-pad panel
     *  holds no tab/scroll state; the host re-syncs its own `selecting` flag when it next opens the panel. */
    override fun resetToDefault() = setSelecting(false)

    // P7 test seam.
    internal fun selectingLabelForTest(): CharSequence = selectBtn.text
    internal fun actionViewForTest(action: EditAction): View? = actionViews[action]
    internal fun titleBarForTest(): View = titleBar
    internal fun arrowLastDrawCenterForTest(action: EditAction): Pair<Float, Float>? =
        arrowIcons[action]?.lastDrawCenterForTest()

    private fun rowLp() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)

    private fun dpadRow(left: View?, center: View, right: View?): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(left ?: spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(center, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(right ?: spacer(), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

    private fun spacer(): View = View(context)

    /** Text-only key (back chevron + the 开始选择 toggle). */
    private fun textBtn(label: String, action: EditAction, sp: Float): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(palette.keyLabel)
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabel)
        setOnClickListener { onAction(action) }
        actionViews[action] = this
    }

    /** Icon-over-label key (删除/复制/剪切/全选/段首/段尾/粘贴). */
    private fun iconBtn(label: String, action: EditAction, glyph: GlyphDrawable): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(palette.keyLabel)
        setCompoundDrawablesWithIntrinsicBounds(null, glyph, null, null)
        compoundDrawablePadding = dp(2)
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabel)
        setOnClickListener { onAction(action) }
        actionViews[action] = this
    }

    /** Icon-only hollow-arrow key for the D-pad. */
    private fun arrowBtn(action: EditAction, dir: Glyphs.Arrow): View = View(context).apply {
        val glyph = icon(32, 0.40f) { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, dir) }
        arrowIcons[action] = glyph
        background = glyph
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabel)
        setOnClickListener { onAction(action) }
        actionViews[action] = this
    }

    /** Build a palette-tinted [GlyphDrawable] in a [boxDp]² box and register it for recolouring. */
    private fun icon(boxDp: Int, sFactor: Float, render: (Canvas, Paint, Float, Float, Float) -> Unit): GlyphDrawable =
        GlyphDrawable(dp(boxDp), sFactor, 2f * density, render).also { it.applyTint(palette.keyLabel); icons += it }

    /** A [Drawable] wrapper that paints one self-drawn [Glyphs] line icon, tinted to the current palette — lets
     *  the icons ride as TextView compound drawables (so layout/clicks stay the simple TextView path). */
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
            lastCenterY = b.exactCenterY()
            render(canvas, paint, lastCenterX, lastCenterY, boxPx * sFactor)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        private var lastCenterX = Float.NaN
        private var lastCenterY = Float.NaN
    }
}
