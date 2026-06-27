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
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.ime.ClipboardPanelState.Tab
import com.aegis.ime.user.ClipSplitter

class ClipboardView(context: Context) : FrameLayout(context) {

    var onPick: (String) -> Unit = {}
    var onCommitBlock: (String) -> Unit = {}
    var onBack: () -> Unit = {}
    var historyProvider: () -> List<String> = { emptyList() }
    var categoriesProvider: () -> List<String> = { emptyList() }
    var phrasesInProvider: (String) -> List<String> = { emptyList() }
    var onDeleteClips: (List<String>) -> Unit = {}
    var onDeletePhrasesFrom: (String, List<String>) -> Unit = { _, _ -> }
    var onSaveAsPhrasesTo: (String, List<String>) -> Unit = { _, _ -> }
    var onManage: () -> Unit = {}
    var onClearSystemClipboard: () -> Unit = {}
    var onClearHistory: () -> Unit = {}
    var historyEnabledProvider: () -> Boolean = { true }
    var onSetHistoryEnabled: (Boolean) -> Unit = {}

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val st = ClipboardPanelState()
    private var phraseCat = ""

    private val main = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
    private val overlay = FrameLayout(context).apply { visibility = GONE }
    private val listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
    private val listScroll = ScrollView(context).apply { addView(listColumn) }

    private companion object {
        const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
        const val GREEN = 0xFF4C9A55.toInt()
        const val GREEN_PILL = 0xFFD4E8D6.toInt()
        const val RED = 0xFFD9534F.toInt()
        const val RED_PILL = 0xFFF1D3D2.toInt()
        const val GREY_PILL = 0xFFE2E5E9.toInt()
        const val TEXT_DARK = 0xFF202124.toInt()
        const val HINT = 0xFF9AA0A6.toInt()
        const val CARD = 0xFFE9ECF0.toInt()
        const val TRAY = 0xFFEDEFF2.toInt()
        const val BG = 0xFFF4F5F7.toInt()
        const val CLIP_CAP = 1000
        const val PHRASE_CAP = 10000
    }

    private fun ll(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    init {
        addView(main, FrameLayout.LayoutParams(MP, MP))
        addView(overlay, FrameLayout.LayoutParams(MP, MP))
    }

    fun reset() { st.reset(); hideOverlay() }

    fun refresh() {
        main.removeAllViews()
        if (st.selectMode) buildSelectMode() else buildNormal()
    }


    private fun buildNormal() {
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(roundBtn("‹") { onBack() }, ll(dp(34), dp(34)))
            addView(View(context), ll(0, dp(1), 1f))
            addView(pillTray(), ll(WC, dp(36)))
            addView(View(context), ll(0, dp(1), 1f))
            if (st.tab == Tab.PHRASE) addView(roundBtn("＋") { onManage() }, ll(dp(40), dp(34)))
            addView(roundBtn("☰") { enterSelect() }, ll(dp(40), dp(34)))
            addView(roundBtn("⚙") { showGearMenu() }, ll(dp(36), dp(34)))
        }
        main.addView(topBar, ll(MP, dp(50)))
        main.addView(countLine(), ll(MP, WC))

        listColumn.removeAllViews()
        val entries = currentEntries()
        if (entries.isEmpty()) listColumn.addView(emptyHint()) else for (e in entries) listColumn.addView(card(e))
        main.addView(listScroll, ll(MP, 0, 1f))

        if (st.tab == Tab.PHRASE) main.addView(categoryBar(), ll(MP, dp(44)))
    }

    private fun card(text: String): View {
        val expanded = st.expanded == text
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 14f)
            layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val body = TextView(context).apply {
            this.text = text
            maxLines = if (expanded) 6 else 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(TEXT_DARK)
            setPadding(dp(14), dp(12), dp(8), dp(12))
            setOnClickListener { onPick(text) }
            setOnLongClickListener { showLongPressMenu(text); true }
        }
        val chevron = TextView(context).apply {
            this.text = if (expanded) "⌃" else "⌄"
            gravity = Gravity.CENTER
            setTextColor(HINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { st.toggleExpand(text); refresh() }
            setOnLongClickListener { showLongPressMenu(text); true }
        }
        header.addView(body, ll(0, WC, 1f))
        header.addView(chevron, ll(dp(40), MP))
        col.addView(header, ll(MP, WC))
        if (expanded) col.addView(actionRow(text))
        return col
    }

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
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(0xFF455A64.toInt())
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
        addView(roundBtn("✎") { onManage() }, ll(dp(40), dp(34)))
    }

    private fun catChip(name: String, on: Boolean): View = TextView(context).apply {
        text = name
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = if (on) rounded(GREY_PILL, 999f) else null
        setTextColor(if (on) TEXT_DARK else 0xFF566066.toInt())
        setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        setOnClickListener { phraseCat = name; refresh() }
        layoutParams = ll(WC, WC).apply { rightMargin = dp(8) }
    }


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
                setTextColor(if (allSel) GREEN else 0xFF566066.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setOnClickListener { st.selectAll(all); refresh() }
            }, ll(0, WC, 1f))
            addView(TextView(context).apply {
                text = "编辑剪贴板"; gravity = Gravity.CENTER
                setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }, ll(0, WC, 1f))
            addView(TextView(context).apply {
                text = "取消"; gravity = Gravity.END
                setTextColor(0xFF566066.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setOnClickListener { exitSelect() }
            }, ll(0, WC, 1f))
        }
        main.addView(topBar, ll(MP, WC))
        main.addView(countLine(), ll(MP, WC))

        listColumn.removeAllViews()
        for (e in all) listColumn.addView(selectRow(e))
        main.addView(listScroll, ll(MP, 0, 1f))

        val hasSel = st.hasSelection()
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(pillButton("添加常用语", GREEN, GREEN_PILL, hasSel) {
                chooseCategoryThen { c -> onSaveAsPhrasesTo(c, st.selected.toList()); exitSelect() }
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
        background = rounded(CARD, 14f)
        layoutParams = ll(MP, WC).apply { topMargin = dp(8) }
        val on = text in st.selected
        addView(TextView(context).apply {
            this.text = if (on) "●" else "○"
            setTextColor(if (on) GREEN else HINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(14), 0, dp(8), 0)
        }, ll(WC, WC))
        addView(TextView(context).apply {
            this.text = text; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(TEXT_DARK)
            setPadding(0, dp(12), dp(14), dp(12))
        }, ll(0, WC, 1f))
        setOnClickListener { st.toggleSelect(text); refresh() }
    }


    private fun hideOverlay() { overlay.removeAllViews(); overlay.visibility = GONE }

    private fun showOverlay(content: View, gravity: Int = Gravity.CENTER) {
        overlay.removeAllViews()
        overlay.setBackgroundColor(0x66000000)
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

    private fun showLongPressMenu(text: String) {
        val card = menuCard()
        card.addView(menuItem("删除此条内容") { hideOverlay(); deleteOne(text) })
        card.addView(menuDivider())
        card.addView(menuItem("添加常用语") { hideOverlay(); chooseCategoryThen { c -> onSaveAsPhrasesTo(c, listOf(text)) } })
        card.addView(menuDivider())
        card.addView(menuItem("拆分选词") { hideOverlay(); showSplit(text) })
        showOverlay(card)
    }

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

    private fun showSplit(text: String) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(0xFFFFFFFF.toInt(), 16f); setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        panel.addView(TextView(context).apply {
            this.text = "拆分选词"; setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        panel.addView(TextView(context).apply {
            this.text = text; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(HINT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, dp(4), 0, dp(10))
        })
        val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val blocks = ClipSplitter.blocks(text)
        if (blocks.isEmpty()) chips.addView(TextView(context).apply { this.text = "无可拆分内容"; setTextColor(HINT) })
        for (b in blocks) chips.addView(TextView(context).apply {
            this.text = b
            setTextColor(TEXT_DARK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(GREY_PILL, 999f)
            setOnClickListener { onCommitBlock(b) }
            layoutParams = ll(WC, WC).apply { rightMargin = dp(8) }
        })
        panel.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        panel.addView(menuItem("返回") { hideOverlay() }.also { it.setTextColor(GREEN) })
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
        refresh()
    }

    private fun pillTray(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(TRAY, 999f)
        addView(pill("剪贴板", st.tab == Tab.CLIPBOARD) { if (st.switchTab(Tab.CLIPBOARD)) refresh() }, ll(dp(84), dp(34)))
        addView(pill("常用语", st.tab == Tab.PHRASE) { if (st.switchTab(Tab.PHRASE)) refresh() }, ll(dp(84), dp(34)))
    }

    private fun pill(label: String, on: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = if (on) rounded(GREEN_PILL, 999f) else null
        setTextColor(if (on) GREEN else 0xFF606368.toInt())
        setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        setOnClickListener { onClick() }
    }

    private fun countLine(): View = TextView(context).apply {
        val n = currentEntries().size
        val cap = if (st.tab == Tab.CLIPBOARD) CLIP_CAP else PHRASE_CAP
        text = "共$n/${cap}条内容"
        setTextColor(HINT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(16), dp(2), dp(16), dp(6))
    }

    private fun emptyHint(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(40), dp(16), dp(16))
        if (st.tab == Tab.CLIPBOARD) {
            addView(hint("剪贴板为空", 16f, TEXT_DARK)); addView(hint("您复制/剪切的文本会显示在这里", 14f, HINT))
            addView(hint("最多记录1000条哦~", 14f, HINT))
        } else {
            addView(hint("该分类暂无常用语", 16f, TEXT_DARK)); addView(hint("点 ＋ 或 ✎ 添加 / 新建分类", 14f, HINT))
        }
    }

    private fun hint(s: String, size: Float, color: Int) = TextView(context).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setPadding(0, dp(3), 0, dp(3))
    }

    private fun menuCard(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(0xFFFFFFFF.toInt(), 16f)
    }

    private fun menuTitle(s: String): View = TextView(context).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(HINT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(dp(20), dp(12), dp(20), dp(4))
    }

    private fun menuItem(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(TEXT_DARK)
        setPadding(dp(24), dp(16), dp(24), dp(16))
        setOnClickListener { onClick() }
    }

    private fun menuDivider(): View = View(context).apply {
        setBackgroundColor(0xFFEAECEF.toInt())
        layoutParams = LinearLayout.LayoutParams(MP, maxOf(1, dp(1)))
    }

    private fun pillButton(label: String, fg: Int, bg: Int, enabled: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = rounded(if (enabled) bg else GREY_PILL, 999f)
            setTextColor(if (enabled) fg else HINT)
            isClickable = enabled
            if (enabled) setOnClickListener { onClick() }
        }

    private fun roundBtn(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(0xFF455A64.toInt())
        background = rounded(GREY_PILL, 999f)
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * density
    }
}
