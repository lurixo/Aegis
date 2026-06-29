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
        layoutHost(iv)
        return iv
    }

    private fun layoutHost(iv: InputView) {
        val host = iv.parent as FrameLayout
        val density = iv.resources.displayMetrics.density
        val width = (360 * density).toInt()
        val height = (560 * density).toInt()
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, width, height)
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)

    private fun innerView(iv: InputView, name: String): View =
        InputView::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(iv) as View
        }

    private fun keyboard(iv: InputView): View = innerView(iv, "keyboardView")
    private fun container(iv: InputView): ViewGroup = innerView(iv, "panelContainer") as ViewGroup

    @Test fun keyboard_to_panel_swaps_synchronously_with_a_residual_cover_hold() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = attach(controller.get(), InputView(ctx))
            settle()
            val panel = View(ctx)

            iv.showPanel(panel)

            assertTrue("the panel attaches in the same call", panel.parent === container(iv))
            assertEquals("the keyboard leaves in the same call", View.GONE, keyboard(iv).visibility)
            assertEquals(View.VISIBLE, container(iv).visibility)
            assertEquals("the incoming panel is fully opaque from the first frame", 1f, panel.alpha, 0f)
            assertEquals(0f, panel.translationY, 0f)
            assertTrue(iv.panelShown)
            assertTrue("the old keyface survives only as a cover residue", Motion.coverActiveForTest(container(iv)))

            settle()
            assertFalse("the residue ends on its own", Motion.coverActiveForTest(container(iv)))
            assertTrue(panel.parent === container(iv))
            assertEquals(1f, panel.alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun panel_to_panel_swaps_synchronously_with_a_residual_cover_hold() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = attach(controller.get(), InputView(ctx))
            val a = View(ctx)
            val b = View(ctx)
            iv.showPanel(a)
            settle()
            layoutHost(iv)
            assertTrue(a.parent === container(iv))

            iv.showPanel(b)

            assertNull("the outgoing panel detaches in the same call", a.parent)
            assertTrue("the incoming panel attaches in the same call", b.parent === container(iv))
            assertEquals("the slot never doubles in height", View.VISIBLE, container(iv).visibility)
            assertEquals(1f, b.alpha, 0f)
            assertEquals(0f, b.translationY, 0f)
            assertTrue(iv.isPanelShowing(b))
            assertTrue("the outgoing face survives only as a cover residue", Motion.coverActiveForTest(container(iv)))

            settle()
            assertFalse(Motion.coverActiveForTest(container(iv)))
            assertTrue(b.parent === container(iv))
            assertEquals(1f, b.alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun reduced_motion_takes_the_instant_path_to_the_same_end_states() {
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

    @Test fun a_transition_mash_lands_every_step_with_no_lost_panels() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = attach(controller.get(), InputView(ctx))
            val a = View(ctx)
            val b = View(ctx)
            val c = View(ctx)

            iv.showPanel(a)
            assertTrue("the first leg lands its attach at once", a.parent === container(iv))
            iv.showPanel(b)
            assertTrue("the a→b leg lands b at once", b.parent === container(iv))
            assertNull(a.parent)
            iv.showPanel(c)
            assertTrue("the b→c leg lands c at once", c.parent === container(iv))
            assertNull(b.parent)
            iv.showPanel(null)
            assertNull("the final close leaves no orphaned panel", c.parent)
            assertFalse(iv.hasOverlay())
            assertEquals(0, container(iv).childCount)
            assertEquals(View.GONE, container(iv).visibility)
            assertEquals("no orphaned GONE keyboard after the mash", View.VISIBLE, keyboard(iv).visibility)
            assertEquals(1f, keyboard(iv).alpha, 0f)
            assertFalse(iv.panelShown)

            settle()
            assertEquals("the settled state is unchanged once the residues finish", View.VISIBLE, keyboard(iv).visibility)
            assertEquals(1f, keyboard(iv).alpha, 0f)
            assertEquals(View.GONE, container(iv).visibility)
            assertFalse(Motion.coverActiveForTest(keyboard(iv)))
            assertFalse(Motion.coverActiveForTest(container(iv)))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun show_immediately_mid_switch_attaches_instantly_with_no_residue() {
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
            assertFalse("the immediate path clears any running residue", Motion.coverActiveForTest(container(iv)))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun panel_to_keyboard_swaps_synchronously_with_a_residual_cover_hold() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = attach(controller.get(), InputView(ctx))
            val a = View(ctx)
            iv.showPanel(a)
            settle()
            layoutHost(iv)

            iv.showPanel(null)

            assertEquals("the keyboard returns in the same call", View.VISIBLE, keyboard(iv).visibility)
            assertEquals(1f, keyboard(iv).alpha, 0f)
            assertEquals("the panel slot empties in the same call", View.GONE, container(iv).visibility)
            assertNull(a.parent)
            assertFalse(iv.panelShown)
            assertTrue("the leaving panel survives only as a cover residue on the keyboard", Motion.coverActiveForTest(keyboard(iv)))

            settle()
            assertFalse(Motion.coverActiveForTest(keyboard(iv)))
            assertEquals(View.VISIBLE, keyboard(iv).visibility)
            assertEquals(1f, keyboard(iv).alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun detach_keeps_the_attached_panel_and_ends_any_residue() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = attach(controller.get(), InputView(ctx))
            val a = View(ctx)
            iv.showPanel(a)
            assertTrue(a.parent === container(iv))

            (iv.parent as FrameLayout).removeView(iv)

            assertTrue("the shown panel survives a detach", a.parent === container(iv))
            assertTrue(iv.panelShown)
            assertFalse(Motion.coverActiveForTest(container(iv)))
            assertFalse(Motion.coverActiveForTest(keyboard(iv)))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
