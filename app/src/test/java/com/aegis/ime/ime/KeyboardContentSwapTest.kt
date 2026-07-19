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
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.ui.LetterCase
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
class KeyboardContentSwapTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun animationsOn() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    private fun animationsOff() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

    private fun attach(activity: Activity, view: KeyboardView, widthDp: Int = 360, heightDp: Int = 230): KeyboardView {
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
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

    private fun alphaKeyboard(): KeyboardView = KeyboardView(ctx).apply {
        setLayout(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)

    @Test fun real_layout_switch_is_instant_with_the_new_touch_geometry_already_live() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = attach(controller.get(), alphaKeyboard())
            val swaps = kv.contentSwapsForTest()
            val modes = kv.modeSwitchesForTest()
            assertNotNull(kv.centerOfLabelForTest("a"))

            kv.setLayout(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = false), false, false, Lang.CN)

            assertFalse("a real mode change is deliberately instant, never a fade", kv.contentSwapActiveForTest())
            assertEquals("a mode change starts no swap", swaps, kv.contentSwapsForTest())
            assertEquals("the mode change is still counted", modes + 1, kv.modeSwitchesForTest())
            assertNotNull("touch targets are the new layout in the same call", kv.centerOfLabelForTest("ABC"))
            assertNull("no stale key of the old layout remains hittable", kv.centerOfLabelForTest("a"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun layout_id_switch_is_instant_while_a_shift_change_still_crossfades() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = KeyboardView(ctx).apply {
                setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
            }
            attach(controller.get(), kv)
            val swaps = kv.contentSwapsForTest()

            kv.setLayout(Layouts.forId(LayoutId.SYMBOL, Lang.EN), false, false, Lang.EN)
            assertFalse("a layout id switch lands with no active swap", kv.contentSwapActiveForTest())
            assertEquals(swaps, kv.contentSwapsForTest())

            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
            assertFalse("the way back is instant too", kv.contentSwapActiveForTest())
            assertEquals(swaps, kv.contentSwapsForTest())

            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), true, false, Lang.EN)
            assertTrue("an aligned shift change on the same layout still crossfades", kv.contentSwapActiveForTest())
            assertEquals(swaps + 1, kv.contentSwapsForTest())

            settle()
            assertFalse(kv.contentSwapActiveForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun layout_id_switch_cancels_an_in_flight_shift_crossfade() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = KeyboardView(ctx).apply {
                setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
            }
            attach(controller.get(), kv)
            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), true, false, Lang.EN)
            assertTrue(kv.contentSwapActiveForTest())

            kv.setLayout(Layouts.forId(LayoutId.SYMBOL, Lang.EN), true, false, Lang.EN)
            assertFalse("a mode switch mid-crossfade lands instant", kv.contentSwapActiveForTest())
            settle()
            assertFalse(kv.contentSwapActiveForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun reduced_motion_shift_and_layout_changes_are_fully_instant() {
        animationsOff()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = attach(controller.get(), alphaKeyboard())
            val swaps = kv.contentSwapsForTest()

            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.CN), true, false, Lang.CN)
            assertFalse("reduced motion never runs the crossfade", kv.contentSwapActiveForTest())
            assertEquals("reduced motion starts no swap at all", swaps, kv.contentSwapsForTest())

            kv.setLayout(Layouts.forId(LayoutId.SYMBOL, Lang.CN), false, false, Lang.CN)

            assertFalse(kv.contentSwapActiveForTest())
            assertEquals(swaps, kv.contentSwapsForTest())
            assertNotNull("the new layout still applies immediately", kv.boundsOfLabelForTest("€"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun unchanged_layout_storm_stays_flat() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = attach(controller.get(), alphaKeyboard())
            settle()
            val applies = kv.layoutAppliesForTest()
            val modes = kv.modeSwitchesForTest()
            val swaps = kv.contentSwapsForTest()

            repeat(5) { kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN) }

            assertEquals("an unchanged storm applies nothing", applies, kv.layoutAppliesForTest())
            assertEquals(modes, kv.modeSwitchesForTest())
            assertEquals("an unchanged storm never crossfades", swaps, kv.contentSwapsForTest())
            assertFalse(kv.contentSwapActiveForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun shift_and_lock_changes_trigger_the_swap() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = KeyboardView(ctx).apply {
                setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
            }
            attach(controller.get(), kv)
            val swaps = kv.contentSwapsForTest()

            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), true, false, Lang.EN)
            assertEquals("a shift change crossfades", swaps + 1, kv.contentSwapsForTest())
            assertTrue(kv.contentSwapActiveForTest())
            assertEquals("ONCE", kv.shiftRenderState())

            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), true, true, Lang.EN)
            assertEquals("a lock change crossfades", swaps + 2, kv.contentSwapsForTest())
            assertEquals("LOCK", kv.shiftRenderState())
            assertTrue("a rapid re-swap restarts cleanly", kv.contentSwapActiveForTest())

            settle()
            assertFalse(kv.contentSwapActiveForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun case_mode_change_triggers_the_swap_and_a_no_op_set_does_not() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = KeyboardView(ctx).apply {
                setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
            }
            attach(controller.get(), kv)
            val swaps = kv.contentSwapsForTest()

            kv.caseMode = LetterCase.UPPER
            assertEquals("a case-mode change crossfades", swaps + 1, kv.contentSwapsForTest())
            assertTrue(kv.contentSwapActiveForTest())

            settle()
            kv.caseMode = LetterCase.UPPER
            assertEquals("re-setting the same case mode is a no-op", swaps + 1, kv.contentSwapsForTest())
            assertFalse(kv.contentSwapActiveForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun same_id_content_updates_do_not_strobe() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = KeyboardView(ctx).apply {
                setLayout(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = false), false, false, Lang.CN)
            }
            attach(controller.get(), kv)
            settle()
            val swaps = kv.contentSwapsForTest()
            val applies = kv.layoutAppliesForTest()

            kv.setLayout(Layouts.nine(Lang.CN, Layouts.ninePunctuation(listOf("→")), composing = true), false, false, Lang.CN)

            assertEquals("a same-id readout update is a real apply", applies + 1, kv.layoutAppliesForTest())
            assertEquals("…but must NOT crossfade (fluidity, no per-keystroke strobe)", swaps, kv.contentSwapsForTest())
            assertFalse(kv.contentSwapActiveForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun detached_shift_change_never_swaps() {
        animationsOn()
        val kv = KeyboardView(ctx).apply {
            setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
        }
        val swaps = kv.contentSwapsForTest()
        kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), true, false, Lang.EN)
        assertEquals(swaps, kv.contentSwapsForTest())
        assertFalse(kv.contentSwapActiveForTest())
    }

    @Test fun size_change_cancels_an_active_swap() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = KeyboardView(ctx).apply {
                setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
            }
            attach(controller.get(), kv)
            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), true, false, Lang.EN)
            assertTrue(kv.contentSwapActiveForTest())

            val w = (360 * density).toInt()
            val h = (180 * density).toInt()
            kv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            kv.layout(0, 0, w, h)

            assertFalse("a resize lands on the end state instead of scaling a stale frame", kv.contentSwapActiveForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun detach_cancels_an_active_swap() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = KeyboardView(ctx).apply {
                setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
            }
            attach(controller.get(), kv)
            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), true, false, Lang.EN)
            assertTrue(kv.contentSwapActiveForTest())

            (kv.parent as FrameLayout).removeView(kv)

            assertFalse("detach drops the snapshot and ends the swap", kv.contentSwapActiveForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun language_toggle_triggers_the_swap() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = attach(controller.get(), alphaKeyboard())
            val swaps = kv.contentSwapsForTest()
            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
            assertEquals("a language toggle crossfades", swaps + 1, kv.contentSwapsForTest())
            assertTrue(kv.contentSwapActiveForTest())
            settle()
            assertFalse(kv.contentSwapActiveForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
