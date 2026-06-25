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
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.ui.SettingsRoutes

enum class SettingsShortcut(val route: String?) {
    HOME(null),
    INPUT(SettingsRoutes.INPUT),
    KEYBOARD(SettingsRoutes.KEYBOARD),
    DICTS(SettingsRoutes.DICTS),
    USER_DICT(SettingsRoutes.USER_DICT),
    BACKUP(SettingsRoutes.BACKUP),
    ABOUT(SettingsRoutes.ABOUT),
}

class SettingsPanelView(context: Context) : LinearLayout(context), ResettablePanel, CoversToolbar {

    var onPick: (SettingsShortcut) -> Unit = {}
    var onBack: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val edgeInset = (ImeShapes.edgeInsetDp * density).toInt()

    private val ICON_DP = 46
    private val GLYPH_DP = 32f
    private val ICON_STROKE_DP = 2f
    private val CARD_PAD_X_DP = 6
    private val GAP_DP = 8
    private val COLUMNS = 4
    private val WIDE_COLUMNS = 7
    private val WIDE_CARD_MIN_DP = 80
    private val MIN_LABEL_SP = 10
    private val LABEL_LINES = 2

    private var palette = ImePalette.STATIC_LIGHT
    private val titleBtn: PanelHeaderBackControl
    private val cards: List<Card>
    private val grid: CardGrid
    private val scroller: ScrollView

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg)

        titleBtn = PanelBackButton.control(
            context,
            context.getString(R.string.settings_panel_title),
            palette.keyLabel,
        ) { onBack() }.apply {
            isClickable = true
            isFocusable = false
        }
        val titleBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(titleBtn, LayoutParams(LayoutParams.WRAP_CONTENT, dp(PanelBackButton.HIT_DP)).apply { leftMargin = edgeInset })
        }
        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))

        cards = listOf(
            Card(SettingsShortcut.HOME, context.getString(R.string.app_name), Glyphs.brandGrid),
            Card(SettingsShortcut.INPUT, context.getString(R.string.settings_group_input_title), Glyphs.tuneGrid),
            Card(SettingsShortcut.KEYBOARD, context.getString(R.string.settings_group_keyboard_title), Glyphs.keyboardGrid),
            Card(SettingsShortcut.DICTS, context.getString(R.string.settings_group_dicts_title), Glyphs.dictionaryDownloadGrid),
            Card(SettingsShortcut.USER_DICT, context.getString(R.string.settings_group_userdict_title), Glyphs.userDictionaryGrid),
            Card(SettingsShortcut.BACKUP, context.getString(R.string.settings_backup_title), Glyphs.lockedFileGrid),
            Card(SettingsShortcut.ABOUT, context.getString(R.string.settings_group_about_title), Glyphs.infoGrid),
        )
        grid = CardGrid(context).apply {
            setPadding(edgeInset, dp(8), edgeInset, dp(8))
            for (card in cards) addView(card.view)
        }
        scroller = ScrollView(context).apply {
            clipToPadding = false
            addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        restyle()
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        titleBtn.applyTint(p.keyLabel)
        restyle()
    }

    private fun restyle() {
        for (card in cards) card.applyStyle()
    }

    override fun resetToDefault() {
        resetCardFeedback()
        scroller.scrollTo(0, 0)
    }

    override fun onDetachedFromWindow() {
        resetCardFeedback()
        super.onDetachedFromWindow()
    }

    private fun resetCardFeedback() {
        for (card in cards) card.feedback.reset()
    }

    private fun fitLabels(cellWidth: Int) {
        val textWidth = cellWidth - 2 * dp(CARD_PAD_X_DP)
        if (textWidth <= 0) return
        var sp = ImeType.label.toInt()
        while (sp > MIN_LABEL_SP && cards.any { !labelFits(it.view, spPx(sp), textWidth) }) sp--
        val px = spPx(sp)
        for (card in cards) if (card.view.textSize != px) card.view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
    }

    private fun labelFits(view: TextView, px: Float, width: Int): Boolean {
        val paint = TextPaint(view.paint).apply { textSize = px }
        val text = view.text
        if (text.split(' ').any { paint.measureText(it) > width }) return false
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setBreakStrategy(view.breakStrategy)
            .setHyphenationFrequency(view.hyphenationFrequency)
            .build()
            .lineCount <= LABEL_LINES
    }

    private fun spPx(sp: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), resources.displayMetrics)

    internal fun shortcutsForTest(): List<SettingsShortcut> = cards.map { it.shortcut }
    internal fun cardViewForTest(shortcut: SettingsShortcut): TextView = card(shortcut).view
    internal fun cardIconForTest(shortcut: SettingsShortcut): EditPanelView.GlyphDrawable = card(shortcut).icon
    internal fun cardFeedbackLevelForTest(shortcut: SettingsShortcut): Float = card(shortcut).feedback.levelForTest()
    internal fun cardFeedbackDrawableForTest(shortcut: SettingsShortcut): Drawable = card(shortcut).feedback.drawableForTest()
    internal fun titleButtonForTest(): TextView = titleBtn
    internal fun columnsForTest(): Int = grid.columns
    internal fun scrollerForTest(): ScrollView = scroller

    private fun card(shortcut: SettingsShortcut): Card = cards.first { it.shortcut == shortcut }

    private inner class Card(val shortcut: SettingsShortcut, label: String, ink: Glyphs.GridInk) {
        val icon = EditPanelView.GlyphDrawable(dp(ICON_DP), GLYPH_DP / ICON_DP, ICON_STROKE_DP * density, 0f) { c, p, x, y, s ->
            ink.draw(c, p, x, y, s)
        }
        val view: TextView = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = LABEL_LINES
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(CARD_PAD_X_DP), dp(6), dp(CARD_PAD_X_DP), dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
            setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
            compoundDrawablePadding = dp(2)
            isClickable = true
            setOnClickListener { onPick(shortcut) }
        }
        val feedback = ImeKeyFeedback(
            view,
            palette.keySurface,
            palette.keyLabel,
            faceInsetDp = 0f,
            radiusDp = ImeShapes.cardRadiusDp,
        ).apply {
            bind { false }
        }

        fun applyStyle() {
            view.setTextColor(palette.keyLabel)
            icon.applyTint(palette.keyLabel)
            feedback.update(palette.keySurface, palette.keyLabel)
        }
    }

    private inner class CardGrid(context: Context) : ViewGroup(context) {
        var columns = COLUMNS
            private set
        private var fittedWidth = -1

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val gap = dp(GAP_DP)
            val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
            columns = if (inner >= WIDE_COLUMNS * dp(WIDE_CARD_MIN_DP) + (WIDE_COLUMNS - 1) * gap) WIDE_COLUMNS else COLUMNS
            val cellWidth = ((inner - (columns - 1) * gap) / columns).coerceAtLeast(0)
            if (cellWidth != fittedWidth) {
                fittedWidth = cellWidth
                fitLabels(cellWidth)
            }
            val widthSpec = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY)
            var cellHeight = 0
            for (card in cards) {
                card.view.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                cellHeight = maxOf(cellHeight, card.view.measuredHeight)
            }
            val heightSpec = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY)
            for (card in cards) card.view.measure(widthSpec, heightSpec)
            val rows = (cards.size + columns - 1) / columns
            val height = paddingTop + paddingBottom + rows * cellHeight + (rows - 1) * gap
            setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val gap = dp(GAP_DP)
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            for ((i, card) in cards.withIndex()) {
                val v = card.view
                val step = v.measuredWidth + gap
                val col = i % columns
                val left = if (rtl) width - paddingRight - v.measuredWidth - col * step else paddingLeft + col * step
                val top = paddingTop + (i / columns) * (v.measuredHeight + gap)
                v.layout(left, top, left + v.measuredWidth, top + v.measuredHeight)
            }
        }
    }
}
