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

    private fun centerInRoot(root: View, target: View): Pair<Float, Float> {
        var x = target.width / 2f
        var y = target.height / 2f
        var current = target
        while (current !== root) {
            val parent = current.parent as View
            x += current.left + current.translationX - parent.scrollX
            y += current.top + current.translationY - parent.scrollY
            current = parent
        }
        return x to y
    }

    private fun rootSwipe(root: View, target: View, dx: Float) {
        val (x, y) = centerInRoot(root, target)
        send(root, MotionEvent.ACTION_DOWN, x, y, 0)
        send(root, MotionEvent.ACTION_MOVE, x + dx, y, 16)
        send(root, MotionEvent.ACTION_UP, x + dx, y, 32)
    }

    private fun rootSwipe(root: View, target: View, localX: Float, localY: Float, dx: Float) {
        val (centerX, centerY) = centerInRoot(root, target)
        val x = centerX - target.width / 2f + localX
        val y = centerY - target.height / 2f + localY
        send(root, MotionEvent.ACTION_DOWN, x, y, 0)
        send(root, MotionEvent.ACTION_MOVE, x + dx, y, 16)
        send(root, MotionEvent.ACTION_UP, x + dx, y, 32)
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

    @Test fun left_and_right_swipes_and_arrow_clicks_drive_the_same_open_state() {
        val v = clipView(listOf("第一条", "第二条"))
        layout(v)
        rootSwipe(v, bodyOf(v, "第一条"), -200f)
        layout(v)
        val openedBody = bodyOf(v, "第一条")
        assertEquals("第一条", v.swipeRevealedForTest())
        assertTrue((openedBody.parent as View).translationX < 0f)
        assertTrue(clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_collapse)))
        assertNull(v.swipeRevealedForTest())
        assertTrue(clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_expand)))
        assertEquals("第一条", v.swipeRevealedForTest())
        layout(v)
        val action = textViews(v).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_phrases) && it.compoundDrawables[0] != null }
        rootSwipe(v, action, 200f)
        layout(v)
        val closedBody = bodyOf(v, "第一条")
        assertNull(v.swipeRevealedForTest())
        assertEquals(0f, (closedBody.parent as View).translationX, 0f)
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_expand) in allViews(v).mapNotNull { it.contentDescription?.toString() })
    }

    @Test fun right_swipes_from_action_row_gaps_and_vertical_bands_close_the_revealed_row() {
        val clipboard = clipView(listOf("第一条", "第二条"))
        layout(clipboard)
        rootSwipe(clipboard, bodyOf(clipboard, "第一条"), -200f)
        layout(clipboard)
        val clipboardAction = textViews(clipboard).first {
            it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_phrases) && it.compoundDrawables[0] != null
        }
        val clipboardRow = clipboardAction.parent as ViewGroup
        val nextClipboardAction = clipboardRow.getChildAt(1)
        assertTrue(nextClipboardAction.left > clipboardAction.right)
        rootSwipe(
            clipboard,
            clipboardRow,
            (clipboardAction.right + nextClipboardAction.left) / 2f,
            clipboardRow.height / 2f,
            80f,
        )
        assertNull(clipboard.swipeRevealedForTest())

        val phrases = phraseView(listOf("你好", "在吗"))
        layout(phrases)
        rootSwipe(phrases, bodyOf(phrases, "你好"), -200f)
        layout(phrases)
        val phraseAction = textViews(phrases).first {
            it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_edit) && it.compoundDrawables[0] != null
        }
        val phraseRow = phraseAction.parent as ViewGroup
        assertTrue(phraseAction.top > 0)
        rootSwipe(
            phrases,
            phraseRow,
            phraseAction.left + phraseAction.width / 2f,
            phraseAction.top / 2f,
            80f,
        )
        assertNull(phrases.swipeRevealedForTest())
    }

    @Test fun narrow_phrase_row_keeps_the_arrow_visible_and_right_swipe_reachable_from_the_root() {
        for (width in listOf(320, 360)) {
            val v = phraseView(listOf("你好", "在吗"))
            layout(v, width)
            rootSwipe(v, bodyOf(v, "你好"), -80f)
            layout(v, width)
            assertEquals("你好", v.swipeRevealedForTest())
            val collapse = allViews(v).first { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_collapse) }
            val (arrowX, _) = centerInRoot(v, collapse)
            assertTrue(arrowX - collapse.width / 2f >= 0f)
            assertTrue(arrowX + collapse.width / 2f <= v.width)
            assertTrue(collapse.performClick())
            assertNull(v.swipeRevealedForTest())
            layout(v, width)
            val expand = allViews(v).first { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_expand) }
            assertTrue(expand.performClick())
            assertEquals("你好", v.swipeRevealedForTest())
            layout(v, width)
            val edit = textViews(v).first { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_edit) && it.compoundDrawables[0] != null }
            rootSwipe(v, edit, 80f)
            assertNull(v.swipeRevealedForTest())
        }
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
        assertTrue(clickText(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_copy_all)))
        assertEquals("每块各写一条 (N 条), not 1 merged entry", blocks.size, collected.size)
        assertEquals("the blocks recorded are exactly the split blocks", blocks.toSet(), collected.toSet())
    }


    @Test fun manage_menu_renames_move_to_move_category() {
        val v = phraseView(listOf("你好"))
        v.showPhraseManageMenuForTest()
        val ls = labels(overlayOf(v))
        assertTrue("「移动分类」present", ctx.getString(com.aegis.ime.R.string.clip_move_category) in ls)
        assertFalse("the bare 「移动」 label is gone", ls.any { it == ctx.getString(com.aegis.ime.R.string.clip_move) })
    }


    @Test fun clear_history_top_icon_requires_confirmation() {
        var clears = 0
        val v = clipView(listOf("第一条")).apply { onClearHistory = { clears++ } }
        layout(v)
        assertTrue("tap the clear-history icon", clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_clear_history)))
        assertEquals("top icon does not clear immediately", 0, clears)
        assertTrue(clickText(overlayOf(v), ctx.getString(com.aegis.ime.R.string.clip_clear)))
        assertEquals("confirming clears history", 1, clears)
        assertFalse("old settings gear is gone", allViews(v).any { it.contentDescription?.toString() == "设置" })
    }
}
