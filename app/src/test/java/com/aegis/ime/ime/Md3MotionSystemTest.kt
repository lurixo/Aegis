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
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.ui.theme.SettingsMotion
import java.util.concurrent.TimeUnit
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Md3MotionSystemTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private class CountingContext(base: Context) : ContextWrapper(base) {
        var resolverAccesses = 0

        override fun getContentResolver(): ContentResolver {
            resolverAccesses++
            return baseContext.contentResolver
        }
    }

    private class SwapProbe(ctx: Context) : View(ctx) {
        val samples = ArrayList<Pair<Float, Float>>()
        var swap: Motion.ContentSwap? = null

        override fun postInvalidateOnAnimation() {
            swap?.let { samples.add(it.outAlpha to it.inAlpha) }
            super.postInvalidateOnAnimation()
        }
    }

    private fun animationsOn() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    private fun animationsOff() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

    private fun attach(activity: Activity, view: View): View {
        val host = FrameLayout(activity)
        host.addView(view)
        activity.setContentView(host)
        return view
    }

    private fun <T : View> attach(activity: Activity, view: T, width: Int, height: Int): T {
        val host = FrameLayout(activity)
        host.addView(view, FrameLayout.LayoutParams(width, height))
        activity.setContentView(host)
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, width, height)
        return view
    }

    private fun tap(view: View, x: Float, y: Float) {
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0))
    }


    @Test fun fadeThrough_under_reduced_motion_swaps_immediately_at_full_opacity() {
        animationsOff()
        val v = View(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v).apply { alpha = 0.2f }
            var swapped = false
            Motion.fadeThrough(v) { swapped = true }
            assertTrue("reduced motion runs the content swap immediately", swapped)
            assertEquals("reduced motion jumps straight to full opacity", 1f, v.alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
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
        val v = View(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v)
            var swapped = false
            Motion.fadeThrough(v) { swapped = true }
            assertFalse("the animated fade-through defers the swap until the alpha-0 trough", swapped)
        } finally {
            controller.pause().stop().destroy()
        }
    }


    @Test fun contentSwap_under_reduced_motion_lands_on_the_end_state_immediately() {
        animationsOff()
        val v = View(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v)
            val swap = Motion.ContentSwap(v)
            swap.start()
            assertFalse("reduced motion never leaves the swap active", swap.active)
            assertEquals("the incoming content shows at full opacity", 1f, swap.inAlpha, 0f)
            assertEquals("the outgoing snapshot is fully gone", 0f, swap.outAlpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun contentSwap_when_detached_lands_on_the_end_state_immediately() {
        animationsOn()
        val swap = Motion.ContentSwap(View(ctx))
        swap.start()
        assertFalse(swap.active)
        assertEquals(1f, swap.inAlpha, 0f)
        assertEquals(0f, swap.outAlpha, 0f)
    }

    @Test fun contentSwap_attached_and_animated_runs_and_restarts_cancel_safely() {
        animationsOn()
        val v = View(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v)
            val swap = Motion.ContentSwap(v)
            swap.start()
            assertTrue("an attached, animated swap runs", swap.active)
            assertEquals("the incoming face starts from transparent", 0f, swap.inAlpha, 0f)
            assertEquals("the outgoing face starts fully shown", 1f, swap.outAlpha, 0f)
            swap.start()
            assertTrue("a restart mid-swap stays active from a fresh window", swap.active)
            swap.cancel()
            assertFalse("cancel lands the end state", swap.active)
            assertEquals(1f, swap.inAlpha, 0f)
            assertEquals(0f, swap.outAlpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun contentSwap_sequential_under_reduced_motion_lands_on_the_end_state_immediately() {
        animationsOff()
        val v = View(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v)
            val swap = Motion.ContentSwap(v)
            swap.start(sequential = true)
            assertFalse("reduced motion never leaves the swap active", swap.active)
            assertEquals("the incoming content shows at full opacity", 1f, swap.inAlpha, 0f)
            assertEquals("the outgoing snapshot is fully gone", 0f, swap.outAlpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun contentSwap_sequential_when_detached_lands_on_the_end_state_immediately() {
        animationsOn()
        val swap = Motion.ContentSwap(View(ctx))
        swap.start(sequential = true)
        assertFalse(swap.active)
        assertEquals(1f, swap.inAlpha, 0f)
        assertEquals(0f, swap.outAlpha, 0f)
    }

    @Test fun contentSwap_sequential_starts_with_only_the_outgoing_face_shown() {
        animationsOn()
        val v = View(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v)
            val swap = Motion.ContentSwap(v)
            swap.start(sequential = true)
            assertTrue("an attached, animated sequential swap runs", swap.active)
            assertEquals("the incoming face starts hidden", 0f, swap.inAlpha, 0f)
            assertEquals("the outgoing face starts fully shown", 1f, swap.outAlpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun contentSwap_sequential_separates_the_out_phase_from_the_in_phase() {
        animationsOn()
        val v = SwapProbe(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v)
            val swap = Motion.ContentSwap(v)
            v.swap = swap
            swap.start(sequential = true)
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertFalse(swap.active)
            assertEquals(1f, swap.inAlpha, 0f)
            assertEquals(0f, swap.outAlpha, 0f)
            val lastOut = v.samples.indexOfLast { it.first > 0f }
            val firstIn = v.samples.indexOfFirst { it.second > 0f }
            assertTrue("the outgoing face fades while the incoming face is still hidden", v.samples.any { it.first > 0f && it.second == 0f })
            assertTrue("the incoming face rises only once the outgoing face is gone", firstIn >= 0 && v.samples[firstIn].first == 0f)
            assertTrue("the out phase ends before the in phase begins", lastOut < firstIn)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun contentSwap_sequential_never_shows_both_faces_at_once() {
        animationsOn()
        val v = SwapProbe(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v)
            val swap = Motion.ContentSwap(v)
            v.swap = swap
            swap.start(sequential = true)
            assertFalse(swap.outAlpha > 0f && swap.inAlpha > 0f)
            repeat(15) {
                shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
                assertFalse("the sequential swap never blends both faces", swap.outAlpha > 0f && swap.inAlpha > 0f)
            }
            assertTrue(v.samples.isNotEmpty())
            assertFalse("no animation frame ever blends both faces", v.samples.any { it.first > 0f && it.second > 0f })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun contentSwap_default_crossfade_overlaps_both_faces_mid_swap() {
        animationsOn()
        val v = SwapProbe(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v)
            val swap = Motion.ContentSwap(v)
            v.swap = swap
            swap.start()
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertTrue("the aligned crossfade blends both faces mid-swap", v.samples.any { it.first > 0f && it.second > 0f })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun crossfadeColor_from_equals_to_is_a_noop_returning_null() {
        animationsOn()
        val v = View(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v)
            var applied = 0
            val anim = Motion.crossfadeColor(v, Color.RED, Color.RED) { applied = it }
            assertNull("no animator when there is nothing to fade", anim)
            assertEquals("the target is still applied once", Color.RED, applied)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun crossfadeColor_reduced_motion_applies_the_target_immediately() {
        animationsOff()
        val v = View(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v)
            var applied = 0
            val anim = Motion.crossfadeColor(v, Color.RED, Color.BLUE) { applied = it }
            assertNull("reduced motion returns no animator", anim)
            assertEquals("reduced motion jumps straight to the target colour", Color.BLUE, applied)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun crossfadeColor_attached_and_animated_returns_a_running_animator() {
        animationsOn()
        val v = View(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v)
            val anim = Motion.crossfadeColor(v, Color.RED, Color.BLUE) { }
            assertNotNull("an attached, animated colour change runs a cross-fade", anim)
            assertTrue(anim!!.isRunning)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun attached_candidate_and_keyboard_presses_do_not_read_global_settings() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val context = CountingContext(activity)
            val density = activity.resources.displayMetrics.density
            val bar = CandidateView(context).apply { setContent(listOf("你", "泥"), "ni") }
            attach(activity, bar, (360 * density).toInt(), (44 * density).toInt())
            context.resolverAccesses = 0
            var expandAccesses = -1
            bar.onExpand = { expandAccesses = context.resolverAccesses }
            val expandBounds = bar.expandControlBoundsForTest()
            tap(bar, expandBounds.centerX(), expandBounds.centerY())
            assertEquals(0, expandAccesses)
            assertEquals(0, context.resolverAccesses)

            val keyboard = KeyboardView(context).apply {
                setLayout(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
            }
            attach(activity, keyboard, (360 * density).toInt(), (260 * density).toInt())
            context.resolverAccesses = 0
            var picked: Key? = null
            keyboard.onKey = { picked = it }
            val keyCenter = requireNotNull(keyboard.centerOfLabelForTest("a"))
            tap(keyboard, keyCenter.first, keyCenter.second)
            assertEquals("a", picked?.label)
            assertEquals(0, context.resolverAccesses)
        } finally {
            controller.pause().stop().destroy()
        }
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
        val cv = CandidateView(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), cv)
            cv.setContent(listOf("你", "好", "吗"), "nihaoma")
            assertEquals("reduced-motion role change still applies the content immediately", 3, cv.itemCount())
        } finally {
            controller.pause().stop().destroy()
        }
    }


    @Test fun settings_motion_durations_mirror_the_ime_motion_tokens() {
        assertEquals(Motion.MODE_SWITCH.toInt(), SettingsMotion.DURATION_NAV)
        assertEquals(Motion.FADE_IN.toInt(), SettingsMotion.DURATION_FADE_IN)
        assertEquals(Motion.FADE_OUT.toInt(), SettingsMotion.DURATION_FADE_OUT)
        assertEquals(Motion.STATE_CHANGE.toInt(), SettingsMotion.DURATION_STATE)
    }
}
