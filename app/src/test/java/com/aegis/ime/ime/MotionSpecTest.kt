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


    @Test fun duration_ladder_is_the_restrained_short_only_subset() {
        assertEquals(50L, Motion.SHORT1)
        assertEquals(100L, Motion.SHORT2)
        assertEquals(150L, Motion.SHORT3)
        assertEquals(200L, Motion.SHORT4)
        val semantic = listOf(
            Motion.PRESS_IN, Motion.PRESS_OUT, Motion.FADE_IN, Motion.FADE_OUT,
            Motion.STATE_CHANGE, Motion.REVEAL, Motion.MODE_SWITCH,
        )
        assertTrue("no transition may exceed the SHORT4 ceiling (no medium/long tiers)", semantic.all { it <= Motion.SHORT4 })
    }

    @Test fun same_tier_interactions_share_one_duration() {
        assertEquals("reveal == state-change (was 250 vs 200)", Motion.STATE_CHANGE, Motion.REVEAL)
        assertEquals("mode-switch == the standard tier (was 300)", Motion.STATE_CHANGE, Motion.MODE_SWITCH)
        assertEquals(200L, Motion.MODE_SWITCH)
    }

    @Test fun mode_switch_fade_is_tightened_below_the_old_value() {
        assertTrue("the mode-switch fade must be tightened below 300ms", Motion.MODE_SWITCH < 300L)
    }

    @Test fun the_appear_fade_is_the_short3_tier() {
        assertEquals(Motion.SHORT3, Motion.FADE_IN)
        assertEquals(100L, Motion.FADE_OUT)
    }

    @Test fun there_is_exactly_one_reveal_distance_token() {
        assertEquals("the single reveal slide distance (replaced scattered 6/8/10dp)", 8f, Motion.REVEAL_SHIFT_DP, 0f)
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
        assertNoOvershootOrBounce("EMPHASIZED_DECEL", Motion.EMPHASIZED_DECEL)
        assertNoOvershootOrBounce("EMPHASIZED_ACCEL", Motion.EMPHASIZED_ACCEL)
    }

    @Test fun every_settings_easing_is_monotonic_and_bounded_no_overshoot_or_bounce() {
        assertNoOvershootOrBounce("Settings.EmphasizedDecelerate", Interpolator { SettingsMotion.EmphasizedDecelerate.transform(it) })
        assertNoOvershootOrBounce("Settings.EmphasizedAccelerate", Interpolator { SettingsMotion.EmphasizedAccelerate.transform(it) })
    }


    @Test fun settings_durations_are_the_ime_tokens() {
        assertEquals(Motion.MODE_SWITCH.toInt(), SettingsMotion.DURATION_NAV)
        assertEquals(Motion.FADE_IN.toInt(), SettingsMotion.DURATION_FADE_IN)
        assertEquals(Motion.FADE_OUT.toInt(), SettingsMotion.DURATION_FADE_OUT)
        assertEquals(Motion.STATE_CHANGE.toInt(), SettingsMotion.DURATION_STATE)
    }

    @Test fun settings_and_ime_share_the_same_incoming_and_outgoing_curves() {
        val samples = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        for (t in samples) {
            assertEquals("incoming curve mismatch at $t", Motion.EMPHASIZED_DECEL.getInterpolation(t), SettingsMotion.EmphasizedDecelerate.transform(t), 0.01f)
            assertEquals("outgoing curve mismatch at $t", Motion.EMPHASIZED_ACCEL.getInterpolation(t), SettingsMotion.EmphasizedAccelerate.transform(t), 0.01f)
        }
    }
}
