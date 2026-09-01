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

import com.aegis.ime.R

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.layout.EmojiVariants

class EmojiView(context: Context) :
    LinearLayout(context), ResettablePanel, CoversToolbar, KeyHapticsAware, BackspaceBubbleSource {

    var onEmoji: (String) -> Unit = {}
    var onClearRecents: () -> Unit = {}
    var onDeleteRecent: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onBackspaceSwipe: (Boolean) -> Unit = {}

    var backspaceSwipeAvailable: (Boolean) -> Boolean
        get() = backspaceTouch.canSwipe
        set(value) { backspaceTouch.canSwipe = value }
    var onBack: () -> Unit = {}
    var recentProvider: () -> List<String> = { emptyList() }
    override var hapticEnabled = false

    private val titles: List<String> = listOf(context.getString(EmojiCatalog.RECENT_TITLE_RES)) + EmojiCatalog.categories.map { context.getString(it.titleRes) }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val surfaceMetrics = ImePanelSurfaceMetrics.resolve(density, resources.displayMetrics.density * resources.configuration.fontScale)

    private var palette = ImePalette.STATIC_LIGHT
    private var selected = 0
    private val rail = ImePanelCategoryRail(context, density)
    private val railScroll = ImePanelCategoryBar(context, density).apply { addView(rail) }
    private val grid = ImePanelFaceGrid(context, density).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        columnCount = COLUMNS
    }
    private var gridCellWidthPx = surfaceMetrics.minimumGridCellWidthPx
    private var cellHeightPx = surfaceMetrics.gridCellHeightPx
    private var gridRow: LinearLayout? = null
    private val gridScroll = ImePanelViewport(context).apply {
        addView(grid)
        isFillViewport = true
    }
    private val clearDialog = PanelConfirmationOverlay(context)
    private val panelFrame = ImePanelFrame(context, density)
    private val frameTopGapPx = (ImeShapes.toolbarCapsuleMarginDp * density).toInt()
    private val gridFrame = FrameLayout(context)
    private val variantGenderRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
    private val variantSkinRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
    private val variantCard = LinearLayout(context).apply {
        orientation = VERTICAL
        isClickable = true
        val p = dp(6); setPadding(p, p, p, p)
    }
    private val variantScrim = FrameLayout(context).apply {
        isClickable = true
        visibility = GONE
        @Suppress("ClickableViewAccessibility")
        setOnTouchListener { _, e ->
            val consume = visibility == VISIBLE
            if (e.actionMasked == MotionEvent.ACTION_DOWN && isClickable) dismissVariants()
            consume
        }
        addView(variantCard, FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP,
        ).apply { topMargin = dp(10) })
    }
    private var variantGenderForm = ""
    private var variantOwnsPointer = false
    private var locked = false
    private val backBtn = barButton("") { onBack() }
    private val backSlot = FrameLayout(context)
    private val lockBtn = barButton("") { toggleLock() }
    private val lockSlot = FrameLayout(context)
    private val lockGlyph = IconDrawable(density, Glyphs.lockInk(false))
    private val clearGlyph = IconDrawable(density, Glyphs.trashInk)
    private val clearBtn = barButton("") { showClearConfirmation() }
    private val clearSlot = FrameLayout(context)
    private val backspaceGlyph = IconDrawable(density, Glyphs.backspaceInk)
    private val backspaceBtn = barButton("") { onBackspace() }
    private val backspaceSlot = FrameLayout(context)
    private val actionSlots = listOf(backSlot, clearSlot, lockSlot, backspaceSlot)
    private val actionColumnView = actionColumn()
    private val backFeedback = ImeKeyFeedback(backBtn, Color.TRANSPARENT, palette.keyLabelSecondary, faceInsetDp = 0f)
    private val lockFeedback = ImeKeyFeedback(lockBtn, Color.TRANSPARENT, palette.keyLabelSecondary, faceInsetDp = 0f)
    private val clearFeedback = ImeKeyFeedback(clearBtn, Color.TRANSPARENT, palette.keyLabelSecondary, faceInsetDp = 0f)
    private val backspaceFeedback = ImeKeyFeedback(backspaceBtn, Color.TRANSPARENT, palette.keyLabelSecondary, faceInsetDp = 0f)
    private val backspaceTouch = ImeBackspaceTouch(
        backspaceBtn,
        backspaceFeedback,
        density,
        { hapticEnabled },
        { onBackspace() },
        { onBackspaceSwipe(it) },
        { backspaceBubbleObserver?.run() },
    )
    private var backspaceBubbleObserver: Runnable? = null

    override fun bindBackspaceBubbleObserver(observer: Runnable) {
        backspaceBubbleObserver = observer
    }

    override fun backspaceBubbleDirectionUp(): Boolean? = backspaceTouch.bubbleDirectionUp()

    override fun backspaceBubbleArmed(): Boolean = backspaceTouch.bubbleArmed()

    override fun backspaceBubbleAnchor(): View = backspaceBtn

    private val emojiPool = ArrayList<TextView>()
    private val emojiFeedback = HashMap<TextView, ImeKeyFeedback>()
    private val railFeedback = HashMap<TextView, ImeKeyFeedback>()
    private var emptyHintView: TextView? = null
    private val colorAnimators = HashMap<TextView, ValueAnimator>()
    private val emojiClick = View.OnClickListener { v ->
        val e = (v as TextView).text.toString()
        onEmoji(e); if (!locked) onBack()
    }
    private val emojiLongClick = View.OnLongClickListener { v ->
        val e = (v as TextView).text.toString()
        if (selected == 0) {
            showDeleteConfirmation(e)
            true
        } else if (EmojiVariants.hasVariants(e)) {
            openVariants(e)
            variantOwnsPointer = true
            parent?.requestDisallowInterceptTouchEvent(true)
            true
        } else false
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg)
        backBtn.text = context.getString(R.string.panel_back)
        backBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        lockBtn.contentDescription = context.getString(R.string.panel_lock)
        lockBtn.setCompoundDrawablesWithIntrinsicBounds(lockGlyph, null, null, null)
        clearBtn.contentDescription = context.getString(R.string.emoji_clear_recent)
        clearBtn.setCompoundDrawablesWithIntrinsicBounds(clearGlyph, null, null, null)
        clearGlyph.tint(palette.keyLabelSecondary)
        backspaceBtn.contentDescription = context.getString(R.string.edit_delete)
        backspaceBtn.setCompoundDrawablesWithIntrinsicBounds(backspaceGlyph, null, null, null)
        backspaceGlyph.tint(palette.keyLabelSecondary)
        backFeedback.bind { hapticEnabled }
        lockFeedback.bind { hapticEnabled }
        clearFeedback.bind { hapticEnabled }
        updateLockFace()

        for ((i, t) in titles.withIndex()) rail.addView(railTab(i, t))

        gridFrame.addView(gridScroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        gridFrame.addView(variantScrim, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        variantCard.addView(variantGenderRow)
        variantCard.addView(
            object : android.widget.HorizontalScrollView(context) {
                override fun shouldDelayChildPressedState(): Boolean = false
            }.apply {
                isHorizontalScrollBarEnabled = false
                addView(variantSkinRow)
            },
        )
        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(gridFrame, LayoutParams(gridWidthPx(), LayoutParams.MATCH_PARENT))
            addView(actionColumnView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        gridRow = content
        railScroll.setBackgroundColor(palette.keyboardBg)
        val panelColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, gridAreaHeightPx()))
            addView(railScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        panelFrame.outlineColor = palette.separator
        panelFrame.apply {
            addView(panelColumn, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(clearDialog, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addView(panelFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = frameTopGapPx })
        showCategory(0)
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        railScroll.setBackgroundColor(p.keyboardBg)
        actionColumnView.applyPalette(p)
        grid.ruleColor = p.separator
        panelFrame.outlineColor = p.separator
        rail.underlineColor = p.accentBottom
        railScroll.ruleColor = p.separator
        for (button in listOf(backBtn, clearBtn, backspaceBtn)) button.setTextColor(p.keyLabelSecondary)
        backFeedback.update(Color.TRANSPARENT, p.keyLabelSecondary)
        clearFeedback.update(Color.TRANSPARENT, p.keyLabelSecondary)
        backspaceFeedback.update(Color.TRANSPARENT, p.keyLabelSecondary)
        clearGlyph.tint(p.keyLabelSecondary)
        backspaceGlyph.tint(p.keyLabelSecondary)
        for (cell in emojiPool) {
            cell.setTextColor(p.keyLabel)
            emojiFeedback[cell]?.update(Color.TRANSPARENT, p.keyLabel)
        }
        emptyHintView?.setTextColor(p.keyHint)
        updateLockFace()
        styleRail(-1)
    }

    override fun resetToDefault() {
        resetLock()
        Motion.reset(lockBtn)
        dismissVariantsImmediately()
        variantOwnsPointer = false
        clearDialog.dismissImmediately()
        Motion.reset(gridScroll)
        showCategory(0, animate = false)
        gridScroll.scrollTo(0, 0)
        gridScroll.fling(0)
        railScroll.scrollTo(0, 0)
        railScroll.fling(0)
        backspaceTouch.cancel()
        backFeedback.reset()
        lockFeedback.reset()
        clearFeedback.reset()
        for (feedback in railFeedback.values) feedback.reset()
        for (feedback in emojiFeedback.values) feedback.reset()
    }

    fun resetLock() { locked = false; updateLockFace() }

    private fun toggleLock() {
        locked = !locked
        Motion.coverThrough(lockBtn, palette.keyboardBg) { updateLockFace() }
    }

    private fun updateLockFace() {
        val tint = if (locked) palette.candidateFirst else palette.keyLabelSecondary
        lockGlyph.ink = Glyphs.lockInk(locked)
        lockGlyph.tint(tint)
        lockBtn.isSelected = locked
        lockFeedback.update(Color.TRANSPARENT, tint)
    }

    internal fun selectedCategoryForTest(): Int = selected
    internal fun openCategoryForTest(index: Int) = showCategory(index)
    internal fun lockedForTest(): Boolean = locked
    internal fun toggleLockForTest() = toggleLock()
    internal fun backBtnForTest(): TextView = backBtn
    internal fun clearBtnForTest(): TextView = clearBtn
    internal fun backspaceBtnForTest(): TextView = backspaceBtn
    internal fun lockBtnForTest(): TextView = lockBtn
    internal fun lockSlotForTest(): View = lockSlot
    internal fun railTabForTest(index: Int): TextView = rail.getChildAt(index) as TextView
    internal fun railTabFeedbackLevelForTest(index: Int): Float =
        railFeedback[railTabForTest(index)]?.levelForTest() ?: 0f
    internal fun emojiCellsAllocatedForTest(): Int = emojiPool.size
    internal fun gridCellCountForTest(): Int = grid.childCount
    internal fun gridCellForTest(index: Int): TextView? = grid.getChildAt(index) as? TextView
    internal fun gridCellFeedbackLevelForTest(index: Int): Float =
        gridCellForTest(index)?.let { emojiFeedback[it]?.levelForTest() } ?: 0f
    internal fun gridColumnCountForTest(): Int = grid.columnCount
    internal fun cellHeightForTest(): Int = cellHeightPx
    internal fun categoryBarForTest(): View = railScroll
    internal fun gridRuleColorForTest(): Int = grid.ruleColor
    internal fun panelFrameForTest(): ImePanelFrame = panelFrame
    internal fun categoryRailForTest(): ImePanelCategoryRail = rail
    internal fun actionColumnForTest(): View = actionColumnView
    internal fun gridCellTextsForTest(): List<String> =
        (0 until grid.childCount).mapNotNull { (grid.getChildAt(it) as? TextView)?.text?.toString() }
    internal fun tapCellForTest(index: Int): Boolean = (grid.getChildAt(index) as? TextView)?.performClick() ?: false
    internal fun gridScrollYForTest(): Int = gridScroll.scrollY
    internal fun gridViewportForTest(): View = gridScroll
    internal fun clearDialogVisibleForTest(): Boolean = clearDialog.visibility == View.VISIBLE
    internal fun confirmClearForTest(): Boolean = clearDialog.confirmForTest()
    internal fun cancelClearForTest(): Boolean = clearDialog.cancelForTest()
    internal fun dismissClearForTest(): Boolean = clearDialog.performClick()

    fun refresh() = showCategory(selected)

    private fun gridWidthPx(): Int = grid.columnCount * gridCellWidthPx

    private fun fitActionIcons(boxWidthPx: Int) {
        if (boxWidthPx <= 0) return
        for ((button, glyph) in listOf(clearBtn to clearGlyph, lockBtn to lockGlyph, backspaceBtn to backspaceGlyph)) {
            if (glyph.boxWidthPx == boxWidthPx) continue
            glyph.boxWidthPx = boxWidthPx
            button.setCompoundDrawablesWithIntrinsicBounds(glyph, null, null, null)
        }
    }

    private fun gridAreaHeightPx(): Int = ROWS * cellHeightPx

    private fun updateRowHeight(px: Int) {
        val next = px.coerceAtLeast(1)
        if (next == cellHeightPx) return
        cellHeightPx = next
        for (cell in emojiPool) {
            cell.layoutParams?.let { if (it.height != next) { it.height = next; cell.layoutParams = it } }
        }
    }

    private fun applyPanelBands() {
        gridRow?.let { row ->
            row.getChildAt(0)?.layoutParams?.let { lp ->
                val want = gridWidthPx()
                if (lp.width != want) { lp.width = want; row.getChildAt(0).layoutParams = lp }
            }
            row.layoutParams?.let { lp ->
                val want = gridAreaHeightPx()
                if (lp.height != want) { lp.height = want; row.layoutParams = lp }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val totalHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (totalWidth > 0) {
            updateGridMetrics(surfaceMetrics.fitGrid(totalWidth, COLUMNS))
            fitActionIcons(totalWidth - gridWidthPx())
        }
        if (totalHeight > 0) {
            updateRowHeight((totalHeight - frameTopGapPx) / (ROWS + 1))
        }
        applyPanelBands()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updateGridMetrics(metrics: ImePanelGridMetrics) {
        if (grid.columnCount == metrics.columns && gridCellWidthPx == metrics.cellWidthPx) return
        val children = (0 until grid.childCount).map { grid.getChildAt(it) }
        grid.removeAllViews()
        grid.columnCount = metrics.columns
        gridCellWidthPx = metrics.cellWidthPx
        for (cell in emojiPool) {
            val params = cell.layoutParams as GridLayout.LayoutParams
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
            params.width = gridCellWidthPx
            cell.layoutParams = params
        }
        emptyHintView?.let { hint ->
            val params = hint.layoutParams as GridLayout.LayoutParams
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
            params.columnSpec = GridLayout.spec(0, metrics.columns, 1f)
            params.width = 0
            hint.layoutParams = params
        }
        for (child in children) grid.addView(child, child.layoutParams)
    }

    private fun showCategory(index: Int, animate: Boolean = true) {
        dismissVariants()
        val tabChanged = index != selected
        val prev = selected
        selected = index
        styleRail(if (tabChanged) prev else -1)
        val swap = {
            bindGrid(selected)
            if (tabChanged) {
                gridScroll.scrollTo(0, 0)
                gridScroll.fling(0)
            }
        }
        if (animate && tabChanged && gridScroll.isShown) Motion.coverThrough(gridScroll, palette.keyboardBg, swap)
        else swap()
    }

    private fun styleRail(crossfadeFrom: Int) {
        rail.selectedIndex = selected
        for (i in 0 until rail.childCount) {
            val tab = rail.getChildAt(i) as TextView
            val on = i == selected
            tab.isSelected = on
            val color = if (on) palette.keyLabel else palette.keyLabelSecondary
            if (crossfadeFrom >= 0 && (i == selected || i == crossfadeFrom)) crossfadeTabColor(tab, color) else tab.setTextColor(color)
            railFeedback[tab]?.update(Color.TRANSPARENT, color)
        }
    }

    private fun bindGrid(index: Int) {
        grid.removeAllViews()
        val emoji = if (index == 0) recentProvider() else EmojiCatalog.supported[index - 1].emoji
        if (emoji.isEmpty()) { grid.addView(obtainEmptyHint()); return }
        for (i in emoji.indices) {
            val cell = obtainEmojiCell(i)
            if (cell.text != emoji[i]) cell.text = emoji[i]
            cell.tag = i
            grid.addView(cell)
        }
    }

    private fun obtainEmptyHint(): TextView = emptyHintView ?: TextView(context).apply {
        text = context.getString(R.string.emoji_empty_hint)
        gravity = Gravity.CENTER
        setTextColor(palette.keyHint)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(dp(16), dp(40), dp(16), dp(16))
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(0, grid.columnCount, 1f)
            setGravity(Gravity.FILL_HORIZONTAL)
        }
    }.also { emptyHintView = it }

    private fun crossfadeTabColor(tab: TextView, color: Int) {
        colorAnimators.remove(tab)?.cancel()
        Motion.crossfadeColor(tab, tab.currentTextColor, color) { tab.setTextColor(it) }?.let { colorAnimators[tab] = it }
    }

    private fun railTab(index: Int, title: String): TextView = TextView(context).apply {
        text = title
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(CATEGORY_PADDING_DP), 0, dp(CATEGORY_PADDING_DP), 0)
        minimumWidth = dp(CATEGORY_MIN_WIDTH_DP)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
        isClickable = true
        val on = index == selected
        isSelected = on
        railFeedback[this] = ImeKeyFeedback(
            this,
            Color.TRANSPARENT,
            if (on) palette.keyLabel else palette.keyLabelSecondary,
            faceInsetDp = 0f,
            radiusDp = 0f,
        ).also { it.bind { hapticEnabled } }
        setOnClickListener { showCategory(index) }
    }

    private fun obtainEmojiCell(index: Int): TextView {
        if (index < emojiPool.size) return emojiPool[index]
        val tv = TextView(context).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.display)
            setPadding(0, 0, 0, 0)
            isClickable = true
            isLongClickable = true
            setOnClickListener(emojiClick)
            setOnLongClickListener(emojiLongClick)
            layoutParams = GridLayout.LayoutParams().apply {
                width = gridCellWidthPx
                height = cellHeightPx
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
                setGravity(Gravity.FILL)
            }
        }
        ImeKeyFeedback(
            tv,
            Color.TRANSPARENT,
            palette.keyLabel,
            radiusDp = 0f,
            faceInsetPxOverride = 0f,
        ).also {
            it.bind { hapticEnabled }
            emojiFeedback[tv] = it
        }
        emojiPool.add(tv)
        return tv
    }


    private fun openVariants(base: String) {
        variantGenderForm = base
        styleVariantCard()
        buildGenderRow(base)
        buildSkinRow(base)
        variantCard.isClickable = true
        variantScrim.isClickable = true
        variantScrim.visibility = View.VISIBLE
        variantScrim.bringToFront()
        Motion.showNow(variantScrim)
        Motion.showNow(variantCard)
    }

    private fun dismissVariants() {
        if (variantScrim.visibility != View.VISIBLE || !variantScrim.isClickable) return
        variantScrim.isClickable = false
        disableClicks(variantCard)
        Motion.hideNow(variantScrim)
    }

    private fun disableClicks(v: View) {
        v.isClickable = false
        v.isLongClickable = false
        if (v is ViewGroup) for (i in 0 until v.childCount) disableClicks(v.getChildAt(i))
    }

    private fun dismissVariantsImmediately() {
        variantScrim.isClickable = false
        Motion.reset(variantScrim)
        variantScrim.visibility = View.GONE
    }

    private fun commitVariant(form: String) {
        onEmoji(form)
        dismissVariants()
        if (!locked) onBack()
    }

    private fun buildGenderRow(base: String) {
        val forms = EmojiVariants.genderForms(base)
        variantGenderRow.removeAllViews()
        if (forms.size <= 1) { variantGenderRow.visibility = View.GONE; return }
        variantGenderRow.visibility = View.VISIBLE
        for (f in forms) variantGenderRow.addView(variantCell(f, selected = f == variantGenderForm) {
            if (EmojiVariants.skinForms(f).size > 1) {
                variantGenderForm = f
                buildGenderRow(base)
                buildSkinRow(f)
            } else commitVariant(f)
        })
    }

    private fun buildSkinRow(form: String) {
        val tones = EmojiVariants.skinForms(form)
        variantSkinRow.removeAllViews()
        if (tones.size <= 1) { variantSkinRow.visibility = View.GONE; return }
        variantSkinRow.visibility = View.VISIBLE
        for (t in tones) variantSkinRow.addView(variantCell(t, selected = false) { commitVariant(t) })
    }

    private fun variantCell(glyph: String, selected: Boolean, onTap: () -> Unit): TextView =
        TextView(context).apply {
            text = glyph
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.display)
            val p = dp(6); setPadding(p, p, p, p)
            minWidth = dp(42)
            isClickable = true
            if (selected) background = GradientDrawable().apply {
                setColor(palette.keySurface); cornerRadius = ImeShapes.chipRadiusDp * density
            }
            Motion.applyTapFeedback(this, palette.keyLabel, radiusDp = ImeShapes.chipRadiusDp)
            setOnClickListener { if (isClickable) onTap() }
        }

    private fun styleVariantCard() {
        variantScrim.setBackgroundColor(Color.TRANSPARENT)
        variantCard.background = GradientDrawable().apply {
            setColor(palette.keyboardBg); cornerRadius = ImeShapes.cardRadiusDp * density
        }
        variantCard.elevation = dp(8).toFloat()
    }

    internal fun longPressCellForTest(index: Int): Boolean =
        (grid.getChildAt(index) as? TextView)?.let { emojiLongClick.onLongClick(it) } ?: false
    internal fun openVariantsForTest(emoji: String) = openVariants(emoji)
    internal fun variantVisibleForTest(): Boolean = variantScrim.visibility == View.VISIBLE
    internal fun variantBackdropForTest(): View = variantScrim
    internal fun variantGenderFormsForTest(): List<String> =
        (0 until variantGenderRow.childCount).map { (variantGenderRow.getChildAt(it) as TextView).text.toString() }
    internal fun variantSkinFormsForTest(): List<String> =
        (0 until variantSkinRow.childCount).map { (variantSkinRow.getChildAt(it) as TextView).text.toString() }
    internal fun tapVariantGenderForTest(index: Int): Boolean =
        (variantGenderRow.getChildAt(index) as? TextView)?.performClick() ?: false
    internal fun tapVariantSkinForTest(index: Int): Boolean =
        (variantSkinRow.getChildAt(index) as? TextView)?.performClick() ?: false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && variantOwnsPointer) {
            variantOwnsPointer = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (variantOwnsPointer && ev.actionMasked != MotionEvent.ACTION_DOWN) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!variantOwnsPointer) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            variantOwnsPointer = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        backspaceTouch.cancel()
        backFeedback.reset()
        lockFeedback.reset()
        clearFeedback.reset()
        for (feedback in railFeedback.values) feedback.reset()
        for (feedback in emojiFeedback.values) feedback.reset()
        super.onDetachedFromWindow()
    }

    private fun showClearConfirmation() {
        clearDialog.show(
            context.getString(R.string.emoji_clear_recent_confirm),
            context.getString(R.string.clip_clear),
            context.getString(R.string.clip_cancel),
            palette,
        ) {
            onClearRecents()
            showCategory(selected)
        }
    }

    private fun showDeleteConfirmation(emoji: String) {
        clearDialog.show(
            context.getString(R.string.emoji_delete_recent_confirm, emoji),
            context.getString(R.string.clip_delete),
            context.getString(R.string.clip_cancel),
            palette,
        ) {
            onDeleteRecent(emoji)
            showCategory(selected)
        }
    }

    private fun actionColumn(): ImePanelActionColumn = ImePanelActionColumn(context, density).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        applyPalette(palette)
        backBtn.gravity = Gravity.CENTER; backBtn.setPadding(0, 0, 0, 0)
        clearBtn.gravity = Gravity.CENTER; clearBtn.setPadding(0, 0, 0, 0)
        lockBtn.gravity = Gravity.CENTER; lockBtn.setPadding(0, 0, 0, 0)
        backspaceBtn.gravity = Gravity.CENTER; backspaceBtn.setPadding(0, 0, 0, 0)
        val slots = listOf(
            panelActionSlot(backSlot, backBtn),
            panelActionSlot(clearSlot, clearBtn),
            panelActionSlot(lockSlot, lockBtn),
            panelActionSlot(backspaceSlot, backspaceBtn),
        )
        for (slot in slots) {
            slot.layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(slot, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.keyLabelSecondary)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private class IconDrawable(density: Float, initialInk: Glyphs.Ink) : Drawable() {
        private val sizePx = ImePanelSurfaceMetrics.actionIconPx(ImeType.body, density)
        var boxWidthPx = (ImePanelSurfaceMetrics.ACTION_WIDTH_DP * density).toInt()
        private val intrinsicH = (ImePanelSurfaceMetrics.FACE_HEIGHT_DP * density).toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        var ink: Glyphs.Ink = initialInk
            set(value) { field = value; invalidateSelf() }
        fun tint(color: Int) { paint.color = color; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            val b = bounds
            ink.draw(canvas, paint, b.exactCenterX(), b.exactCenterY(), sizePx)
        }
        override fun getIntrinsicWidth() = boxWidthPx
        override fun getIntrinsicHeight() = intrinsicH
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    internal companion object {
        const val COLUMNS = 4
        const val ROWS = 4
        const val CATEGORY_PADDING_DP = 12
        const val CATEGORY_MIN_WIDTH_DP = 48
    }
}
