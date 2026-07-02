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

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * debug.17 clipboard/phrase panel interaction coverage. Includes top add-phrase behavior, the categoryBar
 * pencil submenu, clipboard and phrase left-swipe reveals, phrase pinning, split blocks, and regression checks
 * for chevron expansion and long-press menus. Overlay menus live in ClipboardView child 1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Debug17PanelTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT

    private fun overlayOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(1)
    private fun mainOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(0)
    private fun textViews(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(x: View) { if (x is TextView) out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun labels(root: View): List<String> = textViews(root).mapNotNull { it.text?.toString() }
    private fun click(root: View, label: String): Boolean {
        val tv = textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() } ?: return false
        tv.performClick(); return true
    }
    /** Chinese IME behavior note. */
    private fun chip(root: View, label: String): TextView? =
        textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() }
    private fun bgColor(v: View): Int? = (v.background as? GradientDrawable)?.color?.defaultColor
    // Chinese IME behavior note.
    // contentDescription instead.
    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun descs(root: View): List<String> = allViews(root).mapNotNull { it.contentDescription?.toString() }
    private fun clickDesc(root: View, desc: String): Boolean {
        val v = allViews(root).firstOrNull { it.contentDescription?.toString() == desc && it.hasOnClickListeners() } ?: return false
        v.performClick(); return true
    }
    private fun longClickDesc(root: View, desc: String): Boolean {
        val v = allViews(root).firstOrNull { it.contentDescription?.toString() == desc && it.hasOnClickListeners() } ?: return false
        return v.performLongClick()
    }

    private fun phraseView(phrases: List<String> = listOf("你好", "在吗", "稍等")): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认", "工作") }
        phrasesInProvider = { c -> if (c == "默认") phrases else listOf("已收到") }
        applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
    }
    private fun clipView(history: List<String> = listOf("hello")): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history }; categoriesProvider = { listOf("默认") }
        applyPalette(pal); refresh()
    }

    // Chinese IME behavior note.

    @Test fun top_plus_adds_phrase_to_current_category_not_a_new_category() {
        var addPhraseCat: String? = null
        var addCategoryFired = false
        val v = phraseView().apply { onAddPhrase = { addPhraseCat = it }; onAddCategory = { addCategoryFired = true } }
        assertTrue("top ＋ present on 常用语 tab", clickDesc(v, "添加常用语"))
        assertEquals("＋ adds a phrase to the CURRENT category", "默认", addPhraseCat)
        assertFalse("＋ no longer creates a category", addCategoryFired)
    }

    // Chinese IME behavior note.

    @Test fun categorybar_pencil_opens_manage_menu() {
        val v = phraseView()
        assertTrue(clickDesc(v, "管理常用语"))
        val ls = labels(overlayOf(v))
        assertTrue("menu has 移动分类", "移动分类" in ls) // Chinese IME behavior note.
        assertTrue("menu has 添加分类", "添加分类" in ls)
    }

    @Test fun manage_menu_move_category_enters_category_sort_mode() {
        val v = phraseView()
        clickDesc(v, "管理常用语"); assertTrue(click(overlayOf(v), "移动分类")) // Chinese IME behavior note.
        assertTrue("移动分类 → category sort mode", v.isCategorySortModeForTest())
        assertFalse("does not enter phrase sort mode", v.isSortModeForTest())
        val ls = labels(v)
        assertTrue("category sort header", "拖动分类" in ls); assertTrue("done button", "完成" in ls)
    }

    @Test fun manage_menu_add_category_triggers_inline_create() {
        var addCategoryFired = false
        val v = phraseView().apply { onAddCategory = { addCategoryFired = true } }
        clickDesc(v, "管理常用语"); assertTrue(click(overlayOf(v), "添加分类"))
        assertTrue("添加分类 → inline 新建分类", addCategoryFired)
    }

    @Test fun sort_mode_done_exits() {
        val v = phraseView(); v.enterSortModeForTest()
        assertTrue(v.isSortModeForTest())
        assertTrue(click(v, "完成")); assertFalse(v.isSortModeForTest())
    }

    @Test fun sort_mode_drag_reorders_current_category() {
        var reorder: Triple<String, Int, Int>? = null
        val v = phraseView().apply { onReorderPhrase = { c, f, t -> reorder = Triple(c, f, t) } }
        v.enterSortModeForTest()
        v.dragStartForTest(0); v.dragMoveToForTest(2); v.dragDropForTest()
        assertEquals("drag in 排序模式 reorders the current category", Triple("默认", 0, 2), reorder)
    }

    @Test fun category_sort_mode_drag_reorders_categories_not_phrases() {
        var categoryReorder: Pair<Int, Int>? = null
        var phraseReorder: Triple<String, Int, Int>? = null
        val v = phraseView().apply {
            onReorderCategory = { from, to -> categoryReorder = from to to }
            onReorderPhrase = { c, f, t -> phraseReorder = Triple(c, f, t) }
        }
        v.enterCategorySortModeForTest()
        v.dragStartForTest(0); v.dragMoveToForTest(1); v.dragDropForTest()
        assertEquals("category drag calls category reorder callback", 0 to 1, categoryReorder)
        assertNull("category drag must not call phrase reorder", phraseReorder)
    }

    @Test fun category_sort_mode_updates_category_chip_order() {
        val cats = mutableListOf("默认", "工作", "私人")
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { cats }
            phrasesInProvider = { emptyList() }
            onReorderCategory = { from, to -> cats.add(to, cats.removeAt(from)) }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
        }
        v.enterCategorySortModeForTest()
        v.dragStartForTest(2); v.dragMoveToForTest(0); v.dragDropForTest()
        assertEquals(listOf("私人", "默认", "工作"), labels(v).filter { it in cats })
        assertTrue(click(v, "完成"))
        val chipOrder = textViews(v)
            .filter { it.text?.toString() in cats && it.hasOnClickListeners() }
            .map { it.text.toString() }
        assertEquals("category chips follow persisted category order", listOf("私人", "默认", "工作"), chipOrder)
    }

    // Chinese IME behavior note.

    @Test fun clipboard_left_swipe_reveals_action_row_without_expanding() {
        val v = clipView()
        v.revealSwipeForTest("hello")
        assertEquals("hello", v.swipeRevealedForTest())
        val ls = labels(v)
        assertTrue("添加常用语 action", ls.any { it.contains("常用语") })
        assertTrue("拆词 action", ls.any { it.contains("拆词") })
        assertTrue("删除 action", ls.any { it.contains("删除") })
        assertTrue("NOT expanded (chevron shows 展开)", "展开" in descs(v))
        assertFalse("not the expanded chevron", "收起" in descs(v))
    }

    @Test fun clipboard_left_swipe_shows_four_line_body_without_expanding_chevron() {
        val long = (1..8).joinToString("\n") { "line $it" }
        val v = clipView(listOf(long))
        v.revealSwipeForTest(long)
        val body = textViews(v).first { it.text?.toString() == long }
        assertTrue("left-swipe wraps the body like expanded cards", body.parent is android.widget.ScrollView)
        assertTrue("body itself is not capped to two text lines", body.maxLines > 4)
        assertTrue("chevron still reports collapsed state", "展开" in descs(v))
        assertFalse("left-swipe is not chevron expansion", "收起" in descs(v))
    }

    @Test fun clipboard_chevron_expand_still_works_and_clears_a_swipe() {
        val v = clipView()
        v.revealSwipeForTest("hello"); assertEquals("hello", v.swipeRevealedForTest())
        assertTrue("chevron present", clickDesc(v, "展开"))
        assertNull("⌄展开 supersedes the swipe reveal", v.swipeRevealedForTest())
        assertTrue("now expanded", "收起" in descs(v))
    }

    @Test fun clipboard_longpress_menu_unchanged() {
        val v = clipView()
        val body = textViews(v).first { it.text?.toString() == "hello" }
        assertTrue("body keeps its long-press menu", body.performLongClick())
        val ls = labels(overlayOf(v))
        assertTrue("拆分选词", "拆分选词" in ls); assertTrue("添加常用语", "添加常用语" in ls); assertTrue("删除此条内容", "删除此条内容" in ls)
    }

    // Chinese IME behavior note.

    @Test fun phrase_left_swipe_reveals_edit_pin_delete() {
        val v = phraseView()
        v.revealSwipeForTest("在吗")
        val ls = labels(v)
        assertTrue("编辑", "编辑" in ls); assertTrue("置顶", "置顶" in ls); assertTrue("删除", "删除" in ls)
        assertFalse("swipe row is NOT the expand row (no 移动)", "移动" in ls)
    }

    @Test fun phrase_left_swipe_shows_four_line_body_without_expanding_chevron() {
        val long = (1..8).joinToString("\n") { "phrase $it" }
        val v = phraseView(listOf(long))
        v.revealSwipeForTest(long)
        val body = textViews(v).first { it.text?.toString() == long }
        assertTrue("left-swiped phrase body is four-line bounded", body.parent is android.widget.ScrollView)
        assertTrue("phrase body itself is not a two-line preview", body.maxLines > 4)
        assertTrue("chevron still reports collapsed state", "展开" in descs(v))
        assertFalse("left-swipe is not chevron expansion", "收起" in descs(v))
    }

    @Test fun category_switch_clears_a_stale_swipe_reveal() {
        // case #1: switching category via a chip must drop a reveal so it can't render on the new category.
        val v = phraseView()
        v.revealSwipeForTest("在吗"); assertEquals("在吗", v.swipeRevealedForTest())
        assertTrue("switch to 工作 chip", click(v, "工作"))
        assertNull("a category switch drops the stale reveal", v.swipeRevealedForTest())
    }

    @Test fun a_decided_horizontal_gesture_never_commits() {
        // debug.18 F: settleSwipe runs ONLY after the gesture was decided horizontal (mode==1, onClick already
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        // TAP is covered end-to-end in ClipboardViewInteractionTest.a_plain_tap_does_not_reveal_and_still_commits.)
        val picked = ArrayList<String>()
        val v = clipView().apply { onPick = { picked.add(it) } }
        v.settleSwipeForTest(-3f, "hello"); assertEquals("any leftward swipe reveals", "hello", v.swipeRevealedForTest())
        v.hideSwipeForTest()
        v.settleSwipeForTest(0f, "hello")
        assertTrue("a decided horizontal gesture never 上屏s", picked.isEmpty())
        assertNull("a non-leftward drift does not reveal", v.swipeRevealedForTest())
    }

    @Test fun clear_left_swipe_reveals_clear_right_swipe_hides() {
        val v = clipView()
        v.settleSwipeForTest(-100f, "hello"); assertEquals("clear left → reveal", "hello", v.swipeRevealedForTest())
        v.settleSwipeForTest(100f, "hello"); assertNull("clear right → hide", v.swipeRevealedForTest())
    }

    @Test fun phrase_pin_moves_item_to_top() {
        var reorder: Triple<String, Int, Int>? = null
        val v = phraseView().apply { onReorderPhrase = { c, f, t -> reorder = Triple(c, f, t) } }
        v.revealSwipeForTest("稍等") // index 2
        assertTrue(click(v, "置顶"))
        assertEquals("置顶 = reorder to index 0", Triple("默认", 2, 0), reorder)
    }

    @Test fun phrase_expand_row_is_unchanged_edit_move_delete() {
        // Chinese IME behavior note.
        val v = phraseView().apply { expandForTest("你好") }
        val ls = labels(v)
        assertTrue("移动" in ls); assertTrue("编辑" in ls); assertTrue("删除" in ls)
        assertFalse("expand row has no 置顶", "置顶" in ls)
    }

    // Chinese IME behavior note.

    // Chinese IME behavior note.

    /** Click the 🗑 in the move-chooser row for [name] (a sibling of the name within the same row). */
    private fun deleteTargetInChooser(v: ClipboardView, name: String) {
        fun groups(x: View): Sequence<ViewGroup> = sequence {
            if (x is ViewGroup) { yield(x); for (i in 0 until x.childCount) yieldAll(groups(x.getChildAt(i))) }
        }
        val row = groups(overlayOf(v)).firstOrNull { g ->
            val kids = (0 until g.childCount).map { g.getChildAt(it) }
            kids.any { it is TextView && it.text?.toString() == name } && kids.any { it.contentDescription?.toString() == "删除分类" }
        } ?: error("no chooser row for $name")
        (0 until row.childCount).map { row.getChildAt(it) }.first { it.contentDescription?.toString() == "删除分类" }.performClick()
    }

    private fun moveChooserView(cats: MutableList<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { cats }
        phrasesInProvider = { _ -> listOf("你好") }
        onDeleteCategory = { cats.remove(it) }
        applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
        showMoveChooserForTest("默认")
    }

    @Test fun move_chooser_delete_category_reuses_onDeleteCategory_and_refreshes() {
        val cats = mutableListOf("默认", "工作", "私人")
        val deleted = ArrayList<String>()
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { cats }; phrasesInProvider = { _ -> listOf("你好") }
            onDeleteCategory = { deleted.add(it); cats.remove(it) }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh(); showMoveChooserForTest("默认")
        }
        assertTrue("工作 + 私人 listed", "工作" in labels(overlayOf(v)) && "私人" in labels(overlayOf(v)))
        deleteTargetInChooser(v, "工作")
        assertEquals("delete reuses onDeleteCategory", listOf("工作"), deleted)
        val ls = labels(overlayOf(v))
        assertFalse("工作 gone after refresh", "工作" in ls)
        assertTrue("私人 still there", "私人" in ls)
        assertFalse("panel categoryBar chip also refreshed (no stale 工作 chip)", "工作" in labels(mainOf(v)))
    }

    @Test fun move_chooser_trash_does_not_trigger_a_move() {
        val cats = mutableListOf("默认", "工作")
        var moved: Triple<String, String, String>? = null
        val v = moveChooserView(cats)
        v.onMovePhrase = { f, t, to -> moved = Triple(f, t, to) }
        // re-open so the row closures capture the new onMovePhrase
        v.showMoveChooserForTest("默认")
        deleteTargetInChooser(v, "工作")
        assertNull("🗑 must delete only, never move", moved)
    }

    @Test fun move_chooser_name_tap_still_moves() {
        var moved: Triple<String, String, String>? = null
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认", "工作") }; phrasesInProvider = { _ -> listOf("你好") }
            onMovePhrase = { f, t, to -> moved = Triple(f, t, to) }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh(); showMoveChooserForTest("默认")
        }
        assertTrue("tap the target name", click(overlayOf(v), "工作"))
        assertEquals("name tap still moves (unchanged)", Triple("默认", "", "工作"), moved)
    }

    @Test fun move_chooser_offers_new_category_alongside_targets() {
        val v = phraseView()
        v.expandForTest("你好"); assertTrue(click(v, "移动"))
        val ls = labels(overlayOf(v))
        assertTrue("target 工作 present", "工作" in ls)
        assertTrue("＋ 新建分类… available in the non-empty chooser too", "＋ 新建分类…" in ls)
    }

    @Test fun split_blocks_start_neutral() {
        val v = clipView()
        v.showSplitForTest("你好abc")
        val a = chip(overlayOf(v), "你"); val b = chip(overlayOf(v), "abc")
        assertTrue("blocks present", a != null && b != null)
        assertEquals("block default uses enter background", pal.accentBottom, bgColor(a!!))
        assertEquals("block default uses enter background", pal.accentBottom, bgColor(b!!))
        assertEquals("block default text uses enter label", pal.accentLabel, a.currentTextColor)
        assertEquals("block default text uses enter label", pal.accentLabel, b.currentTextColor)
        assertTrue("nothing copied yet", v.splitSelectedForTest().isEmpty())
    }

    @Test fun split_block_tap_highlights_and_copies_panel_stays_open() {
        val copied = ArrayList<String>()
        val v = clipView().apply { onCopyBlockToAegis = { copied.add(it) } }
        v.showSplitForTest("你好abc")
        val a = chip(overlayOf(v), "你")!!
        val b = chip(overlayOf(v), "好")!!
        val defaultBg = bgColor(a)
        val defaultText = a.currentTextColor
        a.performClick()
        assertEquals("tapped block copied to aegis", listOf("你"), copied)
        assertEquals("tapped block uses copied background", pal.chipBg, bgColor(a))
        assertEquals("tapped block uses copied text", pal.chipText, a.currentTextColor)
        assertNotEquals("copied background differs from default", defaultBg, bgColor(a))
        assertNotEquals("copied text differs from default", defaultText, a.currentTextColor)
        assertEquals("untapped block keeps default background", defaultBg, bgColor(b))
        assertEquals("untapped block keeps default text", defaultText, b.currentTextColor)
        assertTrue("tracked as selected", "你" in v.splitSelectedForTest())
        assertEquals("panel stays open", View.VISIBLE.toLong(), overlayOf(v).visibility.toLong())
    }

    @Test fun split_copy_all_copies_each_block_separately_and_highlights_all() {
        // Chinese IME behavior note.
        val copied = ArrayList<String>()
        val v = clipView().apply { onCopyBlockToAegis = { copied.add(it) } }
        v.showSplitForTest("你好abc")
        val blockLabels = listOf("你", "好", "abc")
        val chips = blockLabels.map { chip(overlayOf(v), it)!! }
        val defaultBg = bgColor(chips.first())
        val defaultText = chips.first().currentTextColor
        assertTrue(click(overlayOf(v), "全部复制"))
        assertEquals("全部复制 copies each block separately", listOf("你", "好", "abc"), copied)
        assertEquals("all blocks marked", setOf("你", "好", "abc"), v.splitSelectedForTest())
        chips.forEach { block ->
            assertEquals("all blocks use copied background", pal.chipBg, bgColor(block))
            assertEquals("all blocks use copied text", pal.chipText, block.currentTextColor)
            assertNotEquals("copied background differs from default", defaultBg, bgColor(block))
            assertNotEquals("copied text differs from default", defaultText, block.currentTextColor)
        }
    }

    @Test fun split_copy_all_uses_batch_callback_once_when_available() {
        val batches = ArrayList<List<String>>()
        val v = clipView().apply { onCopyBlocksToAegis = { batches.add(it) } }
        v.showSplitForTest("你好abc")
        assertTrue(click(overlayOf(v), "全部复制"))
        assertEquals(listOf(listOf("你", "好", "abc")), batches)
    }

    @Test fun split_chooser_hides_original_preview_and_uses_normal_footer_color() {
        val v = clipView()
        v.showSplitForTest("你好abc")
        assertFalse("original preview removed", "你好abc" in labels(overlayOf(v)))
        assertEquals(pal.keyLabel, textViews(overlayOf(v)).first { it.text?.toString() == "返回" }.currentTextColor)
        assertEquals(pal.keyLabel, textViews(overlayOf(v)).first { it.text?.toString() == "全部复制" }.currentTextColor)
    }

    // Chinese IME behavior note.

    private fun scrollViews(root: View): List<android.widget.ScrollView> {
        val out = ArrayList<android.widget.ScrollView>()
        fun walk(x: View) { if (x is android.widget.ScrollView) out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }

    @Test fun phrase_note_is_displayed_but_pick_commits_the_original() {
        val picked = ArrayList<String>()
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认") }
            phrasesInProvider = { _ -> listOf("longoriginal") }
            phraseNoteProvider = { _, t -> if (t == "longoriginal") "别名" else "" }
            onPick = { picked.add(it) }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
        }
        assertTrue("list shows the NOTE", "别名" in labels(v))
        assertFalse("list does NOT show the original text", "longoriginal" in labels(v))
        textViews(v).first { it.text?.toString() == "别名" }.performClick()
        assertEquals("tapping the note commits the ORIGINAL text", listOf("longoriginal"), picked)
    }

    @Test fun phrase_without_note_shows_original_text() {
        assertTrue("你好" in labels(phraseView())) // default provider → no note → original
    }

    @Test fun expanded_phrase_card_has_a_note_action() {
        var note: Pair<String, String>? = null
        val v = phraseView().apply { onEditNote = { c, t -> note = c to t }; expandForTest("你好") }
        assertTrue("备注" in labels(v))
        assertTrue(click(v, "备注"))
        assertEquals("默认" to "你好", note)
    }

    @Test fun manage_menu_offers_import_and_export() {
        val imports = ArrayList<Boolean>(); var exp = 0
        val v = phraseView().apply { onImportPhrasesWithMode = { imports.add(it) }; onExportPhrases = { exp++ } }
        v.showPhraseManageMenuForTest()
        val ls = labels(overlayOf(v))
        assertTrue("导入常用语" in ls); assertTrue("导出常用语" in ls)
        assertTrue(click(overlayOf(v), "导入常用语"))
        assertTrue("import opens the in-panel confirmation", "覆盖" in labels(overlayOf(v)) && "合并（推荐）" in labels(overlayOf(v)))
        assertTrue(click(overlayOf(v), "合并（推荐）"))
        assertEquals(listOf(true), imports)
        v.showPhraseManageMenuForTest()
        assertTrue(click(overlayOf(v), "导入常用语"))
        assertTrue(click(overlayOf(v), "覆盖"))
        assertEquals(listOf(true, false), imports)
        v.showPhraseManageMenuForTest()
        assertTrue(click(overlayOf(v), "导出常用语")); assertEquals(1, exp)
    }

    @Test fun import_confirmation_uses_normal_panel_colors() {
        val v = phraseView()
        v.showPhraseManageMenuForTest()
        assertTrue(click(overlayOf(v), "导入常用语"))
        val expected = setOf(
            "导入常用语",
            "「合并」把导入内容累加到现有常用语（按分类去重）；「覆盖」用导入文件整体替换常用语库。空文件不会清空。",
            "覆盖",
            "合并（推荐）",
            "取消",
        )
        val views = textViews(overlayOf(v)).filter { it.text?.toString() in expected }
        assertEquals(expected.size, views.size)
        assertTrue(views.all { it.currentTextColor == pal.keyLabel })
    }

    @Test fun phrase_tab_last_top_icon_clears_current_category_with_confirm() {
        var cleared: String? = null
        val v = phraseView().apply { onClearCategory = { cleared = it } }
        assertTrue("phrase tab top bar carries the clear-category icon", "清空分类" in descs(mainOf(v)))
        assertFalse("⚙ gear is NOT on the phrase tab", "设置" in descs(mainOf(v)))
        v.confirmClearForTest()
        val ls = labels(overlayOf(v))
        assertTrue("confirm overlay (二次确认)", "清空" in ls && "取消" in ls)
        assertNull("nothing cleared until confirmed", cleared)
        assertTrue(click(overlayOf(v), "清空")); assertEquals("clears the CURRENT category", "默认", cleared)
    }

    @Test fun clipboard_tab_top_right_icon_confirms_before_clearing_history() {
        var clears = 0
        val v = clipView().apply { onClearHistory = { clears++ } }
        assertTrue("clipboard tab top bar carries clear-history", "清空剪贴板历史" in descs(mainOf(v)))
        assertFalse("old settings gear is not present", "设置" in descs(mainOf(v)))
        assertTrue(clickDesc(v, "清空剪贴板历史"))
        assertEquals("tap only opens confirmation", 0, clears)
        assertTrue("confirmation offers clear", "清空" in labels(overlayOf(v)))
        assertTrue(click(overlayOf(v), "清空"))
        assertEquals("confirmed clear fires once", 1, clears)
    }

    @Test fun clipboard_clear_icon_long_press_keeps_recording_toggle_reachable() {
        val v = clipView()
        assertTrue(longClickDesc(v, "清空剪贴板历史"))
        assertTrue("history recording toggle remains reachable", labels(overlayOf(v)).any { it.startsWith("剪贴板记录:") })
    }

    @Test fun clipboard_and_phrase_tab_row_uses_text_color_only() {
        val clip = clipView()
        val tabs = textViews(clip).filter { it.text?.toString() in setOf("剪贴板", "常用语") }
        assertEquals(2, tabs.size)
        assertTrue(tabs.all { it.background == null })
        assertEquals(pal.candidateFirst, tabs.first { it.text?.toString() == "剪贴板" }.currentTextColor)
        assertEquals(pal.keyLabel, tabs.first { it.text?.toString() == "常用语" }.currentTextColor)

        val phrase = phraseView()
        val phraseTabs = textViews(phrase).filter { it.text?.toString() in setOf("剪贴板", "常用语") }
        assertEquals(pal.keyLabel, phraseTabs.first { it.text?.toString() == "剪贴板" }.currentTextColor)
        assertEquals(pal.candidateFirst, phraseTabs.first { it.text?.toString() == "常用语" }.currentTextColor)
    }

    @Test fun phrase_category_row_uses_text_edit_button_without_chip_backgrounds() {
        val v = phraseView()
        val category = textViews(v).first { it.text?.toString() == "默认" && it.hasOnClickListeners() }
        val inactiveCategory = textViews(v).first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
        val manage = textViews(v).first { it.text?.toString() == "编辑" && it.contentDescription?.toString() == "管理常用语" }
        assertEquals(pal.candidateFirst, category.currentTextColor)
        assertEquals(pal.keyLabel, inactiveCategory.currentTextColor)
        assertEquals(pal.keyLabel, manage.currentTextColor)
        assertTrue(category.background == null)
        assertTrue(inactiveCategory.background == null)
        assertTrue(manage.background == null)
        assertTrue(clickDesc(v, "管理常用语"))
    }

    @Test fun select_and_action_row_defaults_use_body_text_color() {
        val selected = clipView().apply { enterSelectForTest() }
        assertEquals(pal.keyLabel, textViews(selected).first { it.text?.toString() == "全选" }.currentTextColor)
        assertEquals(pal.keyLabel, textViews(selected).first { it.text?.toString() == "取消" }.currentTextColor)

        val expanded = clipView().apply { expandForTest("hello") }
        val actions = textViews(expanded)
            .filter { it.text?.toString() in setOf("常用语", "拆词", "删除") && it.compoundDrawables.any { d -> d != null } }
        assertEquals(3, actions.size)
        assertTrue(actions.all { it.currentTextColor == pal.keyLabel })
    }

    @Test fun select_mode_action_buttons_match_split_block_colors_when_enabled() {
        val clip = clipView().apply { enterSelectForTest(listOf("hello")) }
        for (label in listOf("添加常用语", "删除")) {
            val button = textViews(clip).first { it.text?.toString() == label }
            assertEquals("$label uses split block background", pal.accentBottom, bgColor(button))
            assertEquals("$label uses split block text", pal.accentLabel, button.currentTextColor)
            assertTrue("$label remains clickable when enabled", button.hasOnClickListeners())
        }

        val phrase = phraseView().apply { enterSelectForTest(listOf("你好")) }
        for (label in listOf("移动到分类", "删除")) {
            val button = textViews(phrase).first { it.text?.toString() == label }
            assertEquals("$label uses split block background", pal.accentBottom, bgColor(button))
            assertEquals("$label uses split block text", pal.accentLabel, button.currentTextColor)
            assertTrue("$label remains clickable when enabled", button.hasOnClickListeners())
        }
    }

    @Test fun clear_confirmation_title_and_actions_use_body_text_color() {
        val phrase = phraseView()
        phrase.confirmClearForTest()
        val phraseViews = textViews(overlayOf(phrase)).filter {
            it.text?.toString() in setOf("清空分类「默认」的全部常用语?", "清空", "取消")
        }
        assertEquals(3, phraseViews.size)
        assertTrue(phraseViews.all { it.currentTextColor == pal.keyLabel })
        val phraseActions = phraseViews.filter { it.text?.toString() in setOf("清空", "取消") }
        assertEquals(2, phraseActions.size)

        val clip = clipView()
        clip.confirmClearHistoryForTest()
        val clipViews = textViews(overlayOf(clip)).filter { it.text?.toString() in setOf("清空剪贴板历史?", "清空", "取消") }
        assertEquals(3, clipViews.size)
        assertTrue(clipViews.all { it.currentTextColor == pal.keyLabel })
        val clipActions = clipViews.filter { it.text?.toString() in setOf("清空", "取消") }
        assertEquals(2, clipActions.size)
    }

    @Test fun chooser_titles_use_body_text_color() {
        val move = phraseView().apply { showMoveChooserForTest("默认") }
        assertEquals(pal.keyLabel, textViews(overlayOf(move)).first { it.text?.toString() == "移动到分类" }.currentTextColor)

        val singleTarget = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认") }
            phrasesInProvider = { _ -> listOf("你好") }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
            showMoveChooserForTest("默认")
        }
        assertEquals(pal.keyLabel, textViews(overlayOf(singleTarget)).first { it.text?.toString() == "没有其它分类" }.currentTextColor)

        val category = clipView().apply { expandForTest("hello") }
        textViews(category)
            .first { tv -> tv.text?.toString() == "常用语" && tv.compoundDrawables.any { d -> d != null } }
            .performClick()
        assertEquals(pal.keyLabel, textViews(overlayOf(category)).first { it.text?.toString() == "选择分类" }.currentTextColor)
    }

    @Test fun expanding_a_card_wraps_its_body_in_a_scrollview() {
        val baseline = scrollViews(clipView(listOf("a long clip"))).size // the list ScrollView only
        val expanded = clipView(listOf("a long clip")).apply { expandForTest("a long clip") }
        assertTrue("F1: an expanded card adds a bounded ScrollView around its body", scrollViews(expanded).size > baseline)
    }
}
