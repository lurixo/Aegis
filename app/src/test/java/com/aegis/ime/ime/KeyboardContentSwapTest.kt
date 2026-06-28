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
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.KeyboardLayout
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.ui.LetterCase
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

            kv.setLayout(Layouts.nine(Layouts.ninePunctuation(), composing = false), false, false, Lang.CN)

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
                setLayout(Layouts.nine(Layouts.ninePunctuation(), composing = false), false, false, Lang.CN)
            }
            attach(controller.get(), kv)
            val applies = kv.layoutAppliesForTest()
            val modes = kv.modeSwitchesForTest()

            kv.setLayout(Layouts.nine(Layouts.ninePunctuation(listOf("→")), composing = true), false, false, Lang.CN)

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

    private fun hosted(layout: KeyboardLayout, shifted: Boolean = false, language: Lang = Lang.CN): Pair<FrameLayout, KeyboardView> {
        val width = (360 * density).toInt()
        val kv = KeyboardView(ctx).apply { setLayout(layout, shifted, false, language) }
        val host = FrameLayout(ctx)
        host.addView(kv, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT))
        layOut(host)
        return host to kv
    }

    private fun layOut(host: FrameLayout) {
        host.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        host.layout(0, 0, host.measuredWidth, host.measuredHeight)
    }

    private var gestureClock = 0L

    private fun gestureOutcomes(kv: KeyboardView): List<String> {
        val outcomes = ArrayList<String>()
        kv.onKey = { outcomes += "key ${it.action} ${it.label} ${it.output}" }
        kv.onBackspaceSwipe = { outcomes += "backspace swipe $it" }
        val slide = 30f * density
        for (row in 0 until 9) for (column in 0 until 13) {
            val x = kv.width * (column + 0.5f) / 13f
            val y = kv.height * (row + 0.5f) / 9f
            for ((dx, dy) in listOf(0f to 0f, 3f to 2f, slide to 0f, 0f to -slide, 0f to slide)) {
                outcomes += "gesture $row $column $dx $dy"
                val down = gestureClock
                kv.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
                kv.dispatchTouchEvent(MotionEvent.obtain(down, down + 16, MotionEvent.ACTION_MOVE, x + dx / 2f, y + dy / 2f, 0))
                kv.dispatchTouchEvent(MotionEvent.obtain(down, down + 32, MotionEvent.ACTION_MOVE, x + dx, y + dy, 0))
                kv.dispatchTouchEvent(MotionEvent.obtain(down, down + 48, MotionEvent.ACTION_UP, x + dx, y + dy, 0))
                gestureClock += 1000
            }
        }
        return outcomes
    }

    private fun assertSameTouchGeometry(label: String, expected: KeyboardView, actual: KeyboardView) {
        assertEquals("$label size", expected.width to expected.height, actual.width to actual.height)
        assertEquals("$label key faces", expected.keyBoundsForTest(), actual.keyBoundsForTest())
        assertEquals("$label hit areas", expected.keyHitBoundsForTest(), actual.keyHitBoundsForTest())
        assertEquals("$label reading column keys", expected.scrollColumnKeysForTest(), actual.scrollColumnKeysForTest())
        if (expected.scrollColumnKeysForTest().isNotEmpty()) {
            assertEquals("$label reading column", expected.scrollRegionForTest(), actual.scrollRegionForTest())
            assertEquals("$label reading cells", expected.scrollCellHeightForTest(), actual.scrollCellHeightForTest(), 0f)
        }
        val outcomes = gestureOutcomes(actual)
        assertEquals("$label DOWN/MOVE/UP outcomes", gestureOutcomes(expected), outcomes)
        assertTrue("$label the sweep reaches every key", actual.keyBoundsForTest().all { (key, _) -> outcomes.any { it.startsWith("key ${key.action} ${key.label} ") } })
        assertTrue("$label the sweep reaches the reading column", actual.scrollColumnKeysForTest().isEmpty() || outcomes.any { it.startsWith("key ${actual.scrollColumnKeysForTest().first().action} ") })
    }

    @Test fun nine_reading_column_updates_skip_the_layout_request_but_refresh_hits() {
        val (host, kv) = hosted(Layouts.nine(Layouts.ninePunctuation(), composing = false))
        val readings = listOf("ni", "mi", "mo", "ni'", "yi", "zi", "si").map {
            Key(it, output = it, action = KeyAction.PICK_READING, weight = 0.85f)
        } + Key("6", action = KeyAction.PICK_DIGIT, weight = 0.85f)
        val next = Layouts.nine(readings, composing = true)

        kv.setLayout(next, false, false, Lang.CN)

        assertFalse("a reading column update keeps the row count, so no layout pass", kv.isLayoutRequested)
        assertFalse(host.isLayoutRequested)
        assertEquals("the update itself still applies", listOf("ni", "mi", "mo", "ni'", "yi", "zi", "si", "6"), kv.scrollColumnKeysForTest().map { it.label })
        assertSameTouchGeometry("nine", hosted(next).second, kv)

        kv.setLayout(Layouts.nine(readings.drop(3), composing = true), false, false, Lang.CN)
        assertFalse("a shorter reading column still skips the layout pass", kv.isLayoutRequested)
        assertSameTouchGeometry("nine shorter", hosted(Layouts.nine(readings.drop(3), composing = true)).second, kv)
    }

    @Test fun alpha_face_updates_skip_the_layout_request_but_refresh_hits() {
        val (host, kv) = hosted(Layouts.forId(LayoutId.ALPHA, Lang.CN, composing = false))
        val composingFace = Layouts.forId(LayoutId.ALPHA, Lang.CN, composing = true)

        kv.setLayout(composingFace, false, false, Lang.CN)
        assertFalse("a composing face swap keeps the sizing, so no layout pass", kv.isLayoutRequested)
        assertFalse(host.isLayoutRequested)
        assertSameTouchGeometry("alpha composing", hosted(composingFace).second, kv)

        val english = Layouts.forId(LayoutId.ALPHA, Lang.EN)
        kv.setLayout(english, true, false, Lang.EN)
        assertFalse("a shifted language swap keeps the sizing", kv.isLayoutRequested)
        assertSameTouchGeometry("alpha shifted english", hosted(english, shifted = true, language = Lang.EN).second, kv)

        val symbols = Layouts.forId(LayoutId.SYMBOL, Lang.EN)
        kv.setLayout(symbols, false, false, Lang.EN)
        assertFalse("a same-sizing page swap keeps the sizing", kv.isLayoutRequested)
        assertSameTouchGeometry("symbols", hosted(symbols, language = Lang.EN).second, kv)
    }

    @Test fun sizing_changes_still_request_a_layout_pass() {
        val (host, kv) = hosted(Layouts.forId(LayoutId.ALPHA, Lang.CN))

        kv.setLayout(Layouts.nine(Layouts.ninePunctuation()), false, false, Lang.CN)
        assertTrue("fractional cells change the dock sizing", kv.isLayoutRequested)
        assertTrue(host.isLayoutRequested)
        layOut(host)
        assertFalse(kv.isLayoutRequested)

        val numbers = Layouts.forId(LayoutId.NUMBER, Lang.CN)
        kv.setLayout(numbers, false, false, Lang.CN)
        assertTrue("leaving fractional cells changes the dock sizing", kv.isLayoutRequested)
        layOut(host)
        val fourRowHeight = kv.height

        val threeRows = numbers.copy(rows = numbers.rows.drop(1), rowCount = 3)
        kv.setLayout(threeRows, false, false, Lang.CN)
        assertTrue("a row count change requests a layout pass", kv.isLayoutRequested)
        assertTrue(host.isLayoutRequested)
        layOut(host)
        assertTrue("the pass measures the new row count", kv.height < fourRowHeight)
        assertSameTouchGeometry("three rows", hosted(threeRows).second, kv)
    }
}
