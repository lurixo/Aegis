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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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


    private fun drawnPixel(view: View): Int {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap.getPixel(0, 0)
    }

    @Test fun coverThrough_under_reduced_motion_swaps_immediately_at_full_opacity() {
        animationsOff()
        val v = View(ctx)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            attach(controller.get(), v).apply { alpha = 0.2f }
            var swapped = false
            Motion.coverThrough(v, Color.WHITE) { swapped = true }
            assertTrue("reduced motion runs the content swap immediately", swapped)
            assertEquals("reduced motion jumps straight to full opacity", 1f, v.alpha, 0f)
            assertFalse("reduced motion leaves no cover residue", Motion.coverActiveForTest(v))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun coverThrough_when_detached_swaps_immediately() {
        animationsOn()
        val v = View(ctx)
        var swapped = false
        Motion.coverThrough(v, Color.WHITE) { swapped = true }
        assertTrue("a detached view swaps immediately (no frame loop to run the fade)", swapped)
        assertEquals(1f, v.alpha, 0f)
        assertFalse(Motion.coverActiveForTest(v))
    }

    @Test fun coverThrough_when_attached_and_animated_swaps_synchronously_and_never_dips() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val v = attach(controller.get(), View(ctx).apply { setBackgroundColor(Color.GREEN) }, 80, 80)
            var swapped = false
            Motion.coverThrough(v, Color.WHITE) {
                swapped = true
                v.setBackgroundColor(Color.RED)
            }
            assertTrue("the content swap runs synchronously, never deferred to a trough", swapped)
            assertEquals(View.VISIBLE, v.visibility)
            assertEquals("the content stays fully opaque under the residue", 1f, v.alpha, 0f)
            assertTrue("the residue hold runs on top of the new content", Motion.coverActiveForTest(v))
            assertEquals("the residue holds the old face at full strength", Color.GREEN, drawnPixel(v))
            repeat(4) {
                shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
                assertEquals("no frame ever dips the content", 1f, v.alpha, 0f)
                assertEquals(View.VISIBLE, v.visibility)
                assertEquals("combined opacity never drops below one", 0xFF, Color.alpha(drawnPixel(v)))
                val px = drawnPixel(v)
                assertTrue("a frame is either the held old face or the new face — never a fading blend", px == Color.GREEN || px == Color.RED)
                if (Motion.coverActiveForTest(v)) assertEquals("while present the residue stays at full opacity", Color.GREEN, px)
            }
            assertFalse("the residue is removed in one step once the hold elapses", Motion.coverActiveForTest(v))
            assertEquals("the settled view shows the new face", Color.RED, drawnPixel(v))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun coverPair(activity: Activity): Pair<View, View> {
        val host = FrameLayout(activity)
        val outgoing = View(ctx).apply { setBackgroundColor(Color.GREEN) }
        val incoming = View(ctx).apply {
            setBackgroundColor(Color.RED)
            visibility = View.GONE
        }
        host.addView(outgoing, FrameLayout.LayoutParams(80, 80))
        host.addView(incoming, FrameLayout.LayoutParams(80, 80))
        activity.setContentView(host)
        host.measure(
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, 80, 80)
        return incoming to outgoing
    }

    @Test fun coverSwap_keeps_the_incoming_fully_opaque_while_the_residue_holds() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val (incoming, outgoing) = coverPair(controller.get())

            Motion.coverSwap(incoming, outgoing, Color.WHITE)

            assertEquals("the incoming face shows immediately", View.VISIBLE, incoming.visibility)
            assertEquals(1f, incoming.alpha, 0f)
            assertEquals("the outgoing view is gone in the same call", View.GONE, outgoing.visibility)
            assertTrue("the residue hold runs on the incoming view", Motion.coverActiveForTest(incoming))
            assertEquals("the residue holds the outgoing face at full strength", Color.GREEN, drawnPixel(incoming))
            repeat(4) {
                shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
                assertEquals("no frame ever dips the incoming content", 1f, incoming.alpha, 0f)
                assertEquals(View.VISIBLE, incoming.visibility)
                val px = drawnPixel(incoming)
                assertEquals("combined opacity never drops below one", 0xFF, Color.alpha(px))
                assertTrue("a frame is either the held old face or the new face — never a fading blend", px == Color.GREEN || px == Color.RED)
                if (Motion.coverActiveForTest(incoming)) assertEquals("while present the residue stays at full opacity", Color.GREEN, px)
            }
            assertFalse("the residue is removed in one step once the hold elapses", Motion.coverActiveForTest(incoming))
            assertEquals("the settled swap shows the incoming face", Color.RED, drawnPixel(incoming))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun coverSwap_toward_the_settled_state_is_an_idempotent_no_op() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val (incoming, outgoing) = coverPair(controller.get())
            Motion.coverSwap(incoming, outgoing, Color.WHITE)
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            assertFalse(Motion.coverActiveForTest(incoming))

            repeat(3) { Motion.coverSwap(incoming, outgoing, Color.WHITE) }

            assertFalse("a repeated swap toward the settled state starts nothing", Motion.coverActiveForTest(incoming))
            assertEquals(1f, incoming.alpha, 0f)
            assertEquals(View.VISIBLE, incoming.visibility)
            assertEquals(View.GONE, outgoing.visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun cancelCover_and_reset_land_the_final_state_with_no_residue() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val v = attach(controller.get(), View(ctx).apply { setBackgroundColor(Color.GREEN) }, 80, 80)
            Motion.coverThrough(v, Color.WHITE) { v.setBackgroundColor(Color.RED) }
            assertTrue(Motion.coverActiveForTest(v))
            Motion.cancelCover(v)
            assertFalse("cancel ends the residue hold", Motion.coverActiveForTest(v))
            assertEquals("cancel jumps to the final content with the overlay cleared", Color.RED, drawnPixel(v))
            assertEquals(1f, v.alpha, 0f)
            assertEquals(View.VISIBLE, v.visibility)

            v.setBackgroundColor(Color.GREEN)
            Motion.coverThrough(v, Color.WHITE) { v.setBackgroundColor(Color.RED) }
            assertTrue(Motion.coverActiveForTest(v))
            Motion.reset(v)
            assertFalse("reset ends the residue hold", Motion.coverActiveForTest(v))
            assertEquals(Color.RED, drawnPixel(v))
            assertEquals(1f, v.alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun snapshot_gates_to_the_instant_branch_when_detached_zero_sized_or_reduced() {
        animationsOn()
        assertNull("a detached view yields no snapshot", Motion.snapshot(View(ctx), Color.WHITE))
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val zero = attach(controller.get(), View(ctx))
            assertNull("a zero-sized view yields no snapshot", Motion.snapshot(zero, Color.WHITE))
            val sized = attach(controller.get(), View(ctx), 80, 80)
            assertNotNull("an attached, sized view yields the cover snapshot", Motion.snapshot(sized, Color.WHITE))
            animationsOff()
            assertNull("reduced motion yields no snapshot", Motion.snapshot(sized, Color.WHITE))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun snapshot_captures_the_scrolled_viewport() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(View(ctx).apply { setBackgroundColor(Color.BLUE) }, LinearLayout.LayoutParams(40, 40))
            column.addView(View(ctx).apply { setBackgroundColor(Color.MAGENTA) }, LinearLayout.LayoutParams(40, 40))
            val scroll = attach(activity, ScrollView(activity).apply { addView(column) }, 40, 40)
            scroll.scrollTo(0, 40)
            val snap = requireNotNull(Motion.snapshot(scroll, Color.WHITE))
            assertEquals("the snapshot shows what the scrolled viewport showed", Color.MAGENTA, snap.getPixel(0, 0))
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


    @Test fun candidate_strip_covers_on_role_change_but_not_on_candidate_updates() {
        val cv = CandidateView(ctx)
        val start = cv.contentTransitionsForTest()
        cv.setContent(listOf("你"), "ni")
        assertEquals("toolbar→candidates covers once", start + 1, cv.contentTransitionsForTest())
        cv.setContent(listOf("你", "好"), "nihao")
        cv.setContent(listOf("你", "好", "吗"), "nihaoma")
        assertEquals("candidate→candidate updates must NOT cover (fluidity, no strobe)", start + 1, cv.contentTransitionsForTest())
        cv.setContent(emptyList(), "")
        assertEquals("candidates→toolbar covers once", start + 2, cv.contentTransitionsForTest())
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


    @Test fun settings_motion_durations_stay_on_their_own_literals() {
        assertEquals(200, SettingsMotion.DURATION_NAV)
        assertEquals(150, SettingsMotion.DURATION_FADE_IN)
        assertEquals(100, SettingsMotion.DURATION_FADE_OUT)
        assertEquals(200, SettingsMotion.DURATION_STATE)
    }
}
