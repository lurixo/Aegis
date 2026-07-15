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

import android.app.Activity
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.Gravity
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
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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

    private fun rootTap(root: View, target: View) {
        val bounds = boundsInRoot(root as ViewGroup, target)
        send(root, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        send(root, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 16)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun rootTap(root: View, x: Float, y: Float) {
        send(root, MotionEvent.ACTION_DOWN, x, y, 0)
        send(root, MotionEvent.ACTION_UP, x, y, 16)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun boundsInRoot(root: ViewGroup, target: View): Rect = Rect(0, 0, target.width, target.height).also {
        root.offsetDescendantRectToMyCoords(target, it)
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun textViews(root: View): List<TextView> = allViews(root).filterIsInstance<TextView>()
    private fun actionButtons(root: View): List<TextView> = textViews(root).filter {
        it.compoundDrawables[0] != null && it.background is GradientDrawable && it.hasOnClickListeners()
    }
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
    private fun dp(value: Int): Int = (value * ctx.resources.displayMetrics.density).toInt()
    private fun swipeActions(root: View, descriptions: List<String>): List<View> =
        allViews(root).filterIsInstance<ViewGroup>().map { group ->
            (0 until group.childCount).map(group::getChildAt)
        }.single { children -> children.map { it.contentDescription?.toString() } == descriptions }

    private fun assertSwipeActionStrip(v: ClipboardView, text: String, descriptions: List<String>): List<View> {
        val actions = swipeActions(v, descriptions)
        val size = dp(44)
        val gap = dp(4)
        val strip = actions.first().parent as View
        assertEquals(descriptions, actions.map { it.contentDescription?.toString() })
        assertTrue(actions.all { it !is TextView && it.hasOnClickListeners() })
        assertTrue(actions.all { it.width == size && it.height == size })
        assertTrue(actions.all { it.background is GradientDrawable && (it.background as GradientDrawable).cornerRadius > 0f })
        assertEquals(descriptions.size * (size + gap), strip.width)
        assertEquals(gap, actions.first().left)
        assertEquals(strip.width, actions.last().right)
        actions.zipWithNext().forEach { (left, right) ->
            assertEquals(gap, right.left - left.right)
            assertTrue(left.right <= right.left)
        }
        val header = bodyOf(v, text).parent as View
        val frame = strip.parent as View
        assertEquals(-strip.width.toFloat(), header.translationX, 0f)
        assertEquals(gap.toFloat(), strip.left + actions.first().left - (header.right + header.translationX), 0f)
        assertEquals(frame.width, strip.right)
        return actions
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

    @Test fun clipboard_swipe_reveals_three_icon_actions_while_dropdown_reveals_labeled_actions() {
        val v = clipView(listOf("第一条", "第二条"))
        layout(v)
        rootSwipe(v, bodyOf(v, "第一条"), -200f)
        layout(v)
        val swipeActions = assertSwipeActionStrip(
            v,
            "第一条",
            listOf(
                ctx.getString(com.aegis.ime.R.string.clip_add_phrase),
                ctx.getString(com.aegis.ime.R.string.clip_split_word),
                ctx.getString(com.aegis.ime.R.string.clip_delete),
            ),
        )
        assertEquals("第一条", v.swipeRevealedForTest())
        assertTrue(actionButtons(v).isEmpty())
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_expand) in allViews(v).mapNotNull { it.contentDescription?.toString() })

        rootSwipe(v, swipeActions.last(), 200f)
        assertNull(v.swipeRevealedForTest())
        assertTrue(clickDesc(v, ctx.getString(com.aegis.ime.R.string.clip_expand)))
        layout(v)
        assertNull(v.swipeRevealedForTest())
        assertEquals(0f, (bodyOf(v, "第一条").parent as View).translationX, 0f)
        assertEquals(
            listOf(
                ctx.getString(com.aegis.ime.R.string.clip_phrases),
                ctx.getString(com.aegis.ime.R.string.clip_split_word),
                ctx.getString(com.aegis.ime.R.string.clip_delete),
            ),
            actionButtons(v).map { it.text.toString() },
        )
        assertTrue(ctx.getString(com.aegis.ime.R.string.clip_collapse) in allViews(v).mapNotNull { it.contentDescription?.toString() })
    }

    @Test fun narrow_phrase_swipe_keeps_four_actions_reachable_and_dropdown_actions_distinct() {
        for (width in listOf(320, 360)) {
            val v = phraseView(listOf("你好", "在吗"))
            layout(v, width)
            rootSwipe(v, bodyOf(v, "你好"), -80f)
            layout(v, width)
            assertEquals("你好", v.swipeRevealedForTest())
            val swipeActions = assertSwipeActionStrip(
                v,
                "你好",
                listOf(
                    ctx.getString(com.aegis.ime.R.string.clip_edit),
                    ctx.getString(com.aegis.ime.R.string.clip_note),
                    ctx.getString(com.aegis.ime.R.string.clip_move),
                    ctx.getString(com.aegis.ime.R.string.clip_delete),
                ),
            )
            swipeActions.forEach { action ->
                val (actionX, _) = centerInRoot(v, action)
                assertTrue(actionX - action.width / 2f >= 0f)
                assertTrue(actionX + action.width / 2f <= v.width)
            }
            assertTrue(actionButtons(v).isEmpty())
            rootSwipe(v, swipeActions.last(), 80f)
            assertNull(v.swipeRevealedForTest())
            layout(v, width)
            val expand = allViews(v).first { it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_expand) }
            assertTrue(expand.performClick())
            assertNull(v.swipeRevealedForTest())
            layout(v, width)
            assertEquals(
                listOf(
                    ctx.getString(com.aegis.ime.R.string.clip_edit),
                    ctx.getString(com.aegis.ime.R.string.clip_note),
                    ctx.getString(com.aegis.ime.R.string.clip_move),
                    ctx.getString(com.aegis.ime.R.string.clip_delete),
                ),
                actionButtons(v).map { it.text.toString() },
            )
        }
    }

    @Test fun phrase_swipe_move_reuses_the_expanded_move_asset() {
        val v = phraseView(listOf("你好"))
        v.revealSwipeForTest("你好")
        layout(v)
        val swipeMove = allViews(v).single {
            it !is TextView && it.contentDescription?.toString() == ctx.getString(com.aegis.ime.R.string.clip_move)
        }
        val asset = requireNotNull(swipeMove.tag)
        v.hideSwipeForTest()
        v.expandForTest("你好")
        layout(v)
        val expandedMove = actionButtons(v).single { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_move) }
        assertTrue(asset === expandedMove.tag)
        assertFalse(expandedMove.contentDescription?.toString()?.startsWith("移 ") == true)
    }

    @Test fun tabs_and_categories_dispatch_only_inside_their_own_capsules() {
        val clipboard = ctx.getString(com.aegis.ime.R.string.clip_clipboard)
        val phrases = ctx.getString(com.aegis.ime.R.string.clip_phrases)
        val v = phraseView(listOf("你好"))
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            activity.get().setContentView(v)
            layout(v, w = 600)
            var tabs = textViews(v).filter { it.text?.toString() == clipboard || it.text?.toString() == phrases }
            val tray = tabs.first().parent as View
            assertEquals(tray.width / 2, tabs[0].width)
            assertEquals(tray.width / 2, tabs[1].width)
            assertEquals(tabs[0].right, tabs[1].left)
            rootTap(v, tabs.first { it.text?.toString() == clipboard })
            assertTrue(v.isClipboardTabForTest())
            layout(v, w = 600)
            tabs = textViews(v).filter { it.text?.toString() == clipboard || it.text?.toString() == phrases }
            rootTap(v, tabs.first { it.text?.toString() == phrases })
            assertFalse(v.isClipboardTabForTest())
            layout(v, w = 600)
            val defaults = textViews(v).first { it.text?.toString() == "默认" && it.hasOnClickListeners() }
            val work = textViews(v).first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
            val defaultBounds = boundsInRoot(v, defaults)
            val workBounds = boundsInRoot(v, work)
            assertTrue(defaultBounds.right < workBounds.left)
            rootTap(v, work)
            assertEquals("工作", v.phraseCatForTest())
            layout(v, w = 600)
            rootTap(v, (defaultBounds.right + workBounds.left) / 2f, defaultBounds.exactCenterY())
            assertEquals("工作", v.phraseCatForTest())
            val refreshedDefault = textViews(v).first { it.text?.toString() == "默认" && it.hasOnClickListeners() }
            rootTap(v, refreshedDefault)
            assertEquals("默认", v.phraseCatForTest())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun rtl_swipe_strips_keep_physical_action_order_and_right_edge_anchor() {
        val cases = listOf(
            Triple(
                clipView(listOf("第一条")),
                "第一条",
                listOf(
                    ctx.getString(com.aegis.ime.R.string.clip_add_phrase),
                    ctx.getString(com.aegis.ime.R.string.clip_split_word),
                    ctx.getString(com.aegis.ime.R.string.clip_delete),
                ),
            ),
            Triple(
                phraseView(listOf("你好")),
                "你好",
                listOf(
                    ctx.getString(com.aegis.ime.R.string.clip_edit),
                    ctx.getString(com.aegis.ime.R.string.clip_note),
                    ctx.getString(com.aegis.ime.R.string.clip_move),
                    ctx.getString(com.aegis.ime.R.string.clip_delete),
                ),
            ),
        )
        for ((view, text, expected) in cases) {
            view.layoutDirection = View.LAYOUT_DIRECTION_RTL
            view.revealSwipeForTest(text)
            layout(view)
            val actions = assertSwipeActionStrip(view, text, expected)
            val strip = actions.first().parent as View
            val frame = strip.parent as View
            assertEquals(expected, actions.sortedBy { it.left }.map { it.contentDescription?.toString() })
            assertEquals(frame.width, strip.right)
        }
    }

    @Test fun rtl_dropdown_action_rows_keep_physical_order_and_left_alignment() {
        val cases = listOf(
            Triple(
                clipView(listOf("第一条")),
                "第一条",
                listOf(
                    ctx.getString(com.aegis.ime.R.string.clip_phrases),
                    ctx.getString(com.aegis.ime.R.string.clip_split_word),
                    ctx.getString(com.aegis.ime.R.string.clip_delete),
                ),
            ),
            Triple(
                phraseView(listOf("你好")),
                "你好",
                listOf(
                    ctx.getString(com.aegis.ime.R.string.clip_edit),
                    ctx.getString(com.aegis.ime.R.string.clip_note),
                    ctx.getString(com.aegis.ime.R.string.clip_move),
                    ctx.getString(com.aegis.ime.R.string.clip_delete),
                ),
            ),
        )
        for ((view, text, expected) in cases) {
            view.layoutDirection = View.LAYOUT_DIRECTION_RTL
            view.expandForTest(text)
            layout(view)
            val actions = actionButtons(view)
            val physical = actions.sortedBy { it.left }
            val row = actions.first().parent as ViewGroup
            assertEquals(expected, physical.map { it.text.toString() })
            assertEquals(row.paddingLeft, physical.first().left)
            assertTrue(physical.zipWithNext().all { (left, right) -> left.right <= right.left })
            assertTrue(actions.all {
                Gravity.getAbsoluteGravity(it.gravity, it.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT
            })
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

    @Test fun confirmed_bulk_clear_resets_item_actions_before_same_text_returns() {
        val expand = ctx.getString(com.aegis.ime.R.string.clip_expand)
        val collapse = ctx.getString(com.aegis.ime.R.string.clip_collapse)
        val clear = ctx.getString(com.aegis.ime.R.string.clip_clear)

        fun arm(v: ClipboardView, expanded: Boolean) {
            if (expanded) {
                v.expandForTest("x")
                assertTrue(collapse in allViews(v).mapNotNull { it.contentDescription?.toString() })
            } else {
                v.revealSwipeForTest("x")
                assertEquals("x", v.swipeRevealedForTest())
            }
        }

        fun assertNeutral(v: ClipboardView) {
            val descriptions = allViews(v).mapNotNull { it.contentDescription?.toString() }
            assertNull(v.swipeRevealedForTest())
            assertTrue(expand in descriptions)
            assertFalse(collapse in descriptions)
            assertTrue(actionButtons(v).isEmpty())
        }

        for (expanded in listOf(false, true)) {
            val history = mutableListOf("x")
            val clip = clipView(history).apply { onClearHistory = { history.clear() } }
            arm(clip, expanded)
            clip.confirmClearHistoryForTest()
            assertTrue(clickText(overlayOf(clip), clear))
            assertTrue(history.isEmpty())
            history.add("x")
            clip.refresh()
            assertTrue("x" in labels(mainOf(clip)))
            assertNeutral(clip)

            val phrases = mutableListOf("x")
            val phrase = phraseView(phrases).apply { onClearCategory = { phrases.clear() } }
            arm(phrase, expanded)
            phrase.confirmClearForTest()
            assertTrue(clickText(overlayOf(phrase), clear))
            assertTrue(phrases.isEmpty())
            phrases.add("x")
            phrase.refresh()
            assertTrue("x" in labels(mainOf(phrase)))
            assertNeutral(phrase)
        }
    }
}
