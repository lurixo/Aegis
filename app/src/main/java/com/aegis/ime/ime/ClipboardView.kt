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
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
    var onPickImage: (String) -> Unit = {}            // U22: tap an image entry → paste image (path arg)
    var isImage: (String) -> Boolean = { false }      // M-1: VALIDATED image check (marker + real file), host-supplied
    var thumbnailProvider: (String) -> Bitmap? = { null } // U22: path → CACHED thumbnail (no decode), or null
    var onLoadThumbnail: (String, (Bitmap?) -> Unit) -> Unit = { _, cb -> cb(null) } // U22: decode async, call back on UI thread
    var onCopyBlockToAegis: (String) -> Unit = {}     // 拆词块 → 写 aegis 剪贴板(不上屏/不写系统);面板保持打开
    var onBack: () -> Unit = {}
    var historyProvider: () -> List<String> = { emptyList() }
    var categoriesProvider: () -> List<String> = { emptyList() }
    var phrasesInProvider: (String) -> List<String> = { emptyList() }
    var onDeleteClips: (List<String>) -> Unit = {}
    var onDeletePhrasesFrom: (String, List<String>) -> Unit = { _, _ -> }
    var onSaveAsPhrasesTo: (String, List<String>) -> Unit = { _, _ -> }
    var onManage: () -> Unit = {}                      // open the phrase-manager Activity (naming needs it)
    var onClearSystemClipboard: () -> Unit = {}        // C2
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
    private var TEXT_DARK = palette.keyLabel
    private var HINT = palette.keyHint
    private var CARD = palette.keySurface
    private var TRAY = palette.railBg
    private var BG = palette.panelBg
    private var SUBTEXT = palette.keyLabelSecondary
    private var SEP = palette.separator

    /** F1: recolour from the Monet palette and rebuild. */
    fun applyPalette(p: ImePalette) {
        palette = p
        GREEN = p.candidateFirst; GREEN_PILL = p.chipBg; RED = p.onErrorContainer; RED_PILL = p.errorContainer
        GREY_PILL = p.chipBg; TEXT_DARK = p.keyLabel; HINT = p.keyHint; CARD = p.keySurface
        TRAY = p.railBg; BG = p.panelBg; SUBTEXT = p.keyLabelSecondary; SEP = p.separator
        main.setBackgroundColor(BG)
        refresh()
    }

    private val st = ClipboardPanelState()
    private var phraseCat = "" // selected 常用语 category (category picker, not part of the core state machine)

    private val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
    private val overlay = FrameLayout(context).apply { visibility = GONE }
    // One reused list (scroll position survives refresh() — toggling a ○ deep in 编辑剪贴板 no longer jumps to top).
    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
    private val listScroll = ScrollView(context).apply { addView(listColumn) }

    private companion object {
        const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private fun ll(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    init {
        addView(main, FrameLayout.LayoutParams(MP, MP))
        addView(overlay, FrameLayout.LayoutParams(MP, MP))
    }

    /** Open fresh: clipboard tab, normal mode, nothing expanded/overlaid. */
    fun reset() { st.reset(); hideOverlay() }

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

    fun refresh() {
        main.removeAllViews()
        if (st.selectMode) buildSelectMode() else buildNormal()
    }

    // ---------- normal mode ----------

    private fun buildNormal() {
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(3), dp(8), dp(3)) // U-polish: 3dp so the 44dp buttons fit the 50dp bar (no clip)
            addView(roundBtn("‹") { onBack() }, ll(dp(34), dp(44)))
            addView(View(context), ll(0, dp(1), 1f))
            addView(pillTray(), ll(WC, dp(36)))
            addView(View(context), ll(0, dp(1), 1f))
            // the 常用语 tab adds a ＋; 多选 (☰) lives on BOTH tabs, then ⚙.
            if (st.tab == Tab.PHRASE) addView(roundBtn("＋") { onManage() }, ll(dp(40), dp(44)))
            addView(roundBtn("☰") { enterSelect() }, ll(dp(40), dp(44)))
            addView(roundBtn("⚙") { showGearMenu() }, ll(dp(36), dp(44)))
        }
        main.addView(topBar, ll(MP, dp(50)))
        // U9: no 字数/条数上限 line.
        listColumn.removeAllViews()
        val entries = currentEntries()
        if (entries.isEmpty()) listColumn.addView(emptyHint()) else for (e in entries) listColumn.addView(card(e))
        main.addView(listScroll, ll(MP, 0, 1f))

        if (st.tab == Tab.PHRASE) main.addView(categoryBar(), ll(MP, dp(44)))
    }

    private fun card(text: String): View {
        if (isImage(text)) return imageCard(text) // U22 (M-1: only a marker backed by a real file)
        val expanded = st.expanded == text
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, ImeShapes.cardRadiusDp)
            layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val body = TextView(context).apply {
            this.text = text
            maxLines = if (expanded) 6 else 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setTextColor(TEXT_DARK)
            setPadding(dp(14), dp(12), dp(8), dp(12))
            setOnClickListener { onPick(text) }
            // C6: long-press must live on the views that consume the touch (a clickable child blocks the
            // parent's long-press detector), else hold-release would just paste+close via the click above.
            setOnLongClickListener { showLongPressMenu(text); true }
        }
        val chevron = TextView(context).apply {
            this.text = if (expanded) "⌃" else "⌄"
            gravity = Gravity.CENTER
            setTextColor(HINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setOnClickListener { st.toggleExpand(text); refresh() }
            setOnLongClickListener { showLongPressMenu(text); true }
        }
        header.addView(body, ll(0, WC, 1f))
        header.addView(chevron, ll(dp(40), MP))
        col.addView(header, ll(MP, WC))
        if (expanded) col.addView(actionRow(text))
        return col
    }

    /** U22: an image history entry — a thumbnail (tap → paste) with long-press → delete. No expand/拆词.
     *  The thumbnail is shown from cache if present, else decoded ASYNC (B1: never decode on the UI thread). */
    private fun imageCard(entry: String): View {
        val path = ClipboardStore.imagePath(entry)
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, ImeShapes.cardRadiusDp)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
        }
        val img = ImageView(context).apply {
            adjustViewBounds = true
            maxHeight = dp(140)
            minimumHeight = dp(48) // placeholder height while loading
            scaleType = ImageView.ScaleType.FIT_START
            setOnClickListener { onPickImage(path) }
            setOnLongClickListener { showImageMenu(entry); true }
        }
        col.addView(img, ll(WC, WC))
        val cached = thumbnailProvider(path)
        if (cached != null) {
            img.setImageBitmap(cached)
        } else {
            onLoadThumbnail(path) { bmp ->
                img.post {
                    if (bmp != null) img.setImageBitmap(bmp)
                    else { img.visibility = GONE; col.addView(imageFallback(path, entry), ll(MP, WC)) }
                }
            }
        }
        col.setOnLongClickListener { showImageMenu(entry); true }
        return col
    }

    /** Shown when an image can't be decoded (file pruned / gone) — still tappable to attempt paste. */
    private fun imageFallback(path: String, entry: String): View = hint("［图片］点按粘贴", 15f, TEXT_DARK).apply {
        setOnClickListener { onPickImage(path) }
        setOnLongClickListener { showImageMenu(entry); true }
    }

    /** U22: minimal long-press menu for an image entry (only 删除 applies). */
    private fun showImageMenu(entry: String) {
        val card = menuCard()
        card.addView(menuItem("删除此条内容") { hideOverlay(); deleteOne(entry) })
        showOverlay(card)
    }

    /** Expanded card's bottom action row (C3): +常用语 / 拆词 / 删除. */
    private fun actionRow(text: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), 0, dp(8), dp(10))
        addView(action("＋ 常用语") { chooseCategoryThen { c -> onSaveAsPhrasesTo(c, listOf(text)) } }, ll(0, WC, 1f))
        addView(action("拆 拆词") { showSplit(text) }, ll(0, WC, 1f))
        addView(action("🗑 删除") { deleteOne(text) }, ll(0, WC, 1f))
    }

    private fun action(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setTextColor(SUBTEXT)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setOnClickListener { onClick() }
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
        addView(roundBtn("✎") { onManage() }, ll(dp(40), dp(44))) // ✎ 管理
    }

    private fun catChip(name: String, on: Boolean): View = TextView(context).apply {
        text = name
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.label)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = if (on) rounded(GREY_PILL, ImeShapes.chipRadiusDp) else null // selected chip = grey pill, others plain
        setTextColor(if (on) TEXT_DARK else SUBTEXT)
        setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        setOnClickListener { phraseCat = name; refresh() }
        layoutParams = ll(WC, WC).apply { rightMargin = dp(8) }
    }

    // ---------- select mode (编辑剪贴板) ----------

    private fun enterSelect() { st.enterSelect(); refresh() }
    private fun exitSelect() { st.exitSelect(); refresh() }

    private fun buildSelectMode() {
        val all = currentEntries()
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val allSel = st.isAllSelected(all)
            addView(TextView(context).apply {
                text = if (allSel) "● 全选" else "○ 全选"
                setTextColor(if (allSel) GREEN else SUBTEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
                setOnClickListener { st.selectAll(all); refresh() }
            }, ll(0, WC, 1f))
            addView(TextView(context).apply {
                text = "编辑剪贴板"; gravity = Gravity.CENTER
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
            addView(pillButton("添加常用语", GREEN, GREEN_PILL, hasSel) {
                // M-2: never save image entries as phrases (their marker/path would become a dead 常用语).
                chooseCategoryThen { c -> onSaveAsPhrasesTo(c, st.selected.filterNot { ClipboardStore.isImageEntry(it) }); exitSelect() }
            }, ll(0, dp(44), 1f).apply { rightMargin = dp(8) })
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
        addView(TextView(context).apply {
            this.text = if (on) "●" else "○"
            setTextColor(if (on) GREEN else HINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.title)
            setPadding(dp(14), 0, dp(8), 0)
        }, ll(WC, WC))
        addView(TextView(context).apply {
            // U22: image entries show a label (not the raw marker path) in select mode.
            this.text = if (isImage(text)) "［图片］" else text
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

    /** C6: long-press → centered menu 删除此条内容 / 添加常用语 / 拆分选词. */
    private fun showLongPressMenu(text: String) {
        val card = menuCard()
        card.addView(menuItem("删除此条内容") { hideOverlay(); deleteOne(text) })
        card.addView(menuDivider())
        card.addView(menuItem("添加常用语") { hideOverlay(); chooseCategoryThen { c -> onSaveAsPhrasesTo(c, listOf(text)) } })
        card.addView(menuDivider())
        card.addView(menuItem("拆分选词") { hideOverlay(); showSplit(text) })
        showOverlay(card)
    }

    /** ⚙ menu: clear system clipboard / clear history / toggle recording / manage phrases. */
    private fun showGearMenu() {
        val card = menuCard()
        card.addView(menuItem("清空系统剪贴板") { hideOverlay(); onClearSystemClipboard() })
        card.addView(menuDivider())
        card.addView(menuItem("清空剪贴板历史") { hideOverlay(); onClearHistory(); refresh() })
        card.addView(menuDivider())
        val on = historyEnabledProvider()
        card.addView(menuItem(if (on) "剪贴板记录:开" else "剪贴板记录:关") { hideOverlay(); onSetHistoryEnabled(!on) })
        card.addView(menuDivider())
        card.addView(menuItem("常用语管理") { hideOverlay(); onManage() })
        showOverlay(card)
    }

    /** C5: pick the target category for an add, or jump to the manager to create one. */
    private fun chooseCategoryThen(action: (String) -> Unit) {
        val cats = categoriesProvider()
        if (cats.isEmpty()) { onManage(); return }
        val card = menuCard()
        card.addView(menuTitle("选择分类"))
        for (c in cats) { card.addView(menuDivider()); card.addView(menuItem(c) { hideOverlay(); action(c); refresh() }) }
        card.addView(menuDivider())
        card.addView(menuItem("＋ 新建分类…") { hideOverlay(); onManage() })
        showOverlay(card)
    }

    /** C4: split [text] into blocks; tap a block to commit it (panel stays open). */
    private fun showSplit(text: String) {
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
        if (blocks.isEmpty()) chips.addView(TextView(context).apply { this.text = "无可拆分内容"; setTextColor(HINT) })
        for (b in blocks) chips.addView(TextView(context).apply {
            this.text = b
            setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(GREY_PILL, ImeShapes.chipRadiusDp)
            setOnClickListener { onCopyBlockToAegis(b) } // 拆词块写 aegis 剪贴板,面板保持打开
            layoutParams = ll(WC, WC).apply { rightMargin = dp(8) }
        })
        panel.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        panel.addView(menuItem("返回") { hideOverlay() }.also { it.setTextColor(GREEN) })
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
        refresh()
    }

    private fun pillTray(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(TRAY, ImeShapes.chipRadiusDp)
        addView(pill("剪贴板", st.tab == Tab.CLIPBOARD) { if (st.switchTab(Tab.CLIPBOARD)) refresh() }, ll(dp(84), dp(34)))
        addView(pill("常用语", st.tab == Tab.PHRASE) { if (st.switchTab(Tab.PHRASE)) refresh() }, ll(dp(84), dp(34)))
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
            addView(hint("该分类暂无常用语", 16f, TEXT_DARK)); addView(hint("点 ＋ 或 ✎ 添加 / 新建分类", 14f, HINT))
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

    private fun roundBtn(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, ImeType.body); setTextColor(SUBTEXT)
        background = rounded(GREY_PILL, ImeShapes.chipRadiusDp) // light circular chip with the ☰/＋/⚙ glyphs
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * density
    }
}
