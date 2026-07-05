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
import android.graphics.Color
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.ui.theme.SettingsMotion
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Md3MotionSystemTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun animationsOn() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    private fun animationsOff() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

    private fun attach(view: View): View {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(activity)
        host.addView(view)
        activity.setContentView(host)
        return view
    }


    @Test fun fadeThrough_under_reduced_motion_swaps_immediately_at_full_opacity() {
        animationsOff()
        val v = attach(View(ctx)).apply { alpha = 0.2f }
        var swapped = false
        Motion.fadeThrough(v) { swapped = true }
        assertTrue("reduced motion runs the content swap immediately", swapped)
        assertEquals("reduced motion jumps straight to full opacity", 1f, v.alpha, 0f)
    }

    @Test fun fadeThrough_when_detached_swaps_immediately() {
        animationsOn()
        val v = View(ctx)
        var swapped = false
        Motion.fadeThrough(v) { swapped = true }
        assertTrue("a detached view swaps immediately (no frame loop to run the fade)", swapped)
        assertEquals(1f, v.alpha, 0f)
    }

    @Test fun fadeThrough_when_attached_and_animated_defers_the_swap_to_the_trough() {
        animationsOn()
        val v = attach(View(ctx))
        var swapped = false
        Motion.fadeThrough(v) { swapped = true }
        assertFalse("the animated fade-through defers the swap until the alpha-0 trough", swapped)
    }


    @Test fun crossfadeColor_from_equals_to_is_a_noop_returning_null() {
        animationsOn()
        val v = attach(View(ctx))
        var applied = 0
        val anim = Motion.crossfadeColor(v, Color.RED, Color.RED) { applied = it }
        assertNull("no animator when there is nothing to fade", anim)
        assertEquals("the target is still applied once", Color.RED, applied)
    }

    @Test fun crossfadeColor_reduced_motion_applies_the_target_immediately() {
        animationsOff()
        val v = attach(View(ctx))
        var applied = 0
        val anim = Motion.crossfadeColor(v, Color.RED, Color.BLUE) { applied = it }
        assertNull("reduced motion returns no animator", anim)
        assertEquals("reduced motion jumps straight to the target colour", Color.BLUE, applied)
    }

    @Test fun crossfadeColor_attached_and_animated_returns_a_running_animator() {
        animationsOn()
        val v = attach(View(ctx))
        val anim = Motion.crossfadeColor(v, Color.RED, Color.BLUE) { }
        assertNotNull("an attached, animated colour change runs a cross-fade", anim)
        assertTrue(anim!!.isRunning)
    }


    private fun laidOutKeyboard(): KeyboardView {
        val kv = KeyboardView(ctx)
        kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.CN), isShifted = false, isLocked = false, language = Lang.CN)
        val density = ctx.resources.displayMetrics.density
        kv.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((220 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        kv.layout(0, 0, kv.measuredWidth, kv.measuredHeight)
        return kv
    }

    @Test fun keyboard_mode_change_is_detected_but_a_same_id_re_render_is_not() {
        val kv = laidOutKeyboard()
        val before = kv.modeSwitchesForTest()
        kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.CN), isShifted = true, isLocked = false, language = Lang.CN)
        assertEquals("a same-id re-render is not a mode change", before, kv.modeSwitchesForTest())
        kv.setLayout(Layouts.forId(LayoutId.SYMBOL, Lang.CN), isShifted = false, isLocked = false, language = Lang.CN)
        assertEquals("a real mode change is counted once", before + 1, kv.modeSwitchesForTest())
        kv.setLayout(Layouts.forId(LayoutId.NUMBER, Lang.CN), isShifted = false, isLocked = false, language = Lang.CN)
        assertEquals(before + 2, kv.modeSwitchesForTest())
    }


    @Test fun candidate_strip_fades_on_role_change_but_not_on_candidate_updates() {
        val cv = CandidateView(ctx)
        val start = cv.contentTransitionsForTest()
        cv.setContent(listOf("你"), "ni")
        assertEquals("toolbar→candidates fades once", start + 1, cv.contentTransitionsForTest())
        cv.setContent(listOf("你", "好"), "nihao")
        cv.setContent(listOf("你", "好", "吗"), "nihaoma")
        assertEquals("candidate→candidate updates must NOT fade (fluidity, no strobe)", start + 1, cv.contentTransitionsForTest())
        cv.setContent(emptyList(), "")
        assertEquals("candidates→toolbar fades once", start + 2, cv.contentTransitionsForTest())
    }

    @Test fun candidate_strip_applies_content_immediately_under_reduced_motion() {
        animationsOff()
        val cv = attach(CandidateView(ctx)) as CandidateView
        cv.setContent(listOf("你", "好", "吗"), "nihaoma")
        assertEquals("reduced-motion role change still applies the content immediately", 3, cv.itemCount())
    }


    @Test fun settings_motion_durations_mirror_the_ime_motion_tokens() {
        assertEquals(Motion.MODE_SWITCH.toInt(), SettingsMotion.DURATION_NAV)
        assertEquals(Motion.FADE_IN.toInt(), SettingsMotion.DURATION_FADE_IN)
        assertEquals(Motion.FADE_OUT.toInt(), SettingsMotion.DURATION_FADE_OUT)
        assertEquals(Motion.STATE_CHANGE.toInt(), SettingsMotion.DURATION_STATE)
    }
}
