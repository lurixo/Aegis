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

class ClipboardView(context: Context) : FrameLayout(context), ResettablePanel {

    var onPick: (String) -> Unit = {}
    var onCopyBlockToAegis: (String) -> Unit = {}
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
    var onClearHistory: () -> Unit = {}
    var historyEnabledProvider: () -> Boolean = { true }
    var onSetHistoryEnabled: (Boolean) -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var palette = ImePalette.STATIC_LIGHT
    private var GREEN = palette.candidateFirst
    private var GREEN_PILL = palette.chipBg
    private var RED = palette.onErrorContainer
    private var RED_PILL = palette.errorContainer
    private var GREY_PILL = palette.chipBg
    private var CHIP_NEUTRAL = palette.keySurfacePressed
    private var TEXT_DARK = palette.keyLabel
    private var HINT = palette.keyHint
    private var CARD = palette.keySurface
    private var BG = palette.keyboardBg
    private var SEP = palette.separator

    fun applyPalette(p: ImePalette) {
        palette = p
        GREEN = p.candidateFirst; GREEN_PILL = p.chipBg; RED = p.onErrorContainer; RED_PILL = p.errorContainer
        GREY_PILL = p.chipBg; CHIP_NEUTRAL = p.keySurfacePressed; TEXT_DARK = p.keyLabel; HINT = p.keyHint; CARD = p.keySurface
        BG = p.keyboardBg; SEP = p.separator
        main.setBackgroundColor(BG)
        refresh()
    }

    private val st = ClipboardPanelState()
    private var phraseCat = ""

    private var swipeRevealed: String? = null
    private var sortMode = false
    private var categorySortMode = false
    private val splitSelected = mutableSetOf<String>()

    fun showPhraseTab(category: String) {
        st.switchTab(ClipboardPanelState.Tab.PHRASE)
        swipeRevealed = null; sortMode = false; categorySortMode = false
        if (category.isNotEmpty() && category in categoriesProvider()) phraseCat = category
        refresh()
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

    private val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
    private val overlay = FrameLayout(context).apply { visibility = GONE }
    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
    private val listScroll = ScrollView(context).apply { addView(listColumn) }

    private companion object {
        const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DISPLAY_CAP = 2000
        const val SWIPE_VERTICAL_BIAS = 1.5f
    }

    private fun preview(s: String): CharSequence = if (s.length > DISPLAY_CAP) s.substring(0, DISPLAY_CAP) + "…" else s

    private fun ll(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    init {
        addView(main, FrameLayout.LayoutParams(MP, MP))
        addView(overlay, FrameLayout.LayoutParams(MP, MP))
    }

    fun reset() { st.reset(); hideOverlay(); swipeRevealed = null; sortMode = false; categorySortMode = false }

    override fun resetToDefault() {
        reset()
        phraseCat = ""
        listScroll.scrollTo(0, 0)
    }

    internal fun isClipboardTabForTest(): Boolean = st.tab == ClipboardPanelState.Tab.CLIPBOARD
    internal fun phraseCatForTest(): String = phraseCat
    internal fun forcePhrasesStateForTest(cat: String) { st.switchTab(ClipboardPanelState.Tab.PHRASE); phraseCat = cat }
    internal fun enterSelectForTest(selected: List<String> = emptyList()) { st.enterSelect(); st.selected.addAll(selected); refresh() }
    internal fun showMoveChooserForTest(current: String) { chooseMoveCategoryThen(current, emptyList()) { target -> onMovePhrase(current, "", target) } }
    internal fun dragStartForTest(index: Int) { if (categorySortMode) startCategoryDrag(index) else startDrag(index) }
    internal fun dragStartAtForTest(index: Int, rawY: Float) { if (categorySortMode) startCategoryDrag(index, rawY) else startDrag(index, rawY) }
    internal fun dragMoveToForTest(index: Int) { moveDragTo(index) }
    internal fun dragMoveAtForTest(index: Int, rawY: Float) { updateDraggedTranslation(rawY); moveDragTo(index, rawY) }
    internal fun dragDropForTest() { endDrag() }
    internal fun isDraggingForTest(): Boolean = isDragging
    internal fun dragTranslationYForTest(): Float = dragView?.translationY ?: 0f
    internal fun expandForTest(text: String) { if (st.expanded != text) st.toggleExpand(text); refresh() }
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
            if (st.tab == Tab.PHRASE) addView(glyphToolbarBtn(desc = "添加常用语", onClick = { onAddPhrase(currentCategory()) }) { c, p, x, y, s -> Glyphs.drawPlus(c, p, x, y, s) }, iconLp(true))
            addView(glyphToolbarBtn(desc = "多选", onClick = { enterSelect() }) { c, p, x, y, s -> Glyphs.drawList(c, p, x, y, s) }, iconLp(true))
            if (st.tab == Tab.PHRASE) addView(glyphToolbarBtn(desc = "清空分类", tint = RED, onClick = { confirmClearCurrentCategory() }) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }, iconLp(true))
            else addView(glyphToolbarBtn(desc = "清空剪贴板历史", tint = RED, onClick = { confirmClearHistory() }) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }.apply {
                setOnLongClickListener { showHistoryRecordingMenu(); true }
            }, iconLp(true))
        }
        main.addView(topBar, ll(MP, dp(50)))
        listColumn.removeAllViews()
        val entries = currentEntries()
        if (entries.isEmpty()) listColumn.addView(emptyHint()) else for ((i, e) in entries.withIndex()) listColumn.addView(card(e, i))
        main.addView(listScroll, ll(MP, 0, 1f))

        if (st.tab == Tab.PHRASE) main.addView(categoryBar(), ll(MP, dp(44)))
    }

    private fun phraseDisplayText(text: String): String {
        val note = phraseNoteProvider(currentCategory(), text)
        return if (note.isNotEmpty()) note else text
    }

    private fun boundedExpandBody(body: TextView): View {
        val maxH = body.lineHeight * 4 + body.paddingTop + body.paddingBottom
        return object : ScrollView(context) {
            private var lastRawY = 0f
            override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    lastRawY = e.rawY
                    if (canScrollVertically(1) || canScrollVertically(-1)) parent?.requestDisallowInterceptTouchEvent(true)
                }
                return super.onInterceptTouchEvent(e)
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                    val dir = if (e.rawY < lastRawY) 1 else -1
                    lastRawY = e.rawY
                    parent?.requestDisallowInterceptTouchEvent(canScrollVertically(dir))
                }
                return super.onTouchEvent(e)
            }
        }.apply { isFillViewport = false; addView(body) }
    }

    private fun card(text: String, index: Int): View {
        val expanded = st.expanded == text
        val revealed = swipeRevealed == text
        val openBody = expanded || revealed
        val phrase = st.tab == Tab.PHRASE
        val display = if (phrase) phraseDisplayText(text) else text
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, ImeShapes.cardRadiusDp)
            layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val body = TextView(context).apply {
            this.text = preview(display)
            maxLines = if (openBody) Integer.MAX_VALUE else 2
            ellipsize = if (openBody) null else android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTextColor(TEXT_DARK)
            setPadding(dp(14), dp(12), dp(8), dp(12))
            setOnClickListener { if (swipeRevealed == text) hideSwipe() else onPick(text) }
            if (!phrase) setOnLongClickListener { showLongPressMenu(text); true }
        }
        val chevron = glyphView(TEXT_DARK, 7) { c, p, x, y, s -> Glyphs.drawChevron(c, p, x, y, s, down = !expanded) }.apply {
            contentDescription = if (expanded) "收起" else "展开"
            setOnClickListener { swipeRevealed = null; st.toggleExpand(text); refresh() }
            if (!phrase) setOnLongClickListener { showLongPressMenu(text); true }
        }
        header.addView(if (openBody) boundedExpandBody(body) else body, ll(0, WC, 1f))
        header.addView(chevron, ll(dp(40), MP))
        col.addView(header, ll(MP, WC))
        when {
            expanded -> col.addView(if (phrase) phraseActionRow(text) else actionRow(text))
            revealed -> { col.addView(if (phrase) phraseSwipeRow(text, index) else actionRow(text)); attachSwipeReveal(body, text) }
            phrase -> attachDragHandle(body, col, index, text)
            else -> attachSwipeReveal(body, text)
        }
        return col
    }

    private fun actionRow(text: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), 0, dp(8), dp(10))
        addView(glyphAction("常用语", render = { c, p, x, y, s -> Glyphs.drawPlus(c, p, x, y, s) }) { chooseCategoryThen(listOf(text)) }, ll(0, WC, 1f))
        addView(glyphAction("拆词", render = { c, p, x, y, s -> Glyphs.drawCut(c, p, x, y, s) }) { showSplit(text) }, ll(0, WC, 1f))
        addView(glyphAction("删除", render = { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }) { deleteOne(text) }, ll(0, WC, 1f))
    }

    private fun phraseActionRow(text: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), 0, dp(8), dp(10))
        val cat = currentCategory()
        addView(glyphAction("编辑", render = { c, p, x, y, s -> Glyphs.drawEditCaret(c, p, x, y, s) }) { onEditPhrase(cat, text) }, ll(0, WC, 1f))
        addView(glyphAction("备注", render = { c, p, x, y, s -> Glyphs.drawTag(c, p, x, y, s) }) { onEditNote(cat, text) }, ll(0, WC, 1f))
        addView(glyphAction("移动", render = { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, Glyphs.Arrow.RIGHT) }) { chooseMoveCategoryThen(cat, listOf(text)) { target -> onMovePhrase(cat, text, target); refresh() } }, ll(0, WC, 1f))
        addView(glyphAction("删除", render = { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }) { deleteOne(text) }, ll(0, WC, 1f))
    }

    private fun phraseSwipeRow(text: String, index: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), 0, dp(8), dp(10))
        val cat = currentCategory()
        addView(glyphAction("编辑", render = { c, p, x, y, s -> Glyphs.drawEditCaret(c, p, x, y, s) }) { onEditPhrase(cat, text) }, ll(0, WC, 1f))
        addView(glyphAction("置顶", render = { c, p, x, y, s -> Glyphs.drawArrow(c, p, x, y, s, Glyphs.Arrow.UP) }) { onReorderPhrase(cat, index, 0); swipeRevealed = null; refresh() }, ll(0, WC, 1f))
        addView(glyphAction("删除", render = { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }) { deleteOne(text) }, ll(0, WC, 1f))
    }


    private fun attachDragHandle(touchTarget: View, card: View, index: Int, text: String) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var mode = 0
        val longPress = Runnable { startDrag(index, downY); card.parent?.requestDisallowInterceptTouchEvent(true) }
        touchTarget.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; mode = 0
                    dragHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        updateDraggedTranslation(e.rawY)
                        indexAtRawY(e.rawY)?.let { moveDragTo(it, e.rawY) }
                        true
                    } else {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                            dragHandler.removeCallbacks(longPress)
                            mode = if (abs(dy) > abs(dx) * SWIPE_VERTICAL_BIAS) 2 else 1
                            if (mode == 1) card.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        mode == 1
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

    private fun settleSwipe(dx: Float, text: String) {
        if (dx < 0f) revealSwipe(text) else hideSwipe()
    }

    private fun revealSwipe(text: String) {
        if (st.expanded == text) st.toggleExpand(text)
        swipeRevealed = text
        refresh()
    }

    private fun hideSwipe() { if (swipeRevealed != null) { swipeRevealed = null; refresh() } }

    private fun indexAtRawY(rawY: Float): Int? {
        val n = listColumn.childCount
        if (n == 0) return null
        val tops = IntArray(n); val heights = IntArray(n); val loc = IntArray(2)
        for (i in 0 until n) {
            val child = listColumn.getChildAt(i)
            child.getLocationOnScreen(loc)
            tops[i] = loc[1]; heights[i] = child.height
        }
        return rowAt(tops, heights, dragVisualIndex, rawY.toInt())
    }

    internal fun rowAt(tops: IntArray, heights: IntArray, skip: Int, y: Int): Int? {
        for (i in tops.indices) {
            if (i == skip) continue
            if (y >= tops[i] && y <= tops[i] + heights[i]) return i
        }
        return null
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
        }
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
        }
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
        val loc = IntArray(2)
        listColumn.getLocationOnScreen(loc)
        val baseTop = loc[1] + view.top
        view.translationY = rawY - dragTouchOffsetY - baseTop
    }

    private fun endDrag() {
        val from = dragFrom; val to = dragCurrent
        val kind = dragKind
        dragView?.let { it.translationZ = 0f; it.alpha = 1f; it.translationY = 0f }
        dragFrom = -1; dragCurrent = -1; dragVisualIndex = -1; dragTouchOffsetY = 0f; dragLastRawY = 0f; dragView = null; dragKind = DragKind.NONE
        if (from >= 0 && to >= 0 && from != to) {
            if (kind == DragKind.CATEGORY) onReorderCategory(from, to) else onReorderPhrase(currentCategory(), from, to)
            refresh()
        }
        else refresh()
    }

    override fun onDetachedFromWindow() {
        dragHandler.removeCallbacksAndMessages(null)
        dragFrom = -1; dragCurrent = -1; dragVisualIndex = -1; dragTouchOffsetY = 0f; dragLastRawY = 0f; dragView = null; dragKind = DragKind.NONE
        super.onDetachedFromWindow()
    }


    private fun enterSortMode() { swipeRevealed = null; categorySortMode = false; sortMode = true; refresh() }
    private fun exitSortMode() { sortMode = false; refresh() }
    private fun enterCategorySortMode() { swipeRevealed = null; sortMode = false; categorySortMode = true; refresh() }
    private fun exitCategorySortMode() { categorySortMode = false; refresh() }

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
            this.text = preview(phraseDisplayText(text)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body); setTextColor(TEXT_DARK)
            setPadding(dp(14), dp(12), dp(8), dp(12))
        }, ll(0, WC, 1f))
        val handle = glyphView(TEXT_DARK, 9) { c, p, x, y, s -> Glyphs.drawList(c, p, x, y, s) }
        col.addView(handle, ll(dp(44), MP))
        attachSortDrag(handle, col, index)
        return col
    }

    private fun attachSortDrag(handle: View, card: View, index: Int) {
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startDrag(index, e.rawY); card.parent?.requestDisallowInterceptTouchEvent(true); true }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) { updateDraggedTranslation(e.rawY); indexAtRawY(e.rawY)?.let { moveDragTo(it, e.rawY) }; true } else false
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
                    if (isDragging) { updateDraggedTranslation(e.rawY); indexAtRawY(e.rawY)?.let { moveDragTo(it, e.rawY) }; true } else false
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

    private fun showPhraseManageMenu() {
        val card = menuCard()
        card.addView(menuItem("移动分类") { hideOverlay(); enterCategorySortMode() })
        card.addView(menuDivider())
        card.addView(menuItem("添加分类") { hideOverlay(); onAddCategory() })
        card.addView(menuDivider())
        card.addView(menuItem("导入常用语") { hideOverlay(); onImportPhrases() })
        card.addView(menuDivider())
        card.addView(menuItem("导出常用语") { hideOverlay(); onExportPhrases() })
        showOverlay(card)
    }

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
        setOnClickListener { swipeRevealed = null; phraseCat = name; refresh() }
        setOnLongClickListener { showCategoryMenu(name); true }
        layoutParams = ll(WC, WC).apply { rightMargin = dp(8) }
    }

    private fun showCategoryMenu(name: String) {
        val card = menuCard()
        card.addView(menuItem("重命名「$name」") { hideOverlay(); onRenameCategory(name) })
        card.addView(menuDivider())
        card.addView(menuItem("删除「$name」") { hideOverlay(); onDeleteCategory(name); if (phraseCat == name) phraseCat = ""; swipeRevealed = null; refresh() })
        showOverlay(card)
    }


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
                text = "全选"
                setTextColor(if (allSel) GREEN else TEXT_DARK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setCompoundDrawablesWithIntrinsicBounds(glyphIcon(if (allSel) GREEN else TEXT_DARK, 22) { c, p, x, y, s -> Glyphs.drawRadio(c, p, x, y, s, allSel) }, null, null, null)
                compoundDrawablePadding = dp(6)
                setOnClickListener { st.selectAll(all); refresh() }
            }, ll(0, WC, 1f))
            addView(TextView(context).apply {
                text = if (st.tab == Tab.PHRASE) "编辑常用语" else "编辑剪贴板"
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
        addView(glyphView(if (on) GREEN else TEXT_DARK, 8) { c, p, x, y, s -> Glyphs.drawRadio(c, p, x, y, s, on) }, ll(dp(40), MP))
        addView(TextView(context).apply {
            this.text = if (st.tab == Tab.PHRASE) phraseDisplayText(text) else text
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body); setTextColor(TEXT_DARK)
            setPadding(0, dp(12), dp(14), dp(12))
        }, ll(0, WC, 1f))
        setOnClickListener { st.toggleSelect(text); refresh() }
    }


    private fun hideOverlay() { overlay.removeAllViews(); overlay.visibility = GONE }

    private fun showOverlay(content: View, gravity: Int = Gravity.CENTER) {
        overlay.removeAllViews()
        overlay.setBackgroundColor(0x00000000)
        overlay.setOnClickListener { hideOverlay() }
        val scroll = ScrollView(context).apply { isClickable = true; addView(content) }
        val lp = FrameLayout.LayoutParams(WC, WC, gravity).apply { val m = dp(24); leftMargin = m; rightMargin = m; topMargin = m; bottomMargin = m }
        overlay.addView(scroll, lp)
        overlay.visibility = VISIBLE
        scroll.post {
            val maxH = (overlay.height * 0.82f).toInt()
            if (maxH in 1 until scroll.height) { lp.height = maxH; scroll.layoutParams = lp }
        }
    }

    private fun chooseMoveCategoryThen(current: String, moveTexts: List<String>, after: () -> Unit = {}, action: (String) -> Unit) {
        val targets = categoriesProvider().filter { it != current }
        val card = menuCard()
        if (targets.isEmpty()) {
            card.addView(menuTitle("没有其它分类"))
            card.addView(menuDivider())
            card.addView(menuItem("＋ 新建分类…") { hideOverlay(); after(); onAddCategoryThenMove(current, moveTexts) })
        } else {
            card.addView(menuTitle("移动到分类"))
            for (c in targets) { card.addView(menuDivider()); card.addView(moveTargetRow(c, current, moveTexts, after, action)) }
            card.addView(menuDivider())
            card.addView(menuItem("＋ 新建分类…") { hideOverlay(); after(); onAddCategoryThenMove(current, moveTexts) })
        }
        showOverlay(card)
    }

    private fun moveTargetRow(name: String, current: String, moveTexts: List<String>, after: () -> Unit, action: (String) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(menuItem(name) { hideOverlay(); action(name) }, ll(0, WC, 1f))
            addView(
                glyphView(RED, 9) { c, p, x, y, s -> Glyphs.drawTrash(c, p, x, y, s) }.apply {
                    contentDescription = "删除分类"
                    setOnClickListener {
                        onDeleteCategory(name); if (phraseCat == name) phraseCat = ""; swipeRevealed = null
                        refresh()
                        chooseMoveCategoryThen(current, moveTexts, after, action)
                    }
                },
                ll(dp(52), dp(48)),
            )
        }

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

    private fun chooseCategoryThen(pending: List<String>, after: () -> Unit = {}) {
        val cats = categoriesProvider()
        if (cats.isEmpty()) { after(); onAddCategoryThenAdd(pending); return }
        val card = menuCard()
        card.addView(menuTitle("选择分类"))
        for (c in cats) { card.addView(menuDivider()); card.addView(menuItem(c) { hideOverlay(); onSaveAsPhrasesTo(c, pending); after(); refresh() }) }
        card.addView(menuDivider())
        card.addView(menuItem("＋ 新建分类…") { hideOverlay(); after(); onAddCategoryThenAdd(pending) })
        showOverlay(card)
    }

    private fun showSplit(text: String) {
        splitSelected.clear()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = ImeShapes.cardRadiusDp * density; setStroke(dp(1), SEP) }
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
                background = rounded(CHIP_NEUTRAL, ImeShapes.chipRadiusDp)
                layoutParams = ll(WC, WC).apply { rightMargin = dp(8) }
            }
            chip.setOnClickListener {
                splitSelected.add(b)
                chip.background = rounded(GREY_PILL, ImeShapes.chipRadiusDp)
                onCopyBlockToAegis(b)
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
                for (c in chipViews) c.background = rounded(GREY_PILL, ImeShapes.chipRadiusDp)
                for (b in blocks) onCopyBlockToAegis(b)
            }
        }, ll(WC, WC))
        panel.addView(footer, ll(MP, WC))
        showOverlay(panel)
    }


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
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = ImeShapes.cardRadiusDp * density; setStroke(dp(1), SEP) }
    }

    private fun menuTitle(s: String, color: Int = TEXT_DARK): View = TextView(context).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label); setPadding(dp(20), dp(12), dp(20), dp(4))
    }

    private fun menuItem(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.CENTER_VERTICAL or Gravity.START
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
            setOnClickListener { onClick() }
        }

    private fun glyphAction(label: String, tint: Int = TEXT_DARK, render: (Canvas, Paint, Float, Float, Float) -> Unit, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label); setTextColor(TEXT_DARK)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setCompoundDrawablesWithIntrinsicBounds(glyphIcon(tint, 20, render), null, null, null)
            compoundDrawablePadding = dp(4)
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * density
    }
}
