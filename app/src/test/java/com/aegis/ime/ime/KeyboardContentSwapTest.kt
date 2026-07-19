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
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.ui.LetterCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    private fun letterFace(kv: KeyboardView, label: String): String {
        val key = kv.keyBoundsForTest().first { it.first.label == label }.first
        return kv.displayLabelForTest(key)
    }

    @Test fun real_layout_switch_is_instant_with_the_new_touch_geometry_already_live() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = attach(controller.get(), alphaKeyboard())
            val modes = kv.modeSwitchesForTest()
            assertNotNull(kv.centerOfLabelForTest("a"))

            kv.setLayout(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = false), false, false, Lang.CN)

            assertEquals("the mode change is counted once", modes + 1, kv.modeSwitchesForTest())
            assertNotNull("touch targets are the new layout in the same call", kv.centerOfLabelForTest("ABC"))
            assertNull("no stale key of the old layout remains hittable", kv.centerOfLabelForTest("a"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun layout_id_switches_are_instant_both_ways() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = attach(controller.get(), alphaKeyboard())
            val modes = kv.modeSwitchesForTest()

            kv.setLayout(Layouts.forId(LayoutId.SYMBOL, Lang.CN), false, false, Lang.CN)
            assertEquals(modes + 1, kv.modeSwitchesForTest())
            assertNotNull("the new page's keys are live in the same call", kv.boundsOfLabelForTest("€"))

            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
            assertEquals("the way back is instant too", modes + 2, kv.modeSwitchesForTest())
            assertNotNull(kv.centerOfLabelForTest("a"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun shift_and_lock_changes_render_the_new_faces_in_the_same_call() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = KeyboardView(ctx).apply {
                setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
            }
            attach(controller.get(), kv)
            val applies = kv.layoutAppliesForTest()
            val modes = kv.modeSwitchesForTest()
            assertEquals("a", letterFace(kv, "a"))

            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), true, false, Lang.EN)
            assertEquals("a shift change is a single apply", applies + 1, kv.layoutAppliesForTest())
            assertEquals("ONCE", kv.shiftRenderState())
            assertEquals("the shifted face renders in the same call", "A", letterFace(kv, "a"))

            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), true, true, Lang.EN)
            assertEquals("a lock change is a single apply", applies + 2, kv.layoutAppliesForTest())
            assertEquals("LOCK", kv.shiftRenderState())
            assertEquals("A", letterFace(kv, "a"))

            assertEquals("shift and lock changes are never mode switches", modes, kv.modeSwitchesForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun reduced_motion_shift_and_layout_changes_are_fully_instant() {
        animationsOff()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = attach(controller.get(), alphaKeyboard())

            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.CN), true, false, Lang.CN)
            assertEquals("the shift state lands in the same call", "ONCE", kv.shiftRenderState())

            kv.setLayout(Layouts.forId(LayoutId.SYMBOL, Lang.CN), false, false, Lang.CN)

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
            val applies = kv.layoutAppliesForTest()
            val modes = kv.modeSwitchesForTest()

            repeat(5) { kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN) }

            assertEquals("an unchanged storm applies nothing", applies, kv.layoutAppliesForTest())
            assertEquals(modes, kv.modeSwitchesForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun case_mode_change_renders_the_new_faces_in_the_same_call() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = KeyboardView(ctx).apply {
                setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
            }
            attach(controller.get(), kv)
            assertEquals("a", letterFace(kv, "a"))

            kv.caseMode = LetterCase.UPPER
            assertEquals("the new case renders in the same call", "A", letterFace(kv, "a"))

            kv.caseMode = LetterCase.UPPER
            assertEquals("re-setting the same case mode keeps the face", "A", letterFace(kv, "a"))

            kv.caseMode = LetterCase.LOWER
            assertEquals("a", letterFace(kv, "a"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun same_id_content_updates_apply_in_place() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = KeyboardView(ctx).apply {
                setLayout(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = false), false, false, Lang.CN)
            }
            attach(controller.get(), kv)
            val applies = kv.layoutAppliesForTest()
            val modes = kv.modeSwitchesForTest()

            kv.setLayout(Layouts.nine(Lang.CN, Layouts.ninePunctuation(listOf("→")), composing = true), false, false, Lang.CN)

            assertEquals("a same-id readout update is a real apply", applies + 1, kv.layoutAppliesForTest())
            assertEquals("…but never a mode switch (no per-keystroke strobe)", modes, kv.modeSwitchesForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun detached_shift_change_still_applies_instantly() {
        animationsOn()
        val kv = KeyboardView(ctx).apply {
            setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
        }
        kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), true, false, Lang.EN)
        assertEquals("ONCE", kv.shiftRenderState())
    }

    @Test fun language_toggle_is_a_single_instant_apply() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val kv = attach(controller.get(), alphaKeyboard())
            val applies = kv.layoutAppliesForTest()
            val modes = kv.modeSwitchesForTest()
            kv.setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
            assertEquals("a language toggle is a single apply", applies + 1, kv.layoutAppliesForTest())
            assertEquals("a language toggle is never a mode switch", modes, kv.modeSwitchesForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
