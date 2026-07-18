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
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class PanelTransitionSymmetryTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun animationsOn() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    private fun animationsOff() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

    private fun attach(activity: Activity, iv: InputView): InputView {
        val host = FrameLayout(activity)
        host.addView(iv)
        activity.setContentView(host)
        return iv
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)

    private fun innerView(iv: InputView, name: String): View =
        InputView::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(iv) as View
        }

    private fun keyboard(iv: InputView): View = innerView(iv, "keyboardView")
    private fun container(iv: InputView): ViewGroup = innerView(iv, "panelContainer") as ViewGroup

    @Test fun keyboard_to_panel_lets_the_keyboard_exit_before_the_panel_attaches() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = attach(controller.get(), InputView(ctx))
            val panel = View(ctx)

            iv.showPanel(panel)

            assertEquals("the keyboard stays visible while its exit fade runs", View.VISIBLE, keyboard(iv).visibility)
            assertNull("the panel only attaches once the outgoing settles", panel.parent)
            assertTrue("the logical panel state flips at once", iv.panelShown)
            assertFalse("only one of keyboard and panel occupies the slot", container(iv).visibility == View.VISIBLE)

            settle()
            assertEquals("the exit hands over to the panel reveal", View.GONE, keyboard(iv).visibility)
            assertTrue(panel.parent === container(iv))
            assertEquals(View.VISIBLE, container(iv).visibility)
            assertEquals(1f, panel.alpha, 0f)
            assertEquals(0f, panel.translationY, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun panel_to_panel_lets_the_outgoing_exit_before_the_incoming_attaches() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = attach(controller.get(), InputView(ctx))
            val a = View(ctx)
            val b = View(ctx)
            iv.showPanel(a)
            settle()
            assertTrue(a.parent === container(iv))

            iv.showPanel(b)

            assertTrue("the outgoing panel keeps its slot while fading", a.parent === container(iv))
            assertEquals(View.VISIBLE, a.visibility)
            assertNull("the incoming panel waits for the exit to settle", b.parent)
            assertEquals("the slot never doubles in height", View.VISIBLE, container(iv).visibility)

            settle()
            assertNull("the outgoing panel is detached at the handover", a.parent)
            assertTrue(b.parent === container(iv))
            assertEquals(1f, b.alpha, 0f)
            assertEquals(0f, b.translationY, 0f)
            assertTrue(iv.isPanelShowing(b))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun reduced_motion_takes_the_settle_path_to_the_same_end_states() {
        animationsOff()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = attach(controller.get(), InputView(ctx))
            val a = View(ctx)
            val b = View(ctx)

            iv.showPanel(a)
            assertTrue("reduced motion attaches in the same call", a.parent === container(iv))
            assertEquals(View.GONE, keyboard(iv).visibility)

            iv.showPanel(b)
            assertNull(a.parent)
            assertTrue(b.parent === container(iv))
            assertEquals(1f, b.alpha, 0f)

            iv.showPanel(null)
            assertNull(b.parent)
            assertEquals(View.GONE, container(iv).visibility)
            assertEquals(View.VISIBLE, keyboard(iv).visibility)
            assertFalse(iv.panelShown)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun a_transition_mash_settles_each_pending_handover_with_no_lost_panels() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = attach(controller.get(), InputView(ctx))
            val a = View(ctx)
            val b = View(ctx)
            val c = View(ctx)

            iv.showPanel(a)
            iv.showPanel(b)
            assertTrue("the interrupted keyboard exit lands its attach before the next leg", a.parent === container(iv))
            iv.showPanel(c)
            assertTrue("the interrupted a→b leg lands b before c starts", b.parent === container(iv))
            assertNull(a.parent)
            iv.showPanel(null)
            assertTrue(c.parent === container(iv))
            assertFalse(iv.hasOverlay())

            settle()
            assertNull("the final close leaves no orphaned panel", c.parent)
            assertEquals(0, container(iv).childCount)
            assertEquals(View.GONE, container(iv).visibility)
            assertEquals("no orphaned GONE keyboard after the mash", View.VISIBLE, keyboard(iv).visibility)
            assertEquals(1f, keyboard(iv).alpha, 0f)
            assertFalse(iv.panelShown)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun show_immediately_mid_transition_settles_then_attaches_instantly() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = attach(controller.get(), InputView(ctx))
            val a = View(ctx)
            val clip = View(ctx)

            iv.showPanel(a)
            iv.showPanelImmediately(clip)

            assertTrue("the immediate path never waits on animation", clip.parent === container(iv))
            assertEquals(1f, clip.alpha, 0f)
            assertEquals(0f, clip.translationY, 0f)
            assertEquals(View.GONE, keyboard(iv).visibility)
            assertTrue(iv.isPanelShowing(clip))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun panel_to_keyboard_keeps_the_existing_exit_then_keyboard_return() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = attach(controller.get(), InputView(ctx))
            val a = View(ctx)
            iv.showPanel(a)
            settle()

            iv.showPanel(null)

            assertEquals("the leaving panel fades before the keyboard returns", View.VISIBLE, container(iv).visibility)
            assertTrue(a.parent === container(iv))
            assertEquals("the keyboard waits for the handover", View.GONE, keyboard(iv).visibility)

            settle()
            assertEquals(View.GONE, container(iv).visibility)
            assertEquals(View.VISIBLE, keyboard(iv).visibility)
            assertFalse(iv.panelShown)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun detach_mid_transition_settles_the_pending_handover() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = attach(controller.get(), InputView(ctx))
            val a = View(ctx)
            iv.showPanel(a)
            assertNull(a.parent)

            (iv.parent as FrameLayout).removeView(iv)

            assertTrue("detach lands the pending attach so the panel is not lost", a.parent === container(iv))
            assertTrue(iv.panelShown)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
