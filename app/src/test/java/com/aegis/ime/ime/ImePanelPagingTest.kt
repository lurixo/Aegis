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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImePanelPagingTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun exactly(px: Int) = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)

    private fun rail(vararg widths: Int): ImePanelCategoryRail = ImePanelCategoryRail(ctx, density).apply {
        underlineColor = Color.RED
        for (width in widths) addView(View(ctx), LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT))
        measure(exactly(widths.sum()), exactly(40))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun span(rail: ImePanelCategoryRail): Pair<Float, Float> =
        requireNotNull(rail.underlineBoundsForTest()).let { it.left to it.right }

    private fun drawnUnderline(rail: ImePanelCategoryRail): IntRange? {
        val bitmap = Bitmap.createBitmap(rail.width, rail.height, Bitmap.Config.ARGB_8888)
        try {
            rail.draw(Canvas(bitmap))
            val y = rail.height - 1
            val red = (0 until rail.width).filter { bitmap.getPixel(it, y) == Color.RED }
            return if (red.isEmpty()) null else red.first()..red.last()
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun without_a_page_offset_the_underline_sits_exactly_under_the_selected_tab() {
        val rail = rail(60, 80, 100, 70)
        for ((index, expected) in listOf(0 to (0f to 60f), 1 to (60f to 140f), 2 to (140f to 240f), 3 to (240f to 310f))) {
            rail.selectedIndex = index
            assertEquals("tab $index", expected, span(rail))
            assertEquals("tab $index draws where it reports", expected.first.toInt()..expected.second.toInt() - 1, drawnUnderline(rail))
        }
        val bounds = requireNotNull(rail.underlineBoundsForTest())
        assertEquals(RectF(240f, 40f - 4f * density, 310f, 40f), bounds)
    }

    @Test fun a_positive_page_offset_slides_the_underline_toward_the_next_tab() {
        val rail = rail(60, 80, 100, 70).apply { selectedIndex = 1 }
        rail.pageOffset = 0.25f
        assertEquals(80f to 165f, span(rail))
        assertEquals(80..164, drawnUnderline(rail))
        rail.pageOffset = 1f
        assertEquals("a full page lands exactly on the next tab", 140f to 240f, span(rail))
        rail.pageOffset = 1.5f
        assertEquals("the slide never overshoots the neighbouring tab", 140f to 240f, span(rail))
    }

    @Test fun a_negative_page_offset_slides_the_underline_toward_the_previous_tab() {
        val rail = rail(60, 80, 100, 70).apply { selectedIndex = 2 }
        rail.pageOffset = -0.5f
        assertEquals(100f to 190f, span(rail))
        assertEquals(100..189, drawnUnderline(rail))
        rail.pageOffset = -1f
        assertEquals("a full page lands exactly on the previous tab", 60f to 140f, span(rail))
    }

    @Test fun an_offset_toward_a_missing_or_hidden_tab_keeps_the_underline_in_place() {
        val rail = rail(60, 80, 100, 70)
        rail.selectedIndex = 0
        rail.pageOffset = -0.5f
        assertEquals("nothing before the first tab", 0f to 60f, span(rail))
        rail.selectedIndex = 3
        rail.pageOffset = 0.5f
        assertEquals("nothing after the last tab", 240f to 310f, span(rail))
        rail.selectedIndex = 1
        rail.getChildAt(2).visibility = View.INVISIBLE
        rail.pageOffset = 0.5f
        assertEquals("a hidden neighbour is not a target", 60f to 140f, span(rail))
    }

    @Test fun clearing_the_page_offset_returns_the_underline_to_the_selected_tab() {
        val rail = rail(60, 80, 100, 70).apply { selectedIndex = 1 }
        rail.pageOffset = 0.6f
        rail.pageOffset = 0f
        assertEquals(60f to 140f, span(rail))
        assertEquals(60..139, drawnUnderline(rail))
    }

    private fun animations(on: Boolean) =
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, if (on) 1f else 0f)

    private fun frames(count: Int, each: () -> Unit) = repeat(count) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        each()
    }

    private fun <T> hostedBar(block: (ImePanelCategoryBar) -> T): T {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val bar = ImePanelCategoryBar(activity, density).apply {
                addView(ImePanelCategoryRail(activity, density).apply {
                    repeat(10) { addView(View(activity), LinearLayout.LayoutParams(100, LinearLayout.LayoutParams.MATCH_PARENT)) }
                })
            }
            val host = FrameLayout(activity)
            host.addView(bar, FrameLayout.LayoutParams(300, 40))
            activity.setContentView(host)
            host.measure(exactly(300), exactly(40))
            host.layout(0, 0, 300, 40)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            return block(bar)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun revealing_a_tab_already_in_view_leaves_the_bar_where_it_is() = hostedBar { bar ->
        animations(true)
        bar.reveal(0)
        bar.reveal(2)
        frames(30) { bar.computeScroll() }
        assertEquals(0, bar.scrollX)
    }

    @Test fun a_tab_past_the_right_edge_slides_in_until_its_right_edge_shows() = hostedBar { bar ->
        animations(true)
        bar.reveal(5)
        assertTrue("the reveal is a smooth scroll, not a jump", bar.scrollX < 300)
        frames(30) { bar.computeScroll() }
        assertEquals("tab 5 spans 500..600, so it ends flush with the right edge", 300, bar.scrollX)
    }

    @Test fun a_tab_past_the_left_edge_slides_in_until_its_left_edge_shows() = hostedBar { bar ->
        animations(true)
        bar.scrollTo(450, 0)
        bar.reveal(3)
        assertTrue("the reveal is a smooth scroll, not a jump", bar.scrollX > 300)
        frames(30) { bar.computeScroll() }
        assertEquals(300, bar.scrollX)
    }

    @Test fun a_partly_hidden_tab_is_brought_fully_into_view() = hostedBar { bar ->
        animations(true)
        bar.scrollTo(250, 0)
        bar.reveal(2)
        frames(30) { bar.computeScroll() }
        assertEquals(200, bar.scrollX)
        bar.reveal(4)
        frames(30) { bar.computeScroll() }
        assertEquals(200, bar.scrollX)
        bar.reveal(5)
        frames(30) { bar.computeScroll() }
        assertEquals(300, bar.scrollX)
    }

    @Test fun reduced_motion_jumps_straight_to_the_revealed_tab() = hostedBar { bar ->
        animations(false)
        try {
            bar.reveal(9)
            assertEquals(700, bar.scrollX)
        } finally {
            animations(true)
        }
    }

    @Test fun a_detached_bar_jumps_straight_to_the_revealed_tab() {
        animations(true)
        val bar = ImePanelCategoryBar(ctx, density).apply {
            addView(ImePanelCategoryRail(ctx, density).apply {
                repeat(10) { addView(View(ctx), LinearLayout.LayoutParams(100, LinearLayout.LayoutParams.MATCH_PARENT)) }
            })
            measure(exactly(300), exactly(40))
            layout(0, 0, 300, 40)
        }
        bar.reveal(9)
        assertEquals(700, bar.scrollX)
    }

    @Test fun a_panel_reset_stops_a_reveal_still_in_flight() = hostedBar { bar ->
        animations(true)
        bar.reveal(9)
        frames(2) { bar.computeScroll() }
        assertTrue("precondition: the reveal is under way", bar.scrollX in 1 until 700)
        bar.scrollTo(0, 0)
        bar.fling(0)
        frames(30) { bar.computeScroll() }
        assertEquals("the reset used by the panels wins over the reveal", 0, bar.scrollX)
    }

    private class PagerRig(activity: Activity, private val pages: Int, var selected: Int) {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
        val clicks = mutableListOf<Int>()
        val longClicks = mutableListOf<Int>()
        val binds = mutableListOf<Int>()
        val offsets = mutableListOf<Float>()
        val selections = mutableListOf<Int>()
        var pageable = true
        val tiles = List(8) { index ->
            View(activity).apply {
                isClickable = true
                isLongClickable = true
                setOnClickListener { clicks += index }
                setOnLongClickListener {
                    longClicks += index
                    true
                }
            }
        }
        val current = ImePanelViewport(activity).apply {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                for (tile in tiles) addView(tile, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 100))
            })
        }
        val peek = ImePanelViewport(activity).apply {
            addView(View(activity), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 300))
        }
        val pager = ImePanelPager(activity, current, peek).apply {
            pageCount = { pages }
            selectedPage = { selected }
            canPage = { pageable }
            onBindPeek = { binds += it }
            onOffset = { offsets += it }
            onPageSelected = {
                selections += it
                selected = it
            }
        }
        val host = FrameLayout(activity).apply { addView(pager, FrameLayout.LayoutParams(400, 300)) }

        fun relayout() {
            host.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, 400, 300)
        }

        fun send(action: Int, x: Float, y: Float, time: Long): Boolean {
            val event = MotionEvent.obtain(0, time, action, x, y, 0)
            try {
                return pager.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }

        fun send(action: Int, time: Long, vararg pointers: Triple<Int, Float, Float>): Boolean {
            val properties = Array(pointers.size) {
                MotionEvent.PointerProperties().apply {
                    id = pointers[it].first
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(pointers.size) {
                MotionEvent.PointerCoords().apply {
                    x = pointers[it].second
                    y = pointers[it].third
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(0, time, action, pointers.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            try {
                return pager.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }

        fun dragPastHalfAndRest() {
            send(MotionEvent.ACTION_DOWN, 380f, 150f, 0)
            send(MotionEvent.ACTION_MOVE, 380f - slop - 1f, 150f, 16)
            send(MotionEvent.ACTION_MOVE, 150f, 150f, 100)
            send(MotionEvent.ACTION_MOVE, 150f, 150f, 300)
            send(MotionEvent.ACTION_UP, 150f, 150f, 310)
        }
    }

    private fun <T> pagerRig(pages: Int = 3, selected: Int = 1, animated: Boolean = true, block: (PagerRig) -> T): T {
        animations(animated)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val rig = PagerRig(activity, pages, selected)
            activity.setContentView(rig.host)
            rig.relayout()
            return block(rig)
        } finally {
            controller.pause().stop().destroy()
            animations(true)
        }
    }

    private fun settleFrames(rig: PagerRig) = frames(12) { rig.relayout() }

    private fun assertAtRest(rig: PagerRig) {
        assertFalse(rig.pager.draggingForTest())
        assertFalse(rig.pager.settlingForTest())
        assertEquals(0f, rig.current.translationX, 0f)
        assertEquals(View.GONE, rig.peek.visibility)
        assertEquals(0f, rig.offsets.lastOrNull() ?: 0f, 0f)
    }

    @Test fun a_sideways_drag_follows_the_finger_and_slides_the_neighbour_in_from_that_side() = pagerRig { r ->
        val s = r.slop
        r.send(MotionEvent.ACTION_DOWN, 200f, 150f, 0)
        r.send(MotionEvent.ACTION_MOVE, 200f - s - 30f, 152f, 16)
        assertTrue(r.pager.draggingForTest())
        assertEquals(-30f, r.current.translationX, 0f)
        assertEquals(View.VISIBLE, r.peek.visibility)
        assertEquals("the next page waits flush against the right edge", 370f, r.peek.translationX, 0f)
        assertEquals(listOf(2), r.binds)
        assertEquals(30f / 400f, r.offsets.last(), 1e-6f)
        for ((step, x) in listOf(150f, 120f, 90f, 60f).withIndex()) r.send(MotionEvent.ACTION_MOVE, x, 150f, 32L + step * 16L)
        assertEquals(60f - 200f + s, r.current.translationX, 0f)
        assertEquals(r.current.translationX + 400f, r.peek.translationX, 0f)
        assertEquals("the neighbour is bound once, not on every move", listOf(2), r.binds)
        r.send(MotionEvent.ACTION_MOVE, 240f, 150f, 120)
        val offset = 240f - 200f + s
        assertEquals(offset, r.current.translationX, 0f)
        assertEquals("the previous page slides in from the left", offset - 400f, r.peek.translationX, 0f)
        assertEquals(listOf(2, 0), r.binds)
        assertEquals(-offset / 400f, r.offsets.last(), 1e-6f)
        r.send(MotionEvent.ACTION_MOVE, 140f, 150f, 136)
        assertEquals("crossing back rebinds only because the side changed", listOf(2, 0, 2), r.binds)
    }

    @Test fun a_release_past_half_the_page_settles_onto_the_neighbour() = pagerRig { r ->
        r.dragPastHalfAndRest()
        assertTrue("the release starts a settle, not a jump", r.pager.settlingForTest())
        assertTrue(r.selections.isEmpty())
        val from = r.offsets.size
        settleFrames(r)
        assertEquals(listOf(2), r.selections)
        val settle = r.offsets.drop(from)
        assertEquals("the settle ends exactly one page over before the page lands", 1f, settle.dropLast(1).last(), 0f)
        assertTrue("the settle never backs up", settle.dropLast(1).zipWithNext().all { (a, b) -> b >= a })
        assertAtRest(r)
    }

    @Test fun a_release_short_of_half_snaps_back() = pagerRig { r ->
        val s = r.slop
        r.send(MotionEvent.ACTION_DOWN, 380f, 150f, 0)
        r.send(MotionEvent.ACTION_MOVE, 380f - s - 1f, 150f, 16)
        r.send(MotionEvent.ACTION_MOVE, 230f, 150f, 100)
        r.send(MotionEvent.ACTION_MOVE, 230f, 150f, 300)
        r.send(MotionEvent.ACTION_UP, 230f, 150f, 310)
        assertTrue(r.pager.settlingForTest())
        val from = r.offsets.size
        settleFrames(r)
        assertTrue(r.selections.isEmpty())
        assertTrue("the snap back never overshoots", r.offsets.drop(from).all { it >= 0f })
        assertTrue(r.offsets.drop(from).zipWithNext().all { (a, b) -> b <= a })
        assertEquals(1, r.selected)
        assertAtRest(r)
    }

    @Test fun a_fling_toward_the_neighbour_settles_onto_it_from_a_short_drag() = pagerRig { r ->
        val s = r.slop
        r.send(MotionEvent.ACTION_DOWN, 200f, 150f, 0)
        r.send(MotionEvent.ACTION_MOVE, 200f - s - 4f, 150f, 10)
        r.send(MotionEvent.ACTION_MOVE, 160f, 150f, 20)
        r.send(MotionEvent.ACTION_UP, 150f, 150f, 30)
        assertTrue("the drag is well short of half the page", -r.pager.offsetForTest() < 200f)
        settleFrames(r)
        assertEquals(listOf(2), r.selections)
        assertAtRest(r)
    }

    @Test fun a_fling_back_toward_the_current_page_snaps_back_even_past_half() = pagerRig { r ->
        val s = r.slop
        r.send(MotionEvent.ACTION_DOWN, 380f, 150f, 0)
        r.send(MotionEvent.ACTION_MOVE, 380f - s - 1f, 150f, 16)
        r.send(MotionEvent.ACTION_MOVE, 60f, 150f, 200)
        r.send(MotionEvent.ACTION_MOVE, 100f, 150f, 280)
        r.send(MotionEvent.ACTION_UP, 140f, 150f, 300)
        settleFrames(r)
        assertTrue(r.selections.isEmpty())
        assertAtRest(r)
    }

    @Test fun the_first_and_last_pages_do_not_follow_toward_a_missing_neighbour() {
        pagerRig(selected = 0) { r ->
            val s = r.slop
            r.send(MotionEvent.ACTION_DOWN, 100f, 150f, 0)
            r.send(MotionEvent.ACTION_MOVE, 100f + s + 50f, 150f, 16)
            assertTrue("the sideways drag is still taken from the tile", r.pager.draggingForTest())
            r.send(MotionEvent.ACTION_MOVE, 300f, 150f, 32)
            assertEquals(0f, r.current.translationX, 0f)
            assertEquals(View.GONE, r.peek.visibility)
            assertTrue(r.binds.isEmpty())
            r.send(MotionEvent.ACTION_UP, 390f, 150f, 40)
            settleFrames(r)
            assertTrue(r.selections.isEmpty())
            assertTrue(r.clicks.isEmpty())
            assertAtRest(r)
        }
        pagerRig(selected = 2) { r ->
            val s = r.slop
            r.send(MotionEvent.ACTION_DOWN, 300f, 150f, 0)
            r.send(MotionEvent.ACTION_MOVE, 300f - s - 50f, 150f, 16)
            r.send(MotionEvent.ACTION_MOVE, 10f, 150f, 32)
            assertEquals(0f, r.current.translationX, 0f)
            assertTrue(r.binds.isEmpty())
            r.send(MotionEvent.ACTION_UP, 0f, 150f, 40)
            settleFrames(r)
            assertTrue(r.selections.isEmpty())
            assertTrue(r.clicks.isEmpty())
            assertAtRest(r)
        }
    }

    @Test fun locking_a_drag_cancels_the_pressed_tile_its_click_and_its_long_press() = pagerRig { r ->
        val tile = r.tiles[1]
        r.send(MotionEvent.ACTION_DOWN, 200f, 150f, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getTapTimeout() + 10L))
        assertTrue("precondition: the tile shows its press", tile.isPressed)
        r.send(MotionEvent.ACTION_MOVE, 200f - r.slop - 10f, 150f, 120)
        assertTrue(r.pager.draggingForTest())
        assertFalse("the lock withdraws the press", tile.isPressed)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        assertTrue("the lock also withdraws the pending long press", r.longClicks.isEmpty())
        r.send(MotionEvent.ACTION_UP, 200f - r.slop - 10f, 150f, 900)
        settleFrames(r)
        assertTrue("the tile never taps", r.clicks.isEmpty())
        assertTrue(r.selections.isEmpty())
    }

    @Test fun a_wobble_short_of_the_lock_still_taps_the_tile() = pagerRig { r ->
        val s = r.slop
        r.send(MotionEvent.ACTION_DOWN, 200f, 150f, 0)
        r.send(MotionEvent.ACTION_MOVE, 200f + s / 2f, 150f, 16)
        r.send(MotionEvent.ACTION_MOVE, 200f - s / 2f, 151f, 32)
        r.send(MotionEvent.ACTION_UP, 200f - s / 2f, 151f, 48)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1), r.clicks)

        r.send(MotionEvent.ACTION_DOWN, 200f, 150f, 100)
        r.send(MotionEvent.ACTION_MOVE, 200f + s + 2f, 150f + s - 1f, 116)
        assertFalse("a diagonal short of the 1.5x bias is not a page drag", r.pager.draggingForTest())
        r.send(MotionEvent.ACTION_UP, 200f + s + 2f, 150f + s - 1f, 132)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1, 1), r.clicks)
        assertTrue(r.binds.isEmpty())
        assertTrue(r.selections.isEmpty())
        assertEquals(0f, r.current.translationX, 0f)
    }

    @Test fun a_vertical_scroll_never_turns_into_a_page_drag() = pagerRig { r ->
        val s = r.slop
        r.send(MotionEvent.ACTION_DOWN, 200f, 250f, 0)
        r.send(MotionEvent.ACTION_MOVE, 200f, 250f - s - 20f, 16)
        r.send(MotionEvent.ACTION_MOVE, 200f, 250f - s - 60f, 32)
        assertTrue("precondition: the content scrolls vertically", r.current.scrollY > 0)
        r.send(MotionEvent.ACTION_MOVE, 0f, 250f - s - 60f, 48)
        r.send(MotionEvent.ACTION_MOVE, -150f, 250f - s - 60f, 64)
        assertFalse(r.pager.draggingForTest())
        assertEquals(0f, r.current.translationX, 0f)
        assertTrue(r.binds.isEmpty())
        r.send(MotionEvent.ACTION_UP, -150f, 250f - s - 60f, 80)
        settleFrames(r)
        assertTrue(r.selections.isEmpty())
        assertTrue(r.clicks.isEmpty())
    }

    @Test fun a_touch_that_stops_a_vertical_fling_can_still_page() = pagerRig { r ->
        r.current.fling(4000)
        frames(3) { r.current.computeScroll() }
        val flung = r.current.scrollY
        assertTrue("precondition: the content is flinging", flung > 0)
        r.send(MotionEvent.ACTION_DOWN, 200f, 150f, 0)
        val caught = r.current.scrollY
        frames(3) { r.current.computeScroll() }
        assertEquals("the touch stops the fling", caught, r.current.scrollY)
        r.send(MotionEvent.ACTION_MOVE, 200f - r.slop - 40f, 152f, 16)
        assertTrue("a sideways drag still takes over from the caught fling", r.pager.draggingForTest())
        assertEquals(-40f, r.current.translationX, 0f)
        r.send(MotionEvent.ACTION_MOVE, -20f, 152f, 100)
        r.send(MotionEvent.ACTION_MOVE, -20f, 152f, 300)
        r.send(MotionEvent.ACTION_UP, -20f, 152f, 310)
        settleFrames(r)
        assertEquals(listOf(2), r.selections)
        assertAtRest(r)
    }

    @Test fun a_pager_that_may_not_page_leaves_the_gesture_to_its_content() = pagerRig { r ->
        r.pageable = false
        r.send(MotionEvent.ACTION_DOWN, 200f, 150f, 0)
        r.send(MotionEvent.ACTION_MOVE, 200f - r.slop - 40f, 150f, 16)
        assertFalse(r.pager.draggingForTest())
        r.send(MotionEvent.ACTION_UP, 200f - r.slop - 40f, 150f, 32)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the tile keeps its ordinary tap", listOf(1), r.clicks)

        r.pageable = true
        r.send(MotionEvent.ACTION_DOWN, 200f, 150f, 100)
        r.pageable = false
        r.send(MotionEvent.ACTION_MOVE, 200f - r.slop - 40f, 150f, 116)
        r.pageable = true
        r.send(MotionEvent.ACTION_MOVE, 20f, 150f, 132)
        assertFalse("a gesture blocked mid-way stays blocked", r.pager.draggingForTest())
        r.send(MotionEvent.ACTION_UP, 20f, 150f, 148)
        settleFrames(r)
        assertTrue(r.binds.isEmpty())
        assertTrue(r.selections.isEmpty())
        assertEquals(0f, r.current.translationX, 0f)
    }

    @Test fun reduced_motion_lands_the_page_on_release() = pagerRig(animated = false) { r ->
        r.dragPastHalfAndRest()
        assertEquals(listOf(2), r.selections)
        assertAtRest(r)
    }

    @Test fun a_touch_during_the_settle_lands_the_page_first() = pagerRig { r ->
        r.dragPastHalfAndRest()
        assertTrue(r.pager.settlingForTest())
        r.send(MotionEvent.ACTION_DOWN, 200f, 150f, 400)
        assertEquals("the settle finishes before the new touch lands", listOf(2), r.selections)
        assertFalse(r.pager.settlingForTest())
        assertEquals(0f, r.current.translationX, 0f)
        r.send(MotionEvent.ACTION_UP, 200f, 150f, 420)
        assertEquals(listOf(2), r.selections)
    }

    @Test fun abort_puts_the_page_back_without_selecting() = pagerRig { r ->
        r.send(MotionEvent.ACTION_DOWN, 200f, 150f, 0)
        r.send(MotionEvent.ACTION_MOVE, 200f - r.slop - 30f, 150f, 16)
        assertTrue(r.pager.draggingForTest())
        r.pager.abort()
        assertAtRest(r)
        r.send(MotionEvent.ACTION_MOVE, 0f, 150f, 32)
        r.send(MotionEvent.ACTION_UP, 0f, 150f, 48)
        settleFrames(r)
        assertTrue(r.selections.isEmpty())
        assertAtRest(r)

        r.dragPastHalfAndRest()
        assertTrue(r.pager.settlingForTest())
        r.pager.abort()
        settleFrames(r)
        assertTrue("an aborted settle never lands", r.selections.isEmpty())
        assertAtRest(r)

        r.dragPastHalfAndRest()
        assertTrue(r.pager.settlingForTest())
        r.host.removeView(r.pager)
        settleFrames(r)
        assertTrue("detaching mid-settle drops the switch", r.selections.isEmpty())
        assertAtRest(r)
    }

    @Test fun a_second_finger_neither_jumps_nor_steals_the_drag() = pagerRig { r ->
        val s = r.slop
        r.send(MotionEvent.ACTION_DOWN, 300f, 150f, 0)
        r.send(MotionEvent.ACTION_MOVE, 300f - s - 20f, 150f, 16)
        assertEquals(-20f, r.current.translationX, 0f)
        val lockX = 300f - s - 20f
        val second = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        assertTrue(r.send(second, 32, Triple(0, lockX, 150f), Triple(1, 100f, 150f)))
        assertEquals(-20f, r.current.translationX, 0f)
        assertTrue(r.send(MotionEvent.ACTION_MOVE, 48, Triple(0, lockX - 10f, 150f), Triple(1, 60f, 150f)))
        assertEquals("only the first finger drives the page", -30f, r.current.translationX, 0f)
        assertTrue(r.send(MotionEvent.ACTION_POINTER_UP, 64, Triple(0, lockX - 10f, 150f), Triple(1, 60f, 150f)))
        assertEquals("the hand-off does not jump", -30f, r.current.translationX, 0f)
        assertTrue(r.send(MotionEvent.ACTION_MOVE, 80, Triple(1, 40f, 150f)))
        assertEquals("the remaining finger carries on from where it is", -50f, r.current.translationX, 0f)
        assertTrue(r.send(MotionEvent.ACTION_UP, 96, Triple(1, 40f, 150f)))
        settleFrames(r)
        assertEquals("the remaining finger's flick decides the page", listOf(2), r.selections)
        assertTrue(r.clicks.isEmpty())
        assertAtRest(r)
    }
}
