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
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.ime.theme.ImeShapes
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.ime.ClipboardPanelState.Tab
import com.aegis.ime.user.ClipSplitter

/**
  * Chinese IME behavior note.
  * Chinese IME behavior note.
  * Chinese IME behavior note.
  * Chinese IME behavior note.
  * Chinese IME behavior note.
 * The tab/select/expand state lives in the pure [ClipboardPanelState] (unit-tested).
 */
class ClipboardView(context: Context) : FrameLayout(context), ResettablePanel {

    var onPick: (String) -> Unit = {}                 // commit a clip/phrase and close
    var onCopyBlockToAegis: (String) -> Unit = {} // Chinese IME behavior note.
    var onCopyBlocksToAegis: (List<String>) -> Unit = { blocks -> blocks.forEach { onCopyBlockToAegis(it) } }
    var onBack: () -> Unit = {}
    var historyProvider: () -> List<String> = { emptyList() }
    var categoriesProvider: () -> List<String> = { emptyList() }
    var phrasesInProvider: (String) -> List<String> = { emptyList() }
    var phraseNoteProvider: (String, String) -> String = { _, _ -> "" } // debug.17 F2: (category, phrase) → display note ("" = none)
    var onDeleteClips: (List<String>) -> Unit = {}
    var onDeletePhrasesFrom: (String, List<String>) -> Unit = { _, _ -> }
    var onSaveAsPhrasesTo: (String, List<String>) -> Unit = { _, _ -> }
    var onEditPhrase: (String, String) -> Unit = { _, _ -> }            // debug.16 Option A: (category, phrase) → inline edit
    var onMovePhrase: (String, String, String) -> Unit = { _, _, _ -> } // debug.16: (fromCategory, phrase, toCategory)
    var onMovePhrasesTo: (String, List<String>, String) -> Unit = { _, _, _ -> } // debug.16: batch move (from, phrases, to)
    var onReorderPhrase: (String, Int, Int) -> Unit = { _, _, _ -> }    // debug.16: drag-reorder (category, fromIndex, toIndex)
    var onReorderCategory: (Int, Int) -> Unit = { _, _ -> }
    var onAddPhrase: (String) -> Unit = {} // Chinese IME behavior note.
    var onAddCategory: () -> Unit = {} // Chinese IME behavior note.
    var onAddCategoryThenAdd: (List<String>) -> Unit = {} // Chinese IME behavior note.
    var onAddCategoryThenMove: (String, List<String>) -> Unit = { _, _ -> } // Chinese IME behavior note.
    var onRenameCategory: (String) -> Unit = {} // Chinese IME behavior note.
    var onDeleteCategory: (String) -> Unit = {} // Chinese IME behavior note.
    var onEditNote: (String, String) -> Unit = { _, _ -> } // Chinese IME behavior note.
    var onClearCategory: (String) -> Unit = {} // Chinese IME behavior note.
    var onExportPhrases: () -> Unit = {} // Chinese IME behavior note.
    var onImportPhrases: () -> Unit = {}               // debug.17 E1: back-compat import callback
    var onImportPhrasesWithMode: (Boolean) -> Unit = { onImportPhrases() }
    var onClearHistory: () -> Unit = {}
    var historyEnabledProvider: () -> Boolean = { true }
    var onSetHistoryEnabled: (Boolean) -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    // F1: Monet palette + the panel's colour roles (default = static light = previous look); applyPalette
    // recomputes them and rebuilds. Declared before [main] et al. since those field initializers use them.
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
    private var BG = palette.keyboardBg // P-A: panel floor == the strip/keyboard floor (no top seam)
    private var SEP = palette.separator

    /** F1: recolour from the Monet palette and rebuild. */
    fun applyPalette(p: ImePalette) {
        palette = p
        GREEN = p.candidateFirst; RED = p.onErrorContainer
        GREY_PILL = p.chipBg; SPLIT_BLOCK_BG = p.accentBottom; SPLIT_BLOCK_TEXT = p.accentLabel
        SPLIT_BLOCK_COPIED_BG = p.chipBg; SPLIT_BLOCK_COPIED_TEXT = p.chipText
        TEXT_DARK = p.keyLabel; TEXT_SECONDARY = p.keyLabelSecondary; HINT = p.keyHint; CARD = p.keySurface
        BG = p.keyboardBg; SEP = p.separator // P-A: BG = unified floor
        main.setBackgroundColor(BG)
        refresh()
    }

    private val st = ClipboardPanelState()
    private var phraseCat = "" // Chinese IME behavior note.

    // debug.17: a card's left-swipe reveals an inline action row WITHOUT expanding it (the ⌄ expand + long-press
    // menu are untouched). Only one card reveals at a time; a right-swipe (or tapping its body) hides it.
    private var swipeRevealed: String? = null
    // Phrase reorder mode remains available for direct entry points; category reorder mode is opened from the
    // categoryBar pencil menu's category-move item.
    private var sortMode = false
    private var categorySortMode = false
    // Chinese IME behavior note.
    private val splitSelected = mutableSetOf<String>()

    /**
      * Chinese IME behavior note.
      */
    fun showPhraseTab(category: String) {
        st.switchTab(ClipboardPanelState.Tab.PHRASE)
        swipeRevealed = null; sortMode = false; categorySortMode = false
        if (category.isNotEmpty() && category in categoriesProvider()) phraseCat = category
        refresh()
    }

    // dragFrom is the persisted start index; dragCurrent/dragVisualIndex track the live visual target while
    // dragging. The backing store is still updated once on drop.
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
    // Chinese IME behavior note.
    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
    private val listScroll = ScrollView(context).apply { addView(listColumn) }
    private var listRenderGeneration = 0
    private var pendingListAppend: Runnable? = null

    private companion object {
        const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DISPLAY_CAP = 2000 // Chinese IME behavior note.
        const val INITIAL_SYNC_ROWS = 48
        const val APPEND_ROWS_PER_FRAME = 48
        // debug.18 F: a swipe is treated as a vertical list scroll only when dy dominates dx by this factor —
        // i.e. the direction decision is biased toward HORIZONTAL (left-swipe reveal) unless clearly vertical.
        const val SWIPE_VERTICAL_BIAS = 1.5f
        const val DRAG_AUTO_SCROLL_INTERVAL_MS = 16L
        const val DRAG_AUTO_SCROLL_MIN_STEP_DP = 2
        const val DRAG_AUTO_SCROLL_MAX_STEP_DP = 8
    }

    /** E5: a bounded preview of [s] for display only (full text is kept for tap/save). */
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

    private fun handleActiveDrag(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateActiveDrag(e.rawY)
            MotionEvent.ACTION_UP -> endDrag()
            MotionEvent.ACTION_CANCEL -> cancelDrag()
        }
        return true
    }

    /** Open fresh: clipboard tab, normal mode, nothing expanded/overlaid/swiped/sorting. */
    fun reset() {
        invalidateListRender()
        st.reset(); hideOverlay(); swipeRevealed = null; sortMode = false; categorySortMode = false
    }

    /**
     * P7 (#19): on dismissal, return to the default view — clipboard tab, normal mode, no expanded card /
      * Chinese IME behavior note.
     * never resumes on the last tab / category / scroll position. Extends [reset] (which the state-machine
     * test covers) with the picker + scroll state that lives on the view.
     */
    override fun resetToDefault() {
        reset()
        phraseCat = ""
        listScroll.scrollTo(0, 0)
    }

    // P7 test seams.
    internal fun isClipboardTabForTest(): Boolean = st.tab == ClipboardPanelState.Tab.CLIPBOARD
    internal fun phraseCatForTest(): String = phraseCat
    internal fun forcePhrasesStateForTest(cat: String) { st.switchTab(ClipboardPanelState.Tab.PHRASE); phraseCat = cat }
    internal fun enterSelectForTest(selected: List<String> = emptyList()) { st.enterSelect(); st.selected.addAll(selected); refresh() }
    // debug.16 test seams: the move-target chooser + the drag-reorder state machine (touch plumbing is exercised separately).
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
    internal fun disabledActionTextColorForTest(): Int = TEXT_SECONDARY
    internal fun disabledActionBackgroundColorForTest(): Int = GREY_PILL
    internal fun listScrollRawTopForTest(): Int {
        val loc = IntArray(2)
        listScroll.getLocationOnScreen(loc)
        return loc[1]
    }
    internal fun listScrollRawBottomForTest(): Int = listScrollRawTopForTest() + listScroll.height
    internal fun expandForTest(text: String) { if (st.expanded != text) st.toggleExpand(text); refresh() }
    // Chinese IME behavior note.
    internal fun revealSwipeForTest(text: String) { revealSwipe(text) }
    internal fun hideSwipeForTest() { hideSwipe() }
    internal fun swipeRevealedForTest(): String? = swipeRevealed
    internal fun showPhraseManageMenuForTest() { showPhraseManageMenu() }
    internal fun showHistoryRecordingMenuForTest() { showHistoryRecordingMenu() }
    internal fun confirmClearForTest() { confirmClearCurrentCategory() } // debug.17 E2
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

    fun refresh() {
        invalidateListRender()
        main.removeAllViews()
        when {
            st.selectMode -> buildSelectMode()
            categorySortMode -> buildCategorySortMode()
            sortMode -> buildSortMode()
            else -> buildNormal()
        }
    }

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

    // ---------- normal mode ----------

    private fun buildNormal() {
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            fun iconLp(spaced: Boolean = false) = ll(dp(36), dp(36)).apply { if (spaced) marginStart = dp(6) }
            addView(glyphToolbarBtn(desc = "返回", glyphSizeDp = 8, onClick = { onBack() }) { c, p, x, y, s -> Glyphs.drawBack(c, p, x, y, s) }, iconLp())
            addView(View(context), ll(0, dp(1), 1f))
            addView(pillTray(), ll(WC, dp(36)))
            addView(View(context), ll(0, dp(1), 1f))
            // Chinese IME behavior note.
            // Chinese IME behavior note.
            if (st.tab == Tab.PHRASE) addView(glyphToolbarBtn(desc = "添加常用语", onClick = { onAddPhrase(currentCategory()) }) { c, p, x, y, s -> Glyphs.drawPlus(c, p, x, y, s) }, iconLp(true))
            addView(glyphToolbarBtn(desc = "多选", onClick = { enterSelect() }) { c, p, x, y, s -> Glyphs.drawList(c, p, x, y, s) }, iconLp(true))
            // The last top icon is tab-specific: both destructive clear actions require confirmation.
            if (st.tab == Tab.PHRASE) addView(glyphToolbarBtn(desc = "清空分类", tint = TEXT_DARK, onClick = { confirmClearCurrentCategory() }) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }, iconLp(true))
            else addView(glyphToolbarBtn(desc = "清空剪贴板历史", tint = TEXT_DARK, onClick = { confirmClearHistory() }) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }.apply {
                setOnLongClickListener { showHistoryRecordingMenu(); true }
            }, iconLp(true))
        }
        main.addView(topBar, ll(MP, dp(50)))
        // Chinese IME behavior note.
        val entries = currentEntries()
        populateListRows(entries) { e, i -> card(e, i) }
        main.addView(listScroll, ll(MP, 0, 1f))

        if (st.tab == Tab.PHRASE) main.addView(categoryBar(), ll(MP, dp(44)))
    }

    /** Chinese IME behavior note. */
    private fun phraseDisplayText(text: String): String {
        val note = phraseNoteProvider(currentCategory(), text)
        return if (note.isNotEmpty()) note else text
    }

    /** debug.17 F1: wrap an expanded card's [body] in a vertical ScrollView capped at ~4 text lines, so a long
     *  entry shows 4 lines and scrolls (standard ScrollView inertia) instead of growing unbounded. AT_MOST so a
     *  short entry stays its natural height (no empty padding). */
    private fun boundedExpandBody(body: TextView): View {
        val maxH = body.lineHeight * 4 + body.paddingTop + body.paddingBottom
        return object : ScrollView(context) {
            private var lastRawY = 0f
            override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            // debug.18 G: this inner ScrollView lives inside the outer list ScrollView. Without claiming the
            // Chinese IME behavior note.
            // On DOWN, if we can scroll at all, ask the ancestors NOT to intercept; on MOVE keep that only while
            // we can still scroll in the drag direction, releasing at our boundary so the outer list takes over.
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    lastRawY = e.rawY
                    if (canScrollVertically(1) || canScrollVertically(-1)) parent?.requestDisallowInterceptTouchEvent(true)
                }
                return super.onInterceptTouchEvent(e)
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                    val dir = if (e.rawY < lastRawY) 1 else -1 // finger up → scroll content toward +y; down → -y
                    lastRawY = e.rawY
                    parent?.requestDisallowInterceptTouchEvent(canScrollVertically(dir))
                }
                return super.onTouchEvent(e)
            }
        }.apply { isFillViewport = false; addView(body) }
    }

    private fun card(text: String, index: Int): View {
        val expanded = st.expanded == text
        val revealed = swipeRevealed == text // debug.17: showing its left-swipe action row
        val openBody = expanded || revealed
        val phrase = st.tab == Tab.PHRASE
        // Chinese IME behavior note.
        val display = if (phrase) phraseDisplayText(text) else text
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, ImeShapes.cardRadiusDp)
            layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val body = TextView(context).apply {
            // E5: show only a bounded PREVIEW — a million-char entry would make the TextView measure/layout the
            // Chinese IME behavior note.
            this.text = preview(display)
            // debug.19: expanded and left-swiped cards show the full preview in a bounded four-line body.
            maxLines = if (openBody) Integer.MAX_VALUE else 2
            ellipsize = if (openBody) null else android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTextColor(TEXT_DARK)
            setPadding(dp(14), dp(12), dp(8), dp(12))
            // Chinese IME behavior note.
            Motion.applyTapFeedback(this, TEXT_DARK)
            setOnClickListener { if (swipeRevealed == text) hideSwipe() else onPick(text) }
            // Chinese IME behavior note.
            // Chinese IME behavior note.
            if (!phrase) setOnLongClickListener { showLongPressMenu(text); true }
        }
        // Chinese IME behavior note.
        val chevron = glyphView(TEXT_DARK, 7) { c, p, x, y, s -> Glyphs.drawChevron(c, p, x, y, s, down = !expanded) }.apply {
            contentDescription = if (expanded) "收起" else "展开"
            Motion.applyTapFeedback(this, TEXT_DARK)
            setOnClickListener { swipeRevealed = null; st.toggleExpand(text); refresh() } // Chinese IME behavior note.
            if (!phrase) setOnLongClickListener { showLongPressMenu(text); true }
        }
        header.addView(if (openBody) boundedExpandBody(body) else body, ll(0, WC, 1f)) // open body scrolls (≤4 lines)
        header.addView(chevron, ll(dp(40), MP))
        col.addView(header, ll(MP, WC))
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        // card keeps its long-press drag-reorder; the swipe is merged into the same touch handler.
        when {
            expanded -> addRevealedRow(col, if (phrase) phraseActionRow(text) else actionRow(text))
            revealed -> { addRevealedRow(col, if (phrase) phraseSwipeRow(text, index) else actionRow(text)); attachSwipeReveal(body, text) }
            phrase -> attachDragHandle(body, col, index, text)
            else -> attachSwipeReveal(body, text)
        }
        return col
    }

    private fun addRevealedRow(parent: LinearLayout, row: View) {
        parent.addView(row)
        row.post { Motion.revealIn(row, Motion.EnterFrom.TOP, distanceDp = 6f, duration = Motion.STATE_CHANGE) }
    }

    /** Chinese IME behavior note. */
    private fun actionRow(text: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(24), 0, dp(24), dp(10))
        addView(actionSlot(Gravity.START, glyphAction("常用语", render = { c, p, x, y, s -> Glyphs.drawPlus(c, p, x, y, s) }) { chooseCategoryThen(listOf(text)) }), ll(0, WC, 1f))
        addView(actionSlot(Gravity.CENTER, glyphAction("拆词", render = { c, p, x, y, s -> Glyphs.drawCut(c, p, x, y, s) }) { showSplit(text) }), ll(0, WC, 1f))
        addView(actionSlot(Gravity.END, glyphAction("删除", render = { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }) { deleteOne(text) }), ll(0, WC, 1f))
    }

    private fun actionSlot(gravity: Int, action: View): View = FrameLayout(context).apply {
        addView(action, FrameLayout.LayoutParams(WC, WC, gravity))
    }

    /**
      * Chinese IME behavior note.
      * Chinese IME behavior note.
      */
    private fun phraseActionRow(text: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), 0, dp(8), dp(10))
        val cat = currentCategory()
        addView(glyphAction("编辑", render = { c, p, x, y, s -> Glyphs.drawEditCaret(c, p, x, y, s) }) { onEditPhrase(cat, text) }, ll(0, WC, 1f))
        addView(glyphAction("备注", render = { c, p, x, y, s -> Glyphs.drawTag(c, p, x, y, s) }) { onEditNote(cat, text) }, ll(0, WC, 1f)) // F2
        addView(glyphAction("移动", render = { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, Glyphs.Arrow.RIGHT) }) { chooseMoveCategoryThen(cat, listOf(text)) { target -> onMovePhrase(cat, text, target); refresh() } }, ll(0, WC, 1f))
        addView(glyphAction("删除", render = { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }) { deleteOne(text) }, ll(0, WC, 1f))
    }

    /**
      * Chinese IME behavior note.
     *  which is unchanged. [index] is the phrase's current position in the category. */
    private fun phraseSwipeRow(text: String, index: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), 0, dp(8), dp(10))
        val cat = currentCategory()
        addView(glyphAction("编辑", render = { c, p, x, y, s -> Glyphs.drawEditCaret(c, p, x, y, s) }) { onEditPhrase(cat, text) }, ll(0, WC, 1f))
        addView(glyphAction("置顶", render = { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, Glyphs.Arrow.UP) }) { onReorderPhrase(cat, index, 0); swipeRevealed = null; refresh() }, ll(0, WC, 1f))
        addView(glyphAction("删除", render = { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }) { deleteOne(text) }, ll(0, WC, 1f))
    }

    // Chinese IME behavior note.

    /**
      * Chinese IME behavior note.
     *  pre-long-press HORIZONTAL move (debug.17) becomes the left-swipe reveal — so one touch handler serves
     *  tap + long-press-drag + swipe without ever stealing the others' gestures. */
    private fun attachDragHandle(touchTarget: View, card: View, index: Int, text: String) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var mode = 0 // 0=undecided, 1=horizontal swipe, 2=vertical scroll (drag is tracked by isDragging)
        val longPress = Runnable { startDrag(index, downY); requestDragCapture() }
        touchTarget.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; mode = 0
                    dragHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    false // not consumed yet → a plain tap still reaches onClick
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        updateActiveDrag(e.rawY)
                        true
                    } else {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                            dragHandler.removeCallbacks(longPress) // moved before long-press → not a drag
                            // debug.18 F: bias toward HORIZONTAL — only a clearly vertical gesture (dy dominates by
                            // SWIPE_VERTICAL_BIAS) is treated as a list scroll; everything else is a left-swipe.
                            mode = if (abs(dy) > abs(dx) * SWIPE_VERTICAL_BIAS) 2 else 1
                            if (mode == 1) card.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        mode == 1 // consume a horizontal swipe; let a vertical scroll pass to the ScrollView
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragHandler.removeCallbacks(longPress)
                    when {
                        isDragging && e.actionMasked == MotionEvent.ACTION_UP -> { endDrag(); true }
                        isDragging -> { cancelDrag(); true }
                        mode == 1 && e.actionMasked == MotionEvent.ACTION_UP -> { settleSwipe(e.rawX - downX, text); true }
                        else -> false
                    }
                }
                else -> false
            }
        }
    }

    /**
     *  already-revealed card — so a right-swipe / tap can hide it). Returns false until a horizontal gesture is
     *  certain, so tap / long-press-menu are never swallowed. */
    private fun attachSwipeReveal(target: View, text: String) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var mode = 0
        target.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; mode = 0; false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                        // debug.18 F: bias toward HORIZONTAL — only a clearly vertical gesture is a list scroll.
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

    /** debug.18 F: settle a gesture that was ALREADY decided horizontal (mode==1, disallow-intercept set). It must
      * Chinese IME behavior note.
     *  Any LEFT swipe ([dx] negative) reveals the action row regardless of distance (even a short, deliberate
      * Chinese IME behavior note.
      * Chinese IME behavior note.
      */
    private fun settleSwipe(dx: Float, text: String) {
        if (dx < 0f) revealSwipe(text) else hideSwipe()
    }

    /** debug.17: show [text]'s left-swipe action row (collapsing it first if it was ⌄-expanded). */
    private fun revealSwipe(text: String) {
        if (st.expanded == text) st.toggleExpand(text)
        swipeRevealed = text
        refresh()
    }

    private fun hideSwipe() { if (swipeRevealed != null) { swipeRevealed = null; refresh() } }

    /** Which list row currently sits under [rawY] (screen coords), or null if over none. The dragged card is
     *  EXCLUDED: it's translated to follow the finger so its bounds always contain [rawY] — counting it would
     *  pin the result to [dragFrom] and a downward drag could never reach a lower target. */
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

    /** Pure coordinate→row mapping (unit-tested): the first row whose [tops]/[heights] span contains [y],
     *  skipping the dragged row [skip] (whose translated bounds would otherwise always match). */
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
        else refresh() // reset the lifted card even on a no-op drop
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
        // Drop any pending long-press → drag so a panel close mid-press can't fire startDrag on a stale card.
        dragHandler.removeCallbacksAndMessages(null)
        cancelPendingListAppend()
        resetDragPreviewTranslations()
        dragAutoScrollScheduled = false
        dragFrom = -1; dragCurrent = -1; dragVisualIndex = -1; dragTouchOffsetY = 0f; dragLastRawY = 0f; dragView = null; dragKind = DragKind.NONE
        super.onDetachedFromWindow()
    }

    // Chinese IME behavior note.

    private fun enterSortMode() { swipeRevealed = null; categorySortMode = false; sortMode = true; refresh() }
    private fun exitSortMode() { sortMode = false; refresh() }
    private fun enterCategorySortMode() { swipeRevealed = null; sortMode = false; categorySortMode = true; refresh() }
    private fun exitCategorySortMode() { categorySortMode = false; refresh() }

    /** A focused list of the current category's phrases, each with a ≡ drag handle (touch-and-drag reorders
      * Chinese IME behavior note.
     *  debug.16 drag state machine (startDrag/moveDragTo/endDrag over listColumn) and onReorderPhrase. */
    private fun buildSortMode() {
        val cat = currentCategory()
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(TextView(context).apply {
                text = "拖动排序"
                setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }, ll(0, WC, 1f))
            addView(TextView(context).apply {
                text = if (cat.isEmpty()) "" else cat
                gravity = Gravity.CENTER; setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
            }, ll(0, WC, 1f))
            addView(TextView(context).apply {
                text = "完成"; gravity = Gravity.END
                setTextColor(GREEN); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                Motion.applyTapFeedback(this, GREEN)
                setOnClickListener { exitSortMode() }
            }, ll(0, WC, 1f))
        }
        main.addView(topBar, ll(MP, WC))

        listColumn.removeAllViews()
        val entries = phrasesInProvider(cat)
        if (entries.isEmpty()) listColumn.addView(emptyHint()) else for ((i, e) in entries.withIndex()) listColumn.addView(sortRow(e, i))
        main.addView(listScroll, ll(MP, 0, 1f))
    }

    private fun sortRow(text: String, index: Int): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, ImeShapes.cardRadiusDp)
            layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
        }
        col.addView(TextView(context).apply {
            this.text = preview(phraseDisplayText(text)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END // F2: note alias
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body); setTextColor(TEXT_DARK)
            setPadding(dp(14), dp(12), dp(8), dp(12))
        }, ll(0, WC, 1f))
        val handle = glyphView(TEXT_DARK, 9) { c, p, x, y, s -> Glyphs.drawList(c, p, x, y, s) } // Chinese IME behavior note.
        col.addView(handle, ll(dp(44), MP))
        attachSortDrag(handle, col, index)
        return col
    }

    /** Chinese IME behavior note. */
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
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(TextView(context).apply {
                text = "拖动分类"
                setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }, ll(0, WC, 1f))
            addView(TextView(context).apply {
                text = "完成"; gravity = Gravity.END
                setTextColor(GREEN); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                Motion.applyTapFeedback(this, GREEN)
                setOnClickListener { exitCategorySortMode() }
            }, ll(0, WC, 1f))
        }
        main.addView(topBar, ll(MP, WC))

        listColumn.removeAllViews()
        val cats = categoriesProvider()
        if (cats.isEmpty()) listColumn.addView(emptyHint()) else for ((i, name) in cats.withIndex()) listColumn.addView(categorySortRow(name, i))
        main.addView(listScroll, ll(MP, 0, 1f))
    }

    private fun categorySortRow(name: String, index: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(CARD, ImeShapes.cardRadiusDp)
            layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
        }
        row.addView(TextView(context).apply {
            text = name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body); setTextColor(TEXT_DARK)
            setPadding(dp(14), dp(12), dp(8), dp(12))
            setTypeface(null, if (name == currentCategory()) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }, ll(0, WC, 1f))
        val handle = glyphView(TEXT_DARK, 9) { c, p, x, y, s -> Glyphs.drawList(c, p, x, y, s) }
        row.addView(handle, ll(dp(44), MP))
        attachCategorySortDrag(handle, row, index)
        return row
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

    private fun categoryBar(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val cur = currentCategory()
        for (name in categoriesProvider()) chips.addView(catChip(name, name == cur))
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, ll(0, WC, 1f))
        addView(TextView(context).apply {
            text = "编辑"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTextColor(TEXT_DARK)
            contentDescription = "管理常用语"
            Motion.applyTapFeedback(this, TEXT_DARK)
            setOnClickListener { showPhraseManageMenu() }
        }, ll(dp(48), dp(40)))
    }

    /** debug.19: the categoryBar pencil menu's category-move item reorders categories, not phrases. */
    private fun showPhraseManageMenu() {
        val card = menuCard()
        card.addView(menuItem("移动分类") { hideOverlay(); enterCategorySortMode() })
        card.addView(menuDivider())
        card.addView(menuItem("添加分类") { hideOverlay(); onAddCategory() })
        card.addView(menuDivider())
        card.addView(menuItem("导入常用语") { showImportConfirm() })   // debug.17 E1: choose import mode in-panel before SAF
        card.addView(menuDivider())
        card.addView(menuItem("导出常用语") { hideOverlay(); onExportPhrases() })   // debug.17 E1: SAF export
        showOverlay(card)
    }

    private fun showImportConfirm() {
        val card = menuCard()
        card.addView(menuTitle("导入常用语"))
        card.addView(menuBody("「合并」把导入内容累加到现有常用语（按分类去重）；「覆盖」用导入文件整体替换常用语库。空文件不会清空。"))
        card.addView(menuDivider())
        card.addView(menuItem("覆盖") { hideOverlay(); onImportPhrasesWithMode(false) })
        card.addView(menuDivider())
        card.addView(menuItem("合并（推荐）") { hideOverlay(); onImportPhrasesWithMode(true) })
        card.addView(menuDivider())
        card.addView(menuItem("取消") { hideOverlay() })
        showOverlay(card)
    }

    /** debug.17 E2: clear all phrases in the current category behind a confirmation overlay. */
    private fun confirmClearCurrentCategory() {
        val cat = currentCategory()
        if (cat.isEmpty()) return
        val card = menuCard()
        card.addView(menuTitle("清空分类「$cat」的全部常用语?", color = TEXT_DARK))
        card.addView(menuDivider())
        card.addView(menuItem("清空") { hideOverlay(); onClearCategory(cat); refresh() })
        card.addView(menuDivider())
        card.addView(menuItem("取消") { hideOverlay() })
        showOverlay(card)
    }

    private fun confirmClearHistory() {
        val card = menuCard()
        card.addView(menuTitle("清空剪贴板历史?", color = TEXT_DARK))
        card.addView(menuDivider())
        card.addView(menuItem("清空") { hideOverlay(); onClearHistory(); refresh() })
        card.addView(menuDivider())
        card.addView(menuItem("取消") { hideOverlay() })
        showOverlay(card)
    }

    private fun catChip(name: String, on: Boolean): View = TextView(context).apply {
        text = name
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = null
        setTextColor(if (on) GREEN else TEXT_DARK)
        setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        Motion.applyTapFeedback(this, if (on) GREEN else TEXT_DARK)
        setOnClickListener { swipeRevealed = null; phraseCat = name; refresh() } // debug.17: a category switch drops any stale reveal
        setOnLongClickListener { showCategoryMenu(name); true } // Chinese IME behavior note.
        layoutParams = ll(WC, WC).apply { rightMargin = dp(8) }
    }

    /** Chinese IME behavior note. */
    private fun showCategoryMenu(name: String) {
        val card = menuCard()
        card.addView(menuItem("重命名「$name」") { hideOverlay(); onRenameCategory(name) })
        card.addView(menuDivider())
        card.addView(menuItem("删除「$name」") { hideOverlay(); onDeleteCategory(name); if (phraseCat == name) phraseCat = ""; swipeRevealed = null; refresh() })
        showOverlay(card)
    }

    // ---------- select mode ----------

    private fun enterSelect() { swipeRevealed = null; sortMode = false; categorySortMode = false; st.enterSelect(); refresh() }
    private fun exitSelect() { st.exitSelect(); refresh() }

    private fun buildSelectMode() {
        val all = currentEntries()
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val allSel = st.isAllSelected(all)
            addView(TextView(context).apply {
                text = "全选" // Icon polish: the radio indicator is a self-drawn leading glyph.
                setTextColor(if (allSel) GREEN else TEXT_DARK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setCompoundDrawablesWithIntrinsicBounds(glyphIcon(if (allSel) GREEN else TEXT_DARK, 22) { c, p, x, y, s -> Glyphs.drawRadio(c, p, x, y, s, allSel) }, null, null, null)
                compoundDrawablePadding = dp(6)
                Motion.applyTapFeedback(this, if (allSel) GREEN else TEXT_DARK)
                setOnClickListener { st.selectAll(all); refresh() }
            }, ll(0, WC, 1f))
            addView(TextView(context).apply {
                text = if (st.tab == Tab.PHRASE) "编辑常用语" else "编辑剪贴板" // debug.16: tab-aware select-mode title
                gravity = Gravity.CENTER
                setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }, ll(0, WC, 1f))
            addView(TextView(context).apply {
                text = "取消"; gravity = Gravity.END
                setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                Motion.applyTapFeedback(this, TEXT_DARK)
                setOnClickListener { exitSelect() }
            }, ll(0, WC, 1f))
        }
        main.addView(topBar, ll(MP, WC))

        listColumn.removeAllViews()
        for (e in all) listColumn.addView(selectRow(e))
        main.addView(listScroll, ll(MP, 0, 1f))

        val hasSel = st.hasSelection()
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            if (st.tab == Tab.PHRASE) {
                // debug.16: phrase batch action = move to category; add-phrase makes no sense for existing phrases.
                addView(pillButton("移动到分类", hasSel) {
                    val from = currentCategory(); val victims = st.selected.toList()
                    chooseMoveCategoryThen(from, victims, after = { exitSelect() }) { target -> onMovePhrasesTo(from, victims, target); exitSelect() }
                }, ll(0, dp(44), 1f).apply { rightMargin = dp(8) })
            } else {
                addView(pillButton("添加常用语", hasSel) {
                    chooseCategoryThen(st.selected.toList()) { exitSelect() }
                }, ll(0, dp(44), 1f).apply { rightMargin = dp(8) })
            }
            addView(pillButton("删除", hasSel) {
                val victims = st.selected.toList()
                if (st.tab == Tab.CLIPBOARD) onDeleteClips(victims) else onDeletePhrasesFrom(currentCategory(), victims)
                exitSelect()
            }, ll(0, dp(44), 1f))
        }
        main.addView(bottom, ll(MP, WC))
    }

    private fun selectRow(text: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(CARD, ImeShapes.cardRadiusDp)
        layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
        val on = text in st.selected
        Motion.applyTapFeedback(this, if (on) GREEN else TEXT_DARK)
        // Icon polish: the selection indicator is a self-drawn radio, filled in GREEN when selected.
        addView(glyphView(if (on) GREEN else TEXT_DARK, 8) { c, p, x, y, s -> Glyphs.drawRadio(c, p, x, y, s, on) }, ll(dp(40), MP))
        addView(TextView(context).apply {
            // Chinese IME behavior note.
            this.text = if (st.tab == Tab.PHRASE) phraseDisplayText(text) else text
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body); setTextColor(TEXT_DARK)
            setPadding(0, dp(12), dp(14), dp(12))
        }, ll(0, WC, 1f))
        setOnClickListener { st.toggleSelect(text); refresh() }
    }

    // ---------- overlays ----------

    private fun hideOverlay() { overlay.removeAllViews(); overlay.visibility = GONE }

    private fun showOverlay(content: View, gravity: Int = Gravity.CENTER, maxWidthDp: Int? = null) {
        overlay.removeAllViews()
        // Chinese IME behavior note.
        // separates it from the panel. The overlay stays clickable to catch outside taps for dismissal.
        overlay.setBackgroundColor(0x00000000)
        overlay.setOnClickListener { hideOverlay() }
        // Wrap in a ScrollView so a tall menu (many categories) scrolls instead of clipping items off-screen;
        // the ScrollView is clickable so taps on the card area don't bubble to the scrim and dismiss it.
        val scroll = ScrollView(context).apply { isClickable = true; addView(content) }
        val margin = dp(24)
        val requestedWidth = maxWidthDp?.let { minOf(dp(it), (resources.displayMetrics.widthPixels - margin * 2).coerceAtLeast(dp(260))) } ?: WC
        val lp = FrameLayout.LayoutParams(requestedWidth, WC, gravity).apply { leftMargin = margin; rightMargin = margin; topMargin = margin; bottomMargin = margin }
        overlay.addView(scroll, lp)
        overlay.visibility = VISIBLE
        Motion.revealIn(scroll, Motion.EnterFrom.BOTTOM, distanceDp = 10f, duration = Motion.REVEAL)
        scroll.post {
            val maxH = (overlay.height * 0.82f).toInt()
            if (maxH in 1 until scroll.height) { lp.height = maxH; scroll.layoutParams = lp }
        }
    }

    /** debug.16: pick a DIFFERENT existing category to move [moveTexts] into (the current category is excluded —
     *  moving in place is a no-op), OR — when no other category exists — create a NEW one carrying the move
     *  through the inline create (mirrors chooseCategoryThen's add-carry). [after] is cleanup (e.g. exitSelect)
     *  on the new-category branch; the existing-target branch runs [action]. */
    private fun chooseMoveCategoryThen(current: String, moveTexts: List<String>, after: () -> Unit = {}, action: (String) -> Unit) {
        val targets = categoriesProvider().filter { it != current }
        val card = menuCard()
        if (targets.isEmpty()) {
            card.addView(menuTitle("没有其它分类"))
            card.addView(menuDivider())
            card.addView(menuItem("＋ 新建分类…") { hideOverlay(); after(); onAddCategoryThenMove(current, moveTexts) }) // carry the move
        } else {
            card.addView(menuTitle("移动到分类"))
            // debug.17 addition: each target row = [tap = move into it] + [trash = delete that category]. The trash action reuses the
            // SAME delete semantics as the long-press chip menu (onDeleteCategory + phraseCat/swipe reset) and then
            // re-opens the chooser with the refreshed list. `current` (the move SOURCE) is excluded from targets, so
            // it can never be deleted here.
            for (c in targets) { card.addView(menuDivider()); card.addView(moveTargetRow(c, current, moveTexts, after, action)) }
            card.addView(menuDivider())
            card.addView(menuItem("＋ 新建分类…") { hideOverlay(); after(); onAddCategoryThenMove(current, moveTexts) }) // creating a category is always available with the same carry
        }
        showOverlay(card)
    }

    /** A target row in the move chooser: tapping the name moves into it (original behaviour, unchanged); the
     *  trailing trash action deletes that category with the same semantics as [showCategoryMenu]'s delete, then
     *  re-opens the chooser so the refreshed list is shown. The move only ever fires from a name tap. */
    private fun moveTargetRow(name: String, current: String, moveTexts: List<String>, after: () -> Unit, action: (String) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(menuItem(name) { hideOverlay(); action(name) }, ll(0, WC, 1f))
            // Icon polish: the per-row delete action uses a self-drawn RED trash glyph.
            addView(
                glyphView(RED, 9) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }.apply {
                    contentDescription = "删除分类"
                    Motion.applyTapFeedback(this, RED)
                    setOnClickListener {
                        onDeleteCategory(name); if (phraseCat == name) phraseCat = ""; swipeRevealed = null
                        refresh() // update the panel (categoryBar chips) immediately, exactly like the chip-menu delete
                        chooseMoveCategoryThen(current, moveTexts, after, action) // then re-open the chooser with the refreshed list
                    }
                },
                ll(dp(52), dp(48)),
            )
        }

    /** C6: long-press opens the centered delete/add/split menu. */
    private fun showLongPressMenu(text: String) {
        val card = menuCard()
        card.addView(menuItem("删除此条内容") { hideOverlay(); deleteOne(text) })
        card.addView(menuDivider())
        card.addView(menuItem("添加常用语") { hideOverlay(); chooseCategoryThen(listOf(text)) })
        card.addView(menuDivider())
        card.addView(menuItem("拆分选词") { hideOverlay(); showSplit(text) })
        showOverlay(card)
    }

    private fun showHistoryRecordingMenu() {
        val card = menuCard()
        val on = historyEnabledProvider()
        card.addView(menuItem(if (on) "剪贴板记录:开" else "剪贴板记录:关") { hideOverlay(); onSetHistoryEnabled(!on); refresh() })
        showOverlay(card)
    }

    /** C5: pick a target category to ADD [pending] phrases into, or create a NEW one — carrying [pending]
     *  THROUGH the inline create (debug.16 fix) so the clip still lands in the just-created category. [after]
     *  runs cleanup (e.g. exitSelect) on either branch. */
    private fun chooseCategoryThen(pending: List<String>, after: () -> Unit = {}) {
        val cats = categoriesProvider()
        if (cats.isEmpty()) { after(); onAddCategoryThenAdd(pending); return } // no categories yet → create one carrying the clip
        val card = menuCard()
        card.addView(menuTitle("选择分类"))
        for (c in cats) { card.addView(menuDivider()); card.addView(menuItem(c) { hideOverlay(); onSaveAsPhrasesTo(c, pending); after(); refresh() }) }
        card.addView(menuDivider())
        card.addView(menuItem("＋ 新建分类…") { hideOverlay(); after(); onAddCategoryThenAdd(pending) }) // carry the clip(s)
        showOverlay(card)
    }

    /** C4: split [text] into blocks while keeping the panel open. Tapping a block highlights it and copies it
     *  to the Aegis clipboard; the footer can copy all blocks in one action. */
    private fun showSplit(text: String) {
        splitSelected.clear()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = ImeShapes.cardRadiusDp * density; setStroke(dp(1), SEP) } // U8: bordered, no scrim
        }
        panel.addView(TextView(context).apply {
            this.text = "拆分选词"; setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        })
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val blocks = ClipSplitter.blocks(text)
        val chipViews = ArrayList<TextView>()
        if (blocks.isEmpty()) chips.addView(TextView(context).apply { this.text = "无可拆分内容"; setTextColor(HINT) })
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
            this.text = "返回"; setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setPadding(dp(8), dp(14), dp(16), dp(10)); Motion.applyTapFeedback(this, TEXT_DARK); setOnClickListener { hideOverlay() }
        }, ll(WC, WC))
        footer.addView(View(context), ll(0, dp(1), 1f))
        if (blocks.isNotEmpty()) footer.addView(TextView(context).apply {
            this.text = "全部复制"; gravity = Gravity.END; setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
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

    // ---------- shared bits ----------

    private fun currentCategory(): String {
        val cats = categoriesProvider()
        if (phraseCat !in cats) phraseCat = cats.firstOrNull().orEmpty()
        return phraseCat
    }

    private fun currentEntries(): List<String> =
        if (st.tab == Tab.CLIPBOARD) historyProvider() else phrasesInProvider(currentCategory())

    private fun deleteOne(text: String) {
        if (st.tab == Tab.CLIPBOARD) onDeleteClips(listOf(text)) else onDeletePhrasesFrom(currentCategory(), listOf(text))
        st.collapseIfExpanded(text)
        if (swipeRevealed == text) swipeRevealed = null
        refresh()
    }

    private fun pillTray(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(pill("剪贴板", st.tab == Tab.CLIPBOARD) { if (st.switchTab(Tab.CLIPBOARD)) { swipeRevealed = null; sortMode = false; categorySortMode = false; refresh() } }, ll(dp(84), dp(34)))
        addView(pill("常用语", st.tab == Tab.PHRASE) { if (st.switchTab(Tab.PHRASE)) { swipeRevealed = null; sortMode = false; categorySortMode = false; refresh() } }, ll(dp(84), dp(34)))
    }

    private fun pill(label: String, on: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        background = null
        setTextColor(if (on) GREEN else TEXT_DARK)
        setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        Motion.applyTapFeedback(this, if (on) GREEN else TEXT_DARK)
        setOnClickListener { onClick() }
    }

    private fun emptyHint(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(40), dp(16), dp(16))
        if (st.tab == Tab.CLIPBOARD) {
            addView(hint("剪贴板为空", 16f, TEXT_DARK)); addView(hint("您复制/剪切的文本会显示在这里", 14f, HINT))
        } else {
            addView(hint("该分类暂无常用语", 16f, TEXT_DARK)); addView(hint("点 ＋ 添加常用语,✎ 新建分类", 14f, HINT))
        }
    }

    private fun hint(s: String, size: Float, color: Int) = TextView(context).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setPadding(0, dp(3), 0, dp(3))
    }

    private fun menuCard(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = ImeShapes.cardRadiusDp * density; setStroke(dp(1), SEP) } // U8: bordered, no scrim
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
        text = label; gravity = Gravity.CENTER_VERTICAL or Gravity.START // C6: left-aligned menu items
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body); setTextColor(TEXT_DARK)
        setPadding(dp(24), dp(16), dp(24), dp(16))
        Motion.applyTapFeedback(this, TEXT_DARK)
        setOnClickListener { onClick() }
    }

    private fun menuDivider(): View = View(context).apply {
        setBackgroundColor(SEP)
        layoutParams = LinearLayout.LayoutParams(MP, maxOf(1, dp(1)))
    }

    private fun pillButton(label: String, enabled: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            background = rounded(if (enabled) SPLIT_BLOCK_BG else GREY_PILL, ImeShapes.chipRadiusDp)
            setTextColor(if (enabled) SPLIT_BLOCK_TEXT else TEXT_SECONDARY)
            isClickable = enabled
            if (enabled) {
                Motion.applyTapFeedback(this, SPLIT_BLOCK_TEXT)
                setOnClickListener { onClick() }
            }
        }

    // debug.17 icon polish: self-drawn single-colour Glyphs in place of font-char / emoji icons, matching the
    // app-wide language (target box, 2dp stroke, ROUND caps/joins). A [render] is a Glyphs.draw* call.
    private fun glyphPaint(tint: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * density; color = tint
    }

    /** A standalone View that paints a Glyph centred (chip buttons, chevron, drag handle). [sDp] = half-extent. */
    private fun glyphView(tint: Int, sDp: Int, render: (Canvas, Paint, Float, Float, Float) -> Unit): View =
        object : View(context) {
            private val p = glyphPaint(tint)
            override fun onDraw(c: Canvas) { render(c, p, width / 2f, height / 2f, dp(sDp).toFloat()) }
        }

    /** A self-drawn Glyph as a Drawable, for use as a TextView's leading compound icon (action-row cells). */
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

    /** Transparent top-bar control whose icon is a self-drawn Glyph (replaces a font-char roundBtn).
     *  [desc] is the contentDescription (accessibility + test locator, since there is no text). */
    private fun glyphToolbarBtn(desc: String, tint: Int = TEXT_DARK, glyphSizeDp: Int = 9, onClick: () -> Unit, render: (Canvas, Paint, Float, Float, Float) -> Unit): View =
        glyphView(tint, glyphSizeDp, render).apply {
            contentDescription = desc
            Motion.applyTapFeedback(this, tint)
            setOnClickListener { onClick() }
        }

    /** An action-row cell = self-drawn leading icon + [label] (replaces font-char delete/edit actions). */
    private fun glyphAction(label: String, tint: Int = TEXT_DARK, render: (Canvas, Paint, Float, Float, Float) -> Unit, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label); setTextColor(TEXT_DARK)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setCompoundDrawablesWithIntrinsicBounds(glyphIcon(tint, 18, render), null, null, null)
            compoundDrawablePadding = dp(2)
            Motion.applyTapFeedback(this, tint)
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * density
    }
}
