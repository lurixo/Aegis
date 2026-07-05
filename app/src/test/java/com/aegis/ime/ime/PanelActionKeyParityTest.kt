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
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class PanelActionKeyParityTest {

    @org.junit.Before fun usePlatformHapticFallback() {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.Vibrator::class.java))
            .setHasVibrator(false)
    }

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density
    private val activities = ArrayList<org.robolectric.android.controller.ActivityController<Activity>>()

    @After fun destroyActivities() {
        activities.asReversed().forEach { it.pause().stop().destroy() }
        activities.clear()
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun layout(view: View, widthDp: Int, heightDp: Int = 320) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.get().setContentView(view)
        activities += controller
        relayout(view, widthDp, heightDp)
    }

    private fun relayout(view: View, widthDp: Int, heightDp: Int = 320) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(heightDp), View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun event(action: Int, x: Float, y: Float, time: Long = 0L): MotionEvent =
        MotionEvent.obtain(0, time, action, x, y, 0)

    private fun exercisePressLifecycle(
        name: String,
        tab: TextView,
        level: () -> Float,
    ) {
        val x = tab.width / 2f
        val y = tab.height / 2f
        tab.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
        assertEquals("$name press reaches the shared level", 1f, level(), 0f)
        assertTrue("$name is pressed while the pointer stays inside", tab.isPressed)

        tab.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, -tab.width.toFloat(), y, 10L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
        assertEquals("$name moving outside releases the shared level", 0f, level(), 0f)
        assertFalse("$name moving outside clears pressed state", tab.isPressed)

        tab.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, -tab.width.toFloat(), y, 20L))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("$name move-out cancellation stays released", 0f, level(), 0f)

        tab.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y, 30L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
        assertEquals("$name second press reaches the shared level", 1f, level(), 0f)
        tab.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, x, y, 40L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
        assertEquals("$name cancellation releases the shared level", 0f, level(), 0f)
        assertFalse("$name cancellation clears pressed state", tab.isPressed)
    }

    @Test fun expanded_candidate_actions_use_the_same_static_face_and_haptic_policy() {
        var closes = 0
        var backspaces = 0
        var clears = 0
        var picked = -1
        var pickedReading = -1
        val panel = CandidateGridView(context).apply {
            hapticEnabled = true
            onClose = { closes++ }
            onBackspace = { backspaces++ }
            onClear = { clears++ }
            onPick = { picked = it }
            onPickReading = { pickedReading = it }
            setCandidates(listOf("你", "好"))
            setReadings(listOf("ni", "hao"), selected = 0)
        }
        layout(panel, 360)
        val actions = listOf(
            Triple("collapse", panel.returnButtonForTest(), panel::returnFeedbackLevelForTest),
            Triple("backspace", panel.backspaceButtonForTest(), panel::backspaceFeedbackLevelForTest),
            Triple("redo", panel.clearButtonForTest(), panel::clearFeedbackLevelForTest),
        )
        for ((name, button, level) in actions) {
            val surface = button.background as? ImeKeySurface
            assertNotNull("expanded $name keeps the shared IME press surface", surface)
            assertEquals("expanded $name has no resting key face", Color.TRANSPARENT, requireNotNull(surface).faceColor)
            assertFalse(button.background is RippleDrawable)
            exercisePressLifecycle("expanded $name", button, level)
            assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(button).lastHapticFeedbackPerformed())
        }
        assertEquals(0, closes)
        assertEquals(0, backspaces)
        assertEquals(0, clears)

        for ((index, button) in actions.map { it.second }.withIndex()) {
            val time = 100L + index * 20L
            button.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, button.width / 2f, button.height / 2f, time))
            button.dispatchTouchEvent(event(MotionEvent.ACTION_UP, button.width / 2f, button.height / 2f, time + 10L))
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(1, closes)
        assertEquals(1, backspaces)
        assertEquals(1, clears)

        val candidate = requireNotNull(panel.firstChipForTest())
        val reading = requireNotNull(panel.readingTileForTest(0))
        for ((name, key) in listOf("candidate" to candidate, "reading" to reading)) {
            val surface = key.background as? ImeKeySurface
            assertNotNull("expanded $name uses the shared IME press surface", surface)
            assertEquals(Color.TRANSPARENT, requireNotNull(surface).faceColor)
            assertFalse("expanded $name does not stack a platform ripple", key.foreground is RippleDrawable)
            key.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, key.width / 2f, key.height / 2f, 40L))
            assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(key).lastHapticFeedbackPerformed())
            key.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, key.width / 2f, key.height / 2f, 50L))
        }
        assertEquals(0f, panel.firstChipFeedbackLevelForTest() ?: -1f, 0f)
        assertEquals(0f, panel.readingFeedbackLevelForTest(0) ?: -1f, 0f)
        assertEquals(-1, picked)
        assertEquals(-1, pickedReading)

        candidate.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, candidate.width / 2f, candidate.height / 2f, 60L))
        candidate.dispatchTouchEvent(event(MotionEvent.ACTION_UP, candidate.width / 2f, candidate.height / 2f, 70L))
        reading.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, reading.width / 2f, reading.height / 2f, 80L))
        reading.dispatchTouchEvent(event(MotionEvent.ACTION_UP, reading.width / 2f, reading.height / 2f, 90L))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, picked)
        assertEquals(0, pickedReading)
    }
}
