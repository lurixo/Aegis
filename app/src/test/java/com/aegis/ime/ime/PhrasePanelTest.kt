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

import com.aegis.ime.user.asClipEntries
import com.aegis.ime.user.clipEntries
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    private fun categoryScroll(root: View): HorizontalScrollView =
        allViews(root).filterIsInstance<HorizontalScrollView>()
            .single { scroll ->
                allViews(scroll).filterIsInstance<TextView>().any(View::isLongClickable)
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
    private fun dp(value: Int): Int = (value * ctx.resources.displayMetrics.density).toInt()
    private fun maxAutoScrollStepPx(): Int = (8 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun boundsInRoot(root: ViewGroup, target: View): Rect = Rect(0, 0, target.width, target.height).also {
        root.offsetDescendantRectToMyCoords(target, it)
    }
    private fun phraseView(): ClipboardView = phraseView(listOf("你好", "在吗", "稍等"))
    private fun phraseView(phrases: List<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认", "工作", "私人") }
        phrasesInProvider = { c -> if (c == "默认") phrases else emptyList() }
        applyPalette(pal)
        forcePhrasesStateForTest("默认"); refresh()
    }


    @Test fun expanded_phrase_card_action_row_is_edit_note_move_delete() {
        val v = phraseView().apply { expandForTest("你好") }
        layout(v)
        val expected = listOf(ctx.getString(com.aegis.ime.R.string.clip_edit), ctx.getString(com.aegis.ime.R.string.clip_note), ctx.getString(com.aegis.ime.R.string.clip_move), ctx.getString(com.aegis.ime.R.string.clip_delete))
        val actions = textViews(v).filter { it.text?.toString() in expected && it.compoundDrawables[0] != null && it.hasOnClickListeners() }
        assertEquals(expected, actions.map { it.text.toString() })
        assertTrue(actions.all { it.compoundDrawables[0] != null && it.text.isNotEmpty() })
        assertFalse(labels(v).any { it == "置顶" || it == "Pin to top" })
    }

    @Test fun expanded_clipboard_card_keeps_add_split_delete() {
        val v = ClipboardView(ctx).apply {
            historyProvider = { clipEntries("abc") }; applyPalette(pal); refresh(); expandForTest("abc")
        }
        layout(v)
        val expected = listOf(ctx.getString(com.aegis.ime.R.string.clip_phrases), ctx.getString(com.aegis.ime.R.string.clip_split_word), ctx.getString(com.aegis.ime.R.string.clip_delete))
        val actions = textViews(v).filter { it.text?.toString() in expected && it.compoundDrawables[0] != null && it.hasOnClickListeners() }
        assertEquals(expected, actions.map { it.text.toString() })
        assertTrue(actions.all { it.compoundDrawables[0] != null && it.text.isNotEmpty() })
    }

    @Test fun clipboard_and_phrase_action_buttons_share_height_rounding_and_spacing() {
        val clip = ClipboardView(ctx).apply {
            historyProvider = { clipEntries("abc") }; applyPalette(pal); refresh(); expandForTest("abc")
        }
        layout(clip)
        val phrase = phraseView().apply { expandForTest("你好") }
        layout(phrase)
        val clipActions = textViews(clip)
            .filter { it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_phrases), ctx.getString(com.aegis.ime.R.string.clip_split_word), ctx.getString(com.aegis.ime.R.string.clip_delete)) && it.compoundDrawables.any { d -> d != null } }
        val phraseActions = textViews(phrase)
            .filter { it.text?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_edit), ctx.getString(com.aegis.ime.R.string.clip_note), ctx.getString(com.aegis.ime.R.string.clip_move), ctx.getString(com.aegis.ime.R.string.clip_delete)) && it.compoundDrawables.any { d -> d != null } }
        assertEquals(3, clipActions.size)
        assertEquals(4, phraseActions.size)
        val all = clipActions + phraseActions
        assertEquals(1, all.map { it.layoutParams.height }.toSet().size)
        assertTrue(all.all { it.layoutParams.width == ViewGroup.LayoutParams.WRAP_CONTENT })
        assertTrue(all.all { it.compoundDrawablePadding == it.paint.measureText(" ").roundToInt().coerceAtLeast(1) })
        assertTrue(all.all { Gravity.getAbsoluteGravity(it.gravity, it.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT })
        val heightTolerance = 2 * ctx.resources.displayMetrics.density + 1f
        assertTrue(all.all { abs(it.compoundDrawables[0].intrinsicHeight - it.textSize) <= heightTolerance })
        assertTrue(clipActions.all { clip.isImmediateActionForTest(it) })
        assertTrue(phraseActions.all { phrase.isImmediateActionForTest(it) })
        assertTrue(clipActions.all { it.background === clip.immediateActionDrawableForTest(it) })
        assertTrue(phraseActions.all { it.background === phrase.immediateActionDrawableForTest(it) })
        assertTrue(all.all { it.foreground == null && it.height == dp(48) })
        for (action in all) {
            action.draw(Canvas(Bitmap.createBitmap(action.width, action.height, Bitmap.Config.ARGB_8888)))
            val hit = Rect()
            action.getHitRect(hit)
            assertEquals(Rect(action.left, action.top, action.right, action.bottom), hit)
        }
        val gap = (4 * ctx.resources.displayMetrics.density).toInt()
        assertEquals(listOf(0, gap, gap), clipActions.map { (it.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart })
        assertEquals(listOf(0, gap, gap, gap), phraseActions.map { (it.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart })
        for ((view, body, actions) in listOf(Triple(clip, "abc", clipActions), Triple(phrase, "你好", phraseActions))) {
            val row = actions.first().parent as View
            val surface = row.parent as View
            val header = textViews(view).first { it.text?.toString() == body }.parent as View
            val headerFrame = header.parent as View
            assertTrue(headerFrame.parent === surface)
            assertTrue(surface.background is GradientDrawable)
            assertTrue((surface.background as GradientDrawable).cornerRadius > 0f)
            assertTrue(header.background == null)
            assertEquals((row as ViewGroup).paddingLeft, actions.first().left)
        }
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
        val v = phraseView().apply { onDeletePhrasesFrom = { c, l -> del = c to l; true }; expandForTest("你好") }
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
        assertTrue(ctx.resources.getQuantityString(com.aegis.ime.R.plurals.clip_selected_count, 2, 2) in ls)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_move_to_category) in ls)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete) in ls)
        assertFalse(ctx.getString(com.aegis.ime.R.string.clip_add_phrase) in ls)

        v.toggleSelectForTest("你好")
        assertTrue(ctx.resources.getQuantityString(com.aegis.ime.R.plurals.clip_selected_count, 1, 1) in labels(v))
    }

    @Test fun clipboard_select_mode_keeps_add_phrase_action() {
        val v = ClipboardView(ctx).apply {
            historyProvider = { clipEntries("a", "b") }; applyPalette(pal); refresh(); enterSelectForTest(listOf("a"))
        }
        val ls = labels(v)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_edit_clipboard) in ls); assertTrue(ctx.getString(com.aegis.ime.R.string.clip_add_phrase) in ls); assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete) in ls)
        assertTrue(ctx.resources.getQuantityString(com.aegis.ime.R.plurals.clip_selected_count, 1, 1) in ls)
        assertFalse(ctx.getString(com.aegis.ime.R.string.clip_move_to_category) in ls)

        v.toggleSelectForTest("b")
        assertTrue(ctx.resources.getQuantityString(com.aegis.ime.R.plurals.clip_selected_count, 2, 2) in labels(v))
    }

    @Test fun select_mode_top_actions_keep_physical_order_and_symmetry_in_ltr_and_rtl() {
        for (layoutDirection in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val clipboard = ClipboardView(ctx).apply {
                this.layoutDirection = layoutDirection
                historyProvider = { clipEntries("a", "b") }; applyPalette(pal); refresh(); enterSelectForTest(listOf("a"))
            }
            val phrases = phraseView().apply {
                this.layoutDirection = layoutDirection
                enterSelectForTest(listOf("你好"))
            }
            val geometries = listOf(clipboard, phrases).map { view ->
                layout(view, w = 480, h = 400)
                val selectAll = checkNotNull(view.selectAllActionForTest())
                val cancel = checkNotNull(view.cancelSelectActionForTest())
                val topBar = selectAll.parent as ViewGroup
                val title = topBar.getChildAt(1)
                assertTrue(cancel.parent === topBar)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, selectAll.layoutParams.width)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, cancel.layoutParams.width)
                assertEquals(dp(48), selectAll.height)
                assertEquals(dp(48), cancel.height)
                assertTrue(selectAll.left < cancel.left)
                assertEquals(topBar.paddingLeft, selectAll.left)
                assertEquals(topBar.width - topBar.paddingRight, cancel.right)
                assertEquals(selectAll.left, topBar.width - cancel.right)
                assertEquals(selectAll.top, cancel.top)
                assertEquals(selectAll.bottom, cancel.bottom)
                assertTrue(selectAll.right <= title.left)
                assertTrue(title.right <= cancel.left)
                assertEquals(Gravity.CENTER_VERTICAL or Gravity.START, selectAll.gravity)
                assertEquals(Gravity.CENTER, cancel.gravity)
                assertTrue(selectAll.hasOnClickListeners())
                assertTrue(cancel.hasOnClickListeners())
                assertTrue(view.isImmediateActionForTest(selectAll))
                assertTrue(view.isImmediateActionForTest(cancel))
                assertTrue(selectAll.background === view.immediateActionDrawableForTest(selectAll))
                assertTrue(cancel.background === view.immediateActionDrawableForTest(cancel))
                assertTrue(selectAll.foreground == null && cancel.foreground == null)
                assertEquals(selectAll.paint.measureText(" ").roundToInt().coerceAtLeast(1), selectAll.compoundDrawablePadding)

                val bottomLeftLabel = if (view.isClipboardTabForTest()) {
                    ctx.getString(com.aegis.ime.R.string.clip_add_phrase)
                } else {
                    ctx.getString(com.aegis.ime.R.string.clip_move_to_category)
                }
                val bottomLeft = textViews(view).single { it.text?.toString() == bottomLeftLabel }
                val bottom = bottomLeft.parent as ViewGroup
                val bottomRight = textViews(bottom).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_delete) }
                val selectBounds = boundsInRoot(view, selectAll)
                val cancelBounds = boundsInRoot(view, cancel)
                val bottomLeftBounds = boundsInRoot(view, bottomLeft)
                val bottomRightBounds = boundsInRoot(view, bottomRight)
                assertEquals(dp(48), bottomLeft.height)
                assertEquals(dp(48), bottomRight.height)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, bottomLeft.layoutParams.width)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, bottomRight.layoutParams.width)
                assertEquals(selectBounds.left, bottomLeftBounds.left)
                assertEquals(cancelBounds.right, bottomRightBounds.right)
                assertTrue(bottomLeftBounds.right <= bottomRightBounds.left)
                assertTrue(view.isImmediateActionForTest(bottomLeft))
                assertTrue(view.isImmediateActionForTest(bottomRight))
                assertTrue(bottomLeft.background === view.immediateActionDrawableForTest(bottomLeft))
                assertTrue(bottomRight.background === view.immediateActionDrawableForTest(bottomRight))
                assertEquals(selectAll.currentTextColor, bottomLeft.currentTextColor)
                assertEquals(cancel.currentTextColor, bottomRight.currentTextColor)

                val firstRow = checkNotNull(view.listRowViewForTest(0)) as ViewGroup
                val itemCircle = firstRow.getChildAt(0)
                val itemLabel = firstRow.getChildAt(1) as TextView
                val selectAllCircleCenterX = selectBounds.left + selectAll.paddingLeft + selectAll.compoundDrawables[0].intrinsicWidth / 2
                assertEquals("the per-item circle center lines up with the select-all circle center", selectAllCircleCenterX, boundsInRoot(view, itemCircle).left + itemCircle.width / 2)
                assertTrue(boundsInRoot(view, itemCircle).right <= boundsInRoot(view, itemLabel).left)
                listOf(selectAll.left, selectAll.top, selectAll.right, selectAll.bottom, cancel.left, cancel.top, cancel.right, cancel.bottom)
            }
            assertEquals(1, geometries.toSet().size)
        }
    }

    @Test fun select_mode_row_circle_and_text_line_up_with_the_select_all_control() {
        val select = phraseView().apply { enterSelectForTest() }
        layout(select)
        val row = checkNotNull(select.listRowViewForTest(0)) as ViewGroup
        val radio = row.getChildAt(0)
        val label = row.getChildAt(1) as TextView
        val selectAll = checkNotNull(select.selectAllActionForTest())
        val selectAllBounds = boundsInRoot(select, selectAll)

        val selectAllCircleCenterX = selectAllBounds.left + selectAll.paddingLeft + selectAll.compoundDrawables[0].intrinsicWidth / 2
        assertEquals("the per-item circle center lines up with the select-all circle center", selectAllCircleCenterX, boundsInRoot(select, radio).left + radio.width / 2)
        val selectAllTextLeft = selectAllBounds.left + selectAll.totalPaddingLeft
        val rowTextLeft = boundsInRoot(select, label).left + label.totalPaddingLeft
        assertEquals("the row text lines up with the select-all button text", selectAllTextLeft, rowTextLeft)
        assertTrue("the radio stays left of the text", boundsInRoot(select, radio).right <= boundsInRoot(select, label).left)
    }

    @Test
    fun category_page_header_rows_and_footer_share_the_requested_edges() {
        for (layoutDirection in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val list = phraseView().apply { this.layoutDirection = layoutDirection }
            layout(list, w = 480, h = 400)
            val listBack = allViews(list).filterIsInstance<PanelHeaderBackControl>().single()
            val view = phraseView().apply {
                this.layoutDirection = layoutDirection
                enterCategorySortModeForTest()
            }
            layout(view, w = 480, h = 400)
            val back = allViews(view).filterIsInstance<PanelHeaderBackControl>().single()
            val title = textViews(view).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_manage_categories) }
            val create = textViews(view).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_add_category) }
            val import = textViews(view).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_import_phrases) }
            val export = textViews(view).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_export_phrases) }
            val firstRow = checkNotNull(view.listRowViewForTest(0)) as ViewGroup
            assertEquals(4, firstRow.childCount)
            val categoryArea = firstRow.getChildAt(0) as HorizontalScrollView
            val category = categoryArea.getChildAt(0) as TextView
            val rename = firstRow.getChildAt(1) as TextView
            val delete = firstRow.getChildAt(2) as TextView
            val handle = firstRow.getChildAt(3)
            val backBounds = boundsInRoot(view, back)
            val titleBounds = boundsInRoot(view, title)
            val createBounds = boundsInRoot(view, create)
            val categoryBounds = boundsInRoot(view, categoryArea)
            val renameBounds = boundsInRoot(view, rename)
            val deleteBounds = boundsInRoot(view, delete)
            val handleBounds = boundsInRoot(view, handle)
            assertEquals(ctx.getString(com.aegis.ime.R.string.clip_back), back.text.toString())
            assertEquals("the page's back sits where the phrase list's back sits", boundsInRoot(list, listBack), backBounds)
            assertTrue(back.height >= dp(48))
            assertEquals(pal.keyLabel, back.currentTextColor)
            assertEquals(android.graphics.Typeface.BOLD, title.typeface.style and android.graphics.Typeface.BOLD)
            assertEquals(pal.keyLabel, title.currentTextColor)
            assertTrue(backBounds.right <= titleBounds.left)
            assertTrue(titleBounds.right <= createBounds.left)
            assertEquals(View.LAYOUT_DIRECTION_LTR, firstRow.layoutDirection)
            assertEquals("默认", category.text.toString())
            assertEquals(ctx.getString(com.aegis.ime.R.string.clip_rename), rename.text.toString())
            assertEquals(ctx.getString(com.aegis.ime.R.string.clip_delete), delete.text.toString())
            for (action in listOf(create, rename, delete, import, export)) {
                assertEquals(pal.keyLabel, action.currentTextColor)
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, action.layoutParams.width)
                assertTrue(action.hasOnClickListeners())
                assertTrue(view.isImmediateActionForTest(action))
                assertTrue(action.background === view.immediateActionDrawableForTest(action))
                assertEquals(Color.TRANSPARENT, (action.background as ImeKeySurface).faceColor)
                assertNull(action.foreground)
            }
            assertEquals(dp(48), create.height)
            assertEquals(dp(48), import.height)
            assertEquals(dp(48), export.height)
            assertEquals(firstRow.height, rename.height)
            assertEquals(firstRow.height, delete.height)
            assertEquals(dp(44), handle.width)
            assertEquals(firstRow.height, handle.height)
            assertEquals(categoryBounds.right, renameBounds.left)
            assertEquals(renameBounds.right, deleteBounds.left)
            assertEquals(deleteBounds.right, handleBounds.left)
            val createTextRight = createBounds.left + create.totalPaddingLeft + create.layout.getLineRight(0)
            val handleVisibleRight = handleBounds.exactCenterX() + dp(9) * 0.78f + ctx.resources.displayMetrics.density
            assertEquals(handleVisibleRight, createTextRight, 0.6f)
            val edge = (com.aegis.ime.ime.theme.ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
            assertEquals(edge, boundsInRoot(view, import).left)
            assertEquals(view.width - edge, boundsInRoot(view, export).right)
            assertTrue(boundsInRoot(view, import).right <= boundsInRoot(view, export).left)
        }
        val phraseSort = phraseView().apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            enterSortModeForTest()
        }
        layout(phraseSort, w = 480, h = 400)
        val phraseRow = checkNotNull(phraseSort.listRowViewForTest(0)) as ViewGroup
        val phraseLabelBounds = boundsInRoot(phraseSort, phraseRow.getChildAt(0))
        val phraseHandleBounds = boundsInRoot(phraseSort, phraseRow.getChildAt(1))
        assertEquals(View.LAYOUT_DIRECTION_RTL, phraseRow.layoutDirection)
        assertEquals(phraseHandleBounds.right, phraseLabelBounds.left)
    }

    @Test fun batch_rows_fill_the_same_edges_as_the_list_cards() {
        val list = phraseView()
        layout(list, w = 480, h = 400)
        val card = boundsInRoot(list, checkNotNull(list.listRowViewForTest(0)))
        val select = phraseView().apply { enterSelectForTest() }
        layout(select, w = 480, h = 400)
        val row = checkNotNull(select.listRowViewForTest(0))
        val bounds = boundsInRoot(select, row)
        val face = (row.background as ImeKeySurface).faceBoundsForTest(row.width, row.height)
        assertEquals((com.aegis.ime.ime.theme.ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt(), card.left)
        assertEquals(card.left.toFloat(), bounds.left + face.left, 0.5f)
        assertEquals(card.right.toFloat(), bounds.left + face.right, 0.5f)
        assertEquals("a row fills its height like a card", RectF(0f, 0f, row.width.toFloat(), row.height.toFloat()), face)
        assertEquals("a row stands where a card does", card, bounds)
        val nextCard = boundsInRoot(list, checkNotNull(list.listRowViewForTest(1)))
        val nextRow = boundsInRoot(select, checkNotNull(select.listRowViewForTest(1)))
        assertEquals("rows keep the cards' spacing", nextCard.top - card.bottom, nextRow.top - bounds.bottom)
        assertEquals(nextCard, nextRow)
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
        val v = phraseView().apply { onDeletePhrasesFrom = { c, l -> del = c to l; true }; enterSelectForTest(listOf("你好")) }
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


    @Test fun categorybar_manage_page_new_category_still_triggers_onAddCategory() {
        var adds = 0
        val v = phraseView().apply { onAddCategory = { adds++ } }
        clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_add_phrase)); assertEquals("top-bar ＋ no longer creates a category", 0, adds)
        assertTrue("categoryBar 管理", clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_manage_categories)))
        assertTrue("管理 opens the category page", v.isCategorySortModeForTest())
        assertEquals("管理 opens no menu", View.GONE, overlayOf(v).visibility)
        assertTrue("the page has 新建分类", click(v, ctx.getString(com.aegis.ime.R.string.clip_add_category))); assertEquals(1, adds)
    }

    @Test fun the_empty_phrase_hint_points_to_the_manage_action_in_both_languages() {
        val zh = ctx.createConfigurationContext(
            android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(java.util.Locale.SIMPLIFIED_CHINESE) },
        )
        for (local in listOf(ctx, zh)) {
            val hint = local.getString(com.aegis.ime.R.string.clip_phrases_empty_hint)
            assertTrue("$hint names the manage action", local.getString(com.aegis.ime.R.string.clip_manage) in hint)
            assertFalse("$hint no longer points at a pencil", "✎" in hint)
        }
        assertEquals("点 ＋ 添加常用语，在「管理」里新建分类", zh.getString(com.aegis.ime.R.string.clip_phrases_empty_hint))
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认", "工作") }
            phrasesInProvider = { emptyList() }
            applyPalette(pal); forcePhrasesStateForTest("工作"); refresh()
        }
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_phrases_empty_hint) in labels(v))
    }

    @Test fun category_page_rows_rename_delete_and_stay_on_the_page() {
        val cats = mutableListOf(com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID, "工作", "私人")
        val renamed = ArrayList<String>()
        val deleted = ArrayList<String>()
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { cats.toList() }
            phrasesInProvider = { emptyList() }
            onRenameCategory = { renamed.add(it) }
            onDeleteCategory = { deleted.add(it); cats.remove(it) }
            applyPalette(pal); forcePhrasesStateForTest("工作"); refresh()
            enterCategorySortModeForTest()
        }
        layout(v)
        val shownDefault = ctx.getString(com.aegis.ime.R.string.clip_default_category)
        fun row(label: String): ViewGroup = (0 until v.listRowCountForTest())
            .map { checkNotNull(v.listRowViewForTest(it)) as ViewGroup }
            .single { ((it.getChildAt(0) as HorizontalScrollView).getChildAt(0) as TextView).text.toString() == label }
        assertEquals(listOf(shownDefault, "工作", "私人"), v.listRowTextsForTest())
        assertEquals(
            "the current category stays bold",
            listOf(false, true, false),
            listOf(shownDefault, "工作", "私人").map { ((row(it).getChildAt(0) as HorizontalScrollView).getChildAt(0) as TextView).typeface?.isBold == true },
        )
        assertEquals(
            ctx.getString(com.aegis.ime.R.string.clip_rename_named, shownDefault),
            row(shownDefault).getChildAt(1).contentDescription?.toString(),
        )
        assertTrue(row(shownDefault).getChildAt(1).performClick())
        assertEquals("the default category is renamed by its id", listOf(com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID), renamed)

        assertTrue(row("私人").getChildAt(2).performClick())
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete_category_confirm, "私人") in labels(overlayOf(v)))
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_cancel)))
        assertEquals(View.GONE, overlayOf(v).visibility)
        assertTrue("cancel stays on the page", v.isCategorySortModeForTest())
        assertTrue(deleted.isEmpty())

        assertTrue(row("私人").getChildAt(2).performClick())
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals(listOf("私人"), deleted)
        assertTrue("confirm stays on the page", v.isCategorySortModeForTest())
        layout(v)
        assertEquals("the page lists what is left", listOf(shownDefault, "工作"), v.listRowTextsForTest())
    }

    @Test fun category_page_delete_of_the_last_category_explains_instead_of_confirming() {
        var deleted: String? = null
        val v = singleCatPhraseView().apply {
            onDeleteCategory = { deleted = it }
            enterCategorySortModeForTest()
        }
        layout(v)
        val row = checkNotNull(v.listRowViewForTest(0)) as ViewGroup
        assertTrue(row.getChildAt(2).performClick())
        val shown = labels(overlayOf(v))
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_keep_one_category) in shown)
        assertFalse(ctx.getString(com.aegis.ime.R.string.clip_delete_category_confirm, "默认") in shown)
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_done)))
        assertNull(deleted)
        assertTrue("the notice leaves the page open", v.isCategorySortModeForTest())
    }

    @Test fun category_page_back_returns_to_the_phrase_list() {
        val v = phraseView().apply { enterCategorySortModeForTest() }
        layout(v)
        assertTrue(allViews(v).filterIsInstance<PanelHeaderBackControl>().single().performClick())
        assertFalse(v.isCategorySortModeForTest())
        layout(v)
        assertEquals(listOf("你好", "在吗", "稍等"), v.listRowTextsForTest())
        assertTrue("the category rail is back", allViews(v).any { it is ImePanelCategoryBar })
    }

    @Test fun category_chip_long_press_offers_rename_and_delete_in_one_row() {
        var renamed: String? = null
        var deleted: String? = null
        val v = phraseView().apply { onRenameCategory = { renamed = it }; onDeleteCategory = { deleted = it } }
        layout(v)
        val chip = textViews(v).first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
        assertTrue(chip.performLongClick())
        layout(v)
        val rename = ctx.getString(com.aegis.ime.R.string.clip_rename)
        val delete = ctx.getString(com.aegis.ime.R.string.clip_delete)
        assertEquals("the menu holds just the two actions", listOf(rename, delete), labels(overlayOf(v)))
        val renameAction = textViews(overlayOf(v)).single { it.text?.toString() == rename }
        val deleteAction = textViews(overlayOf(v)).single { it.text?.toString() == delete }
        val renameBox = boundsInRoot(v, renameAction)
        val deleteBox = boundsInRoot(v, deleteAction)
        assertEquals("the actions share one row", renameBox.top, deleteBox.top)
        assertTrue("rename comes first", renameBox.right <= deleteBox.left)
        assertEquals("rename is a 48dp target", dp(48), renameAction.height)
        assertEquals("delete is a 48dp target", dp(48), deleteAction.height)
        assertEquals("one 48dp row inside the card padding", dp(6) + dp(48) + dp(6), (overlayOf(v) as ViewGroup).getChildAt(0).height)
        assertEquals(ctx.getString(com.aegis.ime.R.string.clip_rename_named, "工作"), renameAction.contentDescription?.toString())
        assertEquals(ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作"), deleteAction.contentDescription?.toString())
        assertTrue(v.isImmediateActionForTest(renameAction))
        assertTrue(v.isImmediateActionForTest(deleteAction))
        assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_rename_named, "工作"))); assertEquals("工作", renamed)
        layout(v)
        assertTrue(chip.performLongClick())
        assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作"))); assertNull(deleted)
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete))); assertEquals("工作", deleted)
    }

    @Test fun the_category_menu_stacks_under_its_name_when_one_row_does_not_fit() {
        val rename = ctx.getString(com.aegis.ime.R.string.clip_rename)
        val delete = ctx.getString(com.aegis.ime.R.string.clip_delete)
        val inset = com.aegis.ime.ime.theme.ImeType.popupInsetPx(ctx.resources.displayMetrics)
        fun open(w: Int): ClipboardView = phraseView().also { v ->
            layout(v, w, 320)
            assertTrue(textViews(v).first { it.text?.toString() == "工作" && it.isLongClickable }.performLongClick())
            layout(v, w, 320)
        }
        val wide = open(480)
        val side = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
        val natural = listOf(rename, delete).sumOf { label -> textViews(overlayOf(wide)).single { it.text?.toString() == label }.width } +
            2 * (inset - dp(4)) + 2 * side
        assertEquals("a row that just fits stays one row", listOf(rename, delete), labels(overlayOf(open(natural))))
        val narrow = open(natural - 1)
        assertEquals("the name heads the stacked menu once", listOf("工作", rename, delete), labels(overlayOf(narrow)))
        val title = textViews(overlayOf(narrow)).single { it.text?.toString() == "工作" }
        val actions = listOf(rename, delete).map { label -> textViews(overlayOf(narrow)).single { it.text?.toString() == label } }
        assertFalse("the name is a heading, not an action", title.hasOnClickListeners())
        assertEquals("the name is centered over the actions", Gravity.CENTER_HORIZONTAL, title.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertEquals("the name spans the menu", (title.parent as View).width, title.width)
        assertEquals(
            "the stacked menu keeps the one popup width inside the panel",
            minOf(ImeShapes.popupWidthPx(ctx.resources.displayMetrics), narrow.width - 2 * side),
            (overlayOf(narrow) as ViewGroup).getChildAt(0).width,
        )
        assertTrue("the actions stack", boundsInRoot(narrow, actions[1]).top >= boundsInRoot(narrow, actions[0]).bottom)
        assertTrue("each stacked action keeps a 48dp row", actions.all { it.height == dp(48) })
        assertEquals(
            listOf(ctx.getString(com.aegis.ime.R.string.clip_rename_named, "工作"), ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作")),
            actions.map { it.contentDescription?.toString() },
        )
    }
    @Test fun a_long_name_heading_the_stacked_category_menu_stays_on_one_line() {
        val long = "这是一个很长很长很长很长很长很长很长很长的分类名称用来测试标题"
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认", long) }
            phrasesInProvider = { emptyList() }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
        }
        layout(v, dp(120), 320)
        assertTrue(textViews(v).first { it.text?.toString() == long && it.isLongClickable }.performLongClick())
        layout(v, dp(120), 320)
        val title = textViews(overlayOf(v)).single { it.text?.toString() == long }
        assertFalse("precondition: the menu is stacked under the name", title.hasOnClickListeners())
        assertEquals("the name keeps one line", 1, title.lineCount)
        assertEquals(android.text.TextUtils.TruncateAt.END, title.ellipsize)
        assertTrue("the name is cut short with an ellipsis", title.layout.getEllipsisCount(0) > 0)
        assertTrue("the name stays inside the menu", title.width <= (overlayOf(v) as ViewGroup).getChildAt(0).width)
    }

    @Test fun a_long_category_name_leaves_the_menu_one_row_inside_the_panel() {
        for (long in listOf(
            "这是一个很长很长很长很长很长很长很长很长的分类名称用来测试弹窗宽度",
            "An extremely long category name that keeps going well past the width of the panel",
        )) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认", long) }
                phrasesInProvider = { emptyList() }
                applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
            }
            layout(v)
            assertTrue(textViews(v).first { it.text?.toString() == long && it.hasOnClickListeners() }.performLongClick())
            layout(v)
            val card = (overlayOf(v) as ViewGroup).getChildAt(0)
            val cardBox = boundsInRoot(v, card)
            val side = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
            assertTrue("$long: the menu stays inside the panel", cardBox.left >= side && cardBox.right <= v.width - side)
            assertEquals("$long: one row", dp(6) + dp(48) + dp(6), card.height)
            assertEquals(
                "$long: the actions name the category for accessibility",
                listOf(ctx.getString(com.aegis.ime.R.string.clip_rename_named, long), ctx.getString(com.aegis.ime.R.string.clip_delete_named, long)),
                textViews(overlayOf(v)).filter { it.hasOnClickListeners() }.map { it.contentDescription?.toString() },
            )
        }
    }
    @Test fun the_category_menu_opens_just_above_the_pressed_tab() {
        val names = listOf("默认", "工作", "私人", "这是一个很长很长很长的分类名称")
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { names }
            phrasesInProvider = { c -> if (c == "默认") listOf("你好", "在吗", "稍等") else emptyList() }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
        }
        for ((w, h) in listOf(480 to 320, 480 to 170)) {
            for (name in names) {
                layout(v, w, h)
                val bar = boundsInRoot(v, allViews(v).filterIsInstance<ImePanelCategoryBar>().single())
                val tab = textViews(v).first { it.text?.toString() == name && it.isLongClickable }
                val tabBox = boundsInRoot(v, tab)
                assertTrue(tab.performLongClick())
                layout(v, w, h)
                val card = (overlayOf(v) as ViewGroup).getChildAt(0)
                val cardBox = boundsInRoot(v, card)
                val where = "$name at ${w}x$h"
                assertEquals("$where: sits just above the category bar", bar.top - dp(4), cardBox.bottom)
                val side = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
                assertTrue("$where: stays inside the panel", cardBox.left >= side && cardBox.right <= v.width - side && cardBox.top >= dp(8))
                assertEquals("$where: one row", dp(6) + dp(48) + dp(6), card.height)
                assertEquals(
                    "$where: centred over the pressed tab",
                    (tabBox.left + (tabBox.width() - card.width) / 2).coerceIn(side, v.width - side - card.width),
                    cardBox.left,
                )
                v.hideOverlayForTest()
            }
        }
    }

    @Test fun confirmations_center_the_question_and_pin_actions_to_the_card_edges() {
        val inset = com.aegis.ime.ime.theme.ImeType.popupInsetPx(ctx.resources.displayMetrics)
        val deleteCategory = phraseView().also { v ->
            layout(v)
            assertTrue(textViews(v).first { it.text?.toString() == "工作" && it.isLongClickable }.performLongClick())
            assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_named, "工作")))
        }
        val deletePhrase = phraseView().apply { expandForTest("你好") }.also { v ->
            assertTrue(click(v, ctx.getString(com.aegis.ime.R.string.clip_delete)))
        }
        val clearCategory = phraseView().apply { confirmClearForTest() }
        val clearHistory = clipboardView(listOf("第一条"), listOf("默认")).apply { confirmClearHistoryForTest() }
        for ((name, v) in listOf(
            "delete category" to deleteCategory,
            "delete phrase" to deletePhrase,
            "clear category" to clearCategory,
            "clear history" to clearHistory,
        )) {
            layout(v)
            val card = (overlayOf(v) as ViewGroup).getChildAt(0) as ViewGroup
            val cardBox = boundsInRoot(v, card)
            val column = card.getChildAt(0) as ViewGroup
            val question = column.getChildAt(0) as TextView
            val actions = column.getChildAt(1) as ViewGroup
            val action = actions.getChildAt(0) as TextView
            val cancel = actions.getChildAt(actions.childCount - 1) as TextView
            val actionBox = boundsInRoot(v, action)
            val cancelBox = boundsInRoot(v, cancel)
            assertEquals(ctx.getString(com.aegis.ime.R.string.clip_cancel), cancel.text.toString())
            assertEquals("$name: the question stays centered", Gravity.CENTER_HORIZONTAL, question.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
            assertEquals("$name: the question starts two characters in", inset, question.paddingLeft)
            assertEquals("$name: the card keeps the one popup width", ImeShapes.popupWidthPx(ctx.resources.displayMetrics), card.width)
            assertEquals("$name: the action starts two characters in", cardBox.left + inset, actionBox.left + action.totalPaddingLeft)
            assertEquals("$name: cancel ends two characters in", cardBox.right - inset, cancelBox.right - cancel.totalPaddingRight)
            assertTrue("$name: the actions stay apart", cancelBox.left - actionBox.right >= dp(48))
        }
    }

    @Test fun clipboard_picker_and_import_menus_use_48dp_rows() {
        val clip = clipboardView(listOf("第一条"), listOf("默认", "工作"))
        layout(clip)
        assertTrue(textViews(clip).first { it.text?.toString() == "第一条" && it.isLongClickable }.performLongClick())
        layout(clip)
        for (label in listOf(com.aegis.ime.R.string.clip_delete_item, com.aegis.ime.R.string.clip_add_phrase, com.aegis.ime.R.string.clip_split_title).map(ctx::getString)) {
            assertEquals("clipboard menu $label", dp(48), textViews(overlayOf(clip)).single { it.text?.toString() == label }.height)
        }
        assertTrue(click(overlayOf(clip), ctx.getString(com.aegis.ime.R.string.clip_add_phrase)))
        layout(clip)
        for (label in listOf("默认", "工作", ctx.getString(com.aegis.ime.R.string.clip_new_category))) {
            assertEquals("category picker $label", dp(48), textViews(overlayOf(clip)).single { it.text?.toString() == label }.height)
        }

        val move = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认", "工作", "私人") }
            phrasesInProvider = { emptyList() }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
            showMoveChooserForTest("默认")
        }
        layout(move)
        for (label in listOf("工作", "私人", ctx.getString(com.aegis.ime.R.string.clip_new_category))) {
            assertEquals("move picker $label", dp(48), textViews(overlayOf(move)).single { it.text?.toString() == label }.height)
        }
        for (name in listOf("工作", "私人")) {
            val row = textViews(overlayOf(move)).single { it.text?.toString() == name }.parent as View
            assertEquals("move picker $name row with its delete glyph", dp(48), row.height)
        }

        val import = phraseView().apply { enterCategorySortModeForTest() }
        layout(import)
        assertTrue(click(import, ctx.getString(com.aegis.ime.R.string.clip_import_phrases)))
        layout(import)
        for (label in listOf(com.aegis.ime.R.string.clip_overwrite, com.aegis.ime.R.string.clip_merge_recommended, com.aegis.ime.R.string.clip_back).map(ctx::getString)) {
            assertEquals("import menu $label", dp(48), textViews(overlayOf(import)).single { it.text?.toString() == label }.height)
        }
    }

    @Test fun popups_of_one_kind_keep_one_width_whatever_they_say() {
        val long = "这是一个很长很长很长很长很长很长很长很长的分类名称用来测试弹窗宽度"
        fun withCategories(vararg cats: String) = ClipboardView(ctx).apply {
            categoriesProvider = { cats.toList() }
            phrasesInProvider = { c -> if (c == "默认") listOf("你好") else emptyList() }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
        }
        fun deleteCategory(v: ClipboardView, name: String) = v.also {
            layout(v)
            assertTrue(textViews(v).first { it.text?.toString() == name && it.isLongClickable }.performLongClick())
            assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_named, name)))
        }
        fun pickCategory(cats: List<String>) = clipboardView(listOf("第一条"), cats).also { v ->
            layout(v)
            assertTrue(textViews(v).first { it.text?.toString() == "第一条" && it.isLongClickable }.performLongClick())
            assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_add_phrase)))
        }
        val popups = listOf(
            "delete a short category" to deleteCategory(phraseView(), "工作"),
            "delete a long category" to deleteCategory(withCategories("默认", long), long),
            "delete a phrase" to phraseView().apply { expandForTest("你好") }.also { assertTrue(click(it, ctx.getString(com.aegis.ime.R.string.clip_delete))) },
            "clear a category" to phraseView().apply { confirmClearForTest() },
            "keep one category" to deleteCategory(singleCatPhraseView(), "默认"),
            "pick among short names" to pickCategory(listOf("默认")),
            "pick among long names" to pickCategory(listOf("默认", long)),
            "move among long names" to withCategories("默认", long).apply { showMoveChooserForTest("默认") },
            "import" to phraseView().apply { enterCategorySortModeForTest() }.also {
                layout(it)
                assertTrue(click(it, ctx.getString(com.aegis.ime.R.string.clip_import_phrases)))
            },
        )
        val width = ImeShapes.popupWidthPx(ctx.resources.displayMetrics)
        for ((name, v) in popups) {
            layout(v)
            assertEquals("$name: the one popup width", width, (overlayOf(v) as ViewGroup).getChildAt(0).width)
        }
    }

    @Test fun popups_in_a_panel_narrower_than_the_popup_width_keep_the_edge_inset() {
        val metrics = ctx.resources.displayMetrics
        val edge = (ImeShapes.edgeInsetDp * metrics.density).toInt()
        val narrow = ImeShapes.popupWidthPx(metrics)
        val popups = listOf(
            "clear a category" to phraseView().also { layout(it, w = narrow); it.confirmClearForTest() },
            "move among categories" to ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认", "工作") }
                phrasesInProvider = { emptyList() }
                applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
            }.also { layout(it, w = narrow); it.showMoveChooserForTest("默认") },
            "import" to phraseView().apply { enterCategorySortModeForTest() }.also {
                layout(it, w = narrow)
                assertTrue(click(it, ctx.getString(com.aegis.ime.R.string.clip_import_phrases)))
            },
        )
        for ((name, v) in popups) {
            layout(v, w = narrow)
            val card = (overlayOf(v) as ViewGroup).getChildAt(0)
            assertEquals("$name: the popup narrows to the panel", narrow - 2 * edge, card.width)
            assertEquals("$name: the popup keeps the edge inset on the left", edge, card.left)
            assertEquals("$name: the popup keeps the edge inset on the right", narrow - edge, card.right)
        }
    }

    @Test fun menus_that_fall_back_to_a_column_leave_no_press_feedback_behind() {
        val narrow = dp(120)
        fun menuCard(v: ClipboardView): ViewGroup = ((overlayOf(v) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        val menus = listOf(
            "category menu" to (phraseView() to "工作"),
            "clipboard item menu" to (clipboardView(listOf("第一条"), listOf("默认")) to "第一条"),
        )
        for ((name, pair) in menus) {
            val (v, label) = pair
            layout(v, w = narrow)
            fun open() = assertTrue(textViews(v).first { it.text?.toString() == label && it.isLongClickable }.performLongClick())
            open()
            assertTrue("$name: precondition: the menu falls back to a column", (0 until menuCard(v).childCount).all { menuCard(v).getChildAt(it) is TextView })
            val settled = v.immediateActionFeedbackCountForTest()
            repeat(3) { open() }
            assertEquals("$name: reopening the menu leaves no feedback behind", settled, v.immediateActionFeedbackCountForTest())
        }
    }

    @Test fun a_one_row_menu_with_no_room_above_or_below_keeps_its_whole_row() {
        val edge = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
        fun menuCard(v: ClipboardView): ViewGroup = ((overlayOf(v) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        fun open(v: ClipboardView) = assertTrue(textViews(v).first { it.text?.toString() == "工作" && it.isLongClickable }.performLongClick())
        val roomy = phraseView()
        layout(roomy)
        open(roomy)
        layout(roomy)
        val row = menuCard(roomy).getChildAt(0)
        assertTrue("precondition: the menu is one row", row is android.widget.LinearLayout && (row as android.widget.LinearLayout).orientation == android.widget.LinearLayout.HORIZONTAL)
        val natural = row.width
        val width = natural + 2 * edge + 2
        val cramped = phraseView()
        layout(cramped, w = width, h = dp(10))
        open(cramped)
        layout(cramped, w = width, h = dp(10))
        val card = menuCard(cramped)
        assertTrue("precondition: the menu is still one row", card.getChildAt(0) is android.widget.LinearLayout)
        val box = boundsInRoot(cramped, (overlayOf(cramped) as ViewGroup).getChildAt(0))
        assertTrue("the row keeps its natural width: ${card.getChildAt(0).width} of $natural", card.getChildAt(0).width >= natural)
        assertTrue("the card stays within the edge insets: $box in $width", box.left >= edge && box.right <= width - edge)
    }

    @Test fun category_pickers_keep_long_names_on_one_line() {
        val longNames = listOf(
            "这是一个很长很长很长很长很长很长很长很长的分类名称用来测试弹窗宽度",
            "An extremely long category name that keeps going well past the width of the panel",
        )
        val clip = clipboardView(listOf("第一条"), listOf("默认") + longNames)
        layout(clip)
        assertTrue(textViews(clip).first { it.text?.toString() == "第一条" && it.isLongClickable }.performLongClick())
        assertTrue(click(overlayOf(clip), ctx.getString(com.aegis.ime.R.string.clip_add_phrase)))
        layout(clip)

        val move = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认") + longNames }
            phrasesInProvider = { emptyList() }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
            showMoveChooserForTest("默认")
        }
        layout(move)

        for ((picker, v) in listOf("add to category" to clip, "move to category" to move)) {
            val cardBox = boundsInRoot(v, (overlayOf(v) as ViewGroup).getChildAt(0))
            for (name in longNames) {
                val item = textViews(overlayOf(v)).single { it.text?.toString() == name }
                val itemBox = boundsInRoot(v, item)
                assertEquals("$picker: $name stays on one line", 1, item.lineCount)
                assertTrue("$picker: $name is cut with an ellipsis", item.layout.getEllipsisCount(0) > 0)
                assertEquals("$picker: $name keeps a single row", dp(48), item.height)
                assertTrue("$picker: $name stays inside the card", itemBox.left >= cardBox.left && itemBox.right <= cardBox.right)
            }
        }
        for (trash in allViews(overlayOf(move)).filter { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_delete_category) }) {
            val cardBox = boundsInRoot(move, (overlayOf(move) as ViewGroup).getChildAt(0))
            val trashBox = boundsInRoot(move, trash)
            assertTrue("the delete glyph stays inside the card", trashBox.left >= cardBox.left && trashBox.right <= cardBox.right)
        }
    }

    @Test fun the_clipboard_item_menu_lays_its_actions_in_one_row_and_stacks_when_too_narrow() {
        val inset = com.aegis.ime.ime.theme.ImeType.popupInsetPx(ctx.resources.displayMetrics)
        val actionLabels = listOf(
            com.aegis.ime.R.string.clip_delete_item,
            com.aegis.ime.R.string.clip_add_phrase,
            com.aegis.ime.R.string.clip_split_title,
        ).map(ctx::getString)
        fun open(w: Int): Pair<ClipboardView, List<TextView>> {
            val v = clipboardView(listOf("第一条"), listOf("默认"))
            layout(v, w, 320)
            assertTrue(textViews(v).first { it.text?.toString() == "第一条" && it.isLongClickable }.performLongClick())
            layout(v, w, 320)
            return v to actionLabels.map { label -> textViews(overlayOf(v)).single { it.text?.toString() == label } }
        }

        val (wide, wideActions) = open(480)
        val wideCard = boundsInRoot(wide, (overlayOf(wide) as ViewGroup).getChildAt(0))
        val wideBoxes = wideActions.map { boundsInRoot(wide, it) }
        assertTrue("one row", wideBoxes.all { it.top == wideBoxes[0].top })
        assertTrue("in reading order", wideBoxes.zipWithNext().all { (a, b) -> a.right <= b.left })
        assertEquals("the first action starts two characters in", wideCard.left + inset, wideBoxes.first().left + wideActions.first().totalPaddingLeft)
        assertEquals("the last action ends two characters in", wideCard.right - inset, wideBoxes.last().right - wideActions.last().totalPaddingRight)
        assertTrue("roomy gaps on a wide panel", wideBoxes.zipWithNext().all { (a, b) -> b.left - a.right == dp(16) })
        for (action in wideActions) {
            assertTrue(wide.isImmediateActionForTest(action))
            assertEquals(dp(48), action.height)
        }

        val tight = wideActions.sumOf { it.width } + 2 * (inset - dp(4)) + 2 * (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
        val (squeezed, squeezedActions) = open(tight + 4)
        val squeezedCard = boundsInRoot(squeezed, (overlayOf(squeezed) as ViewGroup).getChildAt(0))
        val squeezedBoxes = squeezedActions.map { boundsInRoot(squeezed, it) }
        assertTrue("a narrow panel keeps one row", squeezedBoxes.all { it.top == squeezedBoxes[0].top })
        assertTrue("by closing the gaps", squeezedBoxes.zipWithNext().all { (a, b) -> b.left - a.right in 0 until dp(16) })
        assertEquals(squeezedCard.left + inset, squeezedBoxes.first().left + squeezedActions.first().totalPaddingLeft)
        assertEquals(squeezedCard.right - inset, squeezedBoxes.last().right - squeezedActions.last().totalPaddingRight)

        val (narrow, narrowActions) = open(tight - 20)
        val narrowBoxes = narrowActions.map { boundsInRoot(narrow, it) }
        assertTrue("too narrow for one row: the actions stack", narrowBoxes.zipWithNext().all { (a, b) -> b.top >= a.bottom })

        assertTrue(wideActions[1].performClick())
        assertTrue(
            "the add action still opens the category chooser",
            ctx.getString(com.aegis.ime.R.string.clip_choose_category) in labels(overlayOf(wide)),
        )
    }

    @Test fun the_clipboard_item_menu_opens_beside_the_pressed_item() {
        val entries = (1..8).map { "第${it}条剪贴板内容" }
        for ((w, h) in listOf(480 to 320, 480 to 200)) {
            val v = clipboardView(entries, listOf("默认"))
            layout(v, w, h)
            val actionLabels = listOf(
                com.aegis.ime.R.string.clip_delete_item,
                com.aegis.ime.R.string.clip_add_phrase,
                com.aegis.ime.R.string.clip_split_title,
            ).map(ctx::getString)
            var above = 0
            var below = 0
            for (i in 0 until v.listRowCountForTest()) {
                layout(v, w, h)
                val viewport = boundsInRoot(v, v.listViewportForTest())
                val row = requireNotNull(v.listRowViewForTest(i))
                val rowBox = boundsInRoot(v, row)
                if (rowBox.top < viewport.top || rowBox.bottom > viewport.bottom) continue
                assertTrue(textViews(row).first { it.isLongClickable }.performLongClick())
                layout(v, w, h)
                val card = (overlayOf(v) as ViewGroup).getChildAt(0) as ViewGroup
                val cardBox = boundsInRoot(v, card)
                val full = card.getChildAt(0).height
                val roomAbove = rowBox.top - dp(4) - dp(8)
                val roomBelow = v.height - dp(8) - rowBox.bottom - dp(4)
                val where = "entry $i at ${w}x$h"
                val actions = actionLabels.map { label -> textViews(card).single { it.text?.toString() == label } }
                assertTrue("$where: the actions share one row", actions.all { boundsInRoot(v, it).top == boundsInRoot(v, actions[0]).top })
                assertEquals("$where: one 48dp row inside the card padding", dp(6) + dp(48) + dp(6), full)
                val side = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
                assertTrue("$where: stays inside the panel", cardBox.left >= side && cardBox.right <= v.width - side)
                assertEquals("$where: stays centred", (v.width - card.width) / 2, cardBox.left)
                assertFalse("$where: leaves the pressed entry uncovered", Rect.intersects(cardBox, rowBox))
                when {
                    full <= roomAbove -> {
                        assertEquals("$where: sits just above the entry", rowBox.top - dp(4), cardBox.bottom); above++
                        assertEquals("$where: shows every action", full, card.height)
                    }
                    full <= roomBelow -> {
                        assertEquals("$where: sits just below the entry", rowBox.bottom + dp(4), cardBox.top); below++
                        assertEquals("$where: shows every action", full, card.height)
                    }
                    roomAbove >= roomBelow -> {
                        assertEquals("$where: takes the roomier side above", rowBox.top - dp(4), cardBox.bottom); above++
                        assertEquals("$where: scrolls within the room above", roomAbove, card.height)
                    }
                    else -> {
                        assertEquals("$where: takes the roomier side below", rowBox.bottom + dp(4), cardBox.top); below++
                        assertEquals("$where: scrolls within the room below", roomBelow, card.height)
                    }
                }
                if (cardBox.top < dp(8) || cardBox.bottom > v.height - dp(8)) fail("$where: the menu leaves the panel: $cardBox")
                v.hideOverlayForTest()
            }
            assertTrue("${w}x$h: a lower entry opens the menu above it", above > 0)
            assertTrue("${w}x$h: the top entry opens the menu below it", below > 0)
        }
    }

    @Test fun deleting_the_only_category_explains_instead_of_confirming() {
        var deleted: String? = null
        val v = singleCatPhraseView().apply { onDeleteCategory = { deleted = it } }
        val chip = textViews(v).first { it.text?.toString() == "默认" && it.hasOnClickListeners() }
        assertTrue(chip.performLongClick())
        assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_named, "默认")))
        val shown = labels(overlayOf(v))
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_keep_one_category) in shown)
        assertFalse(
            "the last category never reaches the delete confirmation",
            ctx.getString(com.aegis.ime.R.string.clip_delete_category_confirm, "默认") in shown,
        )
        assertFalse("the notice offers no delete action", ctx.getString(com.aegis.ime.R.string.clip_delete) in shown)
        assertNull(deleted)
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_done)))
        assertEquals(View.GONE, overlayOf(v).visibility)
        assertNull(deleted)
    }

    @Test fun the_default_category_is_renamed_and_deleted_like_any_other_while_another_remains() {
        val d = com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID
        val shown = ctx.getString(com.aegis.ime.R.string.clip_default_category)
        var renamed: String? = null
        val deleted = ArrayList<String>()
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf(d, "工作") }
            phrasesInProvider = { emptyList() }
            onRenameCategory = { renamed = it }
            onDeleteCategory = { deleted.add(it) }
            applyPalette(pal); forcePhrasesStateForTest(d); refresh()
        }
        val chip = textViews(v).first { it.text?.toString() == shown && it.hasOnClickListeners() }
        assertTrue(chip.performLongClick())
        assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_rename_named, shown)))
        assertEquals("the rename is handed the stable id, not the label", d, renamed)
        assertTrue(chip.performLongClick())
        assertTrue(clickDesc(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete_named, shown)))
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_delete_category_confirm, shown) in labels(overlayOf(v)))
        assertTrue(deleted.isEmpty())
        assertTrue(click(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_delete)))
        assertEquals(listOf(d), deleted)
    }

    @Test fun categorybar_manage_is_a_plain_text_action_beside_the_rail() {
        val v = phraseView()
        layout(v)
        val edit = allViews(v).single {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_manage_categories)
        } as TextView
        assertEquals(ctx.getString(com.aegis.ime.R.string.clip_manage), edit.text.toString())
        val surface = edit.background as ImeKeySurface
        assertTrue(v.isImmediateActionForTest(edit))
        assertEquals(Color.TRANSPARENT, surface.faceColor)
        assertEquals(ImeShapes.toolbarFeedbackRadiusDp * ctx.resources.displayMetrics.density, surface.cornerRadiusPx, 0f)
        assertEquals(pal.keyLabel, edit.currentTextColor)
        assertNull(edit.foreground)
    }

    @Test fun the_phrase_category_bar_keeps_the_shared_edge_inset() {
        val v = phraseView()
        layout(v)
        val inset = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
        val bar = allViews(v).filterIsInstance<ImePanelCategoryBar>().single()
        val rail = allViews(bar).filterIsInstance<ImePanelCategoryRail>().single()
        val manage = allViews(v).single {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_manage_categories)
        }
        assertEquals("the first tab and its underline start at the edge inset", inset, boundsInRoot(v, rail.getChildAt(0)).left)
        assertEquals("the manage action ends at the edge inset", v.width - inset, boundsInRoot(v, manage).right)
        assertEquals("the list cards share the same edges", inset, boundsInRoot(v, checkNotNull(v.listRowViewForTest(0))).left)
    }

    @Test fun the_list_scrollbar_sits_inside_the_shared_edge_inset() {
        val v = phraseView()
        layout(v)
        val inset = (ImeShapes.edgeInsetDp * ctx.resources.displayMetrics.density).toInt()
        val viewport = v.listViewportForTest()
        assertEquals(inset, viewport.paddingLeft)
        assertEquals("the scrollbar is drawn inside the edge inset", inset, viewport.paddingRight)
        assertEquals(View.SCROLLBARS_INSIDE_OVERLAY, viewport.scrollBarStyle)
        assertEquals("the cards keep their edges", inset, boundsInRoot(v, checkNotNull(v.listRowViewForTest(0))).left)
        assertEquals(v.width - inset, boundsInRoot(v, checkNotNull(v.listRowViewForTest(0))).right)
    }

    @Test fun the_phrase_category_bar_is_40dp_tall() {
        val v = phraseView()
        layout(v)
        val bar = allViews(v).filterIsInstance<ImePanelCategoryBar>().single()
        assertEquals(dp(40), bar.height)
        val tab = textViews(v).first { it.text?.toString() == "工作" && it.isLongClickable }
        assertEquals("the tabs fill the bar", dp(40), tab.height)
        val manage = allViews(v).single {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_manage_categories)
        }
        assertEquals("the manage action fills the bar", dp(40), manage.height)
    }

    @Test fun phrase_category_bar_sets_the_symbol_rail_tabs_on_the_panel_background() {
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认", "工作", "私人") }
                phrasesInProvider = { emptyList() }
                applyPalette(palette); forcePhrasesStateForTest("工作"); refresh()
            }
            layout(v)
            val symbols = SymbolsView(ctx).apply { applyPalette(palette); refresh() }
            val bar = allViews(v).filterIsInstance<ImePanelCategoryBar>().single()
            val rail = allViews(bar).filterIsInstance<ImePanelCategoryRail>().single()
            val symbolRail = symbols.categoryRailForTest()
            assertNull("the phrase tabs lie on the panel itself, with no function surface band", bar.background)
            assertEquals("no grid rule is drawn above the phrase tabs", 0, Color.alpha(bar.ruleColor))
            assertEquals(palette.accentBottom, rail.underlineColor)
            assertEquals(symbolRail.underlineColor, rail.underlineColor)
            assertEquals("the underline follows the selected category", 1, rail.selectedIndex)
            val tabs = (0 until rail.childCount).map { rail.getChildAt(it) as TextView }
            assertEquals(listOf("默认", "工作", "私人"), tabs.map { it.text.toString() })
            assertEquals(palette.keyLabel, tabs[1].currentTextColor)
            assertEquals(palette.keyLabelSecondary, tabs[0].currentTextColor)
            assertEquals(palette.keyLabelSecondary, tabs[2].currentTextColor)
            for ((index, tab) in tabs.withIndex()) {
                val reference = symbols.railTabForTest(if (index == 1) 0 else 1)
                assertEquals(index == 1, tab.isSelected)
                assertEquals(reference.isSelected, tab.isSelected)
                assertEquals(reference.currentTextColor, tab.currentTextColor)
                assertEquals(reference.textSize, tab.textSize, 0f)
                assertEquals(reference.typeface?.style, tab.typeface?.style)
                assertEquals(
                    listOf(reference.paddingLeft, reference.paddingTop, reference.paddingRight, reference.paddingBottom),
                    listOf(tab.paddingLeft, tab.paddingTop, tab.paddingRight, tab.paddingBottom),
                )
                assertEquals(reference.minimumWidth, tab.minimumWidth)
                assertEquals(reference.gravity, tab.gravity)
                assertEquals("a tab fills the rail height", bar.height, tab.height)
                val surface = tab.background as ImeKeySurface
                val referenceSurface = reference.background as ImeKeySurface
                assertEquals(referenceSurface.faceColor, surface.faceColor)
                assertNull(tab.foreground)
            }
            val underline = requireNotNull(rail.underlineBoundsForTest())
            assertEquals(tabs[1].left.toFloat(), underline.left, 0f)
            assertEquals(tabs[1].right.toFloat(), underline.right, 0f)
            assertEquals(rail.height.toFloat(), underline.bottom, 0f)
            assertTrue(
                "no capsule remains behind the categories",
                allViews(bar.parent as View).none { it.background is GradientDrawable },
            )
            val frame = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(frame))
            val barBox = boundsInRoot(v, bar)
            val railBox = boundsInRoot(v, rail)
            val manageBox = boundsInRoot(v, (bar.parent as ViewGroup).getChildAt(1))
            for (x in listOf(barBox.left + 1, barBox.centerX(), barBox.right - 2)) {
                assertEquals("the panel background runs across the bar's top edge at x=$x", palette.keyboardBg, frame.getPixel(x, barBox.top))
            }
            assertTrue(railBox.right < manageBox.left)
            assertEquals(
                "the panel background shows between the last tab and the edit action",
                palette.keyboardBg,
                frame.getPixel((railBox.right + manageBox.left) / 2, barBox.centerY()),
            )
        }
    }

    @Test fun phrase_category_tabs_press_like_the_manage_action() {
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认", "工作", "私人") }
                phrasesInProvider = { emptyList() }
                applyPalette(palette); forcePhrasesStateForTest("工作"); refresh()
            }
            layout(v)
            val rail = allViews(v).filterIsInstance<ImePanelCategoryRail>().single()
            val manage = allViews(v).single {
                it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_manage_categories)
            }
            val manageSurface = manage.background as ImeKeySurface
            val faceInset = manageSurface.faceBoundsForTest(manage.width, manage.height).left
            assertTrue("the manage action insets its press", faceInset > 0f)
            assertTrue("the manage action rounds its press", manageSurface.cornerRadiusPx > 0f)
            for (index in 0 until rail.childCount) {
                val tab = rail.getChildAt(index)
                val surface = tab.background as ImeKeySurface
                assertEquals(manageSurface.faceColor, surface.faceColor)
                assertEquals("tab $index", manageSurface.cornerRadiusPx, surface.cornerRadiusPx, 0f)
                assertEquals(
                    "tab $index",
                    RectF(faceInset, faceInset, tab.width - faceInset, tab.height - faceInset),
                    surface.faceBoundsForTest(tab.width, tab.height),
                )
                tab.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, tab.width / 2f, tab.height / 2f, 0))
                val frame = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(frame))
                val box = boundsInRoot(v, tab)
                val face = Rect(box).apply { inset(faceInset.roundToInt(), faceInset.roundToInt()) }
                assertEquals("tab $index: the press stays off the tab corner", palette.keyboardBg, frame.getPixel(box.left, box.top))
                assertEquals("tab $index: the press rounds its corner", palette.keyboardBg, frame.getPixel(face.left, face.top))
                assertTrue("tab $index: the press lights its face", frame.getPixel(face.centerX(), face.top + 1) != palette.keyboardBg)
                tab.dispatchTouchEvent(MotionEvent.obtain(0, 16, MotionEvent.ACTION_CANCEL, tab.width / 2f, tab.height / 2f, 0))
            }
        }
    }

    @Test fun the_selected_tab_press_keeps_its_rounded_bottom_over_the_underline() {
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val v = ClipboardView(ctx).apply {
                categoriesProvider = { listOf("默认", "工作", "私人") }
                phrasesInProvider = { emptyList() }
                applyPalette(palette); forcePhrasesStateForTest("工作"); refresh()
            }
            layout(v)
            val rail = allViews(v).filterIsInstance<ImePanelCategoryRail>().single()
            val tab = rail.getChildAt(rail.selectedIndex)
            val surface = tab.background as ImeKeySurface
            val faceBounds = surface.faceBoundsForTest(tab.width, tab.height)
            val box = boundsInRoot(v, tab)
            val underline = requireNotNull(rail.underlineBoundsForTest())
            val face = Rect(box.left + faceBounds.left.roundToInt(), box.top + faceBounds.top.roundToInt(), box.left + faceBounds.right.roundToInt(), box.top + faceBounds.bottom.roundToInt())
            val row = face.bottom - 1
            assertTrue("precondition: the face's bottom row lies on the underline", row - box.top >= underline.top)
            val resting = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
            tab.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, tab.width / 2f, tab.height / 2f, 0))
            val pressed = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
            assertTrue("the press shows over the underline", pressed.getPixel(face.centerX(), row) != resting.getPixel(face.centerX(), row))
            assertEquals("the press keeps its rounded corner on the underline", resting.getPixel(face.left, row), pressed.getPixel(face.left, row))
            tab.dispatchTouchEvent(MotionEvent.obtain(0, 16, MotionEvent.ACTION_CANCEL, tab.width / 2f, tab.height / 2f, 0))
        }
    }

    @Test fun the_category_page_scrolls_a_long_name_instead_of_cutting_it() {
        val long = "很长很长的分类名称".repeat(6)
        var cats = listOf("默认", long)
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { cats }
            phrasesInProvider = { emptyList() }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
            enterCategorySortModeForTest()
        }
        layout(v)
        val name = textViews(v).single { it.text?.toString() == long }
        val scroller = name.parent as HorizontalScrollView
        assertFalse("the name scroller hides its scrollbar like the copy bar", scroller.isHorizontalScrollBarEnabled)
        assertEquals("the name stays on one line", 1, name.maxLines)
        assertNull("the rest of the name is reached by scrolling, not cut off with an ellipsis", name.ellipsize)
        assertTrue("the whole name is laid out past the visible part", name.width > scroller.width)
        assertTrue(scroller.canScrollHorizontally(1))
        val row = scroller.parent as ViewGroup
        assertEquals("the rename action follows the name area", scroller.right, row.getChildAt(1).left)
        scroller.scrollTo(name.width, 0)
        assertEquals("the end of the name scrolls into view", name.width - scroller.width, scroller.scrollX)
        val short = textViews(v).single { it.text?.toString() == "默认" }
        assertFalse("a name that fits does not scroll", (short.parent as HorizontalScrollView).canScrollHorizontally(1))
        val other = "另一个很长的分类名称".repeat(6)
        cats = listOf("默认", other)
        v.refresh()
        layout(v)
        val rebound = textViews(v).single { it.text?.toString() == other }
        assertTrue("precondition: the new name also scrolls", (rebound.parent as HorizontalScrollView).canScrollHorizontally(1))
        assertEquals("the scrolled row starts its new name at the beginning", 0, (rebound.parent as HorizontalScrollView).scrollX)
    }

    @Test fun dragging_a_long_category_name_sideways_scrolls_it_and_dragging_up_scrolls_the_page() {
        val long = "很长很长的分类名称".repeat(6)
        var actions = 0
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认", "分类1", "分类2", long) + (3..12).map { "分类$it" } }
            phrasesInProvider = { emptyList() }
            onRenameCategory = { actions++ }
            onDeleteCategory = { actions++ }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
            enterCategorySortModeForTest()
        }
        layout(v)
        val name = textViews(v).single { it.text?.toString() == long }
        val scroller = name.parent as HorizontalScrollView
        var page: View = scroller
        while (page !is android.widget.ScrollView) page = page.parent as View
        fun drag(dx: Float, dy: Float) {
            val box = boundsInRoot(v, scroller)
            val x = box.exactCenterX()
            val y = box.exactCenterY()
            v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
            for (step in 1..10) v.dispatchTouchEvent(MotionEvent.obtain(0, step * 16L, MotionEvent.ACTION_MOVE, x + dx * step / 10f, y + dy * step / 10f, 0))
            v.dispatchTouchEvent(MotionEvent.obtain(0, 176, MotionEvent.ACTION_UP, x + dx, y + dy, 0))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
            v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
        }
        assertTrue("precondition: the long name is in view", boundsInRoot(v, scroller).exactCenterY() < page.bottom)
        drag(0f, -dp(60).toFloat())
        assertTrue("an upward drag from the name scrolls the page", page.scrollY > 0)
        assertTrue("precondition: the long name is still in view", boundsInRoot(v, scroller).exactCenterY() > page.top)
        assertEquals("the upward drag leaves the name where it was", 0, scroller.scrollX)
        val pageAfterUpward = page.scrollY
        drag(-dp(120).toFloat(), 0f)
        assertTrue("a sideways drag on the name scrolls it", scroller.scrollX > 0)
        assertEquals("the sideways drag leaves the page where it was", pageAfterUpward, page.scrollY)
        assertEquals("neither drag renames or deletes", 0, actions)
    }

    @Test fun the_category_page_shows_a_stored_multiline_name_on_one_line() {
        val tail = "很长很长的客户分类".repeat(6)
        val stored = "工作\n$tail"
        val v = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("默认", stored) }
            phrasesInProvider = { emptyList() }
            applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
            enterCategorySortModeForTest()
        }
        layout(v)
        val name = textViews(v).single { it.text?.toString()?.startsWith("工作") == true }
        assertEquals("the line break shows as a space", "工作 $tail", name.text.toString())
        assertEquals("the whole name sits on one line", 1, name.lineCount)
        val scroller = name.parent as HorizontalScrollView
        assertTrue("the whole name is laid out past the visible part", name.width > scroller.width)
        scroller.scrollTo(name.width, 0)
        assertEquals("the end of the name scrolls into view", name.width - scroller.width, scroller.scrollX)
    }

    @Test fun categorybar_manage_long_press_opens_the_category_page() {
        val v = phraseView()
        val manage = allViews(v).single {
            it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_manage_categories)
        }

        assertTrue(manage.performLongClick())
        assertTrue(v.isCategorySortModeForTest())
        assertEquals("the page opens without a menu", View.GONE, overlayOf(v).visibility)
        assertTrue(
            textViews(v).any {
                it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_add_category) && it.hasOnClickListeners()
            },
        )
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
        val initialScroll = categoryScroll(v)
        val railViewport = initialScroll.width - initialScroll.paddingLeft - initialScroll.paddingRight
        initialScroll.scrollTo((initialScroll.getChildAt(0).width - railViewport).coerceAtLeast(0), 0)
        val savedScroll = initialScroll.scrollX
        assertTrue(savedScroll > 0)

        val selectedName = categories.last()
        assertTrue(textViews(v).first { it.text?.toString() == selectedName }.performClick())
        layout(v, w = 320, h = 400)

        val retainedScroll = categoryScroll(v)
        val selected = textViews(v).first { it.text?.toString() == selectedName }
        val manage = allViews(v).first { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_manage_categories) }
        assertTrue(retainedScroll !== initialScroll)
        assertEquals(selectedName, v.phraseCatForTest())
        assertTrue(retainedScroll.scrollX > 0)
        assertTrue(selected.left - retainedScroll.scrollX < retainedScroll.width - retainedScroll.paddingRight)
        assertTrue(selected.right - retainedScroll.scrollX > 0)
        assertTrue(retainedScroll.parent === manage.parent)
        assertFalse(allViews(retainedScroll).contains(manage))
        assertTrue("the categories scroll in the symbol panel's rail", retainedScroll is ImePanelCategoryBar)
        assertTrue("the rail marks the selection instead of a pill", selected.isSelected && selected.background is ImeKeySurface)

        v.refresh()
        layout(v, w = 320, h = 400)
        assertEquals(selectedName, v.phraseCatForTest())
        assertTrue(categoryScroll(v).scrollX > 0)
    }


    private fun manyCategoryView(cats: MutableList<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { cats.toList() }
        phrasesInProvider = { emptyList() }
        applyPalette(pal); forcePhrasesStateForTest(cats.first()); refresh()
    }

    private fun assertTabInView(scroll: HorizontalScrollView, tab: View) {
        val strip = scroll.getChildAt(0)
        val start = strip.left + tab.left
        val end = strip.left + tab.right
        val shownStart = scroll.scrollX + scroll.paddingLeft
        val shownEnd = scroll.scrollX + scroll.width - scroll.paddingRight
        assertTrue(
            "tab [$start, $end] must lie inside the rail viewport [$shownStart, $shownEnd]",
            start >= shownStart && end <= shownEnd,
        )
    }

    @Test fun returning_with_a_new_category_scrolls_the_rail_to_it() {
        val cats = (0..10).map { "分类" + it.toString().padStart(2, '0') }.toMutableList()
        val v = manyCategoryView(cats)
        layout(v, w = 320, h = 400)
        assertEquals(0, categoryScroll(v).scrollX)
        v.resetToDefault()
        cats.add("新分类")
        v.showPhraseTab("新分类")
        layout(v, w = 320, h = 400)
        val scroll = categoryScroll(v)
        val tab = textViews(scroll).single { it.text?.toString() == "新分类" }
        assertEquals("新分类", v.phraseCatForTest())
        assertTrue(tab.isSelected)
        assertTrue("the rail had to scroll to reach the new category", scroll.scrollX > 0)
        assertTabInView(scroll, tab)

        scroll.scrollTo(0, 0)
        v.applyPalette(ImePalette.STATIC_DARK)
        layout(v, w = 320, h = 400)
        assertEquals("the reveal happens once; a later rebuild keeps where the user left the rail", 0, categoryScroll(v).scrollX)
    }

    @Test fun reopening_the_phrase_tab_after_an_inline_edit_scrolls_the_rail_to_its_category() {
        val cats = (0..10).map { "分类" + it.toString().padStart(2, '0') }.toMutableList()
        val v = manyCategoryView(cats)
        layout(v, w = 320, h = 400)
        cats.add("新分类")
        v.reopenAfterInline("新分类")
        layout(v, w = 320, h = 400)
        val scroll = categoryScroll(v)
        assertEquals("新分类", v.phraseCatForTest())
        assertTrue(scroll.scrollX > 0)
        assertTabInView(scroll, textViews(scroll).single { it.text?.toString() == "新分类" })
    }

    @Test fun leaving_the_category_page_after_making_a_category_shows_it_on_the_rail() {
        val cats = (0..10).map { "分类" + it.toString().padStart(2, '0') }.toMutableList()
        val v = manyCategoryView(cats)
        layout(v, w = 320, h = 400)
        v.resetToDefault()
        cats.add("新分类")
        v.showCategoryAdmin("新分类")
        layout(v, w = 320, h = 400)
        assertTrue(v.isCategorySortModeForTest())
        assertTrue(allViews(v).filterIsInstance<PanelHeaderBackControl>().single().performClick())
        layout(v, w = 320, h = 400)
        val scroll = categoryScroll(v)
        assertTrue("the rail had to scroll to reach the new category", scroll.scrollX > 0)
        assertTabInView(scroll, textViews(scroll).single { it.text?.toString() == "新分类" })
    }

    @Test fun top_bar_icons_are_uniform_size() {
        val v = phraseView()
        val wanted = setOf(ctx.getString(com.aegis.ime.R.string.clip_back), ctx.getString(com.aegis.ime.R.string.clip_add_phrase), ctx.getString(com.aegis.ime.R.string.clip_edit_phrases), ctx.getString(com.aegis.ime.R.string.clip_clear_category))
        val icons = allViews(v).filter { it.contentDescription?.toString() in wanted && it.hasOnClickListeners() }
        assertEquals("all 4 phrase-tab top icons present", 4, icons.size)
        assertTrue("返回 is no longer a '‹' text glyph", textViews(v).none { it.text?.toString() == "‹" })
        val back = icons.single { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_back) }
        val actions = icons.filterNot { it === back }
        assertTrue("返回 uses the shared panel back control", back is TextView)
        assertEquals(
            "返回 carries its own label",
            ctx.getString(com.aegis.ime.R.string.clip_back),
            (back as TextView).text.toString(),
        )
        val backTarget = (48 * ctx.resources.displayMetrics.density).toInt()
        assertEquals("返回 keeps a 48dp hit target", backTarget, back.layoutParams.height)
        assertEquals(
            "返回 is sized by its label",
            ViewGroup.LayoutParams.WRAP_CONTENT,
            back.layoutParams.width,
        )
        layout(v)
        assertTrue("返回 keeps a 48dp hit target however its label is sized", back.width >= backTarget)
        assertEquals("all top action icons share one width (item7)", 1, actions.map { it.layoutParams.width }.toSet().size)
        assertEquals("all top action icons share one height (item7)", 1, actions.map { it.layoutParams.height }.toSet().size)
        val surfaced = icons.filter { it.contentDescription?.toString() in setOf(ctx.getString(com.aegis.ime.R.string.clip_add_phrase), ctx.getString(com.aegis.ime.R.string.clip_edit_phrases), ctx.getString(com.aegis.ime.R.string.clip_clear_category)) }
        assertTrue(surfaced.all(v::isImmediateActionForTest))
        val iconSize = (48 * ctx.resources.displayMetrics.density).toInt()
        assertTrue(surfaced.all { it.layoutParams.width == iconSize && it.layoutParams.height == iconSize })
        assertTrue(surfaced.all { it.background === v.immediateActionDrawableForTest(it) && it.foreground == null })
        assertTrue(icons.filterNot { it in surfaced }.all { it.background == null })
    }


    private fun clipboardView(history: List<String>, cats: List<String>): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history.asClipEntries() }
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
