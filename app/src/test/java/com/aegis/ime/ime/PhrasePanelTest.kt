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

import android.view.Gravity
import android.view.MotionEvent
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
    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun clickDesc(root: View, desc: String): Boolean {
        val v = allViews(root).firstOrNull { it.contentDescription?.toString() == desc && it.hasOnClickListeners() } ?: return false
        v.performClick(); return true
    }
    private fun clickAction(root: View, label: String): Boolean {
        val tv = textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() && it.compoundDrawables.any { d -> d != null } } ?: return false
        tv.performClick(); return true
    }
    private fun send(root: View, action: Int, y: Float) =
        root.dispatchTouchEvent(MotionEvent.obtain(0, 16, action, 20f, y, 0))
    private fun layout(v: View, w: Int = 480, h: Int = 320) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun phraseView(): ClipboardView = phraseView(listOf("你好", "在吗", "稍等"))
    private fun phraseView(phrases: List<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认", "工作", "私人") }
        phrasesInProvider = { c -> if (c == "默认") phrases else emptyList() }
        applyPalette(pal)
        forcePhrasesStateForTest("默认"); refresh()
    }


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

    @Test fun expanded_clipboard_action_row_wraps_actions_inside_left_center_right_slots() {
        val v = ClipboardView(ctx).apply {
            historyProvider = { listOf("abc") }; applyPalette(pal); refresh(); expandForTest("abc")
        }
        val actions = textViews(v)
            .filter { it.text?.toString() in setOf("常用语", "拆词", "删除") && it.compoundDrawables.any { d -> d != null } }
        assertEquals(3, actions.size)
        val gravities = actions.map { (it.layoutParams as android.widget.FrameLayout.LayoutParams).gravity }
        assertEquals(listOf(Gravity.START, Gravity.CENTER, Gravity.END), gravities)
        assertTrue(actions.all { it.layoutParams.width == ViewGroup.LayoutParams.WRAP_CONTENT })
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

    @Test fun drag_move_reorders_phrase_rows_live_before_drop() {
        var r: Triple<String, Int, Int>? = null
        val v = phraseView().apply { onReorderPhrase = { c, f, t -> r = Triple(c, f, t) } }
        assertEquals(listOf("你好", "在吗", "稍等"), v.listRowTextsForTest())
        v.dragStartForTest(0)
        v.dragMoveToForTest(2)
        assertEquals("row order updates while dragging", listOf("在吗", "稍等", "你好"), v.listRowTextsForTest())
        assertNull("drop callback has not fired yet", r)
        v.dragDropForTest()
        assertEquals(Triple("默认", 0, 2), r)
    }

    @Test fun drag_visual_translation_tracks_finger_before_drop() {
        val v = phraseView()
        v.dragStartAtForTest(0, 20f)
        v.dragMoveAtForTest(0, 76f)
        assertEquals("dragged row follows the finger before any drop", 56f, v.dragTranslationYForTest(), 0.01f)
        v.dragMoveAtForTest(2, 140f)
        assertEquals("row order updates while the drag is still active", listOf("在吗", "稍等", "你好"), v.listRowTextsForTest())
        assertTrue("drop callback has not reset the lifted row yet", v.dragTranslationYForTest() != 0f)
        v.dragDropForTest()
    }

    @Test fun active_drag_stays_captured_after_live_row_reorder_until_pointer_up() {
        var r: Triple<String, Int, Int>? = null
        val v = phraseView().apply { onReorderPhrase = { c, f, t -> r = Triple(c, f, t) } }
        v.dragStartAtForTest(0, 20f)
        v.dragMoveAtForTest(1, 90f)
        assertTrue(v.isDraggingForTest())
        assertTrue(send(v, MotionEvent.ACTION_MOVE, 120f))
        assertTrue("drag remains live after the row moved under the finger", v.isDraggingForTest())
        assertNull(r)
        assertTrue(send(v, MotionEvent.ACTION_UP, 120f))
        assertFalse(v.isDraggingForTest())
        assertEquals(Triple("默认", 0, 1), r)
    }

    @Test fun active_drag_auto_scrolls_at_edges_and_keeps_rows_live_until_drop() {
        val phrases = (0 until 30).map { "P" + it.toString().padStart(2, '0') }
        val drops = ArrayList<Triple<String, Int, Int>>()
        val v = phraseView(phrases).apply {
            onReorderPhrase = { c, f, t -> drops.add(Triple(c, f, t)) }
            enterSortModeForTest()
        }
        layout(v)
        val top = v.listScrollRawTopForTest().toFloat()
        val bottom = v.listScrollRawBottomForTest().toFloat()
        v.dragStartAtForTest(0, top + 24f)

        v.dragUpdateForTest(bottom - 2f)
        val indexAfterEdgeMove = v.listRowTextsForTest().indexOf("P00")
        assertTrue("bottom edge starts the auto-scroll loop", v.isDragAutoScrollScheduledForTest())
        assertTrue("drag remains captured at the bottom edge", v.isDraggingForTest())
        val scrollBeforeDown = v.listScrollYForTest()
        repeat(12) { v.runDragAutoScrollFrameForTest() }
        val indexAfterScroll = v.listRowTextsForTest().indexOf("P00")
        assertTrue("the list scrolls down while the drag stays active", v.listScrollYForTest() > scrollBeforeDown)
        assertTrue(
            "row order keeps updating as edge scrolling changes the target",
            indexAfterScroll > indexAfterEdgeMove,
        )
        assertTrue(v.isDraggingForTest())
        assertTrue("drop callback has not fired during live scrolling", drops.isEmpty())

        v.dragUpdateForTest(top + 2f)
        assertTrue("top edge keeps the auto-scroll loop active", v.isDragAutoScrollScheduledForTest())
        val scrollBeforeUp = v.listScrollYForTest()
        repeat(8) { v.runDragAutoScrollFrameForTest() }
        assertTrue("the list scrolls back up while the same drag is active", v.listScrollYForTest() < scrollBeforeUp)

        v.dragUpdateForTest((top + bottom) / 2f)
        assertFalse("leaving the edge stops auto-scroll without dropping", v.isDragAutoScrollScheduledForTest())
        assertTrue(v.isDraggingForTest())
        val finalIndex = v.listRowTextsForTest().indexOf("P00")
        v.dragDropForTest()
        assertFalse(v.isDraggingForTest())
        assertEquals(listOf(Triple("默认", 0, finalIndex)), drops)
    }

    @Test fun drag_move_reorders_category_rows_live_before_drop() {
        var r: Pair<Int, Int>? = null
        val v = phraseView().apply { onReorderCategory = { f, t -> r = f to t } }
        v.enterCategorySortModeForTest()
        assertEquals(listOf("默认", "工作", "私人"), v.listRowTextsForTest())
        v.dragStartForTest(0)
        v.dragMoveToForTest(2)
        assertEquals("category rows update while dragging", listOf("工作", "私人", "默认"), v.listRowTextsForTest())
        assertNull("drop callback has not fired yet", r)
        v.dragDropForTest()
        assertEquals(0 to 2, r)
    }

    @Test fun rowAt_skips_the_dragged_row_so_downward_drag_finds_a_lower_target() {
        val v = ClipboardView(ctx)
        val tops = intArrayOf(0, 100, 200)
        val heights = intArrayOf(100, 100, 100)
        assertEquals(2, v.rowAt(tops, heights, skip = 0, y = 250))
        assertEquals(1, v.rowAt(tops, heights, skip = 0, y = 150))
        assertEquals(0, v.rowAt(tops, heights, skip = 2, y = 50))
        assertNull(v.rowAt(tops, heights, skip = 0, y = 999))
    }

    @Test fun drag_drop_in_place_is_a_noop() {
        var r: Triple<String, Int, Int>? = null
        val v = phraseView().apply { onReorderPhrase = { c, f, t -> r = Triple(c, f, t) } }
        v.dragStartForTest(1); v.dragMoveToForTest(1); v.dragDropForTest()
        assertNull("same index → no reorder callback", r)
    }


    @Test fun phrase_select_mode_title_and_batch_actions() {
        val v = phraseView().apply { enterSelectForTest(listOf("你好", "在吗")) }
        val ls = labels(v)
        assertTrue("编辑常用语" in ls)
        assertFalse("编辑剪贴板" in ls)
        assertTrue("移动到分类" in ls)
        assertTrue("删除" in ls)
        assertFalse("添加常用语" in ls)
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
        assertTrue(chip.performLongClick())
        assertTrue(click(overlayOf(v), "删除「工作」")); assertEquals("工作", deleted)
    }


    @Test fun top_bar_icons_are_uniform_size() {
        val v = phraseView()
        val wanted = setOf("返回", "添加常用语", "多选", "清空分类")
        val icons = allViews(v).filter { it.contentDescription?.toString() in wanted && it.hasOnClickListeners() }
        assertEquals("all 4 phrase-tab top icons present", 4, icons.size)
        assertTrue("返回 is no longer a '‹' text glyph", textViews(v).none { it.text?.toString() == "‹" })
        assertEquals("all top icons share one width (item7)", 1, icons.map { it.layoutParams.width }.toSet().size)
        assertEquals("all top icons share one height (item7)", 1, icons.map { it.layoutParams.height }.toSet().size)
        assertTrue("top icons are transparent controls, not rounded chips", icons.all { it.background == null })
    }


    private fun clipboardView(history: List<String>, cats: List<String>): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history }
        categoriesProvider = { cats }
        applyPalette(pal); refresh()
    }

    @Test fun clipboard_add_to_new_category_carries_the_clip() {
        var carried: List<String>? = null
        val v = clipboardView(listOf("hello"), listOf("默认")).apply { onAddCategoryThenAdd = { carried = it } }
        v.expandForTest("hello")
        assertTrue(clickAction(v, "常用语"))
        assertTrue(click(overlayOf(v), "＋ 新建分类…"))
        assertEquals("the clip rides the inline new-category flow", listOf("hello"), carried)
    }

    @Test fun clipboard_add_to_new_category_when_none_exist_carries_the_clip() {
        var carried: List<String>? = null
        val v = clipboardView(listOf("hello"), emptyList()).apply { onAddCategoryThenAdd = { carried = it } }
        v.expandForTest("hello")
        assertTrue(clickAction(v, "常用语"))
        assertEquals(listOf("hello"), carried)
    }

    @Test fun clipboard_add_to_existing_category_still_works() {
        var saved: Pair<String, List<String>>? = null
        val v = clipboardView(listOf("hello"), listOf("默认")).apply { onSaveAsPhrasesTo = { c, l -> saved = c to l } }
        v.expandForTest("hello")
        assertTrue(clickAction(v, "常用语"))
        assertTrue(click(overlayOf(v), "默认"))
        assertEquals("默认" to listOf("hello"), saved)
    }


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
        assertEquals("默认" to listOf("你好"), carried)
    }

    @Test fun move_to_existing_category_still_works() {
        var moved: Triple<String, String, String>? = null
        val v = phraseView().apply { onMovePhrase = { f, t, to -> moved = Triple(f, t, to) } }
        v.expandForTest("你好")
        assertTrue(click(v, "移动"))
        assertTrue(click(overlayOf(v), "工作"))
        assertEquals(Triple("默认", "你好", "工作"), moved)
    }
}
