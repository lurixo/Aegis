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

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
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
class KeyFeedbackTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density

    private fun alphaView(): KeyboardView = KeyboardView(context).apply {
        setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
        measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun KeyboardView.down(x: Float, y: Float) = dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
    private fun KeyboardView.move(x: Float, y: Float) = dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_MOVE, x, y, 0))
    private fun KeyboardView.up(x: Float, y: Float) = dispatchTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, x, y, 0))

    @Test fun every_font_drawn_key_label_uses_bold_type() {
        assertTrue(alphaView().keyLabelPaintsAreBoldForTest())
    }


    @Test fun pressing_a_letter_shows_its_enlarged_preview_when_enabled() {
        val v = alphaView().apply { previewAlphaEnabled = true }
        val (x, y) = v.centerOfLabelForTest("q")!!
        v.down(x, y)
        assertTrue("preview armed on a letter press", v.previewActiveForTest())
        assertEquals("q", v.previewLabelForTest())
        v.up(x, y)
        assertFalse("preview retracts on release", v.previewActiveForTest())
        assertNull(v.previewLabelForTest())
    }

    @Test fun no_preview_when_the_toggle_is_off() {
        val v = alphaView().apply { previewAlphaEnabled = false }
        val (x, y) = v.centerOfLabelForTest("q")!!
        v.down(x, y)
        assertFalse("preview stays hidden when disabled", v.previewActiveForTest())
        v.up(x, y)
    }

    @Test fun functional_keys_are_exempt_from_the_preview() {
        val v = alphaView().apply { previewAlphaEnabled = true }
        for (action in listOf(KeyAction.BACKSPACE, KeyAction.SHIFT, KeyAction.ENTER, KeyAction.SPACE)) {
            val (x, y) = v.centerOfActionForTest(action) ?: continue
            v.down(x, y)
            assertFalse("$action must not show a preview", v.previewActiveForTest())
            v.up(x, y)
        }
    }

    @Test fun sliding_off_the_pressed_key_retracts_the_preview() {
        val v = alphaView().apply { previewAlphaEnabled = true }
        val (x, y) = v.centerOfLabelForTest("q")!!
        val (wx, wy) = v.centerOfLabelForTest("w")!!
        v.down(x, y)
        assertTrue(v.previewActiveForTest())
        v.move(wx, wy)
        assertFalse("preview retracts once the finger leaves the pressed key", v.previewActiveForTest())
        v.up(wx, wy)
    }


    @Test fun a_key_press_vibrates_when_haptics_are_enabled() {
        val v = alphaView().apply { hapticEnabled = true }
        val (x, y) = v.centerOfLabelForTest("q")!!
        v.down(x, y)
        assertEquals(
            "a content-key press performs KEYBOARD_TAP haptic feedback",
            HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(v).lastHapticFeedbackPerformed(),
        )
        v.up(x, y)
    }

    @Test fun a_key_press_does_not_vibrate_when_haptics_are_disabled() {
        val v = alphaView().apply { hapticEnabled = false }
        val (x, y) = v.centerOfLabelForTest("q")!!
        v.down(x, y)
        assertEquals(
            "no haptic feedback when the toggle is off",
            -1, shadowOf(v).lastHapticFeedbackPerformed(),
        )
        v.up(x, y)
    }
}
