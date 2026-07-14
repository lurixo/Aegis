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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import kotlin.math.abs
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
    private fun maxAutoScrollStepPx(): Int = (8 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun phraseView(): ClipboardView = phraseView(listOf("你好", "在吗", "稍等"))
    private fun phraseView(phrases: List<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认", "工作", "私人") }
        phrasesInProvider = { c -> if (c == "默认") phrases else emptyList() }
        applyPalette(pal)
        forcePhrasesStateForTest("默认"); refresh()
    }


    @Test fun expanded_phrase_card_action_row_is_edit_note_move_delete() {
        val v = phraseView().apply { expandForTest("你好") }
        val actions = textViews(v).filter { it.compoundDrawables[0] != null && it.background is GradientDrawable }
        assertEquals(listOf(ctx.getString(com.aegis.ime.R.string.clip_edit), ctx.getString(com.aegis.ime.R.string.clip_note), ctx.getString(com.aegis.ime.R.string.clip_move), ctx.getString(com.aegis.ime.R.string.clip_delete)), actions.map { it.text.toString() })
        assertFalse(labels(v).any { it == "置顶" || it == "Pin to top" })
    }

    @Test fun expanded_clipboard_card_keeps_add_split_delete() {
        val v = ClipboardView(ctx).apply {
            historyProvider = { listOf("abc") }; applyPalette(pal); refresh(); expandForTest("abc")
        }
        val ls = labels(v)
        assertTrue(ls.any { it.contains(ctx.getString(com.aegis.ime.R.string.clip_phrases)) }); assertTrue(ls.any { it.contains(ctx.getString(com.aegis.ime.R.string.clip_split_word)) }); assertTrue(ls.any { it.contains(ctx.getString(com.aegis.ime.R.string.clip_delete)) })
    }

    @Test fun clipboard_and_phrase_action_buttons_share_height_rounding_and_spacing() {
        val clip = ClipboardView(ctx).apply {
            historyProvider = { listOf("abc") }; applyPalette(pal); refresh(); expandForTest("abc")
        }
        val clipActions = textViews(clip)
            .filter { it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_phrases), ctx.getString(com.aegis.ime.R.string.clip_split_word), ctx.getString(com.aegis.ime.R.string.clip_delete)) && it.compoundDrawables.any { d -> d != null } }
        val phraseActions = textViews(phraseView().apply { expandForTest("你好") })
            .filter { it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_edit), ctx.getString(com.aegis.ime.R.string.clip_note), ctx.getString(com.aegis.ime.R.string.clip_move), ctx.getString(com.aegis.ime.R.string.clip_delete)) && it.compoundDrawables.any { d -> d != null } }
        assertEquals(3, clipActions.size)
        assertEquals(4, phraseActions.size)
        val all = clipActions + phraseActions
        assertEquals(1, all.map { it.layoutParams.height }.toSet().size)
        assertEquals(1, all.map { it.compoundDrawablePadding }.toSet().size)
        assertEquals((2 * ctx.resources.displayMetrics.density).toInt(), all.first().compoundDrawablePadding)
        val heightTolerance = 2 * ctx.resources.displayMetrics.density + 1f
        assertTrue(all.all { abs(it.compoundDrawables[0].intrinsicHeight - it.textSize) <= heightTolerance })
        assertTrue(all.all { it.background is GradientDrawable })
        assertEquals(1, all.map { (it.background as GradientDrawable).cornerRadius }.toSet().size)
        assertTrue(all.all { (it.background as GradientDrawable).cornerRadius > 0f })
        val gap = (4 * ctx.resources.displayMetrics.density).toInt()
        assertEquals(listOf(0, gap, gap), clipActions.map { (it.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart })
        assertEquals(listOf(0, gap, gap, gap), phraseActions.map { (it.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart })
    }

    @Test fun edit_action_invokes_onEditPhrase() {
        var got: Pair<String, String>? = null
        val v = phraseView().apply { onEditPhrase = { c, t -> got = c to t }; expandForTest("你好") }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_edit)))
        assertEquals("默认" to "你好", got)
    }

    @Test fun move_action_opens_chooser_excluding_current_then_invokes_onMovePhrase() {
        var move: Triple<String, String, String>? = null
        val v = phraseView().apply { onMovePhrase = { f, t, to -> move = Triple(f, t, to) }; expandForTest("你好") }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_move)))
        val chooser = labels(overlayOf(v))
        assertTrue("工作" in chooser); assertTrue("私人" in chooser)
        assertFalse("current category excluded", "默认" in chooser)
        assertTrue(click(overlayOf(v), "工作"))
        assertEquals(Triple("默认", "你好", "工作"), move)
    }

    @Test fun closing_delete_confirmation_does_not_invoke_onDeletePhrasesFrom() {
        var del: Pair<String, List<String>>? = null
        val v = phraseView().apply { onDeletePhrasesFrom = { c, l -> del = c to l }; expandForTest("你好") }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertNull(del)
        overlayOf(v).performClick()
        assertEquals(View.GONE, overlayOf(v).visibility)
        assertNull(del)
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

    @Test fun active_drag_does_not_reparent_the_touched_row_while_preview_reorders() {
        val v = phraseView()
        val row = v.listRowViewForTest(0)
        v.dragStartAtForTest(0, 20f)
        v.dragMoveToForTest(2)
        assertTrue("dragged row remains the same physical child until pointer up", row === v.listRowViewForTest(0))
        assertEquals("visual order still previews the pending drop", listOf("在吗", "稍等", "你好"), v.listRowTextsForTest())
        v.dragCancelForTest()
    }

    @Test fun active_drag_can_move_back_to_the_original_slot_before_drop() {
        val v = phraseView()
        layout(v)
        val top = v.listScrollRawTopForTest().toFloat()
        v.dragStartAtForTest(0, top + 24f)
        v.dragUpdateForTest(top + 220f)
        assertEquals(listOf("在吗", "稍等", "你好"), v.listRowTextsForTest())
        v.dragUpdateForTest(top + 12f)
        assertEquals(listOf("你好", "在吗", "稍等"), v.listRowTextsForTest())
        v.dragCancelForTest()
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
        repeat(24) { v.runDragAutoScrollFrameForTest() }
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
        repeat(12) { v.runDragAutoScrollFrameForTest() }
        assertTrue("the list scrolls back up while the same drag is active", v.listScrollYForTest() < scrollBeforeUp)

        v.dragUpdateForTest((top + bottom) / 2f)
        assertFalse("leaving the edge stops auto-scroll without dropping", v.isDragAutoScrollScheduledForTest())
        assertTrue(v.isDraggingForTest())
        val finalIndex = v.listRowTextsForTest().indexOf("P00")
        v.dragDropForTest()
        assertFalse(v.isDraggingForTest())
        assertEquals(listOf(Triple("默认", 0, finalIndex)), drops)
    }

    @Test fun drag_auto_scroll_step_is_capped_for_control() {
        val phrases = (0 until 40).map { "P" + it.toString().padStart(2, '0') }
        val v = phraseView(phrases).apply { enterSortModeForTest() }
        layout(v)
        val top = v.listScrollRawTopForTest().toFloat()
        val bottom = v.listScrollRawBottomForTest().toFloat()
        v.dragStartAtForTest(0, top + 24f)
        v.dragUpdateForTest(bottom + 1000f)

        val before = v.listScrollYForTest()
        assertTrue(v.runDragAutoScrollFrameForTest())
        val step = v.listScrollYForTest() - before
        assertTrue("edge auto-scroll should stay slow and controllable", step in 1..maxAutoScrollStepPx())
        v.dragCancelForTest()
    }

    @Test fun action_cancel_cleans_phrase_drag_without_reorder_callback() {
        var r: Triple<String, Int, Int>? = null
        val v = phraseView().apply { onReorderPhrase = { c, f, t -> r = Triple(c, f, t) } }
        v.dragStartAtForTest(0, 20f)
        v.dragMoveAtForTest(2, 140f)
        assertTrue(v.isDraggingForTest())
        assertTrue(send(v, MotionEvent.ACTION_CANCEL, 140f))
        assertFalse(v.isDraggingForTest())
        assertNull("cancel is cleanup, not a persisted drop", r)
        assertEquals("cancel restores the original visual order", listOf("你好", "在吗", "稍等"), v.listRowTextsForTest())
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

    @Test fun active_category_drag_auto_scrolls_at_edges_and_drops_once() {
        val cats = (0 until 30).map { "C" + it.toString().padStart(2, '0') }
        val drops = ArrayList<Pair<Int, Int>>()
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { cats }
            phrasesInProvider = { emptyList() }
            onReorderCategory = { f, t -> drops.add(f to t) }
            applyPalette(pal)
            forcePhrasesStateForTest(cats.first())
            refresh()
            enterCategorySortModeForTest()
        }
        layout(v)
        val top = v.listScrollRawTopForTest().toFloat()
        val bottom = v.listScrollRawBottomForTest().toFloat()
        v.dragStartAtForTest(0, top + 24f)

        v.dragUpdateForTest(bottom - 2f)
        val indexAfterEdgeMove = v.listRowTextsForTest().indexOf("C00")
        assertTrue("bottom edge starts category auto-scroll", v.isDragAutoScrollScheduledForTest())
        val scrollBeforeDown = v.listScrollYForTest()
        repeat(24) { v.runDragAutoScrollFrameForTest() }
        val indexAfterScroll = v.listRowTextsForTest().indexOf("C00")
        assertTrue("category list scrolls down while dragging", v.listScrollYForTest() > scrollBeforeDown)
        assertTrue("category row order updates during edge scroll", indexAfterScroll > indexAfterEdgeMove)
        assertTrue(v.isDraggingForTest())
        assertTrue("drop callback waits for pointer up", drops.isEmpty())

        v.dragUpdateForTest((top + bottom) / 2f)
        val finalIndex = v.listRowTextsForTest().indexOf("C00")
        v.dragDropForTest()
        assertFalse(v.isDraggingForTest())
        assertEquals(listOf(0 to finalIndex), drops)
    }

    @Test fun action_cancel_cleans_category_drag_without_reorder_callback() {
        var r: Pair<Int, Int>? = null
        val v = phraseView().apply { onReorderCategory = { f, t -> r = f to t } }
        v.enterCategorySortModeForTest()
        v.dragStartAtForTest(0, 20f)
        v.dragMoveAtForTest(2, 140f)
        assertTrue(v.isDraggingForTest())
        assertTrue(send(v, MotionEvent.ACTION_CANCEL, 140f))
        assertFalse(v.isDraggingForTest())
        assertNull("category cancel is cleanup, not a persisted drop", r)
        assertEquals("category cancel restores the original visual order", listOf("默认", "工作", "私人"), v.listRowTextsForTest())
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
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_edit_phrases) in ls)
        assertFalse(ctx.getString(com.aegis.ime.R.string.clip_edit_clipboard) in ls)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_selected_count, 2) in ls)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_move_to_category) in ls)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete) in ls)
        assertFalse(ctx.getString(com.aegis.ime.R.string.clip_add_phrase) in ls)

        v.toggleSelectForTest("你好")
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_selected_count, 1) in labels(v))
    }

    @Test fun clipboard_select_mode_keeps_add_phrase_action() {
        val v = ClipboardView(ctx).apply {
            historyProvider = { listOf("a", "b") }; applyPalette(pal); refresh(); enterSelectForTest(listOf("a"))
        }
        val ls = labels(v)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_edit_clipboard) in ls); assertTrue(ctx.getString(com.aegis.ime.R.string.clip_add_phrase) in ls); assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete) in ls)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_selected_count, 1) in ls)
        assertFalse(ctx.getString(com.aegis.ime.R.string.clip_move_to_category) in ls)

        v.toggleSelectForTest("b")
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_selected_count, 2) in labels(v))
    }

    @Test fun batch_move_invokes_onMovePhrasesTo_with_selection_and_target() {
        var batch: Triple<String, List<String>, String>? = null
        val v = phraseView().apply {
            onMovePhrasesTo = { f, list, to -> batch = Triple(f, list, to) }
            enterSelectForTest(listOf("你好", "稍等"))
        }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_move_to_category)))
        assertTrue(click(overlayOf(v), "工作"))
        assertEquals("默认", batch?.first)
        assertEquals(listOf("你好", "稍等"), batch?.second)
        assertEquals("工作", batch?.third)
    }

    @Test fun batch_delete_requires_confirmation_before_onDeletePhrasesFrom() {
        var del: Pair<String, List<String>>? = null
        val v = phraseView().apply { onDeletePhrasesFrom = { c, l -> del = c to l }; enterSelectForTest(listOf("你好")) }
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertNull(del)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete_phrase_confirm) in labels(overlayOf(v)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_cancel)))
        assertNull(del)
        assertTrue(v.isSelectModeForTest())
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        overlayOf(v).performClick()
        assertNull(del)
        assertTrue(v.isSelectModeForTest())
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals("默认", del?.first)
        assertEquals(listOf("你好"), del?.second)
        assertFalse(v.isSelectModeForTest())
    }


    @Test fun categorybar_pencil_menu_add_category_still_triggers_onAddCategory() {
        var adds = 0
        val v = phraseView().apply { onAddCategory = { adds++ } }
        clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_add_phrase)); assertEquals("top-bar ＋ no longer creates a category", 0, adds)
        assertTrue("categoryBar ✎", clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_manage_phrases)))
        assertTrue("✎ menu has 添加分类", click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_add_category))); assertEquals(1, adds)
    }

    @Test fun category_chip_long_press_offers_inline_rename_and_delete() {
        var renamed: String? = null
        var deleted: String? = null
        val v = phraseView().apply { onRenameCategory = { renamed = it }; onDeleteCategory = { deleted = it } }
        val chip = textViews(v).first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
        assertTrue(chip.performLongClick())
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_rename_named, "工作"))); assertEquals("工作", renamed)
        assertTrue(chip.performLongClick())
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作"))); assertEquals("工作", deleted)
    }

    @Test fun non_first_category_selection_retains_scroll_with_edit_pinned_outside() {
        val categories = (0..11).map { "分类${it.toString().padStart(2, '0')}" }
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { categories }
            phrasesInProvider = { listOf("短语") }
            applyPalette(pal)
            forcePhrasesStateForTest(categories.first())
            refresh()
        }
        layout(v, w = 320, h = 400)
        val initialScroll = allViews(v).filterIsInstance<HorizontalScrollView>().single()
        initialScroll.scrollTo((initialScroll.getChildAt(0).width - initialScroll.width).coerceAtLeast(0), 0)
        val savedScroll = initialScroll.scrollX
        assertTrue(savedScroll > 0)

        val selectedName = categories.last()
        assertTrue(textViews(v).first { it.text?.toString() == selectedName }.performClick())
        layout(v, w = 320, h = 400)

        val retainedScroll = allViews(v).filterIsInstance<HorizontalScrollView>().single()
        val selected = textViews(v).first { it.text?.toString() == selectedName }
        val manage = allViews(v).first { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_manage_phrases) }
        assertTrue(retainedScroll !== initialScroll)
        assertEquals(selectedName, v.phraseCatForTest())
        assertTrue(retainedScroll.scrollX > 0)
        assertTrue(selected.left - retainedScroll.scrollX < retainedScroll.width)
        assertTrue(selected.right - retainedScroll.scrollX > 0)
        assertTrue(retainedScroll.parent === manage.parent)
        assertFalse(allViews(retainedScroll).contains(manage))
        assertTrue((retainedScroll.parent as View).background is GradientDrawable)
        assertTrue(selected.background is GradientDrawable)

        v.refresh()
        layout(v, w = 320, h = 400)
        assertEquals(selectedName, v.phraseCatForTest())
        assertTrue(allViews(v).filterIsInstance<HorizontalScrollView>().single().scrollX > 0)
    }


    @Test fun top_bar_icons_are_uniform_size() {
        val v = phraseView()
        val wanted = setOf(ctx.getString(com.aegis.ime.R.string.clip_back), ctx.getString(com.aegis.ime.R.string.clip_add_phrase), ctx.getString(com.aegis.ime.R.string.clip_multi_select), ctx.getString(com.aegis.ime.R.string.clip_clear_category))
        val icons = allViews(v).filter { it.contentDescription?.toString() in wanted && it.hasOnClickListeners() }
        assertEquals("all 4 phrase-tab top icons present", 4, icons.size)
        assertTrue("返回 is no longer a '‹' text glyph", textViews(v).none { it.text?.toString() == "‹" })
        assertEquals("all top icons share one width (item7)", 1, icons.map { it.layoutParams.width }.toSet().size)
        assertEquals("all top icons share one height (item7)", 1, icons.map { it.layoutParams.height }.toSet().size)
        val surfaced = icons.filter { it.contentDescription?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_add_phrase), ctx.getString(com.aegis.ime.R.string.clip_multi_select), ctx.getString(com.aegis.ime.R.string.clip_clear_category)) }
        assertTrue(surfaced.all { it.background is GradientDrawable })
        val iconSize = (36 * ctx.resources.displayMetrics.density).toInt()
        assertTrue(surfaced.all { it.layoutParams.width == iconSize && it.layoutParams.height == iconSize })
        assertTrue(surfaced.all { (it.background as GradientDrawable).cornerRadius > 0f })
        assertTrue(icons.filterNot { it in surfaced }.all { it.background == null })
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
        assertTrue(clickAction(v, ctx.getString(com.aegis.ime.R.string.clip_phrases)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_new_category)))
        assertEquals("the clip rides the inline new-category flow", listOf("hello"), carried)
    }

    @Test fun clipboard_add_to_new_category_when_none_exist_carries_the_clip() {
        var carried: List<String>? = null
        val v = clipboardView(listOf("hello"), emptyList()).apply { onAddCategoryThenAdd = { carried = it } }
        v.expandForTest("hello")
        assertTrue(clickAction(v, ctx.getString(com.aegis.ime.R.string.clip_phrases)))
        assertEquals(listOf("hello"), carried)
    }

    @Test fun clipboard_add_to_existing_category_still_works() {
        var saved: Pair<String, List<String>>? = null
        val v = clipboardView(listOf("hello"), listOf("默认")).apply { onSaveAsPhrasesTo = { c, l -> saved = c to l } }
        v.expandForTest("hello")
        assertTrue(clickAction(v, ctx.getString(com.aegis.ime.R.string.clip_phrases)))
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
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_move)))
        assertTrue("no other category → offers 新建", ctx.getString(com.aegis.ime.R.string.clip_no_other_categories) in labels(overlayOf(v)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_new_category)))
        assertEquals("默认" to listOf("你好"), carried)
    }

    @Test fun move_to_existing_category_still_works() {
        var moved: Triple<String, String, String>? = null
        val v = phraseView().apply { onMovePhrase = { f, t, to -> moved = Triple(f, t, to) } }
        v.expandForTest("你好")
        assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_move)))
        assertTrue(click(overlayOf(v), "工作"))
        assertEquals(Triple("默认", "你好", "工作"), moved)
    }
}
