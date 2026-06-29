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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * debug.17 剪贴板/常用语面板交互重做. Covers: ① top ＋ (常用语) adds a phrase to the current category (no longer
 * a 新建分类); ③ the categoryBar ✎ 二级菜单 (移动→排序模式 / 添加分类) + the drag-reorder sort mode; ④ a 剪贴板
 * card's left-swipe reveal (添加常用语/拆词/删除); ⑤ a 常用语 card's left-swipe reveal (编辑/置顶/删除) + 置顶;
 * ⑦ the 拆词 overlay (blocks default NEUTRAL, tap → 浅紫 highlight + copy, 全部复制). The ⌄展开 + 长按菜单 are
 * verified UNCHANGED (zero regression). Overlay menus live in ClipboardView child 1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Debug17PanelTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT

    private fun overlayOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(1)
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
    /** A clickable leaf chip in the overlay with exactly [label] (a 拆词 block / 全部复制). */
    private fun chip(root: View, label: String): TextView? =
        textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() }
    private fun bgColor(v: View): Int? = (v.background as? GradientDrawable)?.color?.defaultColor

    private fun phraseView(phrases: List<String> = listOf("你好", "在吗", "稍等")): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认", "工作") }
        phrasesInProvider = { c -> if (c == "默认") phrases else listOf("已收到") }
        applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
    }
    private fun clipView(history: List<String> = listOf("hello")): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history }; categoriesProvider = { listOf("默认") }
        applyPalette(pal); refresh()
    }

    // ---------- ① top ＋ (常用语 tab) = add a phrase to the current category ----------

    @Test fun top_plus_adds_phrase_to_current_category_not_a_new_category() {
        var addPhraseCat: String? = null
        var addCategoryFired = false
        val v = phraseView().apply { onAddPhrase = { addPhraseCat = it }; onAddCategory = { addCategoryFired = true } }
        assertTrue("top ＋ present on 常用语 tab", click(v, "＋"))
        assertEquals("＋ adds a phrase to the CURRENT category", "默认", addPhraseCat)
        assertFalse("＋ no longer creates a category", addCategoryFired)
    }

    // ---------- ③ categoryBar ✎ 二级菜单 (移动 / 添加分类) + 排序模式 ----------

    @Test fun categorybar_pencil_opens_manage_menu() {
        val v = phraseView()
        assertTrue(click(v, "✎"))
        val ls = labels(overlayOf(v))
        assertTrue("menu has 移动", "移动" in ls)
        assertTrue("menu has 添加分类", "添加分类" in ls)
    }

    @Test fun manage_menu_move_enters_sort_mode() {
        val v = phraseView()
        click(v, "✎"); assertTrue(click(overlayOf(v), "移动"))
        assertTrue("移动 → 排序模式", v.isSortModeForTest())
        val ls = labels(v)
        assertTrue("sort header", "拖动排序" in ls); assertTrue("done button", "完成" in ls)
    }

    @Test fun manage_menu_add_category_triggers_inline_create() {
        var addCategoryFired = false
        val v = phraseView().apply { onAddCategory = { addCategoryFired = true } }
        click(v, "✎"); assertTrue(click(overlayOf(v), "添加分类"))
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

    // ---------- ④ 剪贴板 card left-swipe reveal (+ ⌄展开 / 长按菜单 zero regression) ----------

    @Test fun clipboard_left_swipe_reveals_action_row_without_expanding() {
        val v = clipView()
        v.revealSwipeForTest("hello")
        assertEquals("hello", v.swipeRevealedForTest())
        val ls = labels(v)
        assertTrue("添加常用语 action", ls.any { it.contains("常用语") })
        assertTrue("拆词 action", ls.any { it.contains("拆词") })
        assertTrue("删除 action", ls.any { it.contains("删除") })
        assertTrue("NOT expanded (chevron still ⌄)", "⌄" in ls)
        assertFalse("not the expanded chevron", "⌃" in ls)
    }

    @Test fun clipboard_chevron_expand_still_works_and_clears_a_swipe() {
        val v = clipView()
        v.revealSwipeForTest("hello"); assertEquals("hello", v.swipeRevealedForTest())
        assertTrue("chevron present", click(v, "⌄"))
        assertNull("⌄展开 supersedes the swipe reveal", v.swipeRevealedForTest())
        assertTrue("now expanded", "⌃" in labels(v))
    }

    @Test fun clipboard_longpress_menu_unchanged() {
        val v = clipView()
        val body = textViews(v).first { it.text?.toString() == "hello" }
        assertTrue("body keeps its long-press menu", body.performLongClick())
        val ls = labels(overlayOf(v))
        assertTrue("拆分选词", "拆分选词" in ls); assertTrue("添加常用语", "添加常用语" in ls); assertTrue("删除此条内容", "删除此条内容" in ls)
    }

    // ---------- ⑤ 常用语 card left-swipe reveal (编辑 / 置顶 / 删除) + 置顶 ----------

    @Test fun phrase_left_swipe_reveals_edit_pin_delete() {
        val v = phraseView()
        v.revealSwipeForTest("在吗")
        val ls = labels(v)
        assertTrue("✎ 编辑", "✎ 编辑" in ls); assertTrue("↑ 置顶", "↑ 置顶" in ls); assertTrue("🗑 删除", "🗑 删除" in ls)
        assertFalse("swipe row is NOT the expand row (no 移动)", "→ 移动" in ls)
    }

    @Test fun category_switch_clears_a_stale_swipe_reveal() {
        // case #1: switching category via a chip must drop a reveal so it can't render on the new category.
        val v = phraseView()
        v.revealSwipeForTest("在吗"); assertEquals("在吗", v.swipeRevealedForTest())
        assertTrue("switch to 工作 chip", click(v, "工作"))
        assertNull("a category switch drops the stale reveal", v.swipeRevealedForTest())
    }

    @Test fun sub_threshold_horizontal_drift_falls_back_to_tap() {
        // case #2: a wobbly tap (consumed but below the swipe threshold) must still 上屏, not vanish.
        val picked = ArrayList<String>()
        val v = clipView().apply { onPick = { picked.add(it) } }
        v.settleSwipeForTest(0f, "hello")
        assertEquals("sub-threshold drift = tap → 上屏", listOf("hello"), picked)
        assertNull("no reveal for a sub-threshold drift", v.swipeRevealedForTest())
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
        assertTrue(click(v, "↑ 置顶"))
        assertEquals("置顶 = reorder to index 0", Triple("默认", 2, 0), reorder)
    }

    @Test fun phrase_expand_row_is_unchanged_edit_move_delete() {
        // zero regression: ⌄展开 keeps 编辑/移动/删除 (NOT the swipe row's 置顶).
        val v = phraseView().apply { expandForTest("你好") }
        val ls = labels(v)
        assertTrue("→ 移动" in ls); assertTrue("✎ 编辑" in ls); assertTrue("🗑 删除" in ls)
        assertFalse("expand row has no 置顶", "↑ 置顶" in ls)
    }

    // ---------- ⑦ 拆词 overlay: neutral default → tap highlight + copy; 全部复制 ----------

    @Test fun split_blocks_start_neutral() {
        val v = clipView()
        v.showSplitForTest("你好abc")
        val a = chip(overlayOf(v), "你好"); val b = chip(overlayOf(v), "abc")
        assertTrue("blocks present", a != null && b != null)
        assertEquals("block 你好 default = neutral", pal.keySurfacePressed, bgColor(a!!))
        assertEquals("block abc default = neutral", pal.keySurfacePressed, bgColor(b!!))
        assertTrue("nothing copied yet", v.splitSelectedForTest().isEmpty())
    }

    @Test fun split_block_tap_highlights_and_copies_panel_stays_open() {
        val copied = ArrayList<String>()
        val v = clipView().apply { onCopyBlockToAegis = { copied.add(it) } }
        v.showSplitForTest("你好abc")
        val a = chip(overlayOf(v), "你好")!!
        a.performClick()
        assertEquals("tapped block copied to aegis", listOf("你好"), copied)
        assertEquals("tapped block now 浅紫 highlight", pal.chipBg, bgColor(a))
        assertTrue("tracked as selected", "你好" in v.splitSelectedForTest())
        assertEquals("panel stays open", View.VISIBLE.toLong(), overlayOf(v).visibility.toLong())
    }

    @Test fun split_copy_all_copies_original_and_highlights_all() {
        val copied = ArrayList<String>()
        val v = clipView().apply { onCopyBlockToAegis = { copied.add(it) } }
        v.showSplitForTest("你好abc")
        assertTrue(click(overlayOf(v), "全部复制"))
        assertEquals("全部复制 copies the whole original once", listOf("你好abc"), copied)
        assertEquals("all blocks marked", setOf("你好", "abc"), v.splitSelectedForTest())
        assertEquals("all blocks highlighted", pal.chipBg, bgColor(chip(overlayOf(v), "你好")!!))
    }
}
