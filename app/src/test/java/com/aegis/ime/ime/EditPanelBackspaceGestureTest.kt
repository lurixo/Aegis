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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.KeyAction
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
class EditPanelBackspaceGestureTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val swipeThreshold = 24f * density

    private class Panel(
        val root: ViewGroup,
        val view: EditPanelView,
        private val width: Int,
        private val height: Int,
    ) {
        val actions = mutableListOf<EditAction>()
        val swipes = mutableListOf<Boolean>()

        fun relayout() {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
        }

        fun advance(ms: Long) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
            relayout()
        }

        fun deleteButton(): View = requireNotNull(view.actionViewForTest(EditAction.DELETE))

        fun centerOf(action: EditAction): Pair<Float, Float> {
            val target = requireNotNull(view.actionViewForTest(action))
            val bounds = Rect(0, 0, target.width, target.height)
            root.offsetDescendantRectToMyCoords(target, bounds)
            return bounds.exactCenterX() to bounds.exactCenterY()
        }

        fun send(action: Int, x: Float, y: Float, time: Long) {
            val event = MotionEvent.obtain(0, time, action, x, y, 0)
            try {
                root.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }

        fun dispatch(action: Int, time: Long, ids: IntArray, xs: FloatArray, ys: FloatArray) {
            val properties = Array(ids.size) {
                MotionEvent.PointerProperties().apply { id = ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }
            val coords = Array(ids.size) {
                MotionEvent.PointerCoords().apply { x = xs[it]; y = ys[it]; pressure = 1f; size = 1f }
            }
            val event = MotionEvent.obtain(
                0, time, action, ids.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0,
            )
            try {
                root.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun pointerDown(index: Int) =
        MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun pointerUp(index: Int) =
        MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun withPanel(heightDp: Int = 290, widthDp: Int = 411, block: (Panel) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val root = requireNotNull(controller.get().findViewById<ViewGroup>(android.R.id.content))
            val view = EditPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
            root.addView(view)
            shadowOf(Looper.getMainLooper()).idle()
            val panel = Panel(root, view, (widthDp * density).toInt(), (heightDp * density).toInt())
            panel.relayout()
            view.onAction = { panel.actions += it }
            view.onBackspaceSwipe = { panel.swipes += it }
            block(panel)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun a_tap_on_delete_emits_exactly_one_delete() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        p.advance(100)
        assertEquals("the key must not move under the finger", x to y, p.centerOf(EditAction.DELETE))
        p.send(MotionEvent.ACTION_UP, x, y, 100)

        assertEquals(listOf(EditAction.DELETE), p.actions)
        assertTrue(p.swipes.isEmpty())
    }

    @Test fun holding_delete_repeats_after_400ms_and_never_adds_one_on_release() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        p.advance(399)
        assertTrue("no repeat before 400 ms", p.actions.isEmpty())
        p.advance(1)
        assertEquals("the first repeat lands at 400 ms", 1, p.actions.size)
        p.advance(55)
        assertEquals("the repeat period is 55 ms", 2, p.actions.size)
        p.advance(55 * 4)
        val whileHeld = p.actions.size
        assertEquals(6, whileHeld)
        p.send(MotionEvent.ACTION_UP, x, y, 700)
        p.advance(200)

        assertEquals("releasing after a repeat adds no delete", whileHeld, p.actions.size)
        assertTrue(p.actions.all { it == EditAction.DELETE })
        assertTrue(p.swipes.isEmpty())
    }

    @Test fun an_up_swipe_on_delete_reports_up_and_commits_no_delete() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        p.send(MotionEvent.ACTION_MOVE, x, y - (swipeThreshold + 15f), 12)
        p.send(MotionEvent.ACTION_UP, x, y - (swipeThreshold + 15f), 24)

        assertEquals(listOf(true), p.swipes)
        assertTrue(p.actions.isEmpty())
    }

    @Test fun a_down_swipe_on_delete_reports_down_and_commits_no_delete() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        p.send(MotionEvent.ACTION_MOVE, x, y + (swipeThreshold + 15f), 12)
        p.send(MotionEvent.ACTION_UP, x, y + (swipeThreshold + 15f), 24)

        assertEquals(listOf(false), p.swipes)
        assertTrue(p.actions.isEmpty())
    }

    @Test fun a_vertical_drag_under_the_threshold_stays_a_tap() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        val drift = swipeThreshold - 2f
        assertTrue("precondition: the drift stays inside the key", drift < p.deleteButton().height / 2f)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        p.send(MotionEvent.ACTION_MOVE, x, y - drift, 12)
        p.send(MotionEvent.ACTION_UP, x, y - drift, 24)

        assertTrue("a drag shorter than 24 dp is never a swipe", p.swipes.isEmpty())
        assertEquals(listOf(EditAction.DELETE), p.actions)
    }

    @Test fun a_horizontally_dominant_drag_inside_the_key_stays_a_tap() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        val button = p.deleteButton()
        val drift = swipeThreshold + 15f
        assertTrue("precondition: the drift stays inside the key", drift < button.width / 2f)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        p.send(MotionEvent.ACTION_MOVE, x + drift, y + drift / 2f, 12)
        p.send(MotionEvent.ACTION_UP, x + drift, y + drift / 2f, 24)

        assertTrue("a horizontally dominant drag is never a swipe", p.swipes.isEmpty())
        assertEquals(listOf(EditAction.DELETE), p.actions)
    }

    @Test fun a_release_well_off_the_key_cancels_the_tap() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        val button = p.deleteButton()
        val outside = x + button.width
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        p.send(MotionEvent.ACTION_MOVE, outside, y, 12)
        p.send(MotionEvent.ACTION_UP, outside, y, 24)

        assertTrue(p.swipes.isEmpty())
        assertTrue(p.actions.isEmpty())
        assertFalse("leaving the shared key target clears its pressed state", button.isPressed)
    }

    @Test fun an_outside_first_finger_is_cancelled_before_an_inside_second_finger_takes_over() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        val button = p.deleteButton()
        val outside = x + button.width
        p.dispatch(MotionEvent.ACTION_DOWN, 0, intArrayOf(0), floatArrayOf(x), floatArrayOf(y))
        p.dispatch(MotionEvent.ACTION_MOVE, 8, intArrayOf(0), floatArrayOf(outside), floatArrayOf(y))
        p.dispatch(pointerDown(1), 16, intArrayOf(0, 1), floatArrayOf(outside, x), floatArrayOf(y, y))
        assertTrue("the first finger cannot click after it left the shared target", p.actions.isEmpty())

        p.dispatch(pointerUp(1), 32, intArrayOf(0, 1), floatArrayOf(outside, x), floatArrayOf(y, y))
        assertEquals("the inside second finger owns one tap", listOf(EditAction.DELETE), p.actions)

        p.dispatch(MotionEvent.ACTION_UP, 48, intArrayOf(0), floatArrayOf(outside), floatArrayOf(y))
        p.advance(600)

        assertEquals("the cancelled first pointer adds nothing on release", listOf(EditAction.DELETE), p.actions)
        assertTrue(p.swipes.isEmpty())
    }

    @Test fun a_swipe_after_the_repeat_started_stays_a_repeat() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        p.advance(500)
        val whileHeld = p.actions.size
        assertTrue("precondition: the repeat is running", whileHeld >= 2)
        val outsideY = y - p.deleteButton().height - swipeThreshold
        p.send(MotionEvent.ACTION_MOVE, x, outsideY, 500)
        p.advance(400)
        assertEquals("leaving the key stops the repeat", whileHeld, p.actions.size)
        p.send(MotionEvent.ACTION_UP, x, outsideY, 900)

        assertTrue("a repeat never converts into a swipe", p.swipes.isEmpty())
        assertEquals("lifting after a stopped repeat emits nothing more", whileHeld, p.actions.size)
    }

    @Test fun a_cancelled_stream_stops_the_repeat_and_emits_nothing() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        p.advance(500)
        val whileHeld = p.actions.size
        assertTrue(whileHeld >= 2)
        p.send(MotionEvent.ACTION_CANCEL, x, y, 500)
        p.advance(400)

        assertEquals("a cancel stops the repeat", whileHeld, p.actions.size)
        assertTrue(p.swipes.isEmpty())
        assertFalse("a cancel clears the pressed state", p.deleteButton().isPressed)
    }

    @Test fun detaching_the_panel_stops_a_running_repeat() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        p.advance(500)
        val whileHeld = p.actions.size
        assertTrue(whileHeld >= 2)
        p.root.removeView(p.view)
        p.advance(400)

        assertEquals(whileHeld, p.actions.size)
    }

    @Test fun reopening_the_panel_stops_a_running_repeat() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        p.advance(500)
        val whileHeld = p.actions.size
        assertTrue(whileHeld >= 2)
        p.view.resetToDefault()
        p.advance(400)

        assertEquals(whileHeld, p.actions.size)
    }

    @Test fun a_vertical_swipe_never_scrolls_the_compact_action_list() = withPanel(heightDp = 200) { p ->
        assertTrue("precondition: the compact panel scrolls", p.view.actionContentCanScrollForTest())
        val viewport = p.view.actionViewportForTest()
        val (x, y) = p.centerOf(EditAction.DELETE)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        p.send(MotionEvent.ACTION_MOVE, x, y - (swipeThreshold + 15f), 12)
        p.send(MotionEvent.ACTION_UP, x, y - (swipeThreshold + 15f), 24)

        assertEquals("the swipe must not become a scroll", 0, viewport.scrollY)
        assertEquals(listOf(true), p.swipes)
    }

    @Test fun a_second_finger_settles_the_first_gesture_before_it_takes_over() = withPanel { p ->
        val (x, y) = p.centerOf(EditAction.DELETE)
        val second = x + p.deleteButton().width / 4f
        p.dispatch(MotionEvent.ACTION_DOWN, 0, intArrayOf(0), floatArrayOf(x), floatArrayOf(y))
        p.dispatch(pointerDown(1), 16, intArrayOf(0, 1), floatArrayOf(x, second), floatArrayOf(y, y))
        assertEquals("the first finger settles as a tap", listOf(EditAction.DELETE), p.actions)
        p.dispatch(pointerUp(1), 32, intArrayOf(0, 1), floatArrayOf(x, second), floatArrayOf(y, y))
        assertEquals("the second finger settles as its own tap", 2, p.actions.size)
        p.dispatch(MotionEvent.ACTION_UP, 48, intArrayOf(0), floatArrayOf(x), floatArrayOf(y))
        p.advance(600)

        assertEquals("a settled gesture leaves no repeat behind", 2, p.actions.size)
        assertTrue(p.actions.all { it == EditAction.DELETE })
        assertTrue(p.swipes.isEmpty())
    }

    @Test fun the_accessibility_click_uses_the_same_dispatcher() = withPanel { p ->
        assertTrue(p.deleteButton().performClick())

        assertEquals(listOf(EditAction.DELETE), p.actions)
        assertTrue(p.swipes.isEmpty())
    }

    @Test fun pressing_delete_vibrates_when_key_haptics_are_enabled() = withPanel { p ->
        p.view.hapticEnabled = true
        val (x, y) = p.centerOf(EditAction.DELETE)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)

        assertEquals(
            "the panel delete performs the same KEYBOARD_TAP feedback as the keyboard",
            HapticFeedbackConstants.KEYBOARD_TAP,
            shadowOf(p.deleteButton()).lastHapticFeedbackPerformed(),
        )
        p.send(MotionEvent.ACTION_UP, x, y, 10)
    }

    @Test fun pressing_delete_does_not_vibrate_when_key_haptics_are_disabled() = withPanel { p ->
        p.view.hapticEnabled = false
        val (x, y) = p.centerOf(EditAction.DELETE)
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)

        assertEquals(
            "no feedback when the toggle is off",
            -1,
            shadowOf(p.deleteButton()).lastHapticFeedbackPerformed(),
        )
        p.send(MotionEvent.ACTION_UP, x, y, 10)
    }

    @Test fun delete_only_vibrates_on_the_press() = withPanel { p ->
        p.view.hapticEnabled = false
        val (x, y) = p.centerOf(EditAction.DELETE)
        val button = p.deleteButton()
        p.send(MotionEvent.ACTION_DOWN, x, y, 0)
        assertEquals("precondition: a press with the toggle off is silent", -1, shadowOf(button).lastHapticFeedbackPerformed())

        p.view.hapticEnabled = true
        p.advance(600)
        assertTrue("precondition: the repeat is running", p.actions.size >= 2)
        assertEquals("a repeat never buzzes", -1, shadowOf(button).lastHapticFeedbackPerformed())

        p.send(MotionEvent.ACTION_UP, x, y, 600)

        assertEquals("a release never buzzes", -1, shadowOf(button).lastHapticFeedbackPerformed())
    }

    @Test fun only_the_shared_keyboard_keys_carry_a_key_action() {
        assertEquals(
            listOf(EditAction.DELETE),
            EditAction.entries.filter { it.keyAction != null },
        )
        assertEquals(KeyAction.BACKSPACE, EditAction.DELETE.keyAction)
    }
}
