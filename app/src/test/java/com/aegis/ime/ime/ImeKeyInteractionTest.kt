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

import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeKeyInteractionTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density

    private fun event(action: Int, x: Float, y: Float, time: Long = 0L): MotionEvent =
        MotionEvent.obtain(0, time, action, x, y, 0)

    @Test fun view_keys_use_the_keyboard_press_timeline_and_haptic_policy_without_a_platform_ripple() {
        var haptics = true
        val view = View(context).apply {
            isClickable = true
            layout(0, 0, (100 * density).toInt(), (60 * density).toInt())
        }
        val feedback = ImeKeyFeedback(view, ImePalette.STATIC_LIGHT.keySurface, ImePalette.STATIC_LIGHT.keyLabel)
        feedback.bind { haptics }

        assertFalse(view.background is RippleDrawable)
        assertNull(view.foreground)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, view.width / 2f, view.height / 2f))
        assertEquals(1f, feedback.levelForTest(), 0f)
        assertTrue(view.isPressed)
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(view).lastHapticFeedbackPerformed())

        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, -100f, -100f, 10L))
        assertEquals(0f, feedback.levelForTest(), 0f)
        assertFalse(view.isPressed)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, -100f, -100f, 20L))

        haptics = false
        val silentView = View(context).apply {
            isClickable = true
            layout(0, 0, (100 * density).toInt(), (60 * density).toInt())
        }
        ImeKeyFeedback(silentView, ImePalette.STATIC_LIGHT.keySurface, ImePalette.STATIC_LIGHT.keyLabel)
            .bind { haptics }
        silentView.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, silentView.width / 2f, silentView.height / 2f, 30L))
        assertEquals(-1, shadowOf(silentView).lastHapticFeedbackPerformed())
        silentView.dispatchTouchEvent(event(MotionEvent.ACTION_UP, silentView.width / 2f, silentView.height / 2f, 40L))
    }

    @Test fun shared_backspace_touch_repeats_swipes_and_never_double_fires_the_release() {
        var taps = 0
        var repeats = 0
        val swipes = ArrayList<Boolean>()
        val view = TextView(context).apply {
            isClickable = true
            setOnClickListener { taps++ }
            layout(0, 0, (100 * density).toInt(), (60 * density).toInt())
        }
        val feedback = ImeKeyFeedback(view, ImePalette.STATIC_LIGHT.railBg, ImePalette.STATIC_LIGHT.keyLabel)
        ImeBackspaceTouch(view, feedback, density, { true }, { repeats++ }, { swipes += it })
        val x = view.width / 2f
        val y = view.height / 2f

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BackspaceGesture.REPEAT_DELAY_MS))
        assertEquals(1, repeats)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BackspaceGesture.REPEAT_INTERVAL_MS))
        assertEquals(2, repeats)
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x, y, 500L))
        assertEquals(0, taps)
        assertTrue(swipes.isEmpty())

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, view.height * 0.8f, 600L))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x, view.height * 0.1f, 620L))
        assertEquals(listOf(true), swipes)
        assertEquals(0, taps)

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y, 700L))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x, y, 720L))
        assertEquals(1, taps)
    }
}
