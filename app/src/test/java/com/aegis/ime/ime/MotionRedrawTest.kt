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

import android.provider.Settings
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionRedrawTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private class CountingView(c: android.content.Context) : View(c) {
        var invalidations = 0
        override fun invalidate() { invalidations++; super.invalidate() }
    }

    private fun disableSystemAnimations() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test fun showNow_with_animations_off_invalidates_even_when_alpha_is_already_one() {
        disableSystemAnimations()
        val v = CountingView(ctx).apply { alpha = 1f }
        v.invalidations = 0
        Motion.showNow(v)
        assertEquals("showNow lands fully shown", 1f, v.alpha, 0f)
        assertTrue(
            "showNow must invalidate so freshly-set content repaints (setAlpha(1f) is a no-op)",
            v.invalidations >= 1,
        )
    }

    @Test fun showNow_when_detached_invalidates() {
        val v = CountingView(ctx).apply { alpha = 1f }
        v.invalidations = 0
        Motion.showNow(v)
        assertEquals(1f, v.alpha, 0f)
        assertTrue("detached showNow must still invalidate", v.invalidations >= 1)
    }

    @Test fun preedit_first_setText_under_reduced_motion_schedules_a_repaint() {
        disableSystemAnimations()
        val invalidated = booleanArrayOf(false)
        val pv = object : PreeditView(ctx) {
            override fun invalidate() { invalidated[0] = true; super.invalidate() }
        }
        invalidated[0] = false
        pv.setText("n")
        assertEquals("preedit jumps to shown under reduced motion", 1f, pv.alpha, 0f)
        assertTrue("first pinyin key must repaint the preedit even with animations off", invalidated[0])
    }

    @Test fun preedit_clear_under_reduced_motion_schedules_a_repaint() {
        disableSystemAnimations()
        val invalidated = booleanArrayOf(false)
        val pv = object : PreeditView(ctx) {
            override fun invalidate() { invalidated[0] = true; super.invalidate() }
        }
        pv.setText("n")
        invalidated[0] = false
        pv.setText("")
        assertEquals("reduced motion clears the drawn text in the same call", "", pv.shownTextForTest())
        assertTrue("the instant clear must still repaint the emptied band", invalidated[0])
    }

    @Test fun pressFeedback_snaps_to_end_states_when_no_frame_loop_can_run() {
        val v = CountingView(ctx)
        val press = Motion.PressFeedback(v)

        press.press()
        assertEquals("detached press jumps to the pressed state", 1f, press.level, 0f)
        assertTrue("press feedback invalidates the drawing surface", v.invalidations >= 1)

        v.invalidations = 0
        press.release()
        assertEquals("detached release jumps back to rest", 0f, press.level, 0f)
        assertTrue("release feedback invalidates the drawing surface", v.invalidations >= 1)
    }

    @Test fun showNow_restores_final_geometry_immediately() {
        val v = CountingView(ctx).apply {
            visibility = View.GONE
            alpha = 0.25f
            translationX = 12f
            translationY = 8f
        }
        v.invalidations = 0

        Motion.showNow(v)

        assertEquals(View.VISIBLE, v.visibility)
        assertEquals(1f, v.alpha, 0f)
        assertEquals(0f, v.translationX, 0f)
        assertEquals(0f, v.translationY, 0f)
        assertTrue("the instant show must still repaint the final state", v.invalidations >= 1)
    }
}
