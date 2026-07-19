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

import android.view.animation.Interpolator
import com.aegis.ime.ui.theme.SettingsMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionSpecTest {


    @Test fun duration_ladder_is_the_two_shortest_tiers_only() {
        assertEquals(50L, Motion.SHORT1)
        assertEquals(100L, Motion.SHORT2)
        val semantic = listOf(Motion.PRESS_IN, Motion.PRESS_OUT, Motion.STATE_CHANGE, Motion.COVER_HOLD)
        assertTrue("no duration token may exceed the SHORT2 ceiling", semantic.all { it <= Motion.SHORT2 })
    }

    @Test fun press_feedback_keeps_its_quick_in_relaxed_out_pair() {
        assertEquals(Motion.SHORT1, Motion.PRESS_IN)
        assertEquals(Motion.SHORT2, Motion.PRESS_OUT)
    }

    @Test fun colour_state_changes_are_tightened_to_the_short2_tier() {
        assertEquals(Motion.SHORT2, Motion.STATE_CHANGE)
    }

    @Test fun the_cover_hold_is_the_shortest_tier() {
        assertEquals(Motion.SHORT1, Motion.COVER_HOLD)
    }


    private fun assertNoOvershootOrBounce(name: String, interp: Interpolator) {
        var prev = interp.getInterpolation(0f)
        assertTrue("$name must start at 0", prev in -0.0001f..0.0001f)
        var t = 0f
        while (t <= 1f) {
            val v = interp.getInterpolation(t)
            assertTrue("$name overshoots/undershoots [0,1] at t=$t (v=$v) → would read as a jolt", v >= -0.0001f && v <= 1.0001f)
            assertTrue("$name is non-monotonic at t=$t (v=$v < prev=$prev) → a bounce/oscillation", v >= prev - 0.0001f)
            prev = v
            t += 0.01f
        }
        assertTrue("$name must finish at 1", interp.getInterpolation(1f) in 0.9999f..1.0001f)
    }

    @Test fun every_ime_easing_is_monotonic_and_bounded_no_overshoot_or_bounce() {
        assertNoOvershootOrBounce("STANDARD", Motion.STANDARD)
        assertNoOvershootOrBounce("STANDARD_DECEL", Motion.STANDARD_DECEL)
    }

    @Test fun every_settings_easing_is_monotonic_and_bounded_no_overshoot_or_bounce() {
        assertNoOvershootOrBounce("Settings.EmphasizedDecelerate", Interpolator { SettingsMotion.EmphasizedDecelerate.transform(it) })
        assertNoOvershootOrBounce("Settings.EmphasizedAccelerate", Interpolator { SettingsMotion.EmphasizedAccelerate.transform(it) })
    }


    @Test fun settings_durations_keep_their_literal_values() {
        assertEquals(200, SettingsMotion.DURATION_NAV)
        assertEquals(150, SettingsMotion.DURATION_FADE_IN)
        assertEquals(100, SettingsMotion.DURATION_FADE_OUT)
        assertEquals(200, SettingsMotion.DURATION_STATE)
    }
}
