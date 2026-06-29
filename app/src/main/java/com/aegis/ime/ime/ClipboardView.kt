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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.ime.ClipboardPanelState.Tab
import com.aegis.ime.user.ClipSplitter
import com.aegis.ime.user.ClipboardStore

/**
 * Clipboard-history + canned-phrases panel (C). Green pill tabs over a list of
 * cards. A card expands (chevron) to reveal +常用语 / 拆词 / 删除 (C3); long-press pops the same three
 * as a centered menu (C6). 拆词 splits a clip into tappable blocks (C4) via [ClipSplitter]. The 多选
 * entry opens the "编辑剪贴板" mode (C7: ○全选 / circle selectors / green 添加常用语 + red 删除) on BOTH
 * tabs. The 常用语 tab carries a ＋ (add) and a bottom category-chip row with a ✎ manage entry (C5);
 * the ⚙ menu clears the system clipboard / history and toggles recording (C1/C2). The tab/select/expand
 * state lives in the pure [ClipboardPanelState] (unit-tested).
 */
class ClipboardView(context: Context) : FrameLayout(context), ResettablePanel {

    var onPick: (String) -> Unit = {}                 // commit a clip/phrase and close
    var onCopyBlockToAegis: (String) -> Unit = {}     // 拆词块 → 写 aegis 剪贴板(不上屏/不写系统);面板保持打开
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
    var onAddPhrase: (String) -> Unit = {}             // debug.17: 顶部 ＋(常用语tab) → 在 (category) 下内联新增一条常用语
    var onAddCategory: () -> Unit = {}                 // debug.16 Option A: ＋分类 → inline text input
    var onAddCategoryThenAdd: (List<String>) -> Unit = {} // debug.16: 新建分类 carrying clip(s) to add once created
    var onAddCategoryThenMove: (String, List<String>) -> Unit = { _, _ -> } // debug.16: 新建分类 carrying a move (from, texts)
    var onRenameCategory: (String) -> Unit = {}        // debug.16 Option A: 分类改名 → inline text input
    var onDeleteCategory: (String) -> Unit = {}        // debug.16: 删除分类 (no typing)
    var onEditNote: (String, String) -> Unit = { _, _ -> } // debug.17 F2: (category, phrase) → inline 备注 edit
    var onClearCategory: (String) -> Unit = {}         // debug.17 E2: 清空当前分类所有常用语 (category)
    var onExportPhrases: () -> Unit = {}               // debug.17 E1: SAF 导出全部常用语
    var onImportPhrases: () -> Unit = {}               // debug.17 E1: SAF 导入常用语 (Activity 内选 合并/覆盖)
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
    private var CHIP_NEUTRAL = palette.keySurfacePressed // debug.17 拆词块默认中性底(无高亮),点击后才换成 GREY_PILL(浅紫)
    private var TEXT_DARK = palette.keyLabel
    private var HINT = palette.keyHint
    private var CARD = palette.keySurface
    private var TRAY = palette.railBg
    private var BG = palette.keyboardBg // P-A: panel floor == the strip/keyboard floor (no top seam)
    private var SUBTEXT = palette.keyLabelSecondary
    private var SEP = palette.separator

    /** F1: recolour from the Monet palette and rebuild. */
    fun applyPalette(p: ImePalette) {
        palette = p
        GREEN = p.candidateFirst; GREEN_PILL = p.chipBg; RED = p.onErrorContainer; RED_PILL = p.errorContainer
        GREY_PILL = p.chipBg; CHIP_NEUTRAL = p.keySurfacePressed; TEXT_DARK = p.keyLabel; HINT = p.keyHint; CARD = p.keySurface
        TRAY = p.railBg; BG = p.keyboardBg; SUBTEXT = p.keyLabelSecondary; SEP = p.separator // P-A: BG = unified floor
        main.setBackgroundColor(BG)
        refresh()
    }

    private val st = ClipboardPanelState()
    private var phraseCat = "" // selected 常用语 category (category picker, not part of the core state machine)

    // debug.17: a card's left-swipe reveals an inline action row WITHOUT expanding it (the ⌄ expand + long-press
    // menu are untouched). Only one card reveals at a time; a right-swipe (or tapping its body) hides it.
    private var swipeRevealed: String? = null
    // debug.17: 排序模式 (entered from the 常用语 categoryBar ✎ → 移动). A focused list of the current category's
    // phrases, each draggable to reorder (reuses the debug.16 drag state machine / onReorderPhrase).
    private var sortMode = false
    // debug.17 拆词: blocks the user has tapped (→ highlighted + copied to the aegis clipboard) this session.
    private val splitSelected = mutableSetOf<String>()

    /** debug.16: after an inline edit, reopen on the 常用语 tab (optionally at [category]) instead of the
     *  reset-default 剪贴板 tab, so the user stays where they were editing. */
    fun showPhraseTab(category: String) {
        st.switchTab(ClipboardPanelState.Tab.PHRASE)
        swipeRevealed = null; sortMode = false
        if (category.isNotEmpty() && category in categoriesProvider()) phraseCat = category
        refresh()
    }

    // debug.16: drag-to-reorder a 常用语 (long-press an un-expanded phrase card → drag up/down → drop persists).
    // dragFrom = the index the drag started at; dragCurrent = the index it would land at right now. The store is
    // reordered once on drop via onReorderPhrase(cat, dragFrom, dragCurrent). dragView is the lifted card.
    private var dragFrom = -1
    private var dragCurrent = -1
    private var dragView: View? = null
    private val dragHandler = Handler(Looper.getMainLooper())
    private val isDragging get() = dragFrom >= 0

    private val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
    private val overlay = FrameLayout(context).apply { visibility = GONE }
    // One reused list (scroll position survives refresh() — toggling a ○ deep in 编辑剪贴板 no longer jumps to top).
    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
    private val listScroll = ScrollView(context).apply { addView(listColumn) }

    private companion object {
        const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DISPLAY_CAP = 2000 // E5: max chars shown in a card preview (storage/上屏 stay full)
    }

    /** E5: a bounded preview of [s] for display only (full text is kept for tap/save). */
    private fun preview(s: String): CharSequence = if (s.length > DISPLAY_CAP) s.substring(0, DISPLAY_CAP) + "…" else s

    private fun ll(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    init {
        addView(main, FrameLayout.LayoutParams(MP, MP))
        addView(overlay, FrameLayout.LayoutParams(MP, MP))
    }

    /** Open fresh: clipboard tab, normal mode, nothing expanded/overlaid/swiped/sorting. */
    fun reset() { st.reset(); hideOverlay(); swipeRevealed = null; sortMode = false }

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
    internal fun dragStartForTest(index: Int) { startDrag(index) }
    internal fun dragMoveToForTest(index: Int) { moveDragTo(index) }
    internal fun dragDropForTest() { endDrag() }
    internal fun isDraggingForTest(): Boolean = isDragging
    internal fun expandForTest(text: String) { if (st.expanded != text) st.toggleExpand(text); refresh() }
    // debug.17 test seams: left-swipe reveal, ✎ 二级菜单, 排序模式, 拆词浮层.
    internal fun revealSwipeForTest(text: String) { revealSwipe(text) }
    internal fun hideSwipeForTest() { hideSwipe() }
    internal fun swipeRevealedForTest(): String? = swipeRevealed
    internal fun showPhraseManageMenuForTest() { showPhraseManageMenu() }
    internal fun confirmClearForTest() { confirmClearCurrentCategory() } // debug.17 E2
    internal fun enterSortModeForTest() { enterSortMode() }
    internal fun isSortModeForTest(): Boolean = sortMode
    internal fun showSplitForTest(text: String) { showSplit(text) }
    internal fun splitSelectedForTest(): Set<String> = splitSelected.toSet()
    internal fun settleSwipeForTest(dxPx: Float, text: String) { settleSwipe(dxPx, text) }

    fun refresh() {
        main.removeAllViews()
        when {
            st.selectMode -> buildSelectMode()
            sortMode -> buildSortMode()
            else -> buildNormal()
        }
    }

    // ---------- normal mode ----------

    private fun buildNormal() {
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(3)) // U-polish: 3dp so the 44dp buttons fit the 50dp bar (no clip)
            // item7: every top icon is the SAME size/shape as the ‹ back icon, and the action icons are evenly
            // spaced (a gap between them) instead of crammed.
            fun iconLp(spaced: Boolean = false) = ll(dp(36), dp(44)).apply { if (spaced) marginStart = dp(6) }
            // icon收尾: every top icon is a self-drawn Glyph in a chip (返回/＋/多选/设置/清空) — uniform with the app.
            addView(glyphChipBtn(desc = "返回", onClick = { onBack() }) { c, p, x, y, s -> Glyphs.drawBack(c, p, x, y, s) }, iconLp())
            addView(View(context), ll(0, dp(1), 1f))
            addView(pillTray(), ll(WC, dp(36)))
            addView(View(context), ll(0, dp(1), 1f))
            // debug.17: the 常用语 tab's ＋ now ADDS A PHRASE to the current category (新建分类 moved to the
            // categoryBar ✎ 二级菜单); 多选 (☰) lives on BOTH tabs.
            if (st.tab == Tab.PHRASE) addView(glyphChipBtn(desc = "添加常用语", onClick = { onAddPhrase(currentCategory()) }) { c, p, x, y, s -> Glyphs.drawPlus(c, p, x, y, s) }, iconLp(true))
            addView(glyphChipBtn(desc = "多选", onClick = { enterSelect() }) { c, p, x, y, s -> Glyphs.drawList(c, p, x, y, s) }, iconLp(true))
            // debug.17 E2: the LAST top icon is tab-specific — 常用语 tab = 清空当前分类 (二次确认, RED-tinted 🗑);
            // 剪贴板 tab = ⚙ settings.
            if (st.tab == Tab.PHRASE) addView(glyphChipBtn(desc = "清空分类", tint = RED, onClick = { confirmClearCurrentCategory() }) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }, iconLp(true))
            else addView(glyphChipBtn(desc = "设置", onClick = { showGearMenu() }) { c, p, x, y, s -> Glyphs.drawGear(c, p, x, y, s) }, iconLp(true))
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
            override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }.apply { isFillViewport = false; addView(body) }
    }

    private fun card(text: String, index: Int): View {
        val expanded = st.expanded == text
        val revealed = swipeRevealed == text // debug.17: showing its left-swipe action row
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
            // debug.17 F1: expanded shows the full text (no ellipsis); it rides a bounded ScrollView (≤4 lines).
            maxLines = if (expanded) Integer.MAX_VALUE else 2
            ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
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
        val chevron = glyphView(HINT, 7) { c, p, x, y, s -> Glyphs.drawChevron(c, p, x, y, s, down = !expanded) }.apply {
            contentDescription = if (expanded) "收起" else "展开"
            setOnClickListener { swipeRevealed = null; st.toggleExpand(text); refresh() } // ⌄展开 supersedes a swipe reveal
            if (!phrase) setOnLongClickListener { showLongPressMenu(text); true }
        }
        header.addView(if (expanded) boundedExpandBody(body) else body, ll(0, WC, 1f)) // F1: expanded body scrolls (≤4 lines)
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
        setPadding(dp(8), 0, dp(8), dp(10))
        addView(glyphAction("常用语", render = { c, p, x, y, s -> Glyphs.drawPlus(c, p, x, y, s) }) { chooseCategoryThen(listOf(text)) }, ll(0, WC, 1f))
        addView(glyphAction("拆词", render = { c, p, x, y, s -> Glyphs.drawCut(c, p, x, y, s) }) { showSplit(text) }, ll(0, WC, 1f))
        addView(glyphAction("删除", render = { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }) { deleteOne(text) }, ll(0, WC, 1f))
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
        val longPress = Runnable { startDrag(index); card.parent?.requestDisallowInterceptTouchEvent(true) }
        touchTarget.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; mode = 0
                    dragHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    false // not consumed yet → a plain tap still reaches onClick
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        card.translationY = e.rawY - downY
                        indexAtRawY(e.rawY)?.let { moveDragTo(it) }
                        true
                    } else {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                            dragHandler.removeCallbacks(longPress) // moved before long-press → not a drag
                            mode = if (abs(dx) > abs(dy)) 1 else 2
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
                        mode = if (abs(dx) > abs(dy)) 1 else 2
                        if (mode == 1) { target.cancelLongPress(); target.parent?.requestDisallowInterceptTouchEvent(true) }
                    }
                    mode == 1
                }
                MotionEvent.ACTION_UP -> { if (mode == 1) { settleSwipe(e.rawX - downX, text); true } else false }
                else -> false
            }
        }
    }

    /** A horizontal gesture of [dx] px on the card for [text]: a clear LEFT swipe reveals its action row, a clear
     *  RIGHT swipe hides any reveal. A sub-threshold drift (the handler already consumed the move, so onClick
     *  can't fire) falls back to the TAP action so a slightly-wobbly tap still 上屏s / dismisses — matching the
     *  pre-debug.17 behaviour where the listener never consumed. (Threshold > touch slop.) */
    private fun settleSwipe(dx: Float, text: String) {
        val threshold = dp(40)
        when {
            dx <= -threshold -> revealSwipe(text)
            dx >= threshold -> hideSwipe()
            else -> if (swipeRevealed == text) hideSwipe() else onPick(text) // sub-threshold drift = a tap
        }
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
        val tops = IntArray(n); val heights = IntArray(n); val loc = IntArray(2)
        for (i in 0 until n) {
            val child = listColumn.getChildAt(i)
            child.getLocationOnScreen(loc)
            tops[i] = loc[1]; heights[i] = child.height
        }
        return rowAt(tops, heights, dragFrom, rawY.toInt())
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

    private fun startDrag(index: Int) {
        dragFrom = index; dragCurrent = index
        dragView = listColumn.getChildAt(index)?.also { it.translationZ = dp(8).toFloat(); it.alpha = 0.92f }
    }

    private fun moveDragTo(index: Int) { if (index in 0 until currentEntries().size) dragCurrent = index }

    private fun endDrag() {
        val from = dragFrom; val to = dragCurrent
        dragView?.let { it.translationZ = 0f; it.alpha = 1f; it.translationY = 0f }
        dragFrom = -1; dragCurrent = -1; dragView = null
        if (from >= 0 && to >= 0 && from != to) { onReorderPhrase(currentCategory(), from, to); refresh() }
        else refresh() // reset the lifted card even on a no-op drop
    }

    override fun onDetachedFromWindow() {
        // Drop any pending long-press → drag so a panel close mid-press can't fire startDrag on a stale card.
        dragHandler.removeCallbacksAndMessages(null)
        dragFrom = -1; dragCurrent = -1; dragView = null
        super.onDetachedFromWindow()
    }

    // ---------- debug.17: 排序模式 (reorder the current category, entered from the categoryBar ✎ → 移动) ----------

    private fun enterSortMode() { swipeRevealed = null; sortMode = true; refresh() }
    private fun exitSortMode() { sortMode = false; refresh() }

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
                gravity = Gravity.CENTER; setTextColor(SUBTEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
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
        val handle = glyphView(HINT, 9) { c, p, x, y, s -> Glyphs.drawList(c, p, x, y, s) } // icon收尾: ≡ drag handle
        col.addView(handle, ll(dp(44), MP))
        attachSortDrag(handle, col, index)
        return col
    }

    /** In 排序模式 a touch on the ≡ handle starts the drag immediately (no long-press gate). */
    private fun attachSortDrag(handle: View, card: View, index: Int) {
        var downY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startDrag(index); card.parent?.requestDisallowInterceptTouchEvent(true); true }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) { card.translationY = e.rawY - downY; indexAtRawY(e.rawY)?.let { moveDragTo(it) }; true } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (isDragging) { endDrag(); true } else false }
                else -> false
            }
        }
    }

    private fun categoryBar(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(TRAY)
        setPadding(dp(8), 0, dp(8), 0)
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val cur = currentCategory()
        for (name in categoriesProvider()) chips.addView(catChip(name, name == cur))
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, ll(0, WC, 1f))
        addView(glyphChipBtn(desc = "管理常用语", onClick = { showPhraseManageMenu() }) { c, p, x, y, s -> Glyphs.drawPencil(c, p, x, y, s) }, ll(dp(36), dp(44))) // ✎ → 二级菜单
    }

    /** debug.17: the 常用语 categoryBar ✎ opens a small 二级菜单: 移动 (enter the drag-reorder 排序模式 for the
     *  current category) / 添加分类 (the existing inline new-category editor). 新建分类 used to be the ✎'s only
     *  action (and the top ＋'s) — it now lives here, alongside reordering. */
    private fun showPhraseManageMenu() {
        val card = menuCard()
        card.addView(menuItem("移动") { hideOverlay(); enterSortMode() })
        card.addView(menuDivider())
        card.addView(menuItem("添加分类") { hideOverlay(); onAddCategory() })
        card.addView(menuDivider())
        card.addView(menuItem("导入常用语") { hideOverlay(); onImportPhrases() })   // debug.17 E1: SAF 导入 (合并/覆盖 in the picker)
        card.addView(menuDivider())
        card.addView(menuItem("导出常用语") { hideOverlay(); onExportPhrases() })   // debug.17 E1: SAF 导出
        showOverlay(card)
    }

    /** debug.17 E2: 清空当前分类的全部常用语 — a destructive action behind a confirm overlay (二次确认). */
    private fun confirmClearCurrentCategory() {
        val cat = currentCategory()
        if (cat.isEmpty()) return
        val card = menuCard()
        card.addView(menuTitle("清空分类「$cat」的全部常用语?"))
        card.addView(menuDivider())
        card.addView(menuItem("清空") { hideOverlay(); onClearCategory(cat); refresh() }.also { it.setTextColor(RED) })
        card.addView(menuDivider())
        card.addView(menuItem("取消") { hideOverlay() })
        showOverlay(card)
    }

    private fun catChip(name: String, on: Boolean): View = TextView(context).apply {
        text = name
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = if (on) rounded(GREY_PILL, ImeShapes.chipRadiusDp) else null // selected chip = grey pill, others plain
        setTextColor(if (on) TEXT_DARK else SUBTEXT)
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

    // ---------- select mode (编辑剪贴板) ----------

    private fun enterSelect() { swipeRevealed = null; sortMode = false; st.enterSelect(); refresh() }
    private fun exitSelect() { st.exitSelect(); refresh() }

    private fun buildSelectMode() {
        val all = currentEntries()
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val allSel = st.isAllSelected(all)
            addView(TextView(context).apply {
                text = "全选" // icon收尾: ○/● → self-drawn leading radio
                setTextColor(if (allSel) GREEN else SUBTEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setCompoundDrawablesWithIntrinsicBounds(glyphIcon(if (allSel) GREEN else SUBTEXT, 22) { c, p, x, y, s -> Glyphs.drawRadio(c, p, x, y, s, allSel) }, null, null, null)
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
                setTextColor(SUBTEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
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
                // debug.16: 常用语 batch action = 移动到分类 (＋常用语 makes no sense for items already phrases).
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
        // icon收尾: ○/● selection indicator → self-drawn radio (filled when selected, GREEN), centred in its column.
        addView(glyphView(if (on) GREEN else HINT, 8) { c, p, x, y, s -> Glyphs.drawRadio(c, p, x, y, s, on) }, ll(dp(40), MP))
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

    private fun showOverlay(content: View, gravity: Int = Gravity.CENTER) {
        overlay.removeAllViews()
        // U8: no dim scrim behind the menu (the "暗色长方形") — the bordered card alone
        // separates it from the panel. The overlay stays clickable to catch outside taps for dismissal.
        overlay.setBackgroundColor(0x00000000)
        overlay.setOnClickListener { hideOverlay() }
        // Wrap in a ScrollView so a tall menu (many categories) scrolls instead of clipping items off-screen;
        // the ScrollView is clickable so taps on the card area don't bubble to the scrim and dismiss it.
        val scroll = ScrollView(context).apply { isClickable = true; addView(content) }
        val lp = FrameLayout.LayoutParams(WC, WC, gravity).apply { val m = dp(24); leftMargin = m; rightMargin = m; topMargin = m; bottomMargin = m }
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
            // debug.17 追加: each target row = [tap = move into it] + [🗑 = delete that category]. The 🗑 reuses the
            // SAME delete semantics as the long-press chip menu (onDeleteCategory + phraseCat/swipe reset) and then
            // re-opens the chooser with the refreshed list. `current` (the move SOURCE) is excluded from targets, so
            // it can never be deleted here.
            for (c in targets) { card.addView(menuDivider()); card.addView(moveTargetRow(c, current, moveTexts, after, action)) }
            card.addView(menuDivider())
            card.addView(menuItem("＋ 新建分类…") { hideOverlay(); after(); onAddCategoryThenMove(current, moveTexts) }) // 新建 always available, same carry
        }
        showOverlay(card)
    }

    /** A target row in the move chooser: tapping the name moves into it (original behaviour, unchanged); the
     *  trailing 🗑 deletes that category — same semantics as [showCategoryMenu]'s delete — then re-opens the
     *  chooser so the refreshed list is shown (the move only ever fires from a name tap, never from 🗑). */
    private fun moveTargetRow(name: String, current: String, moveTexts: List<String>, after: () -> Unit, action: (String) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(menuItem(name) { hideOverlay(); action(name) }, ll(0, WC, 1f))
            // icon收尾: the per-row delete 🗑 → self-drawn RED trash glyph.
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

    /** C6: long-press → centered menu 删除此条内容 / 添加常用语 / 拆分选词. */
    private fun showLongPressMenu(text: String) {
        val card = menuCard()
        card.addView(menuItem("删除此条内容") { hideOverlay(); deleteOne(text) })
        card.addView(menuDivider())
        card.addView(menuItem("添加常用语") { hideOverlay(); chooseCategoryThen(listOf(text)) })
        card.addView(menuDivider())
        card.addView(menuItem("拆分选词") { hideOverlay(); showSplit(text) })
        showOverlay(card)
    }

    /** ⚙ menu: clear history / toggle recording / manage phrases. (debug.16: 清空系统剪贴板 removed — OEM
     *  clipboards, e.g. Samsung/Vivo, silently ignore clearPrimaryClip, so the action was unreliable.) */
    private fun showGearMenu() {
        val card = menuCard()
        card.addView(menuItem("清空剪贴板历史") { hideOverlay(); onClearHistory(); refresh() })
        card.addView(menuDivider())
        val on = historyEnabledProvider()
        card.addView(menuItem(if (on) "剪贴板记录:开" else "剪贴板记录:关") { hideOverlay(); onSetHistoryEnabled(!on) })
        // debug.16: 常用语管理 entry removed — category add/rename/delete is now inline (＋ / ✎ / 长按 chip).
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

    /** C4: split [text] into blocks; the panel stays open. debug.17: each block starts NEUTRAL (no
     *  highlight); tapping it turns it 浅紫 (the highlight) AND copies it to the aegis clipboard. The 返回 row
     *  carries a 全部复制 on the far right that copies the whole original text to the aegis clipboard at once. */
    private fun showSplit(text: String) {
        splitSelected.clear()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = ImeShapes.cardRadiusDp * density; setStroke(dp(1), SEP) } // U8: bordered, no scrim
        }
        panel.addView(TextView(context).apply {
            this.text = "拆分选词"; setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        panel.addView(TextView(context).apply {
            this.text = text; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(HINT); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label); setPadding(0, dp(4), 0, dp(10))
        })
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val blocks = ClipSplitter.blocks(text)
        val chipViews = ArrayList<TextView>()
        if (blocks.isEmpty()) chips.addView(TextView(context).apply { this.text = "无可拆分内容"; setTextColor(HINT) })
        for (b in blocks) {
            val chip = TextView(context).apply {
                this.text = b
                setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(CHIP_NEUTRAL, ImeShapes.chipRadiusDp) // 默认中性(无高亮)
                layoutParams = ll(WC, WC).apply { rightMargin = dp(8) }
            }
            chip.setOnClickListener {
                splitSelected.add(b)
                chip.background = rounded(GREY_PILL, ImeShapes.chipRadiusDp) // 点击 → 浅紫高亮(选中反馈)
                onCopyBlockToAegis(b)                                       // 复制到 aegis 剪贴板,面板保持打开
            }
            chipViews.add(chip); chips.addView(chip)
        }
        panel.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        footer.addView(TextView(context).apply {
            this.text = "返回"; setTextColor(GREEN); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setPadding(dp(8), dp(14), dp(16), dp(10)); setOnClickListener { hideOverlay() }
        }, ll(WC, WC))
        footer.addView(View(context), ll(0, dp(1), 1f))
        if (blocks.isNotEmpty()) footer.addView(TextView(context).apply {
            this.text = "全部复制"; gravity = Gravity.END; setTextColor(GREEN); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setPadding(dp(16), dp(14), dp(8), dp(10))
            setOnClickListener {
                splitSelected.addAll(blocks)
                for (c in chipViews) c.background = rounded(GREY_PILL, ImeShapes.chipRadiusDp) // 全选高亮反馈
                onCopyBlockToAegis(text)                                                       // 整段原文一次性写 aegis 剪贴板
            }
        }, ll(WC, WC))
        panel.addView(footer, ll(MP, WC))
        showOverlay(panel)
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
        background = rounded(TRAY, ImeShapes.chipRadiusDp)
        addView(pill("剪贴板", st.tab == Tab.CLIPBOARD) { if (st.switchTab(Tab.CLIPBOARD)) { swipeRevealed = null; sortMode = false; refresh() } }, ll(dp(84), dp(34)))
        addView(pill("常用语", st.tab == Tab.PHRASE) { if (st.switchTab(Tab.PHRASE)) { swipeRevealed = null; sortMode = false; refresh() } }, ll(dp(84), dp(34)))
    }

    private fun pill(label: String, on: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
        background = if (on) rounded(GREEN_PILL, ImeShapes.chipRadiusDp) else null
        setTextColor(if (on) GREEN else SUBTEXT)
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

    private fun menuTitle(s: String): View = TextView(context).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(HINT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label); setPadding(dp(20), dp(12), dp(20), dp(4))
    }

    private fun menuItem(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.CENTER_VERTICAL or Gravity.START // left-aligned menu items
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

    // debug.17 (icon收尾): self-drawn single-colour Glyphs in place of font-char / emoji icons, matching the
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

    /** Top-bar / categoryBar chip button whose icon is a self-drawn Glyph (replaces a font-char roundBtn).
     *  [desc] is the contentDescription (accessibility + test locator, since there is no text). */
    private fun glyphChipBtn(desc: String, tint: Int = SUBTEXT, onClick: () -> Unit, render: (Canvas, Paint, Float, Float, Float) -> Unit): View =
        glyphView(tint, 10, render).apply {
            background = rounded(GREY_PILL, ImeShapes.chipRadiusDp)
            contentDescription = desc
            setOnClickListener { onClick() }
        }

    /** An action-row cell = self-drawn leading icon + [label] (replaces "🗑 删除" / "✎ 编辑" font-char actions). */
    private fun glyphAction(label: String, tint: Int = SUBTEXT, render: (Canvas, Paint, Float, Float, Float) -> Unit, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label); setTextColor(SUBTEXT)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setCompoundDrawablesWithIntrinsicBounds(glyphIcon(tint, 20, render), null, null, null)
            compoundDrawablePadding = dp(4)
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * density
    }
}
