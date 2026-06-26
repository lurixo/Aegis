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

import android.os.Looper
import android.view.MotionEvent
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import java.time.Duration
import org.junit.Assert.assertEquals
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

    @org.junit.Before fun usePlatformHapticFallback() {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.Vibrator::class.java))
            .setHasVibrator(false)
    }

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density

    private fun event(action: Int, x: Float, y: Float, time: Long = 0L): MotionEvent =
        MotionEvent.obtain(0, time, action, x, y, 0)

    @Test fun shared_backspace_touch_repeats_swipes_and_never_double_fires_the_release() {
        var taps = 0
        var repeats = 0
        val swipes = ArrayList<Boolean>()
        val view = TextView(context).apply {
            isClickable = true
            setOnClickListener { taps++ }
            layout(0, 0, (100 * density).toInt(), (60 * density).toInt())
        }
        val feedback = ImeKeyFeedback(view, ImePalette.STATIC_LIGHT.functionSurface, ImePalette.STATIC_LIGHT.keyLabel)
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
