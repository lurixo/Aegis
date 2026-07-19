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
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.ime.theme.ImeShapes
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.WeakHashMap
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.ime.ClipboardPanelState.Tab
import com.aegis.ime.user.ClipSplitter

class ClipboardView(context: Context) : FrameLayout(context), ResettablePanel {

    internal data class RecreationState(
        val phrasesTab: Boolean,
        val phraseCategory: String,
    )

    var onPick: (String) -> Unit = {}
    var onCopyBlockToAegis: (String) -> Unit = {}
    var onCopyBlocksToAegis: (List<String>) -> Unit = { blocks -> blocks.forEach { onCopyBlockToAegis(it) } }
    var onBack: () -> Unit = {}
    var historyProvider: () -> List<String> = { emptyList() }
    var categoriesProvider: () -> List<String> = { emptyList() }
    var phrasesInProvider: (String) -> List<String> = { emptyList() }
    var phraseNoteProvider: (String, String) -> String = { _, _ -> "" }
    var onDeleteClips: (List<String>) -> Unit = {}
    var onDeletePhrasesFrom: (String, List<String>) -> Unit = { _, _ -> }
    var onSaveAsPhrasesTo: (String, List<String>) -> Unit = { _, _ -> }
    var onEditPhrase: (String, String) -> Unit = { _, _ -> }
    var onMovePhrase: (String, String, String) -> Unit = { _, _, _ -> }
    var onMovePhrasesTo: (String, List<String>, String) -> Unit = { _, _, _ -> }
    var onReorderPhrase: (String, Int, Int) -> Unit = { _, _, _ -> }
    var onReorderCategory: (Int, Int) -> Unit = { _, _ -> }
    var onAddPhrase: (String) -> Unit = {}
    var onAddCategory: () -> Unit = {}
    var onAddCategoryThenAdd: (List<String>) -> Unit = {}
    var onAddCategoryThenMove: (String, List<String>) -> Unit = { _, _ -> }
    var onRenameCategory: (String) -> Unit = {}
    var onDeleteCategory: (String) -> Unit = {}
    var onEditNote: (String, String) -> Unit = { _, _ -> }
    var onClearCategory: (String) -> Unit = {}
    var onExportPhrases: () -> Unit = {}
    var onImportPhrases: () -> Unit = {}
    var onImportPhrasesWithMode: (Boolean) -> Unit = { onImportPhrases() }
    var onClearHistory: () -> Unit = {}
    var historyEnabledProvider: () -> Boolean = { true }
    var onSetHistoryEnabled: (Boolean) -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT
    private var GREEN = palette.candidateFirst
    private var RED = palette.onErrorContainer
    private var GREY_PILL = palette.chipBg
    private var SPLIT_BLOCK_BG = palette.accentBottom
    private var SPLIT_BLOCK_TEXT = palette.accentLabel
    private var SPLIT_BLOCK_COPIED_BG = palette.chipBg
    private var SPLIT_BLOCK_COPIED_TEXT = palette.chipText
    private var TEXT_DARK = palette.keyLabel
    private var TEXT_SECONDARY = palette.keyLabelSecondary
    private var HINT = palette.keyHint
    private var CARD = palette.keySurface
    private var BG = palette.keyboardBg
    private var SEP = palette.separator
    private val splitSymbol = "拆"
    private val moveSymbol = "移"

    fun applyPalette(p: ImePalette) {
        palette = p
        GREEN = p.candidateFirst; RED = p.onErrorContainer
        GREY_PILL = p.chipBg; SPLIT_BLOCK_BG = p.accentBottom; SPLIT_BLOCK_TEXT = p.accentLabel
        SPLIT_BLOCK_COPIED_BG = p.chipBg; SPLIT_BLOCK_COPIED_TEXT = p.chipText
        TEXT_DARK = p.keyLabel; TEXT_SECONDARY = p.keyLabelSecondary; HINT = p.keyHint; CARD = p.keySurface
        BG = p.keyboardBg; SEP = p.separator
        main.setBackgroundColor(BG)
        selectRowPool.clear(); sortRowPool.clear(); catSortRowPool.clear()
        refresh(animate = false)
    }

    private val st = ClipboardPanelState()
    private var phraseCat = ""
    private var swipeRevealed: String? = null
    private var pendingSwipeRefresh = false
    private var categoryScrollX = 0

    private var sortMode = false
    private var categorySortMode = false
    private val splitSelected = mutableSetOf<String>()
    private var renderedTab: ClipboardPanelState.Tab? = null
    private var tabTransitions = 0
    private var renderedMode = -1
    private var modeTransitions = 0
    private var pendingCategoryFade = false
    private var contentFades = 0

    fun showPhraseTab(category: String) {
        st.switchTab(ClipboardPanelState.Tab.PHRASE)
        st.collapse(); swipeRevealed = null; sortMode = false; categorySortMode = false
        if (category.isNotEmpty() && category in categoriesProvider()) phraseCat = category
        refresh(animate = false)
    }

    internal fun recreationState(): RecreationState = RecreationState(
        phrasesTab = st.tab == ClipboardPanelState.Tab.PHRASE,
        phraseCategory = phraseCat,
    )

    internal fun restoreRecreationState(state: RecreationState) {
        if (state.phrasesTab) showPhraseTab(state.phraseCategory) else refresh(animate = false)
    }

    private var dragFrom = -1
    private var dragCurrent = -1
    private var dragVisualIndex = -1
    private var dragTouchOffsetY = 0f
    private var dragLastRawY = 0f
    private var dragView: View? = null
    private enum class DragKind { NONE, PHRASE, CATEGORY }
    private var dragKind = DragKind.NONE
    private val dragHandler = Handler(Looper.getMainLooper())
    private val isDragging get() = dragFrom >= 0
    private var dragAutoScrollScheduled = false
    private val dragAutoScrollRunnable = object : Runnable {
        override fun run() {
            dragAutoScrollScheduled = false
            if (runDragAutoScrollFrame()) scheduleDragAutoScroll()
        }
    }

    private val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
    private val overlay = FrameLayout(context).apply { visibility = GONE }
    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
    private val listScroll = ScrollView(context).apply { addView(listColumn) }
    private val fixedChromeOriginalHeights = WeakHashMap<View, Int>()
    private val fixedDescendantOriginalHeights = WeakHashMap<View, Int>()
    private val fixedChromeOriginalPadding = WeakHashMap<View, IntArray>()
    private val fixedDescendantOriginalVisibility = WeakHashMap<View, Int>()
    private val fixedDescendantOriginalTextSize = WeakHashMap<TextView, Float>()
    private val fixedChromePreferredHeights = WeakHashMap<View, Int>()
    private val fixedChromeMinimumHeights = WeakHashMap<View, Int>()
    private var fixedChromePreferredWidth = -1
    private var fixedChromeCompressed: Boolean? = null
    private var listRenderGeneration = 0
    private var pendingListAppend: Runnable? = null
    private var selectAllAction: TextView? = null
    private var cancelSelectAction: TextView? = null

    private class SelectRowHolder(val row: LinearLayout, val radio: RadioGlyph, val label: TextView)
    private class TextRowHolder(val row: LinearLayout, val label: TextView, val handle: View)
    private val selectRowPool = ArrayList<SelectRowHolder>()
    private val sortRowPool = ArrayList<TextRowHolder>()
    private val catSortRowPool = ArrayList<TextRowHolder>()

    private inner class RadioGlyph : View(context) {
        private val paint = glyphPaint(TEXT_DARK)
        private var on = false
        fun bind(tint: Int, checked: Boolean) { paint.color = tint; on = checked; invalidate() }
        override fun onDraw(c: Canvas) = Glyphs.drawRadio(c, paint, width / 2f, height / 2f, dp(8).toFloat(), on)
    }

    private fun retintRow(v: View, color: Int) {
        val fg = v.foreground
        if (fg is RippleDrawable) fg.setColor(ColorStateList.valueOf(Motion.withAlpha(color, 0x24)))
        else Motion.applyTapFeedback(v, color)
    }

    internal fun selectRowsAllocatedForTest(): Int = selectRowPool.size
    internal fun sortRowsAllocatedForTest(): Int = sortRowPool.size
    internal fun catSortRowsAllocatedForTest(): Int = catSortRowPool.size

    private companion object {
        const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DISPLAY_CAP = 2000
        const val INITIAL_SYNC_ROWS = 12
        const val APPEND_ROWS_PER_FRAME = 12
        const val SWIPE_ACTION_SIZE_DP = 44
        const val SWIPE_ACTION_GAP_DP = 4
        const val COMPACT_ACTION_HEIGHT_DP = 36
        const val SWIPE_VERTICAL_BIAS = 1.5f
        const val DRAG_AUTO_SCROLL_INTERVAL_MS = 16L
        const val DRAG_AUTO_SCROLL_MIN_STEP_DP = 2
        const val DRAG_AUTO_SCROLL_MAX_STEP_DP = 8
    }

    private fun preview(s: String): CharSequence = if (s.length > DISPLAY_CAP) s.substring(0, DISPLAY_CAP) + "…" else s

    private fun ll(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    init {
        addView(main, FrameLayout.LayoutParams(MP, MP))
        addView(overlay, FrameLayout.LayoutParams(MP, MP))
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isDragging && ev.actionMasked != MotionEvent.ACTION_DOWN) return handleActiveDrag(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED && main.childCount > 0) {
            val available = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(0)
            val widthCap = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
            val fixed = (0 until main.childCount).map { main.getChildAt(it) }.filter { it !== listScroll }
            val preferredGeometryStale = fixedChromePreferredWidth != widthCap ||
                fixed.any { fixedChromePreferredHeights[it] == null }
            if (preferredGeometryStale) {

                if (fixedChromeCompressed == true) fixed.forEach(::restoreFixedChromeState)
                fixedChromePreferredHeights.clear()
                fixedChromeMinimumHeights.clear()
                for (child in fixed) {
                    val lp = child.layoutParams as LinearLayout.LayoutParams
                    val original = fixedChromeOriginalHeights.getOrPut(child) { lp.height }
                    val preferredHeight = if (original >= 0) original else {
                        child.measure(
                            MeasureSpec.makeMeasureSpec(widthCap, MeasureSpec.AT_MOST),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                        )
                        child.measuredHeight
                    }
                    fixedChromePreferredHeights[child] = preferredHeight
                }
                for (child in fixed) {
                    fixedChromeMinimumHeights[child] = minimumReadableHeight(child)
                        .coerceAtMost(fixedChromePreferredHeights[child] ?: 0)
                }
                fixedChromePreferredWidth = widthCap
            }
            val preferred = fixed.associateWith { fixedChromePreferredHeights[it] ?: 0 }
            val desiredFixed = preferred.values.sum().coerceAtLeast(0)
            val listReserve = minOf(dp(32), (available * 0.25f).roundToInt()).coerceAtMost(available)
            val fixedBudget = (available - listReserve).coerceAtLeast(0)
            val compressed = desiredFixed > fixedBudget
            val targetHeights = mutableMapOf<View, Int>()
            if (compressed) {
                val minima = fixed.associateWith { child -> fixedChromeMinimumHeights[child] ?: 0 }
                val minimumTotal = minima.values.sum()
                if (minimumTotal <= fixedBudget) {

                    val remaining = fixedBudget - minimumTotal
                    val slackTotal = fixed.sumOf { child ->
                        ((preferred[child] ?: 0) - (minima[child] ?: 0)).coerceAtLeast(0)
                    }
                    var assigned = 0
                    for ((index, child) in fixed.withIndex()) {
                        val minimum = minima[child] ?: 0
                        val extra = if (index == fixed.lastIndex) {
                            remaining - assigned
                        } else if (slackTotal > 0) {
                            (remaining * (((preferred[child] ?: 0) - minimum).coerceAtLeast(0)).toFloat() / slackTotal)
                                .roundToInt()
                                .coerceAtMost(remaining - assigned)
                        } else {
                            0
                        }
                        targetHeights[child] = minimum + extra
                        assigned += extra
                    }
                } else {

                    var assigned = 0
                    for ((index, child) in fixed.withIndex()) {
                        val target = if (index == fixed.lastIndex) {
                            fixedBudget - assigned
                        } else if (minimumTotal > 0) {
                            (fixedBudget * (minima[child] ?: 0).toFloat() / minimumTotal).roundToInt()
                                .coerceAtMost(fixedBudget - assigned)
                        } else {
                            0
                        }
                        targetHeights[child] = target
                        assigned += target
                    }
                }
            }
            if (!compressed && fixedChromeCompressed == true) fixed.forEach(::restoreFixedChromeState)
            for (child in fixed) {
                val lp = child.layoutParams as LinearLayout.LayoutParams
                val original = fixedChromeOriginalHeights[child] ?: lp.height
                val targetHeight = if (compressed) targetHeights[child] ?: 0 else original
                if (lp.height != targetHeight) lp.height = targetHeight
                if (compressed) {
                    adaptFixedChrome(
                        child,
                        targetHeight = if (targetHeight >= 0) targetHeight else preferred[child] ?: 0,
                    )
                }
            }
            fixedChromeCompressed = compressed
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun restoreFixedChromeState(root: View) {
        fixedChromeOriginalHeights[root]?.let { original ->
            root.layoutParams?.let { lp -> if (lp.height != original) lp.height = original }
        }
        fixedDescendantOriginalHeights[root]?.let { original ->
            root.layoutParams?.let { lp -> if (lp.height != original) lp.height = original }
        }
        fixedChromeOriginalPadding[root]?.let { original ->
            setPaddingIfChanged(root, original[0], original[1], original[2], original[3])
        }
        fixedDescendantOriginalVisibility[root]?.let { if (root.visibility != it) root.visibility = it }
        if (root is TextView) {
            fixedDescendantOriginalTextSize[root]?.let { original ->
                if (root.textSize != original) root.setTextSize(TypedValue.COMPLEX_UNIT_PX, original)
            }
        }
        val group = root as? ViewGroup ?: return
        for (i in 0 until group.childCount) restoreFixedChromeState(group.getChildAt(i))
    }

    private fun minimumReadableHeight(root: View): Int {
        if (root.visibility == GONE) return 0
        if (root is TextView && !root.text.isNullOrEmpty()) return root.lineHeight.coerceAtLeast(1)
        val group = root as? ViewGroup ?: return if (root.isClickable) dp(20) else 1
        val children = (0 until group.childCount).map { group.getChildAt(it) }.filter { it.visibility != GONE }
        if (children.isEmpty()) return if (root.isClickable) dp(20) else 1
        val childMinimum = children.maxOf(::minimumReadableHeight)

        return childMinimum
    }

    private fun setPaddingIfChanged(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (view.paddingLeft != left || view.paddingTop != top ||
            view.paddingRight != right || view.paddingBottom != bottom
        ) {
            view.setPadding(left, top, right, bottom)
        }
    }

    private fun adaptFixedChrome(root: View, targetHeight: Int) {
        val group = root as? ViewGroup ?: return
        fun scaledPadding(view: View, available: Int): Int {
            val originalPadding = fixedChromeOriginalPadding.getOrPut(view) {
                intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            }

            setPaddingIfChanged(view, originalPadding[0], 0, originalPadding[2], 0)
            return available
        }

        fun adaptDescendant(view: View, available: Int) {
            val lp = view.layoutParams ?: return
            val original = fixedDescendantOriginalHeights.getOrPut(view) { lp.height }
            val assigned = if (original > 0) minOf(original, available) else available
            if (lp.height != assigned) lp.height = assigned
            if (view is TextView) {
                val originalTextSize = fixedDescendantOriginalTextSize.getOrPut(view) { view.textSize }
                val targetTextSize = if (assigned > 0) {

                    val railScale = minOf(1f, assigned.toFloat() / dp(40).coerceAtLeast(1))
                    (originalTextSize * railScale).coerceAtLeast(1f)
                } else {
                    originalTextSize
                }
                if (view.textSize != targetTextSize) {
                    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, targetTextSize)
                }
            }
            (view as? ViewGroup)?.let { vg ->
                val actual = if (assigned >= 0) assigned else available
                val childContent = scaledPadding(view, actual.coerceAtLeast(0))
                val allChildren = (0 until vg.childCount).map { vg.getChildAt(it) }
                allChildren.forEach { child ->
                    fixedDescendantOriginalVisibility.getOrPut(child) { child.visibility }
                }
                val authoredVisible = allChildren.filter {
                    fixedDescendantOriginalVisibility[it] != GONE
                }
                if (vg is LinearLayout && vg.orientation == LinearLayout.VERTICAL && authoredVisible.isNotEmpty()) {
                    val requiredTextLines = authoredVisible.sumOf { child ->
                        (child as? TextView)?.lineHeight?.coerceAtLeast(1) ?: 1
                    }
                    if (authoredVisible.size > 1 && childContent < requiredTextLines) {

                        if (authoredVisible.first().visibility == GONE) authoredVisible.first().visibility = VISIBLE
                        authoredVisible.drop(1).forEach { if (it.visibility != GONE) it.visibility = GONE }
                        adaptDescendant(authoredVisible.first(), childContent)
                    } else {

                        authoredVisible.forEach { child ->
                            val authored = fixedDescendantOriginalVisibility[child] ?: VISIBLE
                            if (child.visibility != authored) child.visibility = authored
                        }
                        var remaining = childContent
                        for ((index, child) in authoredVisible.withIndex()) {
                            val share = if (index == authoredVisible.lastIndex) remaining else childContent / authoredVisible.size
                            adaptDescendant(child, share)
                            remaining = (remaining - share).coerceAtLeast(0)
                        }
                    }
                } else {
                    for (child in authoredVisible) adaptDescendant(child, childContent)
                }
            }
        }
        val contentHeight = scaledPadding(root, targetHeight.coerceAtLeast(0))
        for (i in 0 until group.childCount) adaptDescendant(group.getChildAt(i), contentHeight)
    }

    private fun handleActiveDrag(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateActiveDrag(e.rawY)
            MotionEvent.ACTION_UP -> endDrag()
            MotionEvent.ACTION_CANCEL -> cancelDrag()
        }
        return true
    }

    fun reset() {
        invalidateListRender()
        st.reset(); hideOverlayImmediately(); swipeRevealed = null; sortMode = false; categorySortMode = false
    }

    override fun resetToDefault() {
        reset()
        phraseCat = ""
        categoryScrollX = 0
        listScroll.scrollTo(0, 0)
    }

    internal fun isClipboardTabForTest(): Boolean = st.tab == ClipboardPanelState.Tab.CLIPBOARD
    internal fun phraseCatForTest(): String = phraseCat
    internal fun switchTabForTest(toClipboard: Boolean) {
        if (st.switchTab(if (toClipboard) ClipboardPanelState.Tab.CLIPBOARD else ClipboardPanelState.Tab.PHRASE)) swipeRevealed = null
        refresh()
    }
    internal fun forcePhrasesStateForTest(cat: String) { st.switchTab(ClipboardPanelState.Tab.PHRASE); swipeRevealed = null; phraseCat = cat }
    internal fun enterSelectForTest(selected: List<String> = emptyList()) { swipeRevealed = null; st.enterSelect(); st.selected.addAll(selected); refresh() }
    internal fun isSelectModeForTest(): Boolean = st.selectMode
    internal fun toggleSelectForTest(text: String) { st.toggleSelect(text); refresh() }
    internal fun exitSelectForTest() { exitSelect() }
    internal fun showMoveChooserForTest(current: String) { chooseMoveCategoryThen(current, emptyList()) { target -> onMovePhrase(current, "", target) } }
    internal fun dragStartForTest(index: Int) { if (categorySortMode) startCategoryDrag(index) else startDrag(index) }
    internal fun dragStartAtForTest(index: Int, rawY: Float) { if (categorySortMode) startCategoryDrag(index, rawY) else startDrag(index, rawY) }
    internal fun dragMoveToForTest(index: Int) { moveDragTo(index) }
    internal fun dragMoveAtForTest(index: Int, rawY: Float) { updateActiveDrag(rawY); moveDragTo(index, rawY) }
    internal fun dragDropForTest() { endDrag() }
    internal fun dragCancelForTest() { cancelDrag() }
    internal fun isDraggingForTest(): Boolean = isDragging
    internal fun dragTranslationYForTest(): Float = dragView?.translationY ?: 0f
    internal fun dragUpdateForTest(rawY: Float) { updateActiveDrag(rawY) }
    internal fun runDragAutoScrollFrameForTest(): Boolean = runDragAutoScrollFrame()
    internal fun isDragAutoScrollScheduledForTest(): Boolean = dragAutoScrollScheduled
    internal fun listScrollYForTest(): Int = listScroll.scrollY
    internal fun listRowViewForTest(index: Int): View? = listColumn.getChildAt(index)
    internal fun listRowCountForTest(): Int = listColumn.childCount
    internal fun initialSyncRowsForTest(): Int = INITIAL_SYNC_ROWS
    internal fun runPendingListAppendForTest(): Boolean {
        val r = pendingListAppend ?: return false
        removeCallbacks(r)
        pendingListAppend = null
        r.run()
        return true
    }
    internal fun disabledActionTextColorForTest(): Int = TEXT_DARK
    internal fun disabledActionBackgroundColorForTest(): Int = CARD
    internal fun selectAllActionForTest(): TextView? = selectAllAction
    internal fun cancelSelectActionForTest(): TextView? = cancelSelectAction
    internal fun listScrollRawTopForTest(): Int {
        val loc = IntArray(2)
        listScroll.getLocationOnScreen(loc)
        return loc[1]
    }
    internal fun listScrollRawBottomForTest(): Int = listScrollRawTopForTest() + listScroll.height
    internal fun fixedChromeViewsForTest(): List<View> =
        (0 until main.childCount).map { main.getChildAt(it) }.filter { it !== listScroll }
    internal fun listViewportForTest(): View = listScroll
    internal fun expandForTest(text: String) { swipeRevealed = null; if (st.expanded != text) st.toggleExpand(text); refresh() }
    internal fun revealSwipeForTest(text: String) { revealSwipe(text) }
    internal fun hideSwipeForTest() { hideSwipe() }
    internal fun swipeRevealedForTest(): String? = swipeRevealed
    internal fun showPhraseManageMenuForTest() { showPhraseManageMenu() }
    internal fun showHistoryRecordingMenuForTest() { showHistoryRecordingMenu() }
    internal fun confirmClearForTest() { confirmClearCurrentCategory() }
    internal fun confirmClearHistoryForTest() { confirmClearHistory() }
    internal fun enterSortModeForTest() { enterSortMode() }
    internal fun isSortModeForTest(): Boolean = sortMode
    internal fun enterCategorySortModeForTest() { enterCategorySortMode() }
    internal fun isCategorySortModeForTest(): Boolean = categorySortMode
    internal fun showSplitForTest(text: String) { showSplit(text) }
    internal fun splitSelectedForTest(): Set<String> = splitSelected.toSet()
    internal fun settleSwipeForTest(dxPx: Float, text: String) { settleSwipe(dxPx, text) }
    internal fun listRowTextsForTest(): List<String> {
        val out = ArrayList<String>()
        fun firstText(v: View): String? {
            if (v is TextView) return v.text?.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) firstText(v.getChildAt(i))?.let { return it }
            return null
        }
        for (i in 0 until listColumn.childCount) firstText(listColumn.getChildAt(i))?.let { out.add(it) }
        return dragPreviewOrder(out)
    }

    private fun <T> dragPreviewOrder(items: List<T>): List<T> {
        if (!isDragging || dragFrom !in items.indices || dragCurrent !in items.indices || dragFrom == dragCurrent) return items
        return items.toMutableList().apply {
            val item = removeAt(dragFrom)
            add(dragCurrent.coerceIn(0, size), item)
        }
    }

    fun refresh() = refresh(animate = true)

    private fun refresh(animate: Boolean) {
        val tabChanged = renderedTab != null && renderedTab != st.tab
        val mode = currentRenderMode()
        val modeChanged = renderedMode != -1 && renderedMode != mode
        val categoryChanged = pendingCategoryFade
        pendingCategoryFade = false
        renderedTab = st.tab
        renderedMode = mode
        if (tabChanged) tabTransitions++
        if (modeChanged) modeTransitions++
        if (animate && (tabChanged || modeChanged || categoryChanged) && main.isShown) {
            contentFades++
            Motion.coverThrough(listScroll, BG) { rebuildContent() }
        } else {
            rebuildContent()
        }
    }

    private fun rebuildContent() {
        pendingSwipeRefresh = false
        invalidateListRender()
        fixedChromeOriginalHeights.clear()
        fixedDescendantOriginalHeights.clear()
        fixedChromeOriginalPadding.clear()
        fixedDescendantOriginalVisibility.clear()
        fixedDescendantOriginalTextSize.clear()
        fixedChromePreferredHeights.clear()
        fixedChromeMinimumHeights.clear()
        fixedChromePreferredWidth = -1
        fixedChromeCompressed = null
        selectAllAction = null
        cancelSelectAction = null
        main.removeAllViews()
        when {
            st.selectMode -> buildSelectMode()
            categorySortMode -> buildCategorySortMode()
            sortMode -> buildSortMode()
            else -> buildNormal()
        }
    }

    private fun currentRenderMode(): Int = when {
        st.selectMode -> 1
        categorySortMode -> 3
        sortMode -> 2
        else -> 0
    }

    private fun batchManagementTitle(): String = context.getString(
        if (st.tab == Tab.PHRASE) R.string.clip_edit_phrases else R.string.clip_edit_clipboard,
    )

    internal fun tabTransitionsForTest(): Int = tabTransitions
    internal fun modeTransitionsForTest(): Int = modeTransitions
    internal fun contentFadesForTest(): Int = contentFades
    internal fun overlayVisibleForTest(): Boolean = overlay.visibility == VISIBLE
    internal fun hideOverlayForTest() = hideOverlay()
    internal fun selectPhraseCategoryForTest(name: String) = selectPhraseCategory(name)

    private fun cancelPendingListAppend() {
        pendingListAppend?.let { removeCallbacks(it) }
        pendingListAppend = null
    }

    private fun invalidateListRender() {
        cancelPendingListAppend()
        listRenderGeneration++
    }

    private fun populateListRows(entries: List<String>, row: (String, Int) -> View) {
        listColumn.removeAllViews()
        if (entries.isEmpty()) {
            listColumn.addView(emptyHint())
            return
        }
        val firstEnd = min(entries.size, INITIAL_SYNC_ROWS)
        appendListRows(entries, 0, firstEnd, row)
        if (firstEnd < entries.size) scheduleListAppend(entries, firstEnd, listRenderGeneration, row)
    }

    private fun appendListRows(entries: List<String>, start: Int, end: Int, row: (String, Int) -> View) {
        for (i in start until end) listColumn.addView(row(entries[i], i))
    }

    private fun scheduleListAppend(entries: List<String>, start: Int, generation: Int, row: (String, Int) -> View) {
        val r = Runnable {
            if (generation != listRenderGeneration) return@Runnable
            pendingListAppend = null
            val end = min(entries.size, start + APPEND_ROWS_PER_FRAME)
            appendListRows(entries, start, end, row)
            if (end < entries.size) scheduleListAppend(entries, end, generation, row)
        }
        pendingListAppend = r
        postOnAnimation(r)
    }


    private fun buildNormal() {
        val categories = if (st.tab == Tab.PHRASE) categoriesProvider() else emptyList()
        val category = if (st.tab == Tab.PHRASE) currentCategory(categories) else ""
        val entries = if (st.tab == Tab.CLIPBOARD) historyProvider() else phrasesInProvider(category)
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            fun iconLp(spaced: Boolean = false) = ll(dp(36), dp(36)).apply { if (spaced) marginStart = dp(6) }
            addView(glyphToolbarBtn(desc = context.getString(R.string.clip_back), glyphSizeDp = 8, onClick = { onBack() }) { c, p, x, y, s -> Glyphs.drawBack(c, p, x, y, s) }, iconLp())
            addView(View(context), ll(0, dp(1), 1f))
            addView(pillTray(), ll(WC, dp(34)))
            if (st.tab == Tab.PHRASE) addView(glyphToolbarBtn(desc = context.getString(R.string.clip_add_phrase), onClick = { onAddPhrase(category) }) { c, p, x, y, s -> Glyphs.drawPlus(c, p, x, y, s) }.apply {
                background = rounded(CARD, ImeShapes.toolbarFeedbackRadiusDp)
            }, iconLp(true))
            else addView(View(context), iconLp(true))
            addView(glyphToolbarBtn(desc = batchManagementTitle(), onClick = { enterSelect() }) { c, p, x, y, s -> Glyphs.drawList(c, p, x, y, s) }.apply {
                background = rounded(CARD, ImeShapes.toolbarFeedbackRadiusDp)
            }, iconLp(true))
            if (st.tab == Tab.PHRASE) addView(glyphToolbarBtn(desc = context.getString(R.string.clip_clear_category), tint = TEXT_DARK, onClick = { confirmClearCurrentCategory() }) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }.apply {
                background = rounded(CARD, ImeShapes.toolbarFeedbackRadiusDp)
            }, iconLp(true))
            else addView(glyphToolbarBtn(desc = context.getString(R.string.clip_clear_history), tint = TEXT_DARK, onClick = { confirmClearHistory() }) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }.apply {
                background = rounded(CARD, ImeShapes.toolbarFeedbackRadiusDp)
                setOnLongClickListener { showHistoryRecordingMenu(); true }
            }, iconLp(true))
        }
        main.addView(topBar, ll(MP, dp(50)))
        populateListRows(entries) { e, i -> card(e, i, category) }
        main.addView(listScroll, ll(MP, 0, 1f))

        if (st.tab == Tab.PHRASE) main.addView(categoryBar(categories, category), ll(MP, dp(44)).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
        })
    }

    private fun phraseDisplayText(category: String, text: String): String {
        val note = phraseNoteProvider(category, text)
        return if (note.isNotEmpty()) note else text
    }

    private fun card(text: String, index: Int, category: String): View {
        val expanded = st.expanded == text
        val phrase = st.tab == Tab.PHRASE
        val display = if (phrase) phraseDisplayText(category, text) else text
        val revealWidthDp = swipeRevealWidthDp(phrase)
        lateinit var header: LinearLayout
        val headerFrame = object : FrameLayout(context) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                super.onLayout(changed, left, top, right, bottom)
                header.translationX = if (!expanded && swipeRevealed == text) -swipeRevealPx(this, revealWidthDp) else 0f
            }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
        }
        val surface = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (expanded) background = rounded(CARD, ImeShapes.cardRadiusDp)
        }
        header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (!expanded) background = rounded(CARD, ImeShapes.cardRadiusDp)
        }
        val body = TextView(context).apply {
            this.text = preview(display)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTextColor(TEXT_DARK)
            setPadding(dp(14), dp(12), dp(8), dp(12))
            Motion.applyTapFeedback(this, TEXT_DARK)
            setOnClickListener {
                when {
                    swipeRevealed == text -> hideSwipe(text)
                    st.expanded == text -> { st.collapse(); refresh() }
                    else -> onPick(text)
                }
            }
            if (!phrase) setOnLongClickListener { showLongPressMenu(text); true }
        }
        val chevron = glyphView(TEXT_DARK, 7) { c, p, x, y, s -> Glyphs.drawChevron(c, p, x, y, s, down = !expanded) }.apply {
            contentDescription = if (expanded) context.getString(R.string.clip_collapse) else context.getString(R.string.clip_expand)
            Motion.applyTapFeedback(this, TEXT_DARK)
            setOnClickListener { swipeRevealed = null; st.toggleExpand(text); refresh() }
            if (!phrase) setOnLongClickListener { showLongPressMenu(text); true }
        }
        header.addView(body, ll(0, WC, 1f))
        header.addView(chevron, ll(dp(40), MP))
        if (!expanded) {
            headerFrame.addView(
                swipeActionStrip(text, category, phrase, header, headerFrame, revealWidthDp),
                FrameLayout.LayoutParams(dp(revealWidthDp), MP, Gravity.RIGHT),
            )
        }
        headerFrame.addView(header, FrameLayout.LayoutParams(MP, WC))
        surface.addView(headerFrame, ll(MP, WC))
        if (expanded) surface.addView(if (phrase) phraseActionRow(text, category) else actionRow(text), ll(MP, WC))
        column.addView(surface, ll(MP, WC))
        if (expanded) {
            attachSwipeReveal(chevron, text)
            attachSwipeReveal(body, text)
        } else {
            attachSwipeReveal(chevron, text, header, headerFrame, revealWidthDp)
            if (phrase) attachDragHandle(body, column, index, text, header, headerFrame, revealWidthDp)
            else attachSwipeReveal(body, text, header, headerFrame, revealWidthDp)
        }
        return column
    }

    private fun swipeRevealWidthDp(phrase: Boolean): Int {
        val count = if (phrase) 4 else 3
        return count * (SWIPE_ACTION_SIZE_DP + SWIPE_ACTION_GAP_DP)
    }

    private fun swipeActionStrip(text: String, category: String, phrase: Boolean, header: View, frame: View, revealWidthDp: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
        fun addSwipeAction(action: View, asset: Any? = null) {
            action.apply {
                background = rounded(CARD, ImeShapes.toolbarFeedbackRadiusDp)
                if (asset != null) tag = asset
            }
            addView(action, ll(dp(SWIPE_ACTION_SIZE_DP), dp(SWIPE_ACTION_SIZE_DP)).apply {
                marginStart = dp(SWIPE_ACTION_GAP_DP)
            })
            attachSwipeReveal(action, text, header, frame, revealWidthDp)
        }
        fun addGlyphSwipeAction(desc: String, onClick: () -> Unit, render: (Canvas, Paint, Float, Float, Float) -> Unit) {
            addSwipeAction(glyphToolbarBtn(desc, onClick = onClick, render = render))
        }
        fun addCharSwipeAction(desc: String, symbol: String, onClick: () -> Unit) {
            addSwipeAction(charToolbarBtn(desc, symbol, onClick = onClick), symbol)
        }
        if (phrase) {
            addGlyphSwipeAction(context.getString(R.string.clip_edit), { onEditPhrase(category, text) }) { c, p, x, y, s -> Glyphs.drawEditSquare(c, p, x, y, s) }
            addGlyphSwipeAction(context.getString(R.string.clip_note), { onEditNote(category, text) }) { c, p, x, y, s -> Glyphs.drawTag(c, p, x, y, s) }
            addCharSwipeAction(context.getString(R.string.clip_move), moveSymbol) {
                chooseMoveCategoryThen(category, listOf(text)) { target -> onMovePhrase(category, text, target); refresh() }
            }
            addGlyphSwipeAction(context.getString(R.string.clip_delete), { confirmDelete(listOf(text)) }) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }
        } else {
            addGlyphSwipeAction(context.getString(R.string.clip_add_phrase), { chooseCategoryThen(listOf(text)) }) { c, p, x, y, s -> Glyphs.drawPlus(c, p, x, y, s) }
            addCharSwipeAction(context.getString(R.string.clip_split_word), splitSymbol) { showSplit(text) }
            addGlyphSwipeAction(context.getString(R.string.clip_delete), { confirmDelete(listOf(text)) }) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }
        }
    }

    private fun actionRow(text: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(8))
        addActionButton(glyphAction(context.getString(R.string.clip_phrases), render = { c, p, x, y, s -> Glyphs.drawPlus(c, p, x, y, s) }) { chooseCategoryThen(listOf(text)) })
        addActionButton(charAction(splitSymbol, context.getString(R.string.clip_split_word)) { showSplit(text) }.apply { tag = splitSymbol })
        addActionButton(glyphAction(context.getString(R.string.clip_delete), render = { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }) { confirmDelete(listOf(text)) })
    }

    private fun phraseActionRow(text: String, category: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(8))
        addActionButton(glyphAction(context.getString(R.string.clip_edit), render = { c, p, x, y, s -> Glyphs.drawEditSquare(c, p, x, y, s) }) { onEditPhrase(category, text) })
        addActionButton(glyphAction(context.getString(R.string.clip_note), render = { c, p, x, y, s -> Glyphs.drawTag(c, p, x, y, s) }) { onEditNote(category, text) })
        addActionButton(charAction(moveSymbol, context.getString(R.string.clip_move)) { chooseMoveCategoryThen(category, listOf(text)) { target -> onMovePhrase(category, text, target); refresh() } }.apply { tag = moveSymbol })
        addActionButton(glyphAction(context.getString(R.string.clip_delete), render = { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }) { confirmDelete(listOf(text)) })
    }

    private fun LinearLayout.addActionButton(action: View) {
        addView(action, ll(WC, dp(34)).apply { if (childCount > 0) marginStart = dp(4) })
    }


    private fun attachDragHandle(touchTarget: View, card: View, index: Int, text: String, header: View, frame: View, revealWidthDp: Int) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var mode = 0
        var startTx = 0f
        var revealPx = 0f
        val longPress = Runnable { startDrag(index, downY); requestDragCapture() }
        touchTarget.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; mode = 0
                    revealPx = swipeRevealPx(frame, revealWidthDp)
                    startTx = if (swipeRevealed == text) -revealPx else 0f
                    if (swipeRevealed != text) dragHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        updateActiveDrag(e.rawY)
                        true
                    } else {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                            dragHandler.removeCallbacks(longPress)
                            mode = if (abs(dy) > abs(dx) * SWIPE_VERTICAL_BIAS) 2 else 1
                            if (mode == 1) {
                                card.parent?.requestDisallowInterceptTouchEvent(true)
                                header.animate().cancel()
                            }
                        }
                        if (mode == 1) header.translationX = (startTx + dx).coerceIn(-revealPx, 0f)
                        mode == 1
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragHandler.removeCallbacks(longPress)
                    when {
                        isDragging && e.actionMasked == MotionEvent.ACTION_UP -> { endDrag(); true }
                        isDragging -> { cancelDrag(); true }
                        mode == 1 && e.actionMasked == MotionEvent.ACTION_UP -> { settleSwipe(header, revealPx, text, header.translationX < -revealPx / 2f); true }
                        mode == 1 -> { settleSwipe(header, revealPx, text, startTx != 0f); true }
                        else -> false
                    }
                }
                else -> false
            }
        }
    }

    private fun attachSwipeReveal(target: View, text: String, header: View, frame: View, revealWidthDp: Int) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var mode = 0
        var startTx = 0f
        var revealPx = 0f
        target.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; mode = 0
                    revealPx = swipeRevealPx(frame, revealWidthDp)
                    startTx = if (swipeRevealed == text) -revealPx else 0f
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                        mode = if (abs(dy) > abs(dx) * SWIPE_VERTICAL_BIAS) 2 else 1
                        if (mode == 1) {
                            target.cancelLongPress()
                            target.parent?.requestDisallowInterceptTouchEvent(true)
                            header.animate().cancel()
                        }
                    }
                    if (mode == 1) header.translationX = (startTx + dx).coerceIn(-revealPx, 0f)
                    mode == 1
                }
                MotionEvent.ACTION_UP -> { if (mode == 1) { settleSwipe(header, revealPx, text, header.translationX < -revealPx / 2f); true } else false }
                MotionEvent.ACTION_CANCEL -> { if (mode == 1) { settleSwipe(header, revealPx, text, startTx != 0f); true } else false }
                else -> false
            }
        }
    }

    private fun attachSwipeReveal(target: View, text: String) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var mode = 0
        target.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; mode = 0; false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                        mode = if (abs(dy) > abs(dx) * SWIPE_VERTICAL_BIAS) 2 else 1
                        if (mode == 1) { target.cancelLongPress(); target.parent?.requestDisallowInterceptTouchEvent(true) }
                    }
                    mode == 1
                }
                MotionEvent.ACTION_UP -> { if (mode == 1) { settleSwipe(e.rawX - downX, text); true } else false }
                else -> false
            }
        }
    }

    private fun swipeRevealPx(frame: View, revealWidthDp: Int): Float {
        val full = dp(revealWidthDp)
        return (if (frame.width > 0) minOf(full, frame.width) else full).toFloat()
    }

    private fun settleSwipe(header: View, revealPx: Float, text: String, reveal: Boolean) {
        if (reveal) {
            if (st.expanded != null || (swipeRevealed != null && swipeRevealed != text)) pendingSwipeRefresh = true
            st.collapse()
            swipeRevealed = text
        } else if (swipeRevealed == text) {
            swipeRevealed = null
        }
        val target = if (reveal) -revealPx else 0f
        header.animate().cancel()
        if (!header.isAttachedToWindow || !Motion.enabled()) {
            header.translationX = target
            flushPendingSwipeRefresh()
            return
        }
        header.animate()
            .translationX(target)
            .setDuration(Motion.SHORT2)
            .setInterpolator(Motion.STANDARD_DECEL)
            .withEndAction { flushPendingSwipeRefresh() }
            .start()
    }

    private fun flushPendingSwipeRefresh() {
        if (!pendingSwipeRefresh) return
        pendingSwipeRefresh = false
        refresh()
    }

    private fun settleSwipe(dx: Float, text: String) {
        if (dx < 0f) revealSwipe(text) else hideSwipe(text)
    }

    private fun revealSwipe(text: String) {
        st.collapse()
        if (swipeRevealed == text) return
        swipeRevealed = text
        refresh()
    }

    private fun hideSwipe(text: String? = null) {
        val shown = swipeRevealed ?: return
        if (text == null || shown == text) {
            swipeRevealed = null
            refresh()
        }
    }

    private fun indexAtRawY(rawY: Float): Int? {
        val n = listColumn.childCount
        if (n == 0) return null
        val contentTop = listContentRawTop()
        var target = (n - 1).coerceAtLeast(0)
        for (i in 0 until n) {
            if (i == dragFrom) continue
            val child = listColumn.getChildAt(i)
            if (child.height <= 0) return null
            val center = contentTop + child.top + child.height / 2f
            if (rawY < center) {
                target = if (i < dragFrom) i else i - 1
                break
            }
        }
        return target.coerceIn(0, n - 1)
    }

    private fun listContentRawTop(): Int {
        val loc = IntArray(2)
        listScroll.getLocationOnScreen(loc)
        return loc[1] + listColumn.top - listScroll.scrollY
    }

    internal fun rowAt(tops: IntArray, heights: IntArray, skip: Int, y: Int): Int? {
        for (i in tops.indices) {
            if (i == skip) continue
            if (y >= tops[i] && y <= tops[i] + heights[i]) return i
        }
        return null
    }

    private fun updateActiveDrag(rawY: Float) {
        if (!isDragging) return
        requestDragCapture()
        updateDraggedTranslation(rawY)
        indexAtRawY(rawY)?.let { moveDragTo(it, rawY) }
        updateDragAutoScroll()
    }

    private fun updateDragAutoScroll() {
        if (dragAutoScrollDelta(dragLastRawY) == 0) stopDragAutoScroll() else scheduleDragAutoScroll()
    }

    private fun scheduleDragAutoScroll() {
        if (dragAutoScrollScheduled || !isDragging) return
        dragAutoScrollScheduled = true
        dragHandler.postDelayed(dragAutoScrollRunnable, DRAG_AUTO_SCROLL_INTERVAL_MS)
    }

    private fun stopDragAutoScroll() {
        dragAutoScrollScheduled = false
        dragHandler.removeCallbacks(dragAutoScrollRunnable)
    }

    private fun runDragAutoScrollFrame(): Boolean {
        if (!isDragging) {
            stopDragAutoScroll()
            return false
        }
        requestDragCapture()
        val dy = dragAutoScrollDelta(dragLastRawY)
        if (dy == 0) {
            stopDragAutoScroll()
            return false
        }
        val before = listScroll.scrollY
        listScroll.scrollBy(0, dy)
        updateDraggedTranslation(dragLastRawY)
        indexAtRawY(dragLastRawY)?.let { moveDragTo(it, dragLastRawY) }
        return listScroll.scrollY != before && dragAutoScrollDelta(dragLastRawY) != 0
    }

    private fun dragAutoScrollDelta(rawY: Float): Int {
        val h = listScroll.height
        if (h <= 0) return 0
        val loc = IntArray(2)
        listScroll.getLocationOnScreen(loc)
        val top = loc[1].toFloat()
        val bottom = top + h
        val edge = min(dp(48), h / 3).coerceAtLeast(1)
        return when {
            rawY <= top + edge && listScroll.canScrollVertically(-1) -> -dragAutoScrollStep(top + edge - rawY, edge)
            rawY >= bottom - edge && listScroll.canScrollVertically(1) -> dragAutoScrollStep(rawY - (bottom - edge), edge)
            else -> 0
        }
    }

    private fun dragAutoScrollStep(distanceIntoEdge: Float, edge: Int): Int {
        val minStep = dp(DRAG_AUTO_SCROLL_MIN_STEP_DP).coerceAtLeast(1)
        val maxStep = dp(DRAG_AUTO_SCROLL_MAX_STEP_DP).coerceAtLeast(minStep)
        val ratio = (distanceIntoEdge.coerceIn(0f, edge.toFloat()) / edge)
        return minStep + ((maxStep - minStep) * ratio).toInt()
    }

    private fun requestDragCapture() {
        dragView?.parent?.requestDisallowInterceptTouchEvent(true)
        listColumn.requestDisallowInterceptTouchEvent(true)
        listScroll.requestDisallowInterceptTouchEvent(true)
        requestDisallowInterceptTouchEvent(true)
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun startDrag(index: Int, rawY: Float? = null) {
        dragKind = DragKind.PHRASE
        dragFrom = index; dragCurrent = index
        dragVisualIndex = index
        dragView = listColumn.getChildAt(index)?.also {
            rawY?.let { y ->
                val loc = IntArray(2)
                it.getLocationOnScreen(loc)
                dragTouchOffsetY = y - loc[1]
            }
            it.translationZ = dp(8).toFloat(); it.alpha = 0.92f
            requestDragCapture()
        }
        rawY?.let { y -> updateDraggedTranslation(y); updateDragAutoScroll() }
    }

    private fun startCategoryDrag(index: Int, rawY: Float? = null) {
        dragKind = DragKind.CATEGORY
        dragFrom = index; dragCurrent = index
        dragVisualIndex = index
        dragView = listColumn.getChildAt(index)?.also {
            rawY?.let { y ->
                val loc = IntArray(2)
                it.getLocationOnScreen(loc)
                dragTouchOffsetY = y - loc[1]
            }
            it.translationZ = dp(8).toFloat(); it.alpha = 0.92f
            requestDragCapture()
        }
        rawY?.let { y -> updateDraggedTranslation(y); updateDragAutoScroll() }
    }

    private fun moveDragTo(index: Int, rawY: Float? = null) {
        val n = if (dragKind == DragKind.CATEGORY) categoriesProvider().size else currentEntries().size
        if (index !in 0 until n) return
        val old = dragCurrent
        dragCurrent = index
        dragVisualIndex = index
        if (dragView != null && index != old) {
            updateDragPreviewTranslations()
            rawY?.let { updateDraggedTranslation(it) }
        }
    }

    private fun updateDragPreviewTranslations() {
        val from = dragFrom
        val to = dragCurrent
        if (from !in 0 until listColumn.childCount || to !in 0 until listColumn.childCount) {
            resetDragPreviewTranslations()
            return
        }
        val lifted = dragView
        for (i in 0 until listColumn.childCount) {
            val child = listColumn.getChildAt(i)
            if (child === lifted) continue
            val targetTop = when {
                from < to && i in (from + 1)..to -> listColumn.getChildAt(i - 1).top
                to < from && i in to until from -> listColumn.getChildAt(i + 1).top
                else -> child.top
            }
            child.translationY = (targetTop - child.top).toFloat()
        }
        listColumn.invalidate()
    }

    private fun resetDragPreviewTranslations() {
        for (i in 0 until listColumn.childCount) listColumn.getChildAt(i).translationY = 0f
    }

    private fun updateDraggedTranslation(rawY: Float) {
        val view = dragView ?: return
        dragLastRawY = rawY
        val baseTop = listContentRawTop() + view.top
        view.translationY = rawY - dragTouchOffsetY - baseTop
    }

    private fun endDrag() {
        stopDragAutoScroll()
        val from = dragFrom; val to = dragCurrent
        val kind = dragKind
        resetDragState()
        if (from >= 0 && to >= 0 && from != to) {
            if (kind == DragKind.CATEGORY) onReorderCategory(from, to) else onReorderPhrase(currentCategory(), from, to)
            refresh()
        }
        else refresh()
    }

    private fun cancelDrag() {
        stopDragAutoScroll()
        resetDragState()
        refresh()
    }

    private fun resetDragState() {
        resetDragPreviewTranslations()
        dragView?.let { it.translationZ = 0f; it.alpha = 1f; it.translationY = 0f }
        dragFrom = -1; dragCurrent = -1; dragVisualIndex = -1; dragTouchOffsetY = 0f; dragLastRawY = 0f; dragView = null; dragKind = DragKind.NONE
    }

    override fun onDetachedFromWindow() {
        dragHandler.removeCallbacksAndMessages(null)
        cancelPendingListAppend()
        resetDragPreviewTranslations()
        dragView?.let { it.translationZ = 0f; it.alpha = 1f }
        dragAutoScrollScheduled = false
        dragFrom = -1; dragCurrent = -1; dragVisualIndex = -1; dragTouchOffsetY = 0f; dragLastRawY = 0f; dragView = null; dragKind = DragKind.NONE
        super.onDetachedFromWindow()
    }


    private fun enterSortMode() { st.collapse(); swipeRevealed = null; categorySortMode = false; sortMode = true; refresh() }
    private fun exitSortMode() { sortMode = false; refresh() }
    private fun enterCategorySortMode() { st.collapse(); swipeRevealed = null; sortMode = false; categorySortMode = true; refresh() }
    private fun exitCategorySortMode() { categorySortMode = false; refresh() }

    private fun buildSortMode() {
        val cat = currentCategory(categoriesProvider())
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(TextView(context).apply {
                text = context.getString(R.string.clip_drag_sort)
                setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }, ll(0, WC, 1f))
            addView(TextView(context).apply {
                text = if (cat.isEmpty()) "" else cat
                gravity = Gravity.CENTER; setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
            }, ll(0, WC, 1f))
            addView(TextView(context).apply {
                text = context.getString(R.string.clip_done); gravity = Gravity.END
                setTextColor(GREEN); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                Motion.applyTapFeedback(this, GREEN)
                setOnClickListener { exitSortMode() }
            }, ll(0, WC, 1f))
        }
        main.addView(topBar, ll(MP, WC))

        val entries = phrasesInProvider(cat)
        populateListRows(entries) { e, i -> sortRowFor(e, i, cat) }
        main.addView(listScroll, ll(MP, 0, 1f))
    }

    private fun sortRowFor(text: String, index: Int, category: String): View {
        val h = if (index < sortRowPool.size) sortRowPool[index] else buildTextRow(sortRowPool, maxLines = 2)
        Motion.reset(h.row)
        h.label.text = preview(phraseDisplayText(category, text))
        attachSortDrag(h.handle, h.row, index)
        return h.row
    }

    private fun buildTextRow(pool: ArrayList<TextRowHolder>, maxLines: Int, physicalLtr: Boolean = false): TextRowHolder {
        val label = TextView(context).apply {
            this.maxLines = maxLines; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body); setTextColor(TEXT_DARK)
            setPadding(dp(14), dp(12), dp(8), dp(12))
        }
        val handle = glyphView(TEXT_DARK, 9) { c, p, x, y, s -> Glyphs.drawList(c, p, x, y, s) }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            if (physicalLtr) layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, ImeShapes.cardRadiusDp)
            layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
            addView(label, ll(0, WC, 1f))
            addView(handle, ll(dp(44), MP))
        }
        return TextRowHolder(row, label, handle).also { pool.add(it) }
    }

    private fun attachSortDrag(handle: View, card: View, index: Int) {
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startDrag(index, e.rawY); requestDragCapture(); true }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) { updateActiveDrag(e.rawY); true } else false
                }
                MotionEvent.ACTION_UP -> { if (isDragging) { endDrag(); true } else false }
                MotionEvent.ACTION_CANCEL -> { if (isDragging) { cancelDrag(); true } else false }
                else -> false
            }
        }
    }

    private fun buildCategorySortMode() {
        val cats = categoriesProvider()
        val current = currentCategory(cats)
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(4), dp(8), dp(4))
            addView(TextView(context).apply {
                text = context.getString(R.string.clip_drag_category)
                setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }, ll(WC, WC))
            addView(View(context), ll(0, dp(1), 1f))
            addView(TextView(context).apply {
                text = context.getString(R.string.clip_done); gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setPadding(dp(12), 0, dp(14), 0)
                background = rounded(CARD, ImeShapes.toolbarFeedbackRadiusDp)
                Motion.applyTapFeedback(this, TEXT_DARK, radiusDp = ImeShapes.toolbarFeedbackRadiusDp)
                setOnClickListener { exitCategorySortMode() }
            }, ll(WC, dp(COMPACT_ACTION_HEIGHT_DP)))
        }
        main.addView(topBar, ll(MP, WC))

        populateListRows(cats) { name, i -> catSortRowFor(name, i, current) }
        main.addView(listScroll, ll(MP, 0, 1f))
    }

    private fun catSortRowFor(name: String, index: Int, current: String): View {
        val h = if (index < catSortRowPool.size) catSortRowPool[index] else buildTextRow(catSortRowPool, maxLines = 1, physicalLtr = true)
        Motion.reset(h.row)
        h.label.text = displayCat(name)
        h.label.setTypeface(null, if (name == current) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        attachCategorySortDrag(h.handle, h.row, index)
        return h.row
    }

    private fun attachCategorySortDrag(handle: View, card: View, index: Int) {
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startCategoryDrag(index, e.rawY); requestDragCapture(); true }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) { updateActiveDrag(e.rawY); true } else false
                }
                MotionEvent.ACTION_UP -> { if (isDragging) { endDrag(); true } else false }
                MotionEvent.ACTION_CANCEL -> { if (isDragging) { cancelDrag(); true } else false }
                else -> false
            }
        }
    }

    private fun categoryBar(categories: List<String>, current: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
        background = rounded(CARD, ImeShapes.toolbarPillRadiusDp)
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (name in categories) chips.addView(catChip(name, name == current))
        addView(object : HorizontalScrollView(context) {
            override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
                super.onScrollChanged(left, top, oldLeft, oldTop)
                categoryScrollX = left
            }

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                val saved = categoryScrollX
                super.onLayout(changed, left, top, right, bottom)
                if (scrollX != saved) scrollTo(saved, 0)
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, ll(0, dp(34), 1f))
        addView(TextView(context).apply {
            text = context.getString(R.string.clip_edit)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTextColor(TEXT_DARK)
            contentDescription = context.getString(R.string.clip_manage_phrases)
            Motion.applyTapFeedback(this, TEXT_DARK)
            setOnClickListener { showPhraseManageMenu() }
        }, ll(dp(48), dp(40)))
    }

    private fun showPhraseManageMenu() {
        val card = menuCard()
        card.addView(menuItem(context.getString(R.string.clip_move_category)) { hideOverlay(); enterCategorySortMode() })
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_add_category)) { hideOverlay(); onAddCategory() })
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_import_phrases)) { showImportConfirm() })
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_export_phrases)) { hideOverlay(); onExportPhrases() })
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_cancel)) { hideOverlay() })
        showActionPopup(card)
    }

    private fun showImportConfirm() {
        val card = menuCard()
        card.addView(menuTitle(context.getString(R.string.clip_import_phrases)))
        card.addView(menuBody(context.getString(R.string.clip_import_body)))
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_overwrite)) { hideOverlay(); onImportPhrasesWithMode(false) })
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_merge_recommended)) { hideOverlay(); onImportPhrasesWithMode(true) })
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_cancel)) { hideOverlay() })
        showOverlay(card)
    }

    private fun confirmClearCurrentCategory() {
        val cat = currentCategory()
        if (cat.isEmpty()) return
        val card = menuCard()
        card.addView(menuTitle(context.getString(R.string.clip_clear_category_confirm, displayCat(cat)), color = TEXT_DARK))
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_clear)) { hideOverlay(); onClearCategory(cat); st.collapse(); swipeRevealed = null; refresh() })
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_cancel)) { hideOverlay() })
        showOverlay(card)
    }

    private fun confirmClearHistory() {
        val card = menuCard()
        card.addView(menuTitle(context.getString(R.string.clip_clear_history_confirm), color = TEXT_DARK))
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_clear)) { hideOverlay(); onClearHistory(); st.collapse(); swipeRevealed = null; refresh() })
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_cancel)) { hideOverlay() })
        showOverlay(card)
    }

    private fun displayCat(name: String): String =
        if (name == com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID) context.getString(R.string.clip_default_category) else name

    private fun selectPhraseCategory(name: String) {
        st.collapse()
        swipeRevealed = null
        if (phraseCat != name) pendingCategoryFade = true
        phraseCat = name
        refresh()
    }

    private fun catChip(name: String, on: Boolean): View = TextView(context).apply {
        text = displayCat(name)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = if (on) rounded(GREY_PILL, ImeShapes.toolbarPillRadiusDp) else null
        setTextColor(if (on) GREEN else TEXT_DARK)
        setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        Motion.applyTapFeedback(this, if (on) GREEN else TEXT_DARK, radiusDp = ImeShapes.toolbarPillRadiusDp)
        setOnClickListener { selectPhraseCategory(name) }
        setOnLongClickListener { showCategoryMenu(name); true }
        layoutParams = ll(WC, WC).apply { rightMargin = dp(2) }
    }

    private fun showCategoryMenu(name: String) {
        val card = menuCard()
        card.addView(menuItem(context.getString(R.string.clip_rename_named, displayCat(name))) { hideOverlay(); onRenameCategory(name) })
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_delete_named, displayCat(name))) { hideOverlay(); onDeleteCategory(name); if (phraseCat == name) { phraseCat = ""; pendingCategoryFade = true }; st.collapse(); swipeRevealed = null; refresh() })
        showActionPopup(card)
    }


    private fun enterSelect() { swipeRevealed = null; sortMode = false; categorySortMode = false; st.enterSelect(); refresh() }
    private fun exitSelect() { st.exitSelect(); refresh() }

    private fun buildSelectMode() {
        val categories = if (st.tab == Tab.PHRASE) categoriesProvider() else emptyList()
        val category = if (st.tab == Tab.PHRASE) currentCategory(categories) else ""
        val all = if (st.tab == Tab.CLIPBOARD) historyProvider() else phrasesInProvider(category)
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val allSel = st.isAllSelected(all)
            val selectAll = TextView(context).apply {
                text = context.getString(R.string.clip_select_all)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setTextColor(TEXT_DARK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setCompoundDrawablesWithIntrinsicBounds(glyphIcon(if (allSel) GREEN else TEXT_DARK, 22) { c, p, x, y, s -> Glyphs.drawRadio(c, p, x, y, s, allSel) }, null, null, null)
                compoundDrawablePadding = paint.measureText(" ").roundToInt().coerceAtLeast(1)
                setPadding(dp(9), 0, dp(10), 0)
                background = rounded(CARD, ImeShapes.toolbarFeedbackRadiusDp)
                Motion.applyTapFeedback(this, TEXT_DARK, radiusDp = ImeShapes.toolbarFeedbackRadiusDp)
                setOnClickListener { st.selectAll(all); refresh() }
            }
            selectAllAction = selectAll
            addView(selectAll, ll(WC, dp(COMPACT_ACTION_HEIGHT_DP)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(TextView(context).apply {
                    text = batchManagementTitle()
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }, ll(WC, MP))
                addView(TextView(context).apply {
                    text = context.getString(R.string.clip_selected_count, st.selected.size)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(dp(6), 0, 0, 0)
                    setTextColor(TEXT_SECONDARY); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.caption)
                }, ll(WC, MP))
            }, ll(0, dp(COMPACT_ACTION_HEIGHT_DP), 1f))
            val cancel = compactActionButton(context.getString(R.string.clip_cancel), true) { exitSelect() }
            cancelSelectAction = cancel
            addView(cancel, ll(WC, dp(COMPACT_ACTION_HEIGHT_DP)))
        }
        main.addView(topBar, ll(MP, WC))

        populateListRows(all) { e, i -> selectRowFor(e, i, category) }
        main.addView(listScroll, ll(MP, 0, 1f))

        val hasSel = st.hasSelection()
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            if (st.tab == Tab.PHRASE) {
                addView(compactActionButton(context.getString(R.string.clip_move_to_category), hasSel) {
                    val victims = st.selected.toList()
                    chooseMoveCategoryThen(category, victims, after = { exitSelect() }) { target -> onMovePhrasesTo(category, victims, target); exitSelect() }
                }, ll(WC, dp(COMPACT_ACTION_HEIGHT_DP)))
            } else {
                addView(compactActionButton(context.getString(R.string.clip_add_phrase), hasSel) {
                    chooseCategoryThen(st.selected.toList()) { exitSelect() }
                }, ll(WC, dp(COMPACT_ACTION_HEIGHT_DP)))
            }
            addView(View(context), ll(0, dp(1), 1f))
            addView(compactActionButton(context.getString(R.string.clip_delete), hasSel) {
                val victims = st.selected.toList()
                confirmDelete(victims) { st.exitSelect() }
            }, ll(WC, dp(COMPACT_ACTION_HEIGHT_DP)))
        }
        main.addView(bottom, ll(MP, WC))
    }

    private fun selectRowFor(text: String, index: Int, category: String): View {
        val h = if (index < selectRowPool.size) selectRowPool[index] else buildSelectRow()
        val on = text in st.selected
        val tint = if (on) GREEN else TEXT_DARK
        Motion.reset(h.row)
        h.radio.bind(tint, on)
        h.label.text = if (st.tab == Tab.PHRASE) phraseDisplayText(category, text) else text
        retintRow(h.row, tint)
        h.row.setOnClickListener { st.toggleSelect(text); refresh() }
        return h.row
    }

    private fun buildSelectRow(): SelectRowHolder {
        val radio = RadioGlyph()
        val label = TextView(context).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body); setTextColor(TEXT_DARK)
            setPadding(0, dp(12), dp(14), dp(12))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, ImeShapes.cardRadiusDp)
            layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
            addView(radio, ll(dp(40), MP))
            addView(label, ll(0, WC, 1f))
        }
        return SelectRowHolder(row, radio, label).also { selectRowPool.add(it) }
    }


    private fun hideOverlay() {
        if (overlay.visibility != VISIBLE) {
            overlay.removeAllViews()
            return
        }
        overlay.setOnClickListener(null)
        overlay.isClickable = false
        disableClicks(overlay)
        Motion.hideNow(overlay) { overlay.removeAllViews() }
    }

    private fun hideOverlayImmediately() {
        overlay.setOnClickListener(null)
        overlay.isClickable = false
        Motion.reset(overlay)
        overlay.removeAllViews()
        overlay.visibility = GONE
    }

    private fun disableClicks(v: View) {
        v.isClickable = false
        v.isLongClickable = false
        if (v is ViewGroup) for (i in 0 until v.childCount) disableClicks(v.getChildAt(i))
    }

    private fun showOverlay(content: View, gravity: Int = Gravity.CENTER, maxWidthDp: Int? = null) {
        Motion.reset(overlay)
        overlay.removeAllViews()
        overlay.setBackgroundColor(0x00000000)
        overlay.setOnClickListener { hideOverlay() }
        val scroll = ScrollView(context).apply { isClickable = true; addView(content) }
        val margin = dp(24)
        val requestedWidth = maxWidthDp?.let { minOf(dp(it), (resources.displayMetrics.widthPixels - margin * 2).coerceAtLeast(dp(260))) } ?: WC
        val lp = FrameLayout.LayoutParams(requestedWidth, WC, gravity).apply { leftMargin = margin; rightMargin = margin; topMargin = margin; bottomMargin = margin }
        overlay.addView(scroll, lp)
        overlay.visibility = VISIBLE
        Motion.showNow(scroll)
        scroll.post {
            val maxH = (overlay.height * 0.82f).toInt()
            if (maxH in 1 until scroll.height) { lp.height = maxH; scroll.layoutParams = lp }
        }
    }

    private fun showActionPopup(content: View) = showOverlay(content, maxWidthDp = 320)

    private fun chooseMoveCategoryThen(current: String, moveTexts: List<String>, after: () -> Unit = {}, action: (String) -> Unit) {
        val targets = categoriesProvider().filter { it != current }
        val card = menuCard()
        if (targets.isEmpty()) {
            card.addView(menuTitle(context.getString(R.string.clip_no_other_categories)))
            card.addView(menuDivider())
            card.addView(menuItem(context.getString(R.string.clip_new_category)) { hideOverlay(); after(); onAddCategoryThenMove(current, moveTexts) })
        } else {
            card.addView(menuTitle(context.getString(R.string.clip_move_to_category)))
            for (c in targets) { card.addView(menuDivider()); card.addView(moveTargetRow(c, current, moveTexts, after, action)) }
            card.addView(menuDivider())
            card.addView(menuItem(context.getString(R.string.clip_new_category)) { hideOverlay(); after(); onAddCategoryThenMove(current, moveTexts) })
        }
        showOverlay(card)
    }

    private fun moveTargetRow(name: String, current: String, moveTexts: List<String>, after: () -> Unit, action: (String) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(menuItem(displayCat(name)) { hideOverlay(); action(name) }, ll(0, WC, 1f))
            addView(
                glyphView(RED, 9) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }.apply {
                    contentDescription = context.getString(R.string.clip_delete_category)
                    Motion.applyTapFeedback(this, RED)
                    setOnClickListener {
                        onDeleteCategory(name); if (phraseCat == name) { phraseCat = ""; pendingCategoryFade = true }; st.collapse(); swipeRevealed = null
                        refresh()
                        chooseMoveCategoryThen(current, moveTexts, after, action)
                    }
                },
                ll(dp(52), dp(48)),
            )
        }

    private fun showLongPressMenu(text: String) {
        val card = menuCard()
        card.addView(menuItem(context.getString(R.string.clip_delete_item)) { confirmDelete(listOf(text)) })
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_add_phrase)) { hideOverlay(); chooseCategoryThen(listOf(text)) })
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_split_title)) { hideOverlay(); showSplit(text) })
        showActionPopup(card)
    }

    private fun showHistoryRecordingMenu() {
        val card = menuCard()
        val on = historyEnabledProvider()
        card.addView(menuItem(if (on) context.getString(R.string.clip_history_recording_on) else context.getString(R.string.clip_history_recording_off)) { hideOverlay(); onSetHistoryEnabled(!on); refresh() })
        showActionPopup(card)
    }

    private fun chooseCategoryThen(pending: List<String>, after: () -> Unit = {}) {
        val cats = categoriesProvider()
        if (cats.isEmpty()) { after(); onAddCategoryThenAdd(pending); return }
        val card = menuCard()
        card.addView(menuTitle(context.getString(R.string.clip_choose_category)))
        for (c in cats) { card.addView(menuDivider()); card.addView(menuItem(displayCat(c)) { hideOverlay(); onSaveAsPhrasesTo(c, pending); after(); refresh() }) }
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_new_category)) { hideOverlay(); after(); onAddCategoryThenAdd(pending) })
        showOverlay(card)
    }

    private fun showSplit(text: String) {
        splitSelected.clear()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = ImeShapes.cardRadiusDp * density; setStroke(dp(1), SEP) }
        }
        panel.addView(TextView(context).apply {
            this.text = context.getString(R.string.clip_split_title); setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        })
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val blocks = ClipSplitter.blocks(text)
        val chipViews = ArrayList<TextView>()
        if (blocks.isEmpty()) chips.addView(TextView(context).apply { this.text = context.getString(R.string.clip_nothing_to_split); setTextColor(HINT) })
        for (b in blocks) {
            val chip = TextView(context).apply {
                this.text = b
                setTextColor(SPLIT_BLOCK_TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(SPLIT_BLOCK_BG, ImeShapes.chipRadiusDp)
                layoutParams = ll(WC, WC).apply { rightMargin = dp(8) }
            }
            chip.setOnClickListener {
                splitSelected.add(b)
                chip.setTextColor(SPLIT_BLOCK_COPIED_TEXT)
                chip.background = rounded(SPLIT_BLOCK_COPIED_BG, ImeShapes.chipRadiusDp)
                Motion.applyTapFeedback(chip, SPLIT_BLOCK_COPIED_TEXT)
                onCopyBlockToAegis(b)
            }
            Motion.applyTapFeedback(chip, SPLIT_BLOCK_TEXT)
            chipViews.add(chip); chips.addView(chip)
        }
        panel.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, ll(MP, WC))
        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        footer.addView(TextView(context).apply {
            this.text = context.getString(R.string.clip_back); setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setPadding(dp(8), dp(14), dp(16), dp(10)); Motion.applyTapFeedback(this, TEXT_DARK); setOnClickListener { hideOverlay() }
        }, ll(WC, WC))
        footer.addView(View(context), ll(0, dp(1), 1f))
        if (blocks.isNotEmpty()) footer.addView(TextView(context).apply {
            this.text = context.getString(R.string.clip_copy_all); gravity = Gravity.END; setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setPadding(dp(16), dp(14), dp(8), dp(10))
            Motion.applyTapFeedback(this, TEXT_DARK)
            setOnClickListener {
                splitSelected.addAll(blocks)
                for (c in chipViews) {
                    c.setTextColor(SPLIT_BLOCK_COPIED_TEXT)
                    c.background = rounded(SPLIT_BLOCK_COPIED_BG, ImeShapes.chipRadiusDp)
                }
                onCopyBlocksToAegis(blocks)
            }
        }, ll(WC, WC))
        panel.addView(footer, ll(MP, WC))
        showOverlay(panel, maxWidthDp = 340)
    }


    private fun currentCategory(): String = currentCategory(categoriesProvider())

    private fun currentCategory(categories: List<String>): String {
        if (phraseCat !in categories) phraseCat = categories.firstOrNull().orEmpty()
        return phraseCat
    }

    private fun currentEntries(): List<String> =
        if (st.tab == Tab.CLIPBOARD) historyProvider() else phrasesInProvider(currentCategory())

    private fun confirmDelete(texts: List<String>, after: () -> Unit = {}) {
        val deleteTab = st.tab
        val category = if (deleteTab == Tab.PHRASE) currentCategory() else ""
        val card = menuCard()
        val title = if (deleteTab == Tab.CLIPBOARD) R.string.clip_delete_clip_confirm else R.string.clip_delete_phrase_confirm
        card.addView(menuTitle(context.getString(title), color = TEXT_DARK))
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_delete)) {
            hideOverlay()
            if (deleteTab == Tab.CLIPBOARD) onDeleteClips(texts) else onDeletePhrasesFrom(category, texts)
            texts.forEach(st::collapseIfExpanded)
            swipeRevealed?.let { if (it in texts) swipeRevealed = null }
            after()
            refresh()
        })
        card.addView(menuDivider())
        card.addView(menuItem(context.getString(R.string.clip_cancel)) { hideOverlay() })
        showOverlay(card)
    }

    private fun pillTray(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(CARD, ImeShapes.toolbarPillRadiusDp)
        addView(pill(context.getString(R.string.clip_clipboard), st.tab == Tab.CLIPBOARD, true) { if (st.switchTab(Tab.CLIPBOARD)) { swipeRevealed = null; sortMode = false; categorySortMode = false; refresh() } }, ll(dp(84), MP))
        addView(pill(context.getString(R.string.clip_phrases), st.tab == Tab.PHRASE, false) { if (st.switchTab(Tab.PHRASE)) { swipeRevealed = null; sortMode = false; categorySortMode = false; refresh() } }, ll(dp(84), MP))
    }

    private fun pill(label: String, on: Boolean, left: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        background = if (on) tabSegment(GREY_PILL, left) else null
        setTextColor(if (on) GREEN else TEXT_DARK)
        setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        foreground = RippleDrawable(
            ColorStateList.valueOf(Motion.withAlpha(if (on) GREEN else TEXT_DARK, 0x24)),
            null,
            tabSegment(Color.WHITE, left),
        )
        setOnClickListener { onClick() }
    }

    private fun tabSegment(color: Int, left: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        val r = dp(17).toFloat()
        cornerRadii = if (left) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
    }

    private fun emptyHint(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(40), dp(16), dp(16))
        if (st.tab == Tab.CLIPBOARD) {
            addView(hint(context.getString(R.string.clip_clipboard_empty), 16f, TEXT_DARK)); addView(hint(context.getString(R.string.clip_clipboard_empty_hint), 14f, HINT))
        } else {
            addView(hint(context.getString(R.string.clip_phrases_empty), 16f, TEXT_DARK)); addView(hint(context.getString(R.string.clip_phrases_empty_hint), 14f, HINT))
        }
    }

    private fun hint(s: String, size: Float, color: Int) = TextView(context).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setPadding(0, dp(3), 0, dp(3))
    }

    private fun menuCard(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = ImeShapes.cardRadiusDp * density; setStroke(dp(1), SEP) }
    }

    private fun menuTitle(s: String, color: Int = TEXT_DARK): View = TextView(context).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label); setPadding(dp(20), dp(12), dp(20), dp(4))
    }

    private fun menuBody(s: String): View = TextView(context).apply {
        text = s; gravity = Gravity.START; setTextColor(TEXT_DARK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label); setPadding(dp(20), dp(6), dp(20), dp(10))
    }

    private fun menuItem(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body); setTextColor(TEXT_DARK)
        setPadding(dp(24), dp(16), dp(24), dp(16))
        Motion.applyTapFeedback(this, TEXT_DARK)
        setOnClickListener { onClick() }
    }

    private fun menuDivider(): View = View(context).apply {
        setBackgroundColor(SEP)
        layoutParams = LinearLayout.LayoutParams(MP, maxOf(1, dp(1)))
    }

    private fun compactActionButton(label: String, enabled: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(CARD, ImeShapes.toolbarFeedbackRadiusDp)
            setTextColor(TEXT_DARK)
            isClickable = enabled
            if (enabled) {
                Motion.applyTapFeedback(this, TEXT_DARK, radiusDp = ImeShapes.toolbarFeedbackRadiusDp)
                setOnClickListener { onClick() }
            }
        }

    private fun glyphPaint(tint: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * density; color = tint
    }

    private fun glyphView(tint: Int, sDp: Int, render: (Canvas, Paint, Float, Float, Float) -> Unit): View =
        object : View(context) {
            private val p = glyphPaint(tint)
            override fun onDraw(c: Canvas) { render(c, p, width / 2f, height / 2f, dp(sDp).toFloat()) }
        }

    private fun glyphIcon(tint: Int, boxDp: Int, render: (Canvas, Paint, Float, Float, Float) -> Unit): android.graphics.drawable.Drawable {
        val box = dp(boxDp); val p = glyphPaint(tint)
        return object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: Canvas) { val b = bounds; render(canvas, p, b.exactCenterX(), b.exactCenterY(), box * 0.42f) }
            override fun getIntrinsicWidth() = box
            override fun getIntrinsicHeight() = box
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
            @Deprecated("deprecated in Drawable", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT"))
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun glyphToolbarBtn(desc: String, tint: Int = TEXT_DARK, glyphSizeDp: Int = 9, onClick: () -> Unit, render: (Canvas, Paint, Float, Float, Float) -> Unit): View =
        glyphView(tint, glyphSizeDp, render).apply {
            contentDescription = desc
            Motion.applyTapFeedback(this, tint, radiusDp = ImeShapes.toolbarFeedbackRadiusDp)
            setOnClickListener { onClick() }
        }

    private fun charToolbarBtn(desc: String, symbol: String, tint: Int = TEXT_DARK, onClick: () -> Unit): View {
        val icon = charIcon(symbol, tint, 15)
        return object : View(context) {
            override fun onDraw(canvas: Canvas) {
                val left = (width - icon.intrinsicWidth) / 2
                val top = (height - icon.intrinsicHeight) / 2
                icon.setBounds(left, top, left + icon.intrinsicWidth, top + icon.intrinsicHeight)
                icon.draw(canvas)
            }
        }.apply {
            contentDescription = desc
            Motion.applyTapFeedback(this, tint, radiusDp = ImeShapes.toolbarFeedbackRadiusDp)
            setOnClickListener { onClick() }
        }
    }

    private fun glyphAction(label: String, tint: Int = TEXT_DARK, render: (Canvas, Paint, Float, Float, Float) -> Unit, onClick: () -> Unit): TextView =
        actionButton(label, tint, glyphIcon(tint, 16, render), onClick)

    private fun charAction(symbol: String, label: String, tint: Int = TEXT_DARK, onClick: () -> Unit): TextView =
        actionButton(label, tint, charIcon(symbol, tint, 14), onClick).apply { contentDescription = "$symbol $label" }

    private fun actionButton(label: String, tint: Int, icon: android.graphics.drawable.Drawable, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
            setTextColor(TEXT_DARK)
            setPadding(dp(6), 0, dp(6), 0)
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = paint.measureText(" ").roundToInt().coerceAtLeast(1)
            background = rounded(CARD, ImeShapes.toolbarFeedbackRadiusDp)
            Motion.applyTapFeedback(this, tint, radiusDp = ImeShapes.toolbarFeedbackRadiusDp)
            setOnClickListener { onClick() }
        }

    private fun charIcon(symbol: String, tint: Int, boxDp: Int): android.graphics.drawable.Drawable {
        val box = dp(boxDp)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.caption, resources.displayMetrics)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val ink = android.graphics.Rect()
        textPaint.getTextBounds(symbol, 0, symbol.length, ink)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        return object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: Canvas) {
                val b = bounds
                val inset = density * 0.5f
                canvas.drawRoundRect(
                    b.left + inset,
                    b.top + inset,
                    b.right - inset,
                    b.bottom - inset,
                    dp(2).toFloat(),
                    dp(2).toFloat(),
                    boxPaint,
                )
                canvas.drawText(symbol, b.exactCenterX() - ink.exactCenterX(), b.exactCenterY() - ink.exactCenterY(), textPaint)
            }
            override fun getIntrinsicWidth() = box
            override fun getIntrinsicHeight() = box
            override fun setAlpha(a: Int) { textPaint.alpha = a; boxPaint.alpha = a }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { textPaint.colorFilter = cf; boxPaint.colorFilter = cf }
            @Deprecated("deprecated in Drawable", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT"))
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * density
    }
}
