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
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.layout.EmojiVariants

class EmojiView(context: Context) : LinearLayout(context), ResettablePanel, CoversToolbar, KeyHapticsAware {

    var onEmoji: (String) -> Unit = {}
    var onClearRecents: () -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onBackspaceSwipe: (Boolean) -> Unit = {}
    var onBack: () -> Unit = {}
    var recentProvider: () -> List<String> = { emptyList() }
    override var hapticEnabled = false

    private val titles: List<String> = listOf(context.getString(EmojiCatalog.RECENT_TITLE_RES)) + EmojiCatalog.categories.map { context.getString(it.titleRes) }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val surfaceMetrics = ImePanelSurfaceMetrics.resolve(density)

    private var palette = ImePalette.STATIC_LIGHT
    private var selected = 0
    private val rail = LinearLayout(context).apply { orientation = VERTICAL }
    private val railScroll = ScrollView(context).apply { addView(rail) }
    private val grid = GridLayout(context).apply {
        columnCount = COLUMNS
        setPadding(
            surfaceMetrics.gridSidePaddingPx,
            surfaceMetrics.gridTopPaddingPx,
            surfaceMetrics.gridSidePaddingPx,
            surfaceMetrics.gridSidePaddingPx,
        )
    }
    private var gridCellWidthPx = surfaceMetrics.minimumGridCellWidthPx
    private val gridScroll = ScrollView(context).apply { addView(grid); isFillViewport = true }
    private val clearDialog = PanelConfirmationOverlay(context)
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
    private val backGlyph = IconDrawable(density, 0.41f) { c, p, x, y, s -> Glyphs.drawBack(c, p, x, y, s) }
    private val backBtn = barButton("") { onBack() }
    private val lockBtn = barButton("") { toggleLock() }
    private val lockSlot = FrameLayout(context)
    private val lockGlyph = LockDrawable(density)
    private val clearGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y - s * 0.06f, s) }
    private val clearBtn = barButton("") { showClearConfirmation() }
    private val backspaceGlyph = IconDrawable(density, 0.42f) { c, p, x, y, s -> Glyphs.drawBackspace(c, p, x, y, s) }
    private val backspaceBtn = barButton("") { onBackspace() }
    private val bottomBarView = bottomBar()
    private val backFeedback = ImeKeyFeedback(backBtn, palette.keySurface, palette.keyLabelSecondary, faceInsetDp = 0f)
    private val lockFeedback = ImeKeyFeedback(lockBtn, palette.keySurface, palette.keyLabelSecondary, faceInsetDp = 0f)
    private val clearFeedback = ImeKeyFeedback(clearBtn, palette.keySurface, palette.keyLabelSecondary, faceInsetDp = 0f)
    private val backspaceFeedback = ImeKeyFeedback(backspaceBtn, palette.keySurface, palette.keyLabelSecondary, faceInsetDp = 0f)
    private val backspaceTouch = ImeBackspaceTouch(
        backspaceBtn,
        backspaceFeedback,
        density,
        { hapticEnabled },
        { onBackspace() },
        { onBackspaceSwipe(it) },
    )

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
        if (EmojiVariants.hasVariants(e)) {
            openVariants(e)
            variantOwnsPointer = true
            parent?.requestDisallowInterceptTouchEvent(true)
            true
        } else false
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.keyboardBg)
        backBtn.contentDescription = context.getString(R.string.panel_back)
        backBtn.setCompoundDrawablesWithIntrinsicBounds(backGlyph, null, null, null)
        backGlyph.tint(palette.keyLabelSecondary)
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
        variantCard.addView(variantSkinRow)
        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            railScroll.setBackgroundColor(palette.keyboardBg)
            addView(railScroll, LayoutParams(surfaceMetrics.railWidthPx, LayoutParams.MATCH_PARENT))
            addView(gridFrame, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        val panelColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomBarView, LayoutParams(LayoutParams.MATCH_PARENT, surfaceMetrics.faceHeightPx))
        }
        val panelFrame = FrameLayout(context).apply {
            addView(panelColumn, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(clearDialog, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        addView(panelFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        showCategory(0)
    }

    fun applyPalette(p: ImePalette) {
        palette = p
        setBackgroundColor(p.keyboardBg)
        railScroll.setBackgroundColor(p.keyboardBg)
        bottomBarView.setBackgroundColor(p.keyboardBg)
        for (button in listOf(backBtn, clearBtn, backspaceBtn)) button.setTextColor(p.keyLabelSecondary)
        backFeedback.update(p.keySurface, p.keyLabelSecondary)
        clearFeedback.update(p.keySurface, p.keyLabelSecondary)
        backspaceFeedback.update(p.keySurface, p.keyLabelSecondary)
        backGlyph.tint(p.keyLabelSecondary)
        clearGlyph.tint(p.keyLabelSecondary)
        backspaceGlyph.tint(p.keyLabelSecondary)
        for (cell in emojiPool) {
            cell.setTextColor(p.keyLabel)
            emojiFeedback[cell]?.update(p.keySurface, p.keyLabel)
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
        lockGlyph.closed = locked
        lockGlyph.tint(tint)
        lockBtn.isSelected = locked
        lockFeedback.update(palette.keySurface, tint)
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (totalWidth > 0) {
            updateGridMetrics(surfaceMetrics.fitGrid(totalWidth, COLUMNS))
        }
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
        if (animate && tabChanged && gridScroll.isShown) Motion.coverThrough(gridScroll, palette.keyboardBg) { bindGrid(selected) }
        else bindGrid(index)
    }

    private fun styleRail(crossfadeFrom: Int) {
        for (i in 0 until rail.childCount) {
            val tab = rail.getChildAt(i) as TextView
            val on = i == selected
            tab.isSelected = on
            tab.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            val color = if (on) palette.candidateFirst else palette.keyLabelSecondary
            if (crossfadeFrom >= 0 && (i == selected || i == crossfadeFrom)) crossfadeTabColor(tab, color) else tab.setTextColor(color)
            railFeedback[tab]?.update(if (on) palette.keySurface else Color.TRANSPARENT, color)
        }
    }

    private fun bindGrid(index: Int) {
        grid.removeAllViews()
        val emoji = if (index == 0) recentProvider() else EmojiCatalog.categories[index - 1].emoji
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
        setPadding(dp(2), 0, dp(2), 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            surfaceMetrics.faceHeightPx,
        ).apply {
            if (index == 0) topMargin = surfaceMetrics.topFaceOffsetPx
        }
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 11, ImeType.label.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        isClickable = true
        val on = index == selected
        isSelected = on
        railFeedback[this] = ImeKeyFeedback(
            this,
            if (on) palette.keySurface else Color.TRANSPARENT,
            if (on) palette.candidateFirst else palette.keyLabelSecondary,
            faceInsetDp = 0f,
            radiusDp = ImeShapes.keyRadiusDp,
        ).also { it.bind { hapticEnabled } }
        setOnClickListener { showCategory(index) }
    }

    private fun obtainEmojiCell(index: Int): TextView {
        if (index < emojiPool.size) return emojiPool[index]
        val tv = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.display)
            val p = dp(8)
            setPadding(0, p, 0, p)
            isClickable = true
            isLongClickable = true
            setOnClickListener(emojiClick)
            setOnLongClickListener(emojiLongClick)
            layoutParams = GridLayout.LayoutParams().apply {
                width = gridCellWidthPx
                height = surfaceMetrics.gridCellHeightPx
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
                setGravity(Gravity.FILL_HORIZONTAL)
            }
        }
        ImeKeyFeedback(
            tv,
            palette.keySurface,
            palette.keyLabel,
            faceInsetPxOverride = surfaceMetrics.faceInsetPx.toFloat(),
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

    private fun bottomBar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(palette.keyboardBg)
        backBtn.gravity = Gravity.CENTER; backBtn.setPadding(0, 0, 0, 0)
        clearBtn.gravity = Gravity.CENTER; clearBtn.setPadding(0, 0, 0, 0)
        lockBtn.gravity = Gravity.CENTER; lockBtn.setPadding(0, 0, 0, 0)
        backspaceBtn.gravity = Gravity.CENTER; backspaceBtn.setPadding(0, 0, 0, 0)
        val actionWidth = surfaceMetrics.actionWidthPx
        val slots = listOf(
            panelBottomActionSlot(FrameLayout(context), backBtn, actionWidth),
            panelBottomActionSlot(FrameLayout(context), clearBtn, actionWidth),
            panelBottomActionSlot(lockSlot, lockBtn, actionWidth),
            panelBottomActionSlot(FrameLayout(context), backspaceBtn, actionWidth),
        )
        for (slot in slots) addView(slot, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun barButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        setTextColor(palette.keyLabelSecondary)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private class LockDrawable(private val density: Float) : Drawable() {
        var closed = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun tint(color: Int) { paint.color = color; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            val b = bounds
            Glyphs.drawLock(canvas, paint, b.exactCenterX(), b.exactCenterY(), 18 * density * 0.48f, closed)
        }
        override fun getIntrinsicWidth() = (ImePanelSurfaceMetrics.ACTION_WIDTH_DP * density).toInt()
        override fun getIntrinsicHeight() = (ImePanelSurfaceMetrics.FACE_HEIGHT_DP * density).toInt()
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class IconDrawable(
        private val density: Float,
        private val sFactor: Float,
        private val render: (Canvas, Paint, Float, Float, Float) -> Unit,
    ) : Drawable() {
        private val iconBoxPx = (22 * density).toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun tint(color: Int) { paint.color = color; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            val b = bounds
            render(canvas, paint, b.exactCenterX(), b.exactCenterY(), iconBoxPx * sFactor)
        }
        override fun getIntrinsicWidth() = (ImePanelSurfaceMetrics.ACTION_WIDTH_DP * density).toInt()
        override fun getIntrinsicHeight() = (ImePanelSurfaceMetrics.FACE_HEIGHT_DP * density).toInt()
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val COLUMNS = 7
    }
}
