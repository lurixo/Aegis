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
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class BackspaceBubbleTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val swipeThreshold = BackspaceGesture.SWIPE_THRESHOLD_DP * density
    private fun dp(value: Int) = (value * density).toInt()

    @Test fun the_first_crossing_locks_the_swipe_direction_for_the_whole_press() {
        val g = BackspaceGesture(density)
        assertNull(g.swipeDirectionUp)
        g.begin(100f, 300f)
        assertNull("no direction before the threshold", g.swipeDirectionUp)
        g.move(100f, 300f - (swipeThreshold + 10f), true)
        assertEquals(true, g.swipeDirectionUp)
        g.move(100f, 300f + (swipeThreshold + 10f), true)
        assertEquals("a reversal never swaps the hint for the other one", true, g.swipeDirectionUp)
        assertFalse("but the far side is not the side it fires from", g.swipeArmed)
        g.move(100f, 300f - (swipeThreshold + 10f), true)
        assertTrue("coming back to the locked side arms it again", g.swipeArmed)
        g.finish()
        assertNull("letting go takes the direction away", g.swipeDirectionUp)
    }

    @Test fun a_direction_with_nothing_to_do_shows_no_hint_and_fires_nothing() {
        val g = BackspaceGesture(density)
        var fired = 0
        g.onSwipe = { fired++ }
        g.canSwipe = { up -> !up }

        g.begin(100f, 300f)
        g.move(100f, 300f - (swipeThreshold + 10f), true)

        assertNull("an empty field has nothing to clear, so it offers nothing", g.swipeDirectionUp)
        assertFalse(g.swipeArmed)
        g.finish()
        assertEquals("a hint that was never offered must not fire", 0, fired)
    }

    @Test fun the_other_direction_still_works_when_one_is_unavailable() {
        val g = BackspaceGesture(density)
        var fired: Boolean? = null
        g.onSwipe = { up -> fired = up }
        g.canSwipe = { up -> !up }

        g.begin(100f, 300f)
        g.move(100f, 300f + (swipeThreshold + 10f), true)

        assertEquals(false, g.swipeDirectionUp)
        assertTrue(g.swipeArmed)
        g.finish()
        assertEquals("the direction that has something to do still fires", false, fired)
    }

    @Test fun availability_is_settled_once_when_the_direction_locks() {
        val g = BackspaceGesture(density)
        var asked = 0
        g.canSwipe = { asked++; true }

        g.begin(100f, 300f)
        g.move(100f, 300f - (swipeThreshold + 10f), true)
        g.move(100f, 300f - (swipeThreshold + 40f), true)
        g.move(100f, 300f - (swipeThreshold + 80f), true)

        assertEquals("the field is asked once per press, not once per move", 1, asked)
    }

    @Test fun a_running_repeat_never_reports_a_direction() {
        val g = BackspaceGesture(density)
        var repeats = 0
        g.onRepeat = { repeats++ }
        g.begin(100f, 300f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertTrue("precondition: the repeat is running", repeats >= 2)
        g.move(100f, 300f - (swipeThreshold + 10f), true)
        assertNull("a repeat is never reinterpreted as a swipe hint", g.swipeDirectionUp)
        g.cancel()
    }

    @Test fun cancelling_the_gesture_takes_the_direction_away() {
        val g = BackspaceGesture(density)
        g.begin(100f, 300f)
        g.move(100f, 300f - (swipeThreshold + 10f), true)
        assertEquals(true, g.swipeDirectionUp)
        g.cancel()
        assertNull(g.swipeDirectionUp)
    }

    @Test fun sliding_back_to_the_key_leaves_the_hint_up_but_takes_its_press_away() {
        val g = BackspaceGesture(density)
        g.begin(100f, 300f)
        g.move(100f, 300f - (swipeThreshold + 10f), true)
        assertEquals(true, g.swipeDirectionUp)
        assertTrue("out past the key the hint is the one that would run", g.swipeArmed)
        g.move(100f, 300f - swipeThreshold / 2f, true)
        assertEquals("the hint stays to say which way this press is going", true, g.swipeDirectionUp)
        assertFalse("but back at the key it is no longer the pressed one", g.swipeArmed)
    }

    @Test fun lifting_back_at_the_key_runs_nothing() {
        val g = BackspaceGesture(density)
        val swipes = ArrayList<Boolean>()
        g.onSwipe = { swipes += it }
        g.begin(100f, 300f)
        g.move(100f, 300f - (swipeThreshold + 10f), true)
        g.move(100f, 300f - swipeThreshold / 2f, true)
        assertFalse("a swipe that was taken back is not a tap either", g.finish())
        assertEquals("no bubble was promising anything, so nothing runs", emptyList<Boolean>(), swipes)
    }

    @Test fun sliding_out_the_other_side_neither_shows_nor_runs_the_other_direction() {
        val g = BackspaceGesture(density)
        val swipes = ArrayList<Boolean>()
        g.onSwipe = { swipes += it }
        g.begin(100f, 300f)
        g.move(100f, 300f - (swipeThreshold + 10f), true)
        g.move(100f, 300f, true)
        assertFalse(g.swipeArmed)
        g.move(100f, 300f + (swipeThreshold + 10f), true)
        assertEquals("the locked hint never turns into the other one", true, g.swipeDirectionUp)
        assertFalse("and the far side never arms it", g.swipeArmed)
        g.finish()
        assertEquals("one press runs the direction it was showing, and it showed none", emptyList<Boolean>(), swipes)
    }

    private fun inputView(): InputView = InputView(ctx).apply {
        showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
        showCandidates(listOf("你"), "ni", emptyList())
    }

    private fun attachAndLayout(iv: InputView) {
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(iv)
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(dp(411), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(700), View.MeasureSpec.AT_MOST),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
    }

    private fun InputView.send(action: Int, x: Float, y: Float, t: Long) {
        val event = MotionEvent.obtain(0, t, action, x, y, 0)
        try {
            dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    @Test fun swiping_on_the_keyboard_backspace_draws_the_bubble_beside_the_key() {
        val iv = inputView()
        attachAndLayout(iv)
        assertNull(iv.backspaceBubbleBoundsForTest())
        val key = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.BACKSPACE))
        iv.send(MotionEvent.ACTION_DOWN, key.centerX(), key.centerY(), 0)
        iv.send(MotionEvent.ACTION_MOVE, key.centerX(), key.centerY() - (swipeThreshold + 15f), 16)

        assertEquals(true, iv.backspaceBubbleDirectionUpForTest())
        val clearBubble = requireNotNull(iv.backspaceBubbleBoundsForTest())
        assertTrue("the clear bubble sits above the backspace key", clearBubble.bottom <= key.top)
        assertTrue(
            "the bubble spans the key it hangs over",
            clearBubble.left <= key.centerX() && key.centerX() <= clearBubble.right,
        )
        assertTrue("an edge key clamps the bubble back inside the view", clearBubble.right <= iv.width)
        assertTrue(clearBubble.left >= 0f)

        iv.send(MotionEvent.ACTION_MOVE, key.centerX(), key.centerY() + (swipeThreshold + 15f), 32)
        assertEquals("reversing the finger never shows the other hint", true, iv.backspaceBubbleDirectionUpForTest())
        assertFalse("and off its own side the bubble is not the pressed one", iv.backspaceBubbleArmedForTest())
        val afterReversal = requireNotNull(iv.backspaceBubbleBoundsForTest())
        assertTrue("the clear bubble stays above the backspace key", afterReversal.bottom <= key.top)

        iv.send(MotionEvent.ACTION_UP, key.centerX(), key.centerY() + (swipeThreshold + 15f), 48)
        assertNull("letting go dismisses the bubble", iv.backspaceBubbleBoundsForTest())
    }

    @Test fun the_bubble_carries_a_full_16dp_of_padding_above_and_below_its_row() {
        val iv = inputView()
        attachAndLayout(iv)
        val key = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.BACKSPACE))
        iv.send(MotionEvent.ACTION_DOWN, key.centerX(), key.centerY(), 0)
        iv.send(MotionEvent.ACTION_MOVE, key.centerX(), key.centerY() - (swipeThreshold + 15f), 16)

        val bubble = requireNotNull(iv.backspaceBubbleBoundsForTest())
        assertTrue(
            "the bubble clears its 18dp icon by 16dp on each side, got ${bubble.height()}",
            bubble.height() >= dp(16) * 2 + dp(18),
        )
        iv.send(MotionEvent.ACTION_UP, key.centerX(), key.centerY() - (swipeThreshold + 15f), 32)
    }

    @Test fun a_backspace_repeat_shows_no_bubble() {
        val iv = inputView()
        attachAndLayout(iv)
        val key = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.BACKSPACE))
        iv.send(MotionEvent.ACTION_DOWN, key.centerX(), key.centerY(), 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertNull("holding for repeats never grows a bubble", iv.backspaceBubbleBoundsForTest())
        iv.send(MotionEvent.ACTION_UP, key.centerX(), key.centerY(), 500)
    }

    @Test fun letting_go_of_a_keyboard_swipe_unpresses_the_backspace_key() {
        val iv = inputView()
        attachAndLayout(iv)
        val keyboard = iv.javaClass.getDeclaredField("keyboardView").run { isAccessible = true; get(iv) }
        fun pressedKey(): Any? =
            keyboard.javaClass.getDeclaredField("pressed").run { isAccessible = true; get(keyboard) }
        val key = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.BACKSPACE))

        iv.send(MotionEvent.ACTION_DOWN, key.centerX(), key.centerY(), 0)
        iv.send(MotionEvent.ACTION_MOVE, key.centerX(), key.centerY() - (swipeThreshold + 15f), 16)
        assertNotNull("precondition: the key is held down through the swipe", pressedKey())

        iv.send(MotionEvent.ACTION_UP, key.centerX(), key.centerY() - (swipeThreshold + 15f), 32)
        assertNull("the key leaves the pressed state with the finger", pressedKey())
        assertNull("and the bubble goes with it", iv.backspaceBubbleBoundsForTest())
    }

    @Test fun a_swipe_that_only_shows_up_in_the_release_still_runs() {
        val iv = inputView()
        val swipes = ArrayList<Boolean>()
        val keys = ArrayList<KeyAction?>()
        iv.onBackspaceSwipe = { swipes += it }
        iv.onKey = { keys += it.action }
        attachAndLayout(iv)
        val key = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.BACKSPACE))

        iv.send(MotionEvent.ACTION_DOWN, key.centerX(), key.centerY(), 0)
        iv.send(MotionEvent.ACTION_UP, key.centerX(), key.centerY() - (swipeThreshold + 15f), 16)

        assertEquals("the release carries the swipe even with no move in between", listOf(true), swipes)
        assertEquals("and a swipe never doubles as a delete", emptyList<KeyAction?>(), keys)
    }

    @Test fun coming_back_onto_the_key_before_the_release_takes_the_swipe_back() {
        val iv = inputView()
        val swipes = ArrayList<Boolean>()
        val keys = ArrayList<KeyAction?>()
        iv.onBackspaceSwipe = { swipes += it }
        iv.onKey = { keys += it.action }
        attachAndLayout(iv)
        val key = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.BACKSPACE))

        iv.send(MotionEvent.ACTION_DOWN, key.centerX(), key.centerY(), 0)
        iv.send(MotionEvent.ACTION_MOVE, key.centerX(), key.centerY() - (swipeThreshold + 15f), 16)
        assertTrue("precondition: the bubble is the pressed one before the release", iv.backspaceBubbleArmedForTest())
        iv.send(MotionEvent.ACTION_UP, key.centerX(), key.centerY(), 32)

        assertEquals("letting go back on the key runs nothing", emptyList<Boolean>(), swipes)
        assertEquals("and a swipe that was taken back is not a delete either", emptyList<KeyAction?>(), keys)
    }

    @Test fun letting_go_of_a_panel_swipe_unpresses_the_delete_key() {
        val panel = EditPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(dp(411), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(290), View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val delete = requireNotNull(panel.actionViewForTest(EditAction.DELETE))
        val bounds = Rect(0, 0, delete.width, delete.height)
        panel.offsetDescendantRectToMyCoords(delete, bounds)
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()

        fun send(action: Int, px: Float, py: Float, t: Long) {
            val event = MotionEvent.obtain(0, t, action, px, py, 0)
            try {
                panel.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, x, y, 0)
        send(MotionEvent.ACTION_MOVE, x, y - (swipeThreshold + 15f), 16)
        assertTrue("precondition: the key is held down through the swipe", delete.isPressed)

        send(MotionEvent.ACTION_UP, x, y - (swipeThreshold + 15f), 32)
        assertFalse("the key leaves the pressed state with the finger", delete.isPressed)
        assertNull("and the bubble goes with it", panel.backspaceBubbleDirectionUp())
    }

    @Test fun the_edit_panel_exposes_its_delete_key_as_the_bubble_anchor() {
        val panel = EditPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        val source: BackspaceBubbleSource = panel
        assertNull(source.backspaceBubbleDirectionUp())
        assertEquals(panel.actionViewForTest(EditAction.DELETE), source.backspaceBubbleAnchor())
    }

    @Test fun a_panel_swipe_notifies_the_bound_observer_as_the_direction_changes() {
        val panel = EditPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        var pings = 0
        (panel as BackspaceBubbleSource).bindBackspaceBubbleObserver(Runnable { pings++ })
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(dp(411), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(290), View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val delete = requireNotNull(panel.actionViewForTest(EditAction.DELETE))
        val bounds = Rect(0, 0, delete.width, delete.height)
        panel.offsetDescendantRectToMyCoords(delete, bounds)
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()

        fun send(action: Int, px: Float, py: Float, t: Long) {
            val event = MotionEvent.obtain(0, t, action, px, py, 0)
            try {
                panel.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, x, y, 0)
        send(MotionEvent.ACTION_MOVE, x, y - (swipeThreshold + 15f), 16)
        assertEquals(true, panel.backspaceBubbleDirectionUp())
        assertEquals("crossing the threshold pings the observer once", 1, pings)
        send(MotionEvent.ACTION_UP, x, y - (swipeThreshold + 15f), 32)
        assertNull(panel.backspaceBubbleDirectionUp())
        assertEquals("letting go pings the observer again", 2, pings)
    }
}
