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
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.layout.EmojiCatalog

/**
 * Scrollable emoji panel (C8). A left rail of categories (黄脸 / 手势 / 旗帜) drives a grid of
 * tappable emoji over a bottom bar with a back-to-keyboard button and a code-point-aware backspace.
 * Curated common set from [EmojiCatalog] — no network.
 */
class EmojiView(context: Context) : LinearLayout(context), ResettablePanel {

    var onEmoji: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onBack: () -> Unit = {}
    /** E2: live "最近" (MRU) feed — most-recently-used emoji, newest first (mirrors 符号 panel's 常用). */
    var recentProvider: () -> List<String> = { emptyList() }

    // E2: the "最近" (MRU) tab is prepended; the rest are the catalogue categories.
    private val titles: List<String> = listOf(EmojiCatalog.RECENT_TITLE) + EmojiCatalog.categories.map { it.title }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT
    private var selected = 0
    private val rail = LinearLayout(context).apply { orientation = VERTICAL }
    private val railScroll = ScrollView(context).apply { addView(rail) }
    private val grid = GridLayout(context).apply {
        columnCount = COLUMNS
        val p = dp(4); setPadding(p, p, p, p)
    }
    private val gridScroll = ScrollView(context).apply { addView(grid); isFillViewport = true }
    // debug.17: 锁定 key (parity with the 符号 panel) — when locked, tapping an emoji does NOT close the panel,
    // so several can be inserted in a row. Pixel-identical bar to SymbolsView (返回·锁定·⌫ + self-drawn icons).
    private var locked = false
    private val backBtn = barButton("返回") { onBack() }
    private val lockBtn = barButton("锁定") { toggleLock() }
    private val lockSlot = FrameLayout(context).apply {
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabelSecondary)
        setOnClickListener { toggleLock() }
    }
    private val lockGlyph = LockDrawable(density)
    private val backspaceGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }
    private val backspaceBtn = barButton("") { onBackspace() }
    private val bottomBarView = bottomBar()

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg) // P-A: panel floor == the strip/keyboard floor (no top seam)
        lockBtn.setCompoundDrawablesWithIntrinsicBounds(lockGlyph, null, null, null)
        lockBtn.compoundDrawablePadding = dp(2)
        // debug.18 (item14): ⌫ glyph as the END (right) compound drawable, not LEFT — a left drawable anchors to
        // the button's left edge so the gravity-END + right-padding below were ineffective (⌫ floated mid-bar).
        // As an end drawable it hugs the right edge, equidistant with 返回 on the left (matches SymbolsView).
        backspaceBtn.setCompoundDrawablesWithIntrinsicBounds(null, null, backspaceGlyph, null)
        backspaceGlyph.tint(palette.keyLabelSecondary)
        updateLockFace()

        for ((i, t) in titles.withIndex()) rail.addView(railTab(i, t))

        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            railScroll.setBackgroundColor(palette.railBg)
            addView(railScroll, LayoutParams(dp(60), LayoutParams.MATCH_PARENT))
            addView(gridScroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottomBarView, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
        showCategory(0)
    }

    /** F1: recolour from the Monet palette. */
    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg) // P-A: see init
        railScroll.setBackgroundColor(p.railBg)
        bottomBarView.setBackgroundColor(p.keyboardBg) // P-A: 返回 bar = the unified floor
        (bottomBarView as LinearLayout).let { bar ->
            for (i in 0 until bar.childCount) (bar.getChildAt(i) as? TextView)?.let {
                it.setTextColor(p.keyLabelSecondary)
                Motion.applyTapFeedback(it, p.keyLabelSecondary)
            }
        }
        backspaceGlyph.tint(p.keyLabelSecondary)
        updateLockFace()
        showCategory(selected)
    }

    /**
     * P7 (#19): on dismissal, fall back to the 最近 (MRU) tab at index 0, scrolled to the top, so reopening the
     * emoji panel never resumes on the last category / scroll position (mirrors the 符号 panel opening on 常用).
     */
    override fun resetToDefault() {
        resetLock() // debug.17: open unlocked, like the 符号 panel
        showCategory(0)
        gridScroll.scrollTo(0, 0)
        railScroll.scrollTo(0, 0)
    }

    /** debug.17: clear the lock — call when (re)opening so the panel always starts unlocked (parity with 符号). */
    fun resetLock() { locked = false; updateLockFace() }

    private fun toggleLock() { locked = !locked; updateLockFace() }

    private fun updateLockFace() {
        val tint = if (locked) palette.candidateFirst else palette.keyLabelSecondary
        lockGlyph.closed = locked
        lockGlyph.tint(tint)
        lockBtn.setTextColor(tint)
        Motion.applyTapFeedback(lockBtn, tint)
        Motion.applyTapFeedback(lockSlot, tint)
        lockBtn.setTypeface(null, if (locked) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    // P7 test seams.
    internal fun selectedCategoryForTest(): Int = selected
    internal fun openCategoryForTest(index: Int) = showCategory(index)
    internal fun lockedForTest(): Boolean = locked
    internal fun toggleLockForTest() = toggleLock()
    // debug.18 (item14): the bottom-bar 返回 / ⌫ buttons, so a test can assert ⌫ hugs the right edge symmetrically.
    internal fun backBtnForTest(): TextView = backBtn
    internal fun backspaceBtnForTest(): TextView = backspaceBtn
    internal fun lockBtnForTest(): TextView = lockBtn
    internal fun lockSlotForTest(): View = lockSlot

    private fun showCategory(index: Int) {
        selected = index
        for (i in 0 until rail.childCount) {
            val tab = rail.getChildAt(i) as TextView
            val on = i == index
            tab.setTextColor(if (on) palette.candidateFirst else palette.keyLabelSecondary)
            tab.setBackgroundColor(if (on) palette.keySurface else 0x00000000)
            tab.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            Motion.applyTapFeedback(tab, if (on) palette.candidateFirst else palette.keyLabelSecondary)
        }
        grid.removeAllViews()
        // E2: index 0 = the live 最近 (MRU) feed; the rest are catalogue categories (shifted by one).
        val emoji = if (index == 0) recentProvider() else EmojiCatalog.categories[index - 1].emoji
        if (emoji.isEmpty()) grid.addView(emptyHint()) else for (e in emoji) grid.addView(emojiCell(e))
    }

    /** E2: shown on an empty 最近 tab (no emoji used yet) — mirrors the 符号 panel's empty 常用 hint. */
    private fun emptyHint(): TextView = TextView(context).apply {
        text = "最近使用的表情会显示在这里"
        gravity = Gravity.CENTER
        setTextColor(palette.keyHint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(dp(16), dp(40), dp(16), dp(16))
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(0, COLUMNS, 1f)
            setGravity(Gravity.FILL_HORIZONTAL)
        }
    }

    private fun railTab(index: Int, title: String): TextView = TextView(context).apply {
        text = title
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(0, dp(13), 0, dp(13))
        isClickable = true
        Motion.applyTapFeedback(this, if (index == selected) palette.candidateFirst else palette.keyLabelSecondary)
        setOnClickListener { showCategory(index) }
    }

    private fun emojiCell(emoji: String): TextView = TextView(context).apply {
        text = emoji
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.display)
        val p = dp(8)
        setPadding(0, p, 0, p)
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabel)
        setOnClickListener { onEmoji(emoji); if (!locked) onBack() } // debug.17: locked → stay open for multi-insert
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setGravity(Gravity.FILL_HORIZONTAL)
        }
    }

    private fun bottomBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(palette.keyboardBg) // P-A: same as the unified floor
        // debug.17: pixel-identical to the 符号 panel bar — 返回 hugs LEFT, 锁定(+lock) centres, ⌫ hugs RIGHT.
        backBtn.gravity = Gravity.START or Gravity.CENTER_VERTICAL; backBtn.setPadding(dp(20), 0, 0, 0)
        backspaceBtn.gravity = Gravity.END or Gravity.CENTER_VERTICAL; backspaceBtn.setPadding(0, 0, dp(20), 0)
        lockSlot.addView(lockBtn, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(backBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(lockSlot, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(backspaceBtn, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.keyLabelSecondary)
        isClickable = true
        Motion.applyTapFeedback(this, palette.keyLabelSecondary)
        setOnClickListener { onClick() }
    }

    // debug.17: 照搬 SymbolsView — pixel-identical self-drawn lock + ⌫ drawables (shared Glyphs.drawLock/drawBackspace).
    private class LockDrawable(private val density: Float) : Drawable() {
        var closed = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun tint(color: Int) { paint.color = color; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            val b = bounds
            Glyphs.drawLock(canvas, paint, b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) * 0.48f, closed)
        }
        override fun getIntrinsicWidth() = (18 * density).toInt()
        override fun getIntrinsicHeight() = (18 * density).toInt()
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class IconDrawable(
        private val density: Float,
        private val sFactor: Float,
        private val render: (Canvas, Paint, Float, Float, Float) -> Unit,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun tint(color: Int) { paint.color = color; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            val b = bounds
            render(canvas, paint, b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) * sFactor)
        }
        override fun getIntrinsicWidth() = (22 * density).toInt()
        override fun getIntrinsicHeight() = (22 * density).toInt()
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val COLUMNS = 7
    }
}
