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
 * debug.16 (常用语 tab) full rewrite: expanded-card action row (编辑 / 移动 / 删除), drag-to-reorder state
 * machine, and the tab-aware select mode (编辑常用语 + batch 移动到分类 / 删除). The 剪贴板 history tab is
 * unaffected. Overlay menus live in ClipboardView child 1, so chooser interactions are scoped there to avoid
 * hitting same-named category chips in the bottom bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhrasePanelTest {

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
    // icon收尾: self-drawn icon buttons have NO text — locate by contentDescription.
    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun clickDesc(root: View, desc: String): Boolean {
        val v = allViews(root).firstOrNull { it.contentDescription?.toString() == desc && it.hasOnClickListeners() } ?: return false
        v.performClick(); return true
    }
    /** Click an action-row cell [label]: an icon+label TextView (has a leading compound drawable), so it is not
     *  confused with the same-named tab pill (icon收尾 made the action's label "常用语" collide with the 常用语 tab). */
    private fun clickAction(root: View, label: String): Boolean {
        val tv = textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() && it.compoundDrawables.any { d -> d != null } } ?: return false
        tv.performClick(); return true
    }

    private fun phraseView(): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认", "工作", "私人") }
        phrasesInProvider = { c -> if (c == "默认") listOf("你好", "在吗", "稍等") else emptyList() }
        applyPalette(pal)
        forcePhrasesStateForTest("默认"); refresh()
    }

    // ---- expanded card action row = 编辑 / 移动 / 删除 ----

    @Test fun expanded_phrase_card_action_row_is_edit_move_delete() {
        val v = phraseView().apply { expandForTest("你好") }
        val ls = labels(v)
        assertTrue("编辑" in ls); assertTrue("移动" in ls); assertTrue("删除" in ls)
        assertFalse("＋常用语 makes no sense for a phrase", ls.any { it.contains("常用语") && it.contains("＋") })
        assertFalse("no 拆词 on a phrase", ls.any { it.contains("拆词") })
    }

    @Test fun expanded_clipboard_card_keeps_add_split_delete() {
        val v = ClipboardView(ctx).apply {
            historyProvider = { listOf("abc") }; applyPalette(pal); refresh(); expandForTest("abc")
        }
        val ls = labels(v)
        assertTrue(ls.any { it.contains("常用语") }); assertTrue(ls.any { it.contains("拆词") }); assertTrue(ls.any { it.contains("删除") })
    }

    @Test fun edit_action_invokes_onEditPhrase() {
        var got: Pair<String, String>? = null
        val v = phraseView().apply { onEditPhrase = { c, t -> got = c to t }; expandForTest("你好") }
        assertTrue(click(v, "编辑"))
        assertEquals("默认" to "你好", got)
    }

    @Test fun move_action_opens_chooser_excluding_current_then_invokes_onMovePhrase() {
        var move: Triple<String, String, String>? = null
        val v = phraseView().apply { onMovePhrase = { f, t, to -> move = Triple(f, t, to) }; expandForTest("你好") }
        assertTrue(click(v, "移动"))
        val chooser = labels(overlayOf(v))
        assertTrue("工作" in chooser); assertTrue("私人" in chooser)
        assertFalse("current category excluded", "默认" in chooser)
        assertTrue(click(overlayOf(v), "工作"))
        assertEquals(Triple("默认", "你好", "工作"), move)
    }

    @Test fun delete_action_invokes_onDeletePhrasesFrom() {
        var del: Pair<String, List<String>>? = null
        val v = phraseView().apply { onDeletePhrasesFrom = { c, l -> del = c to l }; expandForTest("你好") }
        assertTrue(click(v, "删除"))
        assertEquals("默认" to listOf("你好"), del)
    }

    // ---- drag to reorder ----

    @Test fun drag_reorder_fires_onReorderPhrase_with_from_and_to() {
        var r: Triple<String, Int, Int>? = null
        val v = phraseView().apply { onReorderPhrase = { c, f, t -> r = Triple(c, f, t) } }
        v.dragStartForTest(0)
        assertTrue(v.isDraggingForTest())
        v.dragMoveToForTest(2)
        v.dragDropForTest()
        assertFalse(v.isDraggingForTest())
        assertEquals(Triple("默认", 0, 2), r)
    }

    @Test fun rowAt_skips_the_dragged_row_so_downward_drag_finds_a_lower_target() {
        // 3 stacked rows, each 100px tall at tops 0/100/200. The dragged row (skip) is excluded — without that,
        // its finger-following translated bounds would always match and a downward drag could never move down.
        val v = ClipboardView(ctx)
        val tops = intArrayOf(0, 100, 200)
        val heights = intArrayOf(100, 100, 100)
        // dragging row 0 downward: a finger at y=250 must resolve to row 2 (not pinned to 0)
        assertEquals(2, v.rowAt(tops, heights, skip = 0, y = 250))
        assertEquals(1, v.rowAt(tops, heights, skip = 0, y = 150))
        // dragging row 2 upward: finger at y=50 → row 0
        assertEquals(0, v.rowAt(tops, heights, skip = 2, y = 50))
        // off the list → null
        assertNull(v.rowAt(tops, heights, skip = 0, y = 999))
    }

    @Test fun drag_drop_in_place_is_a_noop() {
        var r: Triple<String, Int, Int>? = null
        val v = phraseView().apply { onReorderPhrase = { c, f, t -> r = Triple(c, f, t) } }
        v.dragStartForTest(1); v.dragMoveToForTest(1); v.dragDropForTest()
        assertNull("same index → no reorder callback", r)
    }

    // ---- tab-aware select mode + batch move ----

    @Test fun phrase_select_mode_title_and_batch_actions() {
        val v = phraseView().apply { enterSelectForTest(listOf("你好", "在吗")) }
        val ls = labels(v)
        assertTrue("编辑常用语" in ls)            // tab-aware title
        assertFalse("编辑剪贴板" in ls)
        assertTrue("移动到分类" in ls)            // phrase batch action
        assertTrue("删除" in ls)
        assertFalse("添加常用语" in ls)           // that's the clipboard batch action
    }

    @Test fun clipboard_select_mode_keeps_add_phrase_action() {
        val v = ClipboardView(ctx).apply {
            historyProvider = { listOf("a", "b") }; applyPalette(pal); refresh(); enterSelectForTest(listOf("a"))
        }
        val ls = labels(v)
        assertTrue("编辑剪贴板" in ls); assertTrue("添加常用语" in ls); assertTrue("删除" in ls)
        assertFalse("移动到分类" in ls)
    }

    @Test fun batch_move_invokes_onMovePhrasesTo_with_selection_and_target() {
        var batch: Triple<String, List<String>, String>? = null
        val v = phraseView().apply {
            onMovePhrasesTo = { f, list, to -> batch = Triple(f, list, to) }
            enterSelectForTest(listOf("你好", "稍等"))
        }
        assertTrue(click(v, "移动到分类"))
        assertTrue(click(overlayOf(v), "工作"))
        assertEquals("默认", batch?.first)
        assertEquals(listOf("你好", "稍等"), batch?.second)
        assertEquals("工作", batch?.third)
    }

    @Test fun batch_delete_invokes_onDeletePhrasesFrom() {
        var del: Pair<String, List<String>>? = null
        val v = phraseView().apply { onDeletePhrasesFrom = { c, l -> del = c to l }; enterSelectForTest(listOf("你好")) }
        assertTrue(click(v, "删除"))
        assertEquals("默认", del?.first)
        assertEquals(listOf("你好"), del?.second)
    }

    // ---- debug.16 Option A: inline category management triggers ----
    // (debug.17 re-routed these: top ＋ → 添加常用语; categoryBar ✎ → 二级菜单 whose 添加分类 → onAddCategory.
    //  Full coverage is in Debug17PanelTest; this guards that 新建分类 is still reachable from the ✎ menu.)

    @Test fun categorybar_pencil_menu_add_category_still_triggers_onAddCategory() {
        var adds = 0
        val v = phraseView().apply { onAddCategory = { adds++ } }
        clickDesc(v, "添加常用语"); assertEquals("top-bar ＋ no longer creates a category", 0, adds)
        assertTrue("categoryBar ✎", clickDesc(v, "管理常用语"))
        assertTrue("✎ menu has 添加分类", click(overlayOf(v), "添加分类")); assertEquals(1, adds)
    }

    @Test fun category_chip_long_press_offers_inline_rename_and_delete() {
        var renamed: String? = null
        var deleted: String? = null
        val v = phraseView().apply { onRenameCategory = { renamed = it }; onDeleteCategory = { deleted = it } }
        val chip = textViews(v).first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
        assertTrue(chip.performLongClick())
        assertTrue(click(overlayOf(v), "重命名「工作」")); assertEquals("工作", renamed)
        // reopen the chip menu for delete
        assertTrue(chip.performLongClick())
        assertTrue(click(overlayOf(v), "删除「工作」")); assertEquals("工作", deleted)
    }

    // ---- item7: top icons uniform size ----

    @Test fun top_bar_icons_are_uniform_size() {
        val v = phraseView()
        // icon收尾: all top icons are self-drawn Views (no text), located by contentDescription, in one icon slot
        // (返回 / ＋添加常用语 / ☰多选 / 🗑清空分类 on the 常用语 tab).
        val wanted = setOf("返回", "添加常用语", "多选", "清空分类")
        val icons = allViews(v).filter { it.contentDescription?.toString() in wanted && it.hasOnClickListeners() }
        assertEquals("all 4 phrase-tab top icons present", 4, icons.size)
        assertTrue("返回 is no longer a '‹' text glyph", textViews(v).none { it.text?.toString() == "‹" })
        assertEquals("all top icons share one width (item7)", 1, icons.map { it.layoutParams.width }.toSet().size)
        assertEquals("all top icons share one height (item7)", 1, icons.map { it.layoutParams.height }.toSet().size)
    }

    // ---- debug.16 fix: 剪贴板 添加常用语 → 新建分类 must carry the clip into the new category ----

    private fun clipboardView(history: List<String>, cats: List<String>): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history }
        categoriesProvider = { cats }
        applyPalette(pal); refresh() // default tab = 剪贴板
    }

    @Test fun clipboard_add_to_new_category_carries_the_clip() {
        var carried: List<String>? = null
        val v = clipboardView(listOf("hello"), listOf("默认")).apply { onAddCategoryThenAdd = { carried = it } }
        v.expandForTest("hello")
        assertTrue(clickAction(v, "常用语"))           // open the category chooser
        assertTrue(click(overlayOf(v), "＋ 新建分类…"))   // pick 新建分类
        assertEquals("the clip rides the inline new-category flow", listOf("hello"), carried)
    }

    @Test fun clipboard_add_to_new_category_when_none_exist_carries_the_clip() {
        var carried: List<String>? = null
        val v = clipboardView(listOf("hello"), emptyList()).apply { onAddCategoryThenAdd = { carried = it } }
        v.expandForTest("hello")
        assertTrue(clickAction(v, "常用语")) // no categories → straight into create-carrying-clip
        assertEquals(listOf("hello"), carried)
    }

    @Test fun clipboard_add_to_existing_category_still_works() {
        var saved: Pair<String, List<String>>? = null
        val v = clipboardView(listOf("hello"), listOf("默认")).apply { onSaveAsPhrasesTo = { c, l -> saved = c to l } }
        v.expandForTest("hello")
        assertTrue(clickAction(v, "常用语"))
        assertTrue(click(overlayOf(v), "默认"))           // existing-category path unchanged
        assertEquals("默认" to listOf("hello"), saved)
    }

    // ---- debug.16 symmetric fix: 移动到分类 → 新建分类 must carry the move (only reachable with no other category) ----

    /** A 常用语-tab view with a SINGLE category, so the move chooser has no other target → offers 新建分类. */
    private fun singleCatPhraseView(): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认") }
        phrasesInProvider = { c -> if (c == "默认") listOf("你好", "在吗") else emptyList() }
        applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
    }

    @Test fun move_to_new_category_carries_the_move() {
        var carried: Pair<String, List<String>>? = null
        val v = singleCatPhraseView().apply { onAddCategoryThenMove = { from, texts -> carried = from to texts } }
        v.expandForTest("你好")
        assertTrue(click(v, "移动"))
        assertTrue("no other category → offers 新建", "没有其它分类" in labels(overlayOf(v)))
        assertTrue(click(overlayOf(v), "＋ 新建分类…"))
        assertEquals("默认" to listOf("你好"), carried) // the move rides the inline new-category flow
    }

    @Test fun move_to_existing_category_still_works() {
        // regression guard: with another category present, the move chooser still uses the direct path.
        var moved: Triple<String, String, String>? = null
        val v = phraseView().apply { onMovePhrase = { f, t, to -> moved = Triple(f, t, to) } }
        v.expandForTest("你好")
        assertTrue(click(v, "移动"))
        assertTrue(click(overlayOf(v), "工作"))
        assertEquals(Triple("默认", "你好", "工作"), moved)
    }
}
