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
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.layout.SymbolCatalog
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelSwipePagingTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val panelWidth = 480
    private val panelHeight = 220

    @Before fun startOnAFreshAnimationClock() = resetAnimationClock()

    @After fun leaveAFreshAnimationClock() = resetAnimationClock()

    private fun resetAnimationClock() {
        val state = AnimationUtils::class.java.getDeclaredField("sAnimationState").apply { isAccessible = true }
        (state.get(null) as ThreadLocal<*>).remove()
    }

    private class Finger(private val panel: ViewGroup) {
        private var time = 1_000L
        private var downTime = time

        fun down(x: Float, y: Float) {
            time += 100L
            downTime = time
            send(MotionEvent.ACTION_DOWN, x, y)
        }

        fun move(x: Float, y: Float, dt: Long = 16L) {
            time += dt
            send(MotionEvent.ACTION_MOVE, x, y)
        }

        fun up(x: Float, y: Float, dt: Long = 16L) {
            time += dt
            send(MotionEvent.ACTION_UP, x, y)
        }

        fun hold(ms: Long) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
            time += ms
        }

        private fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
            try {
                panel.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun Finger.drag(x: Float, y: Float, dx: Float, slop: Float) {
        down(x, y)
        val sign = if (dx < 0f) -1f else 1f
        move(x + sign * (slop + 1f), y)
        for (step in 1..5) move(x + dx * step / 5f, y)
    }

    private fun Finger.slowSwipe(x: Float, y: Float, dx: Float, slop: Float) {
        drag(x, y, dx, slop)
        move(x + dx, y, 200L)
        up(x + dx, y, 10L)
    }

    private fun Finger.flick(x: Float, y: Float, dx: Float, slop: Float) {
        down(x, y)
        val sign = if (dx < 0f) -1f else 1f
        move(x + sign * (slop + 1f), y, 8L)
        move(x + dx / 2f, y, 8L)
        up(x + dx, y, 8L)
    }

    private fun animations(on: Boolean) =
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, if (on) 1f else 0f)

    private fun layout(panel: View) {
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(panelHeight, View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, panelWidth, panelHeight)
    }

    private fun frames(panel: View, count: Int = 12, each: () -> Unit = {}) = repeat(count) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        each()
        layout(panel)
    }

    private fun <T : ViewGroup> hosted(animated: Boolean = true, create: (Activity) -> T, block: (T) -> Unit) {
        animations(animated)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val panel = create(activity)
            val host = FrameLayout(activity)
            host.addView(panel, FrameLayout.LayoutParams(panelWidth, panelHeight))
            activity.setContentView(host)
            layout(panel)
            frames(panel)
            block(panel)
        } finally {
            controller.pause().stop().destroy()
            animations(true)
        }
    }

    private fun bounds(panel: ViewGroup, view: View): Rect =
        Rect(0, 0, view.width, view.height).also { panel.offsetDescendantRectToMyCoords(view, it) }

    private fun frameOf(panel: ViewGroup, view: View): Rect =
        Rect(view.left, view.top, view.right, view.bottom).also { panel.offsetDescendantRectToMyCoords(view.parent as View, it) }

    private fun maxScroll(viewport: ScrollView): Int =
        ((viewport.getChildAt(0)?.height ?: 0) - viewport.height).coerceAtLeast(0)

    private fun symbols(activity: Activity, recents: List<String> = emptyList()) = SymbolsView(activity).apply {
        recentProvider = { recents }
        applyPalette(light)
        refresh()
    }

    private fun SymbolsView.open(index: Int) {
        openCategoryForTest(index)
        frames(this)
    }

    private fun SymbolsView.viewport(): ScrollView = gridViewportForTest() as ScrollView

    private fun SymbolsView.grid(): Rect = frameOf(this, viewport())

    private fun SymbolsView.tileCenter(symbol: String): Pair<Float, Float> =
        bounds(this, requireNotNull(gridCellForTest(symbol))).let { it.exactCenterX() to it.exactCenterY() }

    private val lastSymbolCategory = SymbolCatalog.categories.size

    @Test fun symbols_a_drag_left_past_half_pages_to_the_next_category_from_its_top() = hosted(create = { symbols(it) }) { panel ->
        panel.open(6)
        val next = panel.gridCellTextsForTest()
        panel.open(5)
        val viewport = panel.viewport()
        viewport.scrollTo(0, maxScroll(viewport))
        assertTrue("precondition: the outgoing category is parked away from its top", viewport.scrollY > 0)
        val grid = panel.grid()
        Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.6f, slop)
        assertEquals("the category switches only once the page lands", 5, panel.selectedCategoryForTest())
        frames(panel)
        assertEquals(6, panel.selectedCategoryForTest())
        assertEquals("the landed page shows the whole next category", next, panel.gridCellTextsForTest())
        assertEquals("the new category starts from its top", 0, viewport.scrollY)
        assertEquals(0f, viewport.translationX, 0f)
        assertEquals(View.GONE, panel.peekViewportForTest().visibility)
        val rail = panel.categoryRailForTest()
        assertEquals(6, rail.selectedIndex)
        assertEquals(0f, rail.pageOffset, 0f)
        val tab = panel.railTabForTest(6)
        assertEquals(tab.left.toFloat(), requireNotNull(rail.underlineBoundsForTest()).left, 0f)
        assertEquals(tab.right.toFloat(), requireNotNull(rail.underlineBoundsForTest()).right, 0f)
        assertEquals("the landed tab takes the selected label colour", light.keyLabel, panel.railTabForTest(6).currentTextColor)
        assertEquals(light.keyLabelSecondary, panel.railTabForTest(5).currentTextColor)
    }

    @Test fun symbols_a_drag_right_past_half_pages_to_the_previous_category() = hosted(create = { symbols(it) }) { panel ->
        panel.open(5)
        val previous = panel.gridCellTextsForTest()
        panel.open(6)
        val grid = panel.grid()
        Finger(panel).slowSwipe(grid.exactCenterX() - 100f, grid.exactCenterY(), grid.width() * 0.6f, slop)
        frames(panel)
        assertEquals(5, panel.selectedCategoryForTest())
        assertEquals(previous, panel.gridCellTextsForTest())
        assertEquals(0, panel.gridScrollYForTest())
        assertEquals(5, panel.categoryRailForTest().selectedIndex)
    }

    @Test fun symbols_the_neighbour_shows_its_real_first_rows_while_the_underline_follows() = hosted(create = { symbols(it) }) { panel ->
        panel.open(6)
        val next = panel.gridCellTextsForTest()
        panel.open(4)
        val previous = panel.gridCellTextsForTest()
        panel.open(5)
        val viewport = panel.viewport()
        val peek = panel.peekViewportForTest()
        val grid = panel.grid()
        val quarter = grid.width() / 4f
        val rail = panel.categoryRailForTest()
        val tabs = (4..6).associateWith { panel.railTabForTest(it) }
        val finger = Finger(panel)
        finger.down(grid.exactCenterX(), grid.exactCenterY())
        finger.move(grid.exactCenterX() - slop - quarter, grid.exactCenterY())
        layout(panel)
        assertEquals(-quarter, viewport.translationX, 0f)
        assertEquals(View.VISIBLE, peek.visibility)
        assertEquals("the next category waits flush right of the page", grid.width() - quarter, peek.translationX, 0f)
        assertEquals(0, peek.scrollY)
        val shown = (SymbolsView.ROWS + 1) * panel.gridColumnCountForTest()
        assertEquals("the neighbour is really bound to the next category's first rows", next.take(shown), panel.peekCellTextsForTest())
        val underline = requireNotNull(rail.underlineBoundsForTest())
        assertEquals(tabs.getValue(5).left + (tabs.getValue(6).left - tabs.getValue(5).left) * 0.25f, underline.left, 0.01f)
        assertEquals(tabs.getValue(5).right + (tabs.getValue(6).right - tabs.getValue(5).right) * 0.25f, underline.right, 0.01f)
        assertEquals("labels keep their colours until the page switches", light.keyLabel, tabs.getValue(5).currentTextColor)
        assertEquals(light.keyLabelSecondary, tabs.getValue(6).currentTextColor)

        finger.move(grid.exactCenterX() + quarter, grid.exactCenterY())
        layout(panel)
        assertTrue(viewport.translationX > 0f)
        assertEquals(viewport.translationX - grid.width(), peek.translationX, 0f)
        assertEquals("dragging right shows the previous category", previous.take(shown), panel.peekCellTextsForTest())
        assertEquals("the net page keeps its wide keys in the neighbour", listOf(2, 2, 1), panel.peekTileSpansForTest().take(3))
        assertTrue(requireNotNull(rail.underlineBoundsForTest()).left < tabs.getValue(5).left)
        assertEquals(5, panel.selectedCategoryForTest())
        finger.up(grid.exactCenterX() + quarter, grid.exactCenterY(), 400L)
        frames(panel)
        assertEquals(5, panel.selectedCategoryForTest())
    }

    @Test fun symbols_a_short_release_snaps_back_keeping_the_category_and_its_scroll() = hosted(create = { symbols(it) }) { panel ->
        panel.open(5)
        val viewport = panel.viewport()
        viewport.scrollTo(0, maxScroll(viewport) / 2)
        val parked = viewport.scrollY
        assertTrue(parked > 0)
        val grid = panel.grid()
        Finger(panel).slowSwipe(grid.exactCenterX() + 60f, grid.exactCenterY(), -grid.width() * 0.3f, slop)
        frames(panel)
        assertEquals(5, panel.selectedCategoryForTest())
        assertEquals("a snap back keeps the category where it was", parked, viewport.scrollY)
        assertEquals(0f, viewport.translationX, 0f)
        assertEquals(View.GONE, panel.peekViewportForTest().visibility)
        val rail = panel.categoryRailForTest()
        assertEquals(0f, rail.pageOffset, 0f)
        assertEquals(panel.railTabForTest(5).left.toFloat(), requireNotNull(rail.underlineBoundsForTest()).left, 0f)
    }

    @Test fun symbols_a_flick_pages_from_a_short_drag() = hosted(create = { symbols(it) }) { panel ->
        panel.open(5)
        val grid = panel.grid()
        Finger(panel).flick(grid.exactCenterX(), grid.exactCenterY(), -60f, slop)
        frames(panel)
        assertEquals(6, panel.selectedCategoryForTest())
        Finger(panel).flick(grid.exactCenterX(), grid.exactCenterY(), 60f, slop)
        frames(panel)
        assertEquals(5, panel.selectedCategoryForTest())
    }

    @Test fun symbols_the_first_and_last_categories_do_not_follow_toward_a_missing_neighbour() =
        hosted(create = { symbols(it, recents = listOf("★", "→", "✓")) }) { panel ->
            val typed = mutableListOf<String>()
            panel.onSymbol = { symbol, _ -> typed += symbol }
            val viewport = panel.viewport()
            val (x, y) = panel.tileCenter("★")
            val finger = Finger(panel)
            finger.down(x, y)
            finger.move(x + slop + 80f, y)
            layout(panel)
            assertTrue("the sideways drag is still taken from the tile", panel.pagerForTest().draggingForTest())
            assertEquals("常用 does not follow a drag toward nothing", 0f, viewport.translationX, 0f)
            assertEquals(View.GONE, panel.peekViewportForTest().visibility)
            finger.move(x + slop + 200f, y)
            assertEquals(0f, viewport.translationX, 0f)
            finger.up(x + slop + 260f, y, 8L)
            frames(panel)
            assertEquals(0, panel.selectedCategoryForTest())
            assertTrue("the sideways drag never types the pressed symbol", typed.isEmpty())

            panel.open(lastSymbolCategory)
            val grid = panel.grid()
            finger.down(grid.exactCenterX(), grid.exactCenterY())
            finger.move(grid.exactCenterX() - slop - 80f, grid.exactCenterY())
            layout(panel)
            assertTrue(panel.pagerForTest().draggingForTest())
            assertEquals("the last category does not follow a drag toward nothing", 0f, viewport.translationX, 0f)
            assertEquals(View.GONE, panel.peekViewportForTest().visibility)
            finger.up(grid.exactCenterX() - slop - 200f, grid.exactCenterY(), 8L)
            frames(panel)
            assertEquals(lastSymbolCategory, panel.selectedCategoryForTest())
            assertTrue(typed.isEmpty())
        }

    @Test fun symbols_a_wobble_short_of_the_lock_types_the_symbol_and_closes_the_panel() = hosted(create = { symbols(it) }) { panel ->
        var typed: String? = null
        var closes = 0
        panel.onSymbol = { symbol, _ -> typed = symbol }
        panel.onBack = { closes++ }
        panel.open(5)
        val symbol = panel.gridCellTextsForTest().first()
        val (x, y) = panel.tileCenter(symbol)
        val finger = Finger(panel)
        finger.down(x, y)
        finger.move(x + slop / 2f, y)
        finger.move(x - slop / 2f, y + 1f)
        finger.up(x - slop / 2f, y + 1f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(symbol, typed)
        assertEquals("an unlocked panel still closes after the tap", 1, closes)
        assertEquals(5, panel.selectedCategoryForTest())
        assertEquals(0f, panel.viewport().translationX, 0f)
    }

    @Test fun symbols_locking_the_page_drag_cancels_the_pressed_tile_without_typing_or_long_press() =
        hosted(create = { symbols(it, recents = listOf("★", "→")) }) { panel ->
            val typed = mutableListOf<String>()
            panel.onSymbol = { symbol, _ -> typed += symbol }
            val tile = requireNotNull(panel.gridCellForTest("★"))
            val (x, y) = panel.tileCenter("★")
            val finger = Finger(panel)
            finger.down(x, y)
            finger.hold(ViewConfiguration.getTapTimeout() + 10L)
            assertTrue("precondition: the tile shows its press", tile.isPressed)
            assertTrue(panel.gridCellFeedbackLevelForTest("★") > 0f)
            finger.move(x - slop - 12f, y)
            assertTrue(panel.pagerForTest().draggingForTest())
            assertFalse("the page drag withdraws the press", tile.isPressed)
            finger.hold(ViewConfiguration.getLongPressTimeout() + 100L)
            assertFalse("the page drag withdraws the long-press delete", panel.clearDialogVisibleForTest())
            finger.up(x - slop - 12f, y, 10L)
            frames(panel)
            assertTrue("the page drag never types", typed.isEmpty())
            assertEquals(0f, panel.gridCellFeedbackLevelForTest("★"), 0f)
            assertEquals(0, panel.selectedCategoryForTest())
        }

    @Test fun symbols_a_delete_confirmation_blocks_paging() = hosted(create = { symbols(it, recents = listOf("★", "→")) }) { panel ->
        val typed = mutableListOf<String>()
        panel.onSymbol = { symbol, _ -> typed += symbol }
        val viewport = panel.viewport()
        val (x, y) = panel.tileCenter("★")
        val finger = Finger(panel)
        finger.down(x, y)
        finger.hold(ViewConfiguration.getLongPressTimeout() + 100L)
        assertTrue("precondition: the long press raised the delete confirmation", panel.clearDialogVisibleForTest())
        finger.move(x - slop - 150f, y)
        layout(panel)
        assertFalse(panel.pagerForTest().draggingForTest())
        assertEquals("the page never follows while the confirmation is up", 0f, viewport.translationX, 0f)
        assertEquals(View.GONE, panel.peekViewportForTest().visibility)
        finger.up(x - slop - 200f, y, 8L)
        frames(panel)
        assertEquals(0, panel.selectedCategoryForTest())
        assertTrue(panel.clearDialogVisibleForTest())
        assertTrue(typed.isEmpty())
        assertTrue(panel.cancelClearForTest())

        assertTrue(panel.clearBtnForTest().performClick())
        assertTrue(panel.clearDialogVisibleForTest())
        layout(panel)
        val grid = panel.grid()
        Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.8f, slop)
        frames(panel)
        assertEquals("a drag over the open confirmation never pages", 0, panel.selectedCategoryForTest())
        assertEquals(0f, viewport.translationX, 0f)
        assertTrue(panel.clearDialogVisibleForTest())
    }

    @Test fun symbols_a_vertical_scroll_never_turns_into_paging() = hosted(create = { symbols(it) }) { panel ->
        panel.open(5)
        val viewport = panel.viewport()
        val grid = panel.grid()
        val x = grid.exactCenterX()
        val y = grid.bottom - 20f
        val finger = Finger(panel)
        finger.down(x, y)
        finger.move(x, y - slop - 20f)
        finger.move(x, y - slop - 50f)
        assertTrue("precondition: the grid scrolls vertically", viewport.scrollY > 0)
        finger.move(x - 150f, y - slop - 50f)
        finger.move(x - 300f, y - slop - 50f)
        layout(panel)
        assertFalse(panel.pagerForTest().draggingForTest())
        assertEquals(0f, viewport.translationX, 0f)
        assertEquals(View.GONE, panel.peekViewportForTest().visibility)
        finger.up(x - 300f, y - slop - 50f)
        frames(panel)
        assertEquals(5, panel.selectedCategoryForTest())
    }

    @Test fun symbols_a_touch_that_stops_a_fling_can_page_and_the_new_category_rests_at_its_top() =
        hosted(create = { symbols(it) }) { panel ->
            panel.open(5)
            val viewport = panel.viewport()
            viewport.scrollTo(0, 0)
            viewport.fling(9000)
            frames(panel, 3) { viewport.computeScroll() }
            assertTrue("precondition: the grid is flinging", viewport.scrollY > 0)
            val grid = panel.grid()
            Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.6f, slop)
            frames(panel) { viewport.computeScroll() }
            assertEquals("a fling-stopping touch still pages", 6, panel.selectedCategoryForTest())
            assertEquals(0, viewport.scrollY)
            frames(panel, 30) {
                viewport.computeScroll()
                assertEquals("the stopped fling never moves the new category", 0, viewport.scrollY)
            }
        }

    @Test fun symbols_the_url_chip_bar_pages_with_the_grid() =
        hosted(create = { symbols(it, recents = listOf("www.", ".com", "★", "→")) }) { panel ->
            val typed = mutableListOf<String>()
            panel.onSymbol = { symbol, _ -> typed += symbol }
            assertTrue("precondition: 常用 shows its URL chips", panel.chipBarVisibleForTest())
            val grid = panel.grid()
            val chipY = grid.top + panel.cellHeightForTest() / 2f + 2f
            Finger(panel).slowSwipe(grid.left + 20f, chipY, -grid.width() * 0.6f, slop)
            frames(panel)
            assertEquals("a drag that starts on a chip pages the whole 常用 page", 1, panel.selectedCategoryForTest())
            assertTrue("the chip under the finger never types", typed.isEmpty())

            val finger = Finger(panel)
            finger.down(grid.exactCenterX(), grid.exactCenterY())
            finger.move(grid.exactCenterX() + slop + 60f, grid.exactCenterY())
            layout(panel)
            assertEquals("the 常用 neighbour carries its chips", listOf("www.", ".com"), panel.peekChipTextsForTest())
            assertEquals(listOf("★", "→"), panel.peekCellTextsForTest())
            finger.up(grid.exactCenterX() + slop + 60f, grid.exactCenterY(), 400L)
            frames(panel)
            assertEquals(1, panel.selectedCategoryForTest())
        }

    @Test fun symbols_tapping_a_tab_mid_drag_cancels_the_drag() = hosted(create = { symbols(it) }) { panel ->
        panel.open(5)
        val viewport = panel.viewport()
        val grid = panel.grid()
        val finger = Finger(panel)
        finger.down(grid.exactCenterX(), grid.exactCenterY())
        finger.move(grid.exactCenterX() - slop - 80f, grid.exactCenterY())
        assertEquals(-80f, viewport.translationX, 0f)
        assertTrue(panel.railTabForTest(3).performClick())
        assertEquals(3, panel.selectedCategoryForTest())
        assertEquals(0f, viewport.translationX, 0f)
        assertEquals(View.GONE, panel.peekViewportForTest().visibility)
        assertEquals(0f, panel.categoryRailForTest().pageOffset, 0f)
        finger.move(grid.left - 100f, grid.exactCenterY())
        assertEquals("the cancelled drag no longer moves the page", 0f, viewport.translationX, 0f)
        finger.up(grid.left - 100f, grid.exactCenterY(), 8L)
        frames(panel)
        assertEquals(3, panel.selectedCategoryForTest())
    }

    @Test fun symbols_a_sideways_drag_on_the_category_bar_only_scrolls_the_bar() = hosted(create = { symbols(it) }) { panel ->
        panel.open(5)
        val bar = panel.categoryBarForTest() as HorizontalScrollView
        val box = frameOf(panel, bar)
        Finger(panel).slowSwipe(box.exactCenterX() + 100f, box.exactCenterY(), -200f, slop)
        frames(panel) { bar.computeScroll() }
        assertTrue("the category bar scrolls under the drag", bar.scrollX > 0)
        assertEquals("the drag never pages the grid", 5, panel.selectedCategoryForTest())
        assertEquals(0f, panel.viewport().translationX, 0f)
        assertEquals(View.GONE, panel.peekViewportForTest().visibility)
        assertEquals(0f, panel.categoryRailForTest().pageOffset, 0f)
    }

    @Test fun symbols_a_reset_mid_drag_or_mid_settle_leaves_no_trace() = hosted(create = { symbols(it) }) { panel ->
        panel.open(5)
        val viewport = panel.viewport()
        val grid = panel.grid()
        val finger = Finger(panel)
        finger.down(grid.exactCenterX(), grid.exactCenterY())
        finger.move(grid.exactCenterX() - slop - 80f, grid.exactCenterY())
        panel.resetToDefault()
        assertEquals(0, panel.selectedCategoryForTest())
        assertEquals(0f, viewport.translationX, 0f)
        assertEquals(View.GONE, panel.peekViewportForTest().visibility)
        assertEquals(0f, panel.categoryRailForTest().pageOffset, 0f)
        finger.up(grid.left - 100f, grid.exactCenterY(), 8L)
        frames(panel)
        assertEquals(0, panel.selectedCategoryForTest())

        panel.open(5)
        Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.6f, slop)
        assertTrue("precondition: the page is settling", panel.pagerForTest().settlingForTest())
        panel.resetToDefault()
        frames(panel)
        assertEquals("a reset drops the settle instead of landing it", 0, panel.selectedCategoryForTest())
        assertEquals(0f, viewport.translationX, 0f)
    }

    @Test fun symbols_a_landed_page_scrolls_its_tab_fully_into_the_category_bar() = hosted(create = { symbols(it) }) { panel ->
        val bar = panel.categoryBarForTest() as HorizontalScrollView
        val lastShown = (0 until lastSymbolCategory).last { panel.railTabForTest(it).right <= bar.width }
        assertTrue("precondition: the next tab starts off screen", panel.railTabForTest(lastShown + 1).right > bar.width)
        panel.open(lastShown)
        assertEquals("a tap leaves the bar where it is", 0, bar.scrollX)
        val grid = panel.grid()
        Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.6f, slop)
        frames(panel, 40) { bar.computeScroll() }
        assertEquals(lastShown + 1, panel.selectedCategoryForTest())
        val tab = panel.railTabForTest(lastShown + 1)
        assertTrue("the bar scrolled", bar.scrollX > 0)
        assertTrue("the landed tab is fully visible", tab.left >= bar.scrollX && tab.right <= bar.scrollX + bar.width)
    }

    @Test fun symbols_reduced_motion_switches_on_release() = hosted(animated = false, create = { symbols(it) }) { panel ->
        panel.open(5)
        val viewport = panel.viewport()
        viewport.scrollTo(0, maxScroll(viewport))
        val grid = panel.grid()
        Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.6f, slop)
        assertEquals("reduced motion lands the page on release", 6, panel.selectedCategoryForTest())
        assertEquals(0, viewport.scrollY)
        assertEquals(0f, viewport.translationX, 0f)
        assertFalse(panel.pagerForTest().settlingForTest())
    }

    @Test fun symbols_the_neighbour_pool_stays_bounded_across_repeated_drag_sweeps() =
        hosted(create = { symbols(it, recents = (1..30).map { index -> "S$index" }) }) { panel ->
            val grid = panel.grid()
            fun sweep() {
                repeat(lastSymbolCategory) {
                    Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.6f, slop)
                    frames(panel)
                }
                assertEquals(lastSymbolCategory, panel.selectedCategoryForTest())
                repeat(lastSymbolCategory) {
                    Finger(panel).slowSwipe(grid.exactCenterX() - 100f, grid.exactCenterY(), grid.width() * 0.6f, slop)
                    frames(panel)
                }
                assertEquals(0, panel.selectedCategoryForTest())
            }
            sweep()
            val pageTiles = panel.tilesAllocatedForTest()
            val peekTiles = panel.peekTilesAllocatedForTest()
            sweep()
            assertEquals("a second drag sweep allocates no new page tiles", pageTiles, panel.tilesAllocatedForTest())
            assertEquals("a second drag sweep allocates no new neighbour tiles", peekTiles, panel.peekTilesAllocatedForTest())
            assertTrue(
                "the neighbour never binds more than one row past the visible rows",
                peekTiles <= (SymbolsView.ROWS + 1) * panel.gridColumnCountForTest(),
            )
            val tapped = SymbolsView(ctx).apply {
                recentProvider = { (1..30).map { index -> "S$index" } }
                applyPalette(light)
            }
            for (index in 0..lastSymbolCategory) tapped.openCategoryForTest(index)
            assertEquals("dragging keeps the page pool at the tap-sweep peak", tapped.tilesAllocatedForTest(), pageTiles)
        }

    private fun emoji(activity: Activity, recents: List<String> = emptyList()) = EmojiView(activity).apply {
        recentProvider = { recents }
        applyPalette(light)
        refresh()
    }

    private fun EmojiView.open(index: Int) {
        openCategoryForTest(index)
        frames(this)
    }

    private fun EmojiView.viewport(): ScrollView = gridViewportForTest() as ScrollView

    private fun EmojiView.grid(): Rect = frameOf(this, viewport())

    private fun EmojiView.cellCenter(index: Int): Pair<Float, Float> =
        bounds(this, requireNotNull(gridCellForTest(index))).let { it.exactCenterX() to it.exactCenterY() }

    private val lastEmojiCategory = EmojiCatalog.categories.size
    private val hand = EmojiCatalog.categories.indexOfFirst { it.id == "hand" } + 1

    @Test fun emoji_a_drag_left_past_half_pages_to_the_next_category_from_its_top() = hosted(create = { emoji(it) }) { panel ->
        panel.open(3)
        val next = panel.gridCellTextsForTest()
        panel.open(2)
        val viewport = panel.viewport()
        viewport.scrollTo(0, maxScroll(viewport))
        assertTrue("precondition: the outgoing category is parked away from its top", viewport.scrollY > 0)
        val grid = panel.grid()
        Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.6f, slop)
        assertEquals(2, panel.selectedCategoryForTest())
        frames(panel)
        assertEquals(3, panel.selectedCategoryForTest())
        assertEquals(next, panel.gridCellTextsForTest())
        assertEquals(0, viewport.scrollY)
        assertEquals(0f, viewport.translationX, 0f)
        assertEquals(View.GONE, panel.peekViewportForTest().visibility)
        assertEquals(3, panel.categoryRailForTest().selectedIndex)
        assertEquals(0f, panel.categoryRailForTest().pageOffset, 0f)
        assertEquals(light.keyLabel, panel.railTabForTest(3).currentTextColor)
        assertEquals(light.keyLabelSecondary, panel.railTabForTest(2).currentTextColor)

        Finger(panel).slowSwipe(grid.exactCenterX() - 100f, grid.exactCenterY(), grid.width() * 0.6f, slop)
        frames(panel)
        assertEquals("dragging right pages back", 2, panel.selectedCategoryForTest())
        assertEquals(0, viewport.scrollY)
    }

    @Test fun emoji_the_neighbour_shows_the_glyph_filtered_first_rows_while_the_underline_follows() =
        hosted(create = { emoji(it) }) { panel ->
            panel.open(3)
            val viewport = panel.viewport()
            val grid = panel.grid()
            val quarter = grid.width() / 4f
            val shown = (EmojiView.ROWS + 1) * panel.gridColumnCountForTest()
            val finger = Finger(panel)
            finger.down(grid.exactCenterX(), grid.exactCenterY())
            finger.move(grid.exactCenterX() - slop - quarter, grid.exactCenterY())
            layout(panel)
            assertEquals(-quarter, viewport.translationX, 0f)
            assertEquals(View.VISIBLE, panel.peekViewportForTest().visibility)
            assertEquals(
                "the neighbour binds the next category's supported glyphs",
                EmojiCatalog.supported[3].emoji.take(shown),
                panel.peekCellTextsForTest(),
            )
            val rail = panel.categoryRailForTest()
            val from = panel.railTabForTest(3)
            val to = panel.railTabForTest(4)
            assertEquals(from.left + (to.left - from.left) * 0.25f, requireNotNull(rail.underlineBoundsForTest()).left, 0.01f)
            assertEquals(light.keyLabel, from.currentTextColor)
            finger.move(grid.exactCenterX() + quarter, grid.exactCenterY())
            assertEquals(EmojiCatalog.supported[1].emoji.take(shown), panel.peekCellTextsForTest())
            finger.up(grid.exactCenterX() + quarter, grid.exactCenterY(), 400L)
            frames(panel)
            assertEquals(3, panel.selectedCategoryForTest())
        }

    @Test fun emoji_the_first_and_last_categories_do_not_follow_toward_a_missing_neighbour() =
        hosted(create = { emoji(it, recents = listOf("🙂", "👍")) }) { panel ->
            val committed = mutableListOf<String>()
            panel.onEmoji = { committed += it }
            val viewport = panel.viewport()
            val (x, y) = panel.cellCenter(0)
            val finger = Finger(panel)
            finger.down(x, y)
            finger.move(x + slop + 80f, y)
            layout(panel)
            assertTrue(panel.pagerForTest().draggingForTest())
            assertEquals(0f, viewport.translationX, 0f)
            assertEquals(View.GONE, panel.peekViewportForTest().visibility)
            finger.up(x + slop + 200f, y, 8L)
            frames(panel)
            assertEquals(0, panel.selectedCategoryForTest())
            assertTrue(committed.isEmpty())

            panel.open(lastEmojiCategory)
            val grid = panel.grid()
            finger.down(grid.exactCenterX(), grid.exactCenterY())
            finger.move(grid.exactCenterX() - slop - 80f, grid.exactCenterY())
            layout(panel)
            assertTrue(panel.pagerForTest().draggingForTest())
            assertEquals(0f, viewport.translationX, 0f)
            finger.up(grid.exactCenterX() - slop - 200f, grid.exactCenterY(), 8L)
            frames(panel)
            assertEquals(lastEmojiCategory, panel.selectedCategoryForTest())
            assertTrue(committed.isEmpty())
        }

    @Test fun emoji_a_wobble_short_of_the_lock_commits_and_closes_the_panel() = hosted(create = { emoji(it) }) { panel ->
        val committed = mutableListOf<String>()
        var closes = 0
        panel.onEmoji = { committed += it }
        panel.onBack = { closes++ }
        panel.open(3)
        val (x, y) = panel.cellCenter(0)
        val finger = Finger(panel)
        finger.down(x, y)
        finger.move(x + slop / 2f, y)
        finger.up(x + slop / 2f, y)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(panel.gridCellTextsForTest().first()), committed)
        assertEquals(1, closes)
        assertEquals(3, panel.selectedCategoryForTest())
    }

    @Test fun emoji_locking_the_page_drag_cancels_the_pressed_cell_without_committing_or_opening_variants() =
        hosted(create = { emoji(it) }) { panel ->
            val committed = mutableListOf<String>()
            panel.onEmoji = { committed += it }
            panel.open(hand)
            val wave = panel.gridCellTextsForTest().indexOf("👋")
            assertTrue("fixture present", wave >= 0)
            val cell = requireNotNull(panel.gridCellForTest(wave))
            val (x, y) = panel.cellCenter(wave)
            val finger = Finger(panel)
            finger.down(x, y)
            finger.hold(ViewConfiguration.getTapTimeout() + 10L)
            assertTrue(cell.isPressed)
            finger.move(x - slop - 12f, y)
            assertTrue(panel.pagerForTest().draggingForTest())
            assertFalse(cell.isPressed)
            finger.hold(ViewConfiguration.getLongPressTimeout() + 100L)
            assertFalse("the page drag withdraws the skin-tone long press", panel.variantVisibleForTest())
            finger.up(x - slop - 12f, y, 10L)
            frames(panel)
            assertTrue(committed.isEmpty())
            assertEquals(0f, panel.gridCellFeedbackLevelForTest(wave), 0f)
            assertEquals(hand, panel.selectedCategoryForTest())
        }

    @Test fun emoji_a_variant_popup_blocks_paging_while_it_holds_or_covers_the_grid() = hosted(create = { emoji(it) }) { panel ->
        panel.open(hand)
        val viewport = panel.viewport()
        val wave = panel.gridCellTextsForTest().indexOf("👋")
        val (x, y) = panel.cellCenter(wave)
        val finger = Finger(panel)
        finger.down(x, y)
        finger.hold(ViewConfiguration.getLongPressTimeout() + 100L)
        assertTrue("precondition: the long press opened the skin tones", panel.variantVisibleForTest())
        finger.move(x - slop - 150f, y)
        layout(panel)
        assertFalse("the popup keeps the pointer it opened under", panel.pagerForTest().draggingForTest())
        assertEquals(0f, viewport.translationX, 0f)
        finger.up(x - slop - 200f, y, 8L)
        frames(panel)
        assertEquals(hand, panel.selectedCategoryForTest())
        assertTrue("the popup stays open after the finger lifts", panel.variantVisibleForTest())

        val grid = panel.grid()
        Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.6f, slop)
        frames(panel)
        assertEquals("a drag over the open popup only dismisses it", hand, panel.selectedCategoryForTest())
        assertFalse(panel.variantVisibleForTest())
        assertEquals(0f, viewport.translationX, 0f)
    }

    @Test fun emoji_a_delete_confirmation_blocks_paging() = hosted(create = { emoji(it, recents = listOf("🙂", "👍")) }) { panel ->
        val (x, y) = panel.cellCenter(0)
        val finger = Finger(panel)
        finger.down(x, y)
        finger.hold(ViewConfiguration.getLongPressTimeout() + 100L)
        assertTrue(panel.clearDialogVisibleForTest())
        finger.move(x - slop - 150f, y)
        layout(panel)
        assertFalse(panel.pagerForTest().draggingForTest())
        assertEquals(0f, panel.viewport().translationX, 0f)
        finger.up(x - slop - 200f, y, 8L)
        frames(panel)
        assertEquals(0, panel.selectedCategoryForTest())
        assertTrue(panel.clearDialogVisibleForTest())
    }

    @Test fun emoji_a_vertical_scroll_never_turns_into_paging() = hosted(create = { emoji(it) }) { panel ->
        panel.open(2)
        val viewport = panel.viewport()
        val grid = panel.grid()
        val x = grid.exactCenterX()
        val y = grid.bottom - 20f
        val finger = Finger(panel)
        finger.down(x, y)
        finger.move(x, y - slop - 20f)
        finger.move(x, y - slop - 50f)
        assertTrue(viewport.scrollY > 0)
        finger.move(x - 300f, y - slop - 50f)
        assertFalse(panel.pagerForTest().draggingForTest())
        assertEquals(0f, viewport.translationX, 0f)
        finger.up(x - 300f, y - slop - 50f)
        frames(panel)
        assertEquals(2, panel.selectedCategoryForTest())
    }

    @Test fun emoji_a_touch_that_stops_a_fling_can_page_and_the_new_category_rests_at_its_top() =
        hosted(create = { emoji(it) }) { panel ->
            panel.open(2)
            val viewport = panel.viewport()
            viewport.fling(9000)
            frames(panel, 3) { viewport.computeScroll() }
            assertTrue(viewport.scrollY > 0)
            val grid = panel.grid()
            Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.6f, slop)
            frames(panel) { viewport.computeScroll() }
            assertEquals(3, panel.selectedCategoryForTest())
            frames(panel, 30) {
                viewport.computeScroll()
                assertEquals(0, viewport.scrollY)
            }
        }

    @Test fun emoji_a_tab_tap_or_a_reset_drops_a_drag_in_progress() = hosted(create = { emoji(it) }) { panel ->
        panel.open(3)
        val viewport = panel.viewport()
        val grid = panel.grid()
        val finger = Finger(panel)
        finger.down(grid.exactCenterX(), grid.exactCenterY())
        finger.move(grid.exactCenterX() - slop - 80f, grid.exactCenterY())
        assertEquals(-80f, viewport.translationX, 0f)
        assertTrue(panel.railTabForTest(5).performClick())
        assertEquals(5, panel.selectedCategoryForTest())
        assertEquals(0f, viewport.translationX, 0f)
        assertEquals(0f, panel.categoryRailForTest().pageOffset, 0f)
        finger.up(grid.left - 100f, grid.exactCenterY(), 8L)
        frames(panel)
        assertEquals(5, panel.selectedCategoryForTest())

        finger.down(grid.exactCenterX(), grid.exactCenterY())
        finger.move(grid.exactCenterX() - slop - 80f, grid.exactCenterY())
        panel.resetToDefault()
        assertEquals(0, panel.selectedCategoryForTest())
        assertEquals(0f, viewport.translationX, 0f)
        assertEquals(View.GONE, panel.peekViewportForTest().visibility)
        finger.up(grid.left - 100f, grid.exactCenterY(), 8L)
        frames(panel)
        assertEquals(0, panel.selectedCategoryForTest())
    }

    @Test fun emoji_a_sideways_drag_on_the_category_bar_only_scrolls_the_bar() = hosted(create = { emoji(it) }) { panel ->
        panel.open(3)
        val bar = panel.categoryBarForTest() as HorizontalScrollView
        val box = frameOf(panel, bar)
        Finger(panel).slowSwipe(box.exactCenterX() + 100f, box.exactCenterY(), -200f, slop)
        frames(panel) { bar.computeScroll() }
        assertTrue("the category bar scrolls under the drag", bar.scrollX > 0)
        assertEquals("the drag never pages the grid", 3, panel.selectedCategoryForTest())
        assertEquals(0f, panel.viewport().translationX, 0f)
        assertEquals(View.GONE, panel.peekViewportForTest().visibility)
        assertEquals(0f, panel.categoryRailForTest().pageOffset, 0f)
    }

    @Test fun emoji_a_landed_page_scrolls_its_tab_fully_into_the_category_bar() = hosted(create = { emoji(it) }) { panel ->
        val bar = panel.categoryBarForTest() as HorizontalScrollView
        val lastShown = (0 until lastEmojiCategory).last { panel.railTabForTest(it).right <= bar.width }
        assertTrue("precondition: the next tab starts off screen", panel.railTabForTest(lastShown + 1).right > bar.width)
        panel.open(lastShown)
        val grid = panel.grid()
        Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.6f, slop)
        frames(panel, 40) { bar.computeScroll() }
        assertEquals(lastShown + 1, panel.selectedCategoryForTest())
        val tab = panel.railTabForTest(lastShown + 1)
        assertTrue(bar.scrollX > 0)
        assertTrue(tab.left >= bar.scrollX && tab.right <= bar.scrollX + bar.width)
    }

    @Test fun emoji_the_neighbour_pool_stays_bounded_across_repeated_drag_sweeps() = hosted(create = { emoji(it) }) { panel ->
        val grid = panel.grid()
        fun sweep() {
            repeat(lastEmojiCategory) {
                Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.6f, slop)
                frames(panel)
            }
            assertEquals(lastEmojiCategory, panel.selectedCategoryForTest())
            repeat(lastEmojiCategory) {
                Finger(panel).slowSwipe(grid.exactCenterX() - 100f, grid.exactCenterY(), grid.width() * 0.6f, slop)
                frames(panel)
            }
            assertEquals(0, panel.selectedCategoryForTest())
        }
        sweep()
        val pageCells = panel.emojiCellsAllocatedForTest()
        val peekCells = panel.peekCellsAllocatedForTest()
        sweep()
        assertEquals(pageCells, panel.emojiCellsAllocatedForTest())
        assertEquals(peekCells, panel.peekCellsAllocatedForTest())
        assertEquals(
            "the page pool still tops out at the largest category",
            EmojiCatalog.supported.maxOf { it.emoji.size },
            pageCells,
        )
        assertTrue(peekCells <= (EmojiView.ROWS + 1) * panel.gridColumnCountForTest())
    }

    @Test fun emoji_reduced_motion_switches_on_release() = hosted(animated = false, create = { emoji(it) }) { panel ->
        panel.open(2)
        val viewport = panel.viewport()
        viewport.scrollTo(0, maxScroll(viewport))
        val grid = panel.grid()
        Finger(panel).slowSwipe(grid.exactCenterX() + 100f, grid.exactCenterY(), -grid.width() * 0.6f, slop)
        assertEquals(3, panel.selectedCategoryForTest())
        assertEquals(0, viewport.scrollY)
        assertFalse(panel.pagerForTest().settlingForTest())
    }
}
