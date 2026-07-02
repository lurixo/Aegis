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

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.user.ClipSplitter
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
class ClipboardViewInteractionTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT

    private fun layout(v: View, w: Int = 480, h: Int = 700) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun send(v: View, action: Int, x: Float, y: Float, t: Long) =
        v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))

    private fun leftSwipe(target: View, dx: Float) {
        send(target, MotionEvent.ACTION_DOWN, 320f, 12f, 0)
        send(target, MotionEvent.ACTION_MOVE, 320f - dx, 12f, 16)
        send(target, MotionEvent.ACTION_UP, 320f - dx, 12f, 32)
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun textViews(root: View): List<TextView> = allViews(root).filterIsInstance<TextView>()
    private fun bodyOf(root: View, text: String): TextView =
        textViews(root).first { it.text?.toString() == text }
    private fun mainOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(0)
    private fun overlayOf(v: ClipboardView): View = (v as ViewGroup).getChildAt(1)
    private fun labels(root: View): List<String> = textViews(root).mapNotNull { it.text?.toString() }
    private fun clickText(root: View, label: String): Boolean {
        val tv = textViews(root).firstOrNull { it.text?.toString() == label && it.hasOnClickListeners() } ?: return false
        tv.performClick(); return true
    }
    private fun clickDesc(root: View, desc: String): Boolean {
        val v = allViews(root).firstOrNull { it.contentDescription?.toString() == desc && it.hasOnClickListeners() } ?: return false
        v.performClick(); return true
    }

    private fun clipView(history: List<String>): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history }; applyPalette(pal); refresh()
    }
    private fun phraseView(phrases: List<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认", "工作") }
        phrasesInProvider = { c -> if (c == "默认") phrases else emptyList() }
        applyPalette(pal); forcePhrasesStateForTest("默认"); refresh()
    }


    @Test fun left_swipe_on_a_clipboard_card_reveals_actions_and_never_commits() {
        var picked: String? = null
        val v = clipView(listOf("第一条", "第二条")).apply { onPick = { picked = it } }
        layout(v)
        leftSwipe(bodyOf(v, "第一条"), dx = 200f)
        assertEquals("the card's action row is revealed", "第一条", v.swipeRevealedForTest())
        assertNull("a left swipe must NOT 上屏", picked)
    }

    @Test fun a_SHORT_left_swipe_on_a_clipboard_card_still_reveals_not_commits() {
        var picked: String? = null
        val v = clipView(listOf("第一条", "第二条")).apply { onPick = { picked = it } }
        layout(v)
        leftSwipe(bodyOf(v, "第一条"), dx = 22f)
        assertEquals("even a short left swipe reveals", "第一条", v.swipeRevealedForTest())
        assertNull("a short left swipe must NOT 上屏", picked)
    }

    @Test fun left_swipe_on_a_phrase_card_reveals_actions_and_never_commits() {
        var picked: String? = null
        val v = phraseView(listOf("你好", "在吗")).apply { onPick = { picked = it } }
        layout(v)
        leftSwipe(bodyOf(v, "你好"), dx = 200f)
        assertEquals("你好", v.swipeRevealedForTest())
        assertNull(picked)
    }

    @Test fun a_SHORT_left_swipe_on_a_phrase_card_still_reveals_not_commits() {
        var picked: String? = null
        val v = phraseView(listOf("你好", "在吗")).apply { onPick = { picked = it } }
        layout(v)
        leftSwipe(bodyOf(v, "你好"), dx = 22f)
        assertEquals("你好", v.swipeRevealedForTest())
        assertNull(picked)
    }

    @Test fun a_plain_tap_does_not_reveal_and_still_commits() {
        var picked: String? = null
        val v = clipView(listOf("第一条")).apply { onPick = { picked = it } }
        layout(v)
        val body = bodyOf(v, "第一条")
        send(body, MotionEvent.ACTION_DOWN, 40f, 12f, 0)
        send(body, MotionEvent.ACTION_UP, 40f, 12f, 8)
        assertNull("a tap reveals nothing (the swipe handler did not consume it)", v.swipeRevealedForTest())
        assertTrue("the tap reaches the card's onClick", body.performClick())
        assertEquals("…which 上屏s the clip", "第一条", picked)
    }

    @Test fun a_clearly_vertical_drag_scrolls_and_neither_reveals_nor_commits() {
        var picked: String? = null
        val v = clipView(listOf("第一条", "第二条")).apply { onPick = { picked = it } }
        layout(v)
        val body = bodyOf(v, "第一条")
        send(body, MotionEvent.ACTION_DOWN, 40f, 12f, 0)
        send(body, MotionEvent.ACTION_MOVE, 40f, 212f, 16)
        send(body, MotionEvent.ACTION_UP, 40f, 212f, 32)
        assertNull("a vertical drag does not reveal", v.swipeRevealedForTest())
        assertNull("a vertical drag does not 上屏", picked)
    }

    @Test fun refresh_renders_new_history_items_without_reopening_panel() {
        val history = mutableListOf("old")
        val v = ClipboardView(ctx).apply {
            historyProvider = { history.toList() }
            applyPalette(pal)
            refresh()
        }
        assertTrue("initial item is visible", "old" in labels(v))
        history.add(0, "new")
        v.refresh()
        assertTrue("new item appears in the existing panel", "new" in labels(v))
        assertTrue("existing item remains visible", "old" in labels(v))
    }

    @Test fun copy_block_callback_can_refresh_open_panel_without_reopening() {
        val history = mutableListOf("old")
        val v = ClipboardView(ctx).apply {
            historyProvider = { history.toList() }
            onCopyBlockToAegis = { block -> history.add(0, block); refresh() }
            applyPalette(pal)
            refresh()
        }
        v.showSplitForTest("复制 block")
        assertTrue(clickText(overlayOf(v), "block"))
        assertTrue("copied block appears in the still-open panel", "block" in labels(mainOf(v)))
        assertTrue("previous history remains visible", "old" in labels(mainOf(v)))
    }


    @Test fun expanded_card_inner_scroll_drags_and_the_outer_list_does_not_steal_it() {
        val long = (1..12).joinToString("\n") { "第${it}行内容" }
        val history = listOf(long) + (1..20).map { "条目$it" }
        val v = clipView(history)
        v.expandForTest(long)
        layout(v)
        val body = bodyOf(v, long)
        val inner = body.parent as ScrollView
        val outer = allViews(v).filterIsInstance<ScrollView>().first { it !== inner }
        assertTrue("precondition: the inner card overflows 4 lines", inner.canScrollVertically(1))
        assertTrue("precondition: the outer list overflows", outer.canScrollVertically(1))

        var x = 0; var y = 0; var cur: View? = body
        while (cur != null && cur !== v) { x += cur.left; y += cur.top; cur = cur.parent as? View }
        val cx = (x + 8).toFloat(); val cy = (y + 6).toFloat()
        send(v, MotionEvent.ACTION_DOWN, cx, cy, 0)
        send(v, MotionEvent.ACTION_MOVE, cx, cy - 60f, 16)
        send(v, MotionEvent.ACTION_MOVE, cx, cy - 120f, 32)
        send(v, MotionEvent.ACTION_UP, cx, cy - 120f, 48)

        assertTrue("the inner card actually scrolled (真滚了)", inner.scrollY > 0)
        assertEquals("the outer list did NOT steal the drag", 0, outer.scrollY)
    }


    @Test fun copy_all_records_each_split_block_separately() {
        val text = "visit https://x.com and copy each block"
        val blocks = ClipSplitter.blocks(text)
        assertTrue("precondition: the text splits into ≥2 blocks", blocks.size >= 2)
        val collected = ArrayList<String>()
        val v = ClipboardView(ctx).apply {
            historyProvider = { listOf(text) }; onCopyBlockToAegis = { collected.add(it) }; applyPalette(pal); refresh()
        }
        v.showSplitForTest(text)
        assertTrue(clickText(overlayOf(v), "全部复制"))
        assertEquals("每块各写一条 (N 条), not 1 merged entry", blocks.size, collected.size)
        assertEquals("the blocks recorded are exactly the split blocks", blocks.toSet(), collected.toSet())
    }


    @Test fun manage_menu_renames_move_to_move_category() {
        val v = phraseView(listOf("你好"))
        v.showPhraseManageMenuForTest()
        val ls = labels(overlayOf(v))
        assertTrue("「移动分类」present", "移动分类" in ls)
        assertFalse("the bare 「移动」 label is gone", ls.any { it == "移动" })
    }


    @Test fun clear_history_top_icon_requires_confirmation() {
        var clears = 0
        val v = clipView(listOf("第一条")).apply { onClearHistory = { clears++ } }
        layout(v)
        assertTrue("tap the clear-history icon", clickDesc(v, "清空剪贴板历史"))
        assertEquals("top icon does not clear immediately", 0, clears)
        assertTrue(clickText(overlayOf(v), "清空"))
        assertEquals("confirming clears history", 1, clears)
        assertFalse("old settings gear is gone", allViews(v).any { it.contentDescription?.toString() == "设置" })
    }
}
