// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.ime

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.aegis.ime.ime.theme.ImePalette
import java.time.Duration
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditPanelKeyFeedbackTest {

    private val immediateActions = EditAction.entries.filter { it != EditAction.BACK }

    private fun withPanel(block: (EditPanelView) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val root = requireNotNull(activity.findViewById<ViewGroup>(android.R.id.content))
            val panel = EditPanelView(activity).apply {
                applyPalette(ImePalette.STATIC_LIGHT)
                setHasSelection(true)
            }
            root.addView(panel)
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(dp(panel, 411), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(panel, 290), View.MeasureSpec.EXACTLY),
            )
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
            block(panel)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun every_immediate_action_uses_the_keyboard_surface_press_timeline_and_haptic() = withPanel { panel ->
        val keyHaptics: KeyHapticsAware = panel
        keyHaptics.hapticEnabled = true
        val dispatched = ArrayList<EditAction>()
        panel.onAction = { dispatched += it }
        val palette = ImePalette.STATIC_LIGHT
        val pressedColor = ColorUtils.compositeColors(
            Motion.withAlpha(palette.keyLabel, 0x22),
            palette.keySurface,
        )

        for ((index, action) in immediateActions.withIndex()) {
            val key = requireNotNull(panel.actionViewForTest(action))
            assertFalse("$action must not use a platform ripple as its key face", key.background is RippleDrawable)
            assertEquals("$action has the same idle face as a normal key", palette.keySurface, faceCenter(key))
            assertEquals(
                "$action starts with no pressed layer",
                0f,
                requireNotNull(panel.actionFeedbackLevelForTest(action)),
                0f,
            )

            val time = index * 200L
            send(key, MotionEvent.ACTION_DOWN, time, time)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))

            assertEquals(
                "$action reaches the normal-key pressed level",
                1f,
                requireNotNull(panel.actionFeedbackLevelForTest(action)),
                0f,
            )
            assertColorWithinCanvasRounding("$action uses the 0x22 state layer", pressedColor, faceCenter(key))
            assertEquals(
                "$action performs keyboard haptics on press",
                HapticFeedbackConstants.KEYBOARD_TAP,
                shadowOf(key).lastHapticFeedbackPerformed(),
            )

            send(key, MotionEvent.ACTION_UP, time, time + Motion.PRESS_IN)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
            assertEquals(
                "$action releases the pressed layer",
                0f,
                requireNotNull(panel.actionFeedbackLevelForTest(action)),
                0f,
            )
        }

        assertEquals("each immediate key dispatches exactly once", immediateActions, dispatched)
    }

    @Test fun palette_updates_every_immediate_key_but_never_turns_the_header_back_into_a_key() = withPanel { panel ->
        panel.applyPalette(ImePalette.STATIC_DARK)

        for (action in immediateActions) {
            val key = requireNotNull(panel.actionViewForTest(action))
            assertEquals("$action follows the current key surface", ImePalette.STATIC_DARK.keySurface, faceCenter(key))
        }

        val back = requireNotNull(panel.actionViewForTest(EditAction.BACK))
        assertTrue("the title action stays the shared panel header control", back is PanelHeaderBackControl)
        assertNull("the title action must not enter the keyboard feedback inventory", panel.actionFeedbackLevelForTest(EditAction.BACK))
        assertTrue("the title action keeps its panel-control ripple", back.foreground is RippleDrawable)
    }

    @Test fun ordinary_actions_follow_the_key_haptics_toggle() = withPanel { panel ->
        panel.hapticEnabled = false
        val left = requireNotNull(panel.actionViewForTest(EditAction.LEFT))
        send(left, MotionEvent.ACTION_DOWN, 0, 0)
        assertEquals("the toggle disables ordinary-key haptics", -1, shadowOf(left).lastHapticFeedbackPerformed())
        send(left, MotionEvent.ACTION_UP, 0, 10)

        panel.hapticEnabled = true
        val copy = requireNotNull(panel.actionViewForTest(EditAction.COPY))
        send(copy, MotionEvent.ACTION_DOWN, 20, 20)
        assertEquals(
            "the toggle enables ordinary-key haptics",
            HapticFeedbackConstants.KEYBOARD_TAP,
            shadowOf(copy).lastHapticFeedbackPerformed(),
        )
        send(copy, MotionEvent.ACTION_UP, 20, 30)
    }

    @Test fun copy_and_cut_have_no_press_or_haptic_until_a_selection_exists() = withPanel { panel ->
        panel.hapticEnabled = true
        val dispatched = ArrayList<EditAction>()
        panel.onAction = { dispatched += it }

        for ((index, action) in listOf(EditAction.COPY, EditAction.CUT).withIndex()) {
            val key = requireNotNull(panel.actionViewForTest(action))
            panel.setHasSelection(false)
            assertFalse("$action is disabled without a selection", key.isEnabled)
            assertFalse("$action is not clickable without a selection", key.isClickable)

            val time = index * 200L
            send(key, MotionEvent.ACTION_DOWN, time, time)
            send(key, MotionEvent.ACTION_UP, time, time + 16)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN + Motion.PRESS_OUT))
            assertEquals(0f, requireNotNull(panel.actionFeedbackLevelForTest(action)), 0f)
            assertEquals(-1, shadowOf(key).lastHapticFeedbackPerformed())
            assertTrue(dispatched.isEmpty())

            panel.setHasSelection(true)
            assertTrue("$action is enabled when a selection exists", key.isEnabled)
            assertTrue("$action is clickable when a selection exists", key.isClickable)
            send(key, MotionEvent.ACTION_DOWN, time + 50, time + 50)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
            assertEquals(1f, requireNotNull(panel.actionFeedbackLevelForTest(action)), 0f)
            assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(key).lastHapticFeedbackPerformed())
            send(key, MotionEvent.ACTION_UP, time + 50, time + 50 + Motion.PRESS_IN)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
            assertEquals(listOf(action), dispatched)
            dispatched.clear()
        }
    }

    @Test fun same_selection_and_palette_updates_preserve_an_active_copy_press() = withPanel { panel ->
        val dispatched = ArrayList<EditAction>()
        panel.onAction = { dispatched += it }
        val copy = requireNotNull(panel.actionViewForTest(EditAction.COPY))

        send(copy, MotionEvent.ACTION_DOWN, 0, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
        assertEquals(1f, requireNotNull(panel.actionFeedbackLevelForTest(EditAction.COPY)), 0f)

        panel.setHasSelection(true)
        assertEquals(1f, requireNotNull(panel.actionFeedbackLevelForTest(EditAction.COPY)), 0f)
        panel.applyPalette(ImePalette.STATIC_DARK)
        assertEquals(1f, requireNotNull(panel.actionFeedbackLevelForTest(EditAction.COPY)), 0f)

        send(copy, MotionEvent.ACTION_UP, 0, Motion.PRESS_IN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
        assertEquals(0f, requireNotNull(panel.actionFeedbackLevelForTest(EditAction.COPY)), 0f)
        assertEquals(listOf(EditAction.COPY), dispatched)
    }

    private fun send(view: View, action: Int, downTime: Long, eventTime: Long) {
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            view.width / 2f,
            view.height / 2f,
            0,
        )
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun faceCenter(view: View): Int {
        assertTrue("the action must be laid out", view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.background.setBounds(0, 0, view.width, view.height)
            view.background.draw(Canvas(bitmap))
            bitmap.getPixel(view.width / 2, view.height / 2)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertColorWithinCanvasRounding(message: String, expected: Int, actual: Int) {
        assertTrue(
            "$message: expected=${Integer.toHexString(expected)} actual=${Integer.toHexString(actual)}",
            abs(Color.alpha(expected) - Color.alpha(actual)) <= 1 &&
                abs(Color.red(expected) - Color.red(actual)) <= 1 &&
                abs(Color.green(expected) - Color.green(actual)) <= 1 &&
                abs(Color.blue(expected) - Color.blue(actual)) <= 1,
        )
    }

    private fun dp(view: View, value: Int): Int = (value * view.resources.displayMetrics.density).toInt()
}
