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
 * Clipboard-history + canned-phrases panel (C). Green pill tabs over a list of
 * cards. A card expands (chevron) to reveal +常用语 / 拆词 / 删除 (C3); long-press pops the same three
 * as a centered menu (C6). 拆词 splits a clip into tappable blocks (C4) via [ClipSplitter]. The 多选
 * entry opens the "编辑剪贴板" mode (C7: ○全选 / circle selectors / green 添加常用语 + red 删除) on BOTH
 * tabs. The 常用语 tab carries a ＋ (add) and a bottom category-chip row with a ✎ manage entry (C5).
 * The tab/select/expand state lives in the pure [ClipboardPanelState] (unit-tested).
 */
class ClipboardView(context: Context) : FrameLayout(context), ResettablePanel {

    var onPick: (String) -> Unit = {}                 // commit a clip/phrase and close
    var onCopyBlockToAegis: (String) -> Unit = {}     // 拆词块 → 写 aegis 剪贴板(不上屏/不写系统);面板保持打开
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
    var onAddPhrase: (String) -> Unit = {}             // debug.17: 顶部 ＋(常用语tab) → 在 (category) 下内联新增一条常用语
    var onAddCategory: () -> Unit = {}                 // debug.16 Option A: ＋分类 → inline text input
    var onAddCategoryThenAdd: (List<String>) -> Unit = {} // debug.16: 新建分类 carrying clip(s) to add once created
    var onAddCategoryThenMove: (String, List<String>) -> Unit = { _, _ -> } // debug.16: 新建分类 carrying a move (from, texts)
    var onRenameCategory: (String) -> Unit = {}        // debug.16 Option A: 分类改名 → inline text input
    var onDeleteCategory: (String) -> Unit = {}        // debug.16: 删除分类 (no typing)
    var onEditNote: (String, String) -> Unit = { _, _ -> } // debug.17 F2: (category, phrase) → inline 备注 edit
    var onClearCategory: (String) -> Unit = {}         // debug.17 E2: 清空当前分类所有常用语 (category)
    var onExportPhrases: () -> Unit = {}               // debug.17 E1: SAF 导出全部常用语
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
    private var GREEN_PILL = palette.chipBg
    private var RED = palette.onErrorContainer    // U-polish: 删除 text on its destructive container
    private var RED_PILL = palette.errorContainer // U-polish: 删除 reads red (MD3 destructive), not a grey chip
    private var GREY_PILL = palette.chipBg
    private var SPLIT_BLOCK_BG = palette.chipBg
    private var SPLIT_BLOCK_TEXT = palette.chipText
    private var SPLIT_BLOCK_COPIED_BG = palette.accentBottom
    private var SPLIT_BLOCK_COPIED_TEXT = palette.accentLabel
    private var TEXT_DARK = palette.keyLabel
    private var HINT = palette.keyHint
    private var CARD = palette.keySurface
    private var BG = palette.keyboardBg // P-A: panel floor == the strip/keyboard floor (no top seam)
    private var SEP = palette.separator

    /** F1: recolour from the Monet palette and rebuild. */
    fun applyPalette(p: ImePalette) {
        palette = p
        GREEN = p.candidateFirst; GREEN_PILL = p.chipBg; RED = p.onErrorContainer; RED_PILL = p.errorContainer
        GREY_PILL = p.chipBg; SPLIT_BLOCK_BG = p.chipBg; SPLIT_BLOCK_TEXT = p.chipText
        SPLIT_BLOCK_COPIED_BG = p.accentBottom; SPLIT_BLOCK_COPIED_TEXT = p.accentLabel
        TEXT_DARK = p.keyLabel; HINT = p.keyHint; CARD = p.keySurface
        BG = p.keyboardBg; SEP = p.separator // P-A: BG = unified floor
        main.setBackgroundColor(BG)
        refresh()
    }

    private val st = ClipboardPanelState()
    private var phraseCat = "" // selected 常用语 category (category picker, not part of the core state machine)

    // debug.17: a card's left-swipe reveals an inline action row WITHOUT expanding it (the ⌄ expand + long-press
    // menu are untouched). Only one card reveals at a time; a right-swipe (or tapping its body) hides it.
    private var swipeRevealed: String? = null
    // Phrase reorder mode remains available for direct entry points; category reorder mode is opened from the
    // categoryBar pencil menu's category-move item.
    private var sortMode = false
    private var categorySortMode = false
    // debug.17 拆词: blocks the user has tapped (→ highlighted + copied to the aegis clipboard) this session.
    private val splitSelected = mutableSetOf<String>()

    /** debug.16: after an inline edit, reopen on the 常用语 tab (optionally at [category]) instead of the
     *  reset-default 剪贴板 tab, so the user stays where they were editing. */
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
    // One reused list (scroll position survives refresh() — toggling a ○ deep in 编辑剪贴板 no longer jumps to top).
    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
    private val listScroll = ScrollView(context).apply { addView(listColumn) }

    private companion object {
        const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DISPLAY_CAP = 2000 // E5: max chars shown in a card preview (storage/上屏 stay full)
        // debug.18 F: a swipe is treated as a vertical list scroll only when dy dominates dx by this factor —
        // i.e. the direction decision is biased toward HORIZONTAL (left-swipe reveal) unless clearly vertical.
        const val SWIPE_VERTICAL_BIAS = 1.5f
        const val DRAG_AUTO_SCROLL_INTERVAL_MS = 16L
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    /** Open fresh: clipboard tab, normal mode, nothing expanded/overlaid/swiped/sorting. */
    fun reset() { st.reset(); hideOverlay(); swipeRevealed = null; sortMode = false; categorySortMode = false }

    /**
     * P7 (#19): on dismissal, return to the default view — clipboard tab, normal mode, no expanded card /
     * overlay, the 常用语 category picker cleared, and the list scrolled to the top — so reopening the panel
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
    internal fun isDraggingForTest(): Boolean = isDragging
    internal fun dragTranslationYForTest(): Float = dragView?.translationY ?: 0f
    internal fun dragUpdateForTest(rawY: Float) { updateActiveDrag(rawY) }
    internal fun runDragAutoScrollFrameForTest(): Boolean = runDragAutoScrollFrame()
    internal fun isDragAutoScrollScheduledForTest(): Boolean = dragAutoScrollScheduled
    internal fun listScrollYForTest(): Int = listScroll.scrollY
    internal fun listScrollRawTopForTest(): Int {
        val loc = IntArray(2)
        listScroll.getLocationOnScreen(loc)
        return loc[1]
    }
    internal fun listScrollRawBottomForTest(): Int = listScrollRawTopForTest() + listScroll.height
    internal fun expandForTest(text: String) { if (st.expanded != text) st.toggleExpand(text); refresh() }
    // debug.17 test seams: left-swipe reveal, ✎ 二级菜单, 排序模式, 拆词浮层.
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
        return out
    }

    fun refresh() {
        main.removeAllViews()
        when {
            st.selectMode -> buildSelectMode()
            categorySortMode -> buildCategorySortMode()
            sortMode -> buildSortMode()
            else -> buildNormal()
        }
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
            // debug.17: the 常用语 tab's ＋ now ADDS A PHRASE to the current category (新建分类 moved to the
            // categoryBar ✎ 二级菜单); 多选 (☰) lives on BOTH tabs.
            if (st.tab == Tab.PHRASE) addView(glyphToolbarBtn(desc = "添加常用语", onClick = { onAddPhrase(currentCategory()) }) { c, p, x, y, s -> Glyphs.drawPlus(c, p, x, y, s) }, iconLp(true))
            addView(glyphToolbarBtn(desc = "多选", onClick = { enterSelect() }) { c, p, x, y, s -> Glyphs.drawList(c, p, x, y, s) }, iconLp(true))
            // The last top icon is tab-specific: both destructive clear actions require confirmation.
            if (st.tab == Tab.PHRASE) addView(glyphToolbarBtn(desc = "清空分类", tint = TEXT_DARK, onClick = { confirmClearCurrentCategory() }) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }, iconLp(true))
            else addView(glyphToolbarBtn(desc = "清空剪贴板历史", tint = TEXT_DARK, onClick = { confirmClearHistory() }) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }.apply {
                setOnLongClickListener { showHistoryRecordingMenu(); true }
            }, iconLp(true))
        }
        main.addView(topBar, ll(MP, dp(50)))
        // U9: no 字数/条数上限 line.
        listColumn.removeAllViews()
        val entries = currentEntries()
        if (entries.isEmpty()) listColumn.addView(emptyHint()) else for ((i, e) in entries.withIndex()) listColumn.addView(card(e, i))
        main.addView(listScroll, ll(MP, 0, 1f))

        if (st.tab == Tab.PHRASE) main.addView(categoryBar(), ll(MP, dp(44)))
    }

    /** debug.17 F2: what a 常用语 SHOWS in the list — its note if set, else the phrase text itself. */
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
            // gesture the outer one intercepts the vertical drag and the inner never scrolls (展开卡滚不动).
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
        // debug.17 F2: a 常用语 with a note DISPLAYS the note (alias); 上屏 (onPick) still uses the original `text`.
        val display = if (phrase) phraseDisplayText(text) else text
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, ImeShapes.cardRadiusDp)
            layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val body = TextView(context).apply {
            // E5: show only a bounded PREVIEW — a million-char entry would make the TextView measure/layout the
            // whole string and jank. Storage + 上屏 (onPick) always use the full `text`, never the preview.
            this.text = preview(display)
            // debug.19: expanded and left-swiped cards show the full preview in a bounded four-line body.
            maxLines = if (openBody) Integer.MAX_VALUE else 2
            ellipsize = if (openBody) null else android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTextColor(TEXT_DARK)
            setPadding(dp(14), dp(12), dp(8), dp(12))
            // debug.17: tapping a SWIPE-revealed card dismisses the action row (it doesn't 上屏); otherwise 上屏.
            setOnClickListener { if (swipeRevealed == text) hideSwipe() else onPick(text) }
            // 剪贴板 history: long-press = the C6 menu. 常用语 (debug.16): long-press an UN-expanded card = drag to
            // reorder (wired below); its 编辑/移动/删除 moved to the expanded action row.
            if (!phrase) setOnLongClickListener { showLongPressMenu(text); true }
        }
        // icon收尾: ⌄/⌃ is now a self-drawn chevron (down=collapsed, up=expanded).
        val chevron = glyphView(TEXT_DARK, 7) { c, p, x, y, s -> Glyphs.drawChevron(c, p, x, y, s, down = !expanded) }.apply {
            contentDescription = if (expanded) "收起" else "展开"
            setOnClickListener { swipeRevealed = null; st.toggleExpand(text); refresh() } // ⌄展开 supersedes a swipe reveal
            if (!phrase) setOnLongClickListener { showLongPressMenu(text); true }
        }
        header.addView(if (openBody) boundedExpandBody(body) else body, ll(0, WC, 1f)) // open body scrolls (≤4 lines)
        header.addView(chevron, ll(dp(40), MP))
        col.addView(header, ll(MP, WC))
        // debug.17: ⌄展开 (unchanged) → the expand action row; left-swipe (NEW) → an inline action row without
        // expanding (剪贴板: 添加常用语/拆词/删除 — same as expand; 常用语: 编辑/置顶/删除). A collapsed 常用语
        // card keeps its long-press drag-reorder; the swipe is merged into the same touch handler.
        when {
            expanded -> col.addView(if (phrase) phraseActionRow(text) else actionRow(text))
            revealed -> { col.addView(if (phrase) phraseSwipeRow(text, index) else actionRow(text)); attachSwipeReveal(body, text) }
            phrase -> attachDragHandle(body, col, index, text)
            else -> attachSwipeReveal(body, text)
        }
        return col
    }

    /** Expanded 剪贴板 history card's bottom action row (C3): +常用语 / 拆词 / 删除. */
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

    /** debug.16: expanded 常用语 card action row = 编辑 / 移动 / 删除 (＋常用语 is meaningless for an existing
     *  phrase; 拆词 isn't wanted here). 编辑 = inline text input (Option A); 移动 picks a target category
     *  in-panel; 删除 reuses deletePhraseFrom. */
    private fun phraseActionRow(text: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), 0, dp(8), dp(10))
        val cat = currentCategory()
        addView(glyphAction("编辑", render = { c, p, x, y, s -> Glyphs.drawEditCaret(c, p, x, y, s) }) { onEditPhrase(cat, text) }, ll(0, WC, 1f))
        addView(glyphAction("备注", render = { c, p, x, y, s -> Glyphs.drawTag(c, p, x, y, s) }) { onEditNote(cat, text) }, ll(0, WC, 1f)) // F2
        addView(glyphAction("移动", render = { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, Glyphs.Arrow.RIGHT) }) { chooseMoveCategoryThen(cat, listOf(text)) { target -> onMovePhrase(cat, text, target); refresh() } }, ll(0, WC, 1f))
        addView(glyphAction("删除", render = { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }) { deleteOne(text) }, ll(0, WC, 1f))
    }

    /** debug.17: a 常用语 card's LEFT-SWIPE action row = 编辑 / 置顶 / 删除. 置顶 reorders the phrase to the top
     *  of its category (reuses onReorderPhrase(cat, index, 0)). Distinct from the ⌄-expand row (编辑/移动/删除),
     *  which is unchanged. [index] is the phrase's current position in the category. */
    private fun phraseSwipeRow(text: String, index: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), 0, dp(8), dp(10))
        val cat = currentCategory()
        addView(glyphAction("编辑", render = { c, p, x, y, s -> Glyphs.drawEditCaret(c, p, x, y, s) }) { onEditPhrase(cat, text) }, ll(0, WC, 1f))
        addView(glyphAction("置顶", render = { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, Glyphs.Arrow.UP) }) { onReorderPhrase(cat, index, 0); swipeRevealed = null; refresh() }, ll(0, WC, 1f))
        addView(glyphAction("删除", render = { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }) { deleteOne(text) }, ll(0, WC, 1f))
    }

    // ---------- debug.16: drag-to-reorder a 常用语 card ----------

    /** Long-press a collapsed 常用语 card → lift it → drag up/down → drop persists the new order. The card's
     *  own onClick (上屏) still fires for a plain tap; a pre-long-press VERTICAL move is treated as a scroll, a
     *  pre-long-press HORIZONTAL move (debug.17) becomes the left-swipe reveal — so one touch handler serves
     *  tap + long-press-drag + swipe without ever stealing the others' gestures. */
    private fun attachDragHandle(touchTarget: View, card: View, index: Int, text: String) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var mode = 0 // 0=undecided, 1=horizontal swipe, 2=vertical scroll (drag is tracked by isDragging)
        val longPress = Runnable { startDrag(index, downY); card.parent?.requestDisallowInterceptTouchEvent(true) }
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
                        isDragging -> { endDrag(); true }
                        mode == 1 && e.actionMasked == MotionEvent.ACTION_UP -> { settleSwipe(e.rawX - downX, text); true }
                        else -> false
                    }
                }
                else -> false
            }
        }
    }

    /** debug.17: a swipe-only reveal handler for cards that don't carry the drag handler (剪贴板 cards, and any
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
     *  NEVER 上屏 — that was the bug where a light left-swipe typed the clip instead of revealing its actions.
     *  Any LEFT swipe ([dx] negative) reveals the action row regardless of distance (even a short, deliberate
     *  swipe); a RIGHT swipe hides any reveal. A plain TAP never reaches here (it stays mode==0 → onClick → 上屏),
     *  so 上屏 still works for taps. */
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
        val tops = IntArray(n); val heights = IntArray(n)
        val contentTop = listContentRawTop()
        for (i in 0 until n) {
            val child = listColumn.getChildAt(i)
            tops[i] = contentTop + child.top; heights[i] = child.height
        }
        return rowAt(tops, heights, dragVisualIndex, rawY.toInt())
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
        val minStep = dp(4).coerceAtLeast(1)
        val maxStep = dp(18).coerceAtLeast(minStep)
        val ratio = (distanceIntoEdge.coerceIn(0f, edge.toFloat()) / edge)
        return minStep + ((maxStep - minStep) * ratio).toInt()
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
            listScroll.requestDisallowInterceptTouchEvent(true)
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
            listScroll.requestDisallowInterceptTouchEvent(true)
        }
        rawY?.let { y -> updateDraggedTranslation(y); updateDragAutoScroll() }
    }

    private fun moveDragTo(index: Int, rawY: Float? = null) {
        val n = if (dragKind == DragKind.CATEGORY) categoriesProvider().size else currentEntries().size
        if (index !in 0 until n) return
        dragCurrent = index
        if (dragView != null && index != dragVisualIndex) {
            moveDraggedViewTo(index)
            rawY?.let { updateDraggedTranslation(it) }
        }
    }

    private fun moveDraggedViewTo(index: Int) {
        val view = dragView ?: return
        val from = dragVisualIndex
        if (from !in 0 until listColumn.childCount) return
        listColumn.removeViewAt(from)
        listColumn.addView(view, index.coerceIn(0, listColumn.childCount))
        dragVisualIndex = index
        listColumn.requestLayout()
        listColumn.invalidate()
        listColumn.post { if (isDragging) updateDraggedTranslation(dragLastRawY) }
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
        dragView?.let { it.translationZ = 0f; it.alpha = 1f; it.translationY = 0f }
        dragFrom = -1; dragCurrent = -1; dragVisualIndex = -1; dragTouchOffsetY = 0f; dragLastRawY = 0f; dragView = null; dragKind = DragKind.NONE
        if (from >= 0 && to >= 0 && from != to) {
            if (kind == DragKind.CATEGORY) onReorderCategory(from, to) else onReorderPhrase(currentCategory(), from, to)
            refresh()
        }
        else refresh() // reset the lifted card even on a no-op drop
    }

    override fun onDetachedFromWindow() {
        // Drop any pending long-press → drag so a panel close mid-press can't fire startDrag on a stale card.
        dragHandler.removeCallbacksAndMessages(null)
        dragAutoScrollScheduled = false
        dragFrom = -1; dragCurrent = -1; dragVisualIndex = -1; dragTouchOffsetY = 0f; dragLastRawY = 0f; dragView = null; dragKind = DragKind.NONE
        super.onDetachedFromWindow()
    }

    // ---------- debug.17: 排序模式 (reorder phrases in the current category) ----------

    private fun enterSortMode() { swipeRevealed = null; categorySortMode = false; sortMode = true; refresh() }
    private fun exitSortMode() { sortMode = false; refresh() }
    private fun enterCategorySortMode() { swipeRevealed = null; sortMode = false; categorySortMode = true; refresh() }
    private fun exitCategorySortMode() { categorySortMode = false; refresh() }

    /** A focused list of the current category's phrases, each with a ≡ drag handle (touch-and-drag reorders
     *  immediately — no long-press needed, since the user explicitly entered this mode). 完成 exits. Reuses the
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
        val handle = glyphView(TEXT_DARK, 9) { c, p, x, y, s -> Glyphs.drawList(c, p, x, y, s) } // icon收尾: ≡ drag handle
        col.addView(handle, ll(dp(44), MP))
        attachSortDrag(handle, col, index)
        return col
    }

    /** In 排序模式 a touch on the ≡ handle starts the drag immediately (no long-press gate). */
    private fun attachSortDrag(handle: View, card: View, index: Int) {
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startDrag(index, e.rawY); card.parent?.requestDisallowInterceptTouchEvent(true); true }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) { updateActiveDrag(e.rawY); true } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (isDragging) { endDrag(); true } else false }
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
                MotionEvent.ACTION_DOWN -> { startCategoryDrag(index, e.rawY); card.parent?.requestDisallowInterceptTouchEvent(true); true }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) { updateActiveDrag(e.rawY); true } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (isDragging) { endDrag(); true } else false }
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
        setOnClickListener { swipeRevealed = null; phraseCat = name; refresh() } // debug.17: a category switch drops any stale reveal
        setOnLongClickListener { showCategoryMenu(name); true } // debug.16: 长按 chip → 改名 / 删除 (inline)
        layoutParams = ll(WC, WC).apply { rightMargin = dp(8) }
    }

    /** debug.16: long-press a category chip → inline 改名 (text input) / 删除. */
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
                addView(pillButton("移动到分类", GREEN, GREEN_PILL, hasSel) {
                    val from = currentCategory(); val victims = st.selected.toList()
                    chooseMoveCategoryThen(from, victims, after = { exitSelect() }) { target -> onMovePhrasesTo(from, victims, target); exitSelect() }
                }, ll(0, dp(44), 1f).apply { rightMargin = dp(8) })
            } else {
                addView(pillButton("添加常用语", GREEN, GREEN_PILL, hasSel) {
                    chooseCategoryThen(st.selected.toList()) { exitSelect() }
                }, ll(0, dp(44), 1f).apply { rightMargin = dp(8) })
            }
            addView(pillButton("删除", RED, RED_PILL, hasSel) {
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
        // Icon polish: the selection indicator is a self-drawn radio, filled in GREEN when selected.
        addView(glyphView(if (on) GREEN else TEXT_DARK, 8) { c, p, x, y, s -> Glyphs.drawRadio(c, p, x, y, s, on) }, ll(dp(40), MP))
        addView(TextView(context).apply {
            // debug.17 F2: a 常用语 with a note shows the note; selection still keys on the original `text`.
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
        // U8: no dim scrim behind the menu (the "暗色长方形") — the bordered card alone
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
                onCopyBlockToAegis(b)
            }
            chipViews.add(chip); chips.addView(chip)
        }
        panel.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, ll(MP, WC))
        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        footer.addView(TextView(context).apply {
            this.text = "返回"; setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setPadding(dp(8), dp(14), dp(16), dp(10)); setOnClickListener { hideOverlay() }
        }, ll(WC, WC))
        footer.addView(View(context), ll(0, dp(1), 1f))
        if (blocks.isNotEmpty()) footer.addView(TextView(context).apply {
            this.text = "全部复制"; gravity = Gravity.END; setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setPadding(dp(16), dp(14), dp(8), dp(10))
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
        setOnClickListener { onClick() }
    }

    private fun menuDivider(): View = View(context).apply {
        setBackgroundColor(SEP)
        layoutParams = LinearLayout.LayoutParams(MP, maxOf(1, dp(1)))
    }

    private fun pillButton(label: String, fg: Int, bg: Int, enabled: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            background = rounded(if (enabled) bg else GREY_PILL, ImeShapes.chipRadiusDp)
            setTextColor(if (enabled) fg else HINT)
            isClickable = enabled
            if (enabled) setOnClickListener { onClick() }
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
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * density
    }
}
