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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionSymmetryTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun animationsOn() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    private fun animationsOff() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

    private fun host(activity: Activity): FrameLayout {
        val host = FrameLayout(activity)
        activity.setContentView(host)
        return host
    }


    @Test fun hideNow_under_reduced_motion_reaches_gone_and_runs_end_action() {
        animationsOff()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val host = host(controller.get())
            val v = View(ctx).apply { alpha = 0.4f; translationY = 8f }.also { host.addView(it) }
            var ended = false
            Motion.hideNow(v) { ended = true }
            assertEquals("the hide jumps straight to GONE", View.GONE, v.visibility)
            assertTrue("the end action runs in the same call (the caller closes synchronously)", ended)
            assertEquals("and the transform is reset to rest", 0f, v.translationY, 0f)
            assertEquals(1f, v.alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun hideNow_when_detached_reaches_end_state_immediately() {
        animationsOn()
        val v = View(ctx)
        var ended = false
        Motion.hideNow(v) { ended = true }
        assertEquals(View.GONE, v.visibility)
        assertTrue(ended)
    }

    @Test fun hideNow_when_attached_and_animated_still_lands_gone_in_the_same_call() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val host = host(controller.get())
            val v = View(ctx).also { host.addView(it) }
            var ended = false
            Motion.hideNow(v) { ended = true }
            assertEquals("the hide never fades — GONE lands in the same call", View.GONE, v.visibility)
            assertTrue("the end action runs synchronously, attached or not", ended)
            assertEquals("the transform is reset to rest for the next show", 1f, v.alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }


    @Test fun coverSwap_under_reduced_motion_swaps_to_the_end_state_immediately() {
        animationsOff()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val host = host(controller.get())
            val outgoing = View(ctx).apply { visibility = View.VISIBLE }.also { host.addView(it) }
            val incoming = View(ctx).apply { visibility = View.GONE }.also { host.addView(it) }
            Motion.coverSwap(incoming, outgoing, Color.WHITE)
            assertEquals(View.GONE, outgoing.visibility)
            assertEquals(View.VISIBLE, incoming.visibility)
            assertEquals(1f, incoming.alpha, 0f)
            assertFalse("reduced motion leaves no cover residue", Motion.coverActiveForTest(incoming))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun coverSwap_when_attached_and_animated_shows_the_incoming_in_the_same_call() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val host = host(controller.get())
            val outgoing = View(ctx).apply { visibility = View.VISIBLE }.also { host.addView(it) }
            val incoming = View(ctx).apply { visibility = View.GONE }.also { host.addView(it) }
            Motion.coverSwap(incoming, outgoing, Color.WHITE)
            assertEquals("the outgoing view leaves in the same call", View.GONE, outgoing.visibility)
            assertEquals("the incoming view shows in the same call — the slot is never empty", View.VISIBLE, incoming.visibility)
            assertEquals("the incoming view is fully opaque from the first frame", 1f, incoming.alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }


    private fun inputView(): InputView = InputView(ctx)

    @Test fun edit_bar_opens_and_closes_symmetrically() {
        val iv = inputView()
        iv.showEditBar(true)
        assertTrue("edit bar shows", iv.isEditBarShowing())
        iv.showEditBar(false)
        assertFalse("edit bar close reaches GONE (symmetric exit, not left visible)", iv.isEditBarShowing())
    }

    @Test fun panel_open_then_close_reaches_the_keyboard_end_state() {
        val iv = inputView()
        iv.showPanel(View(ctx))
        assertTrue("panel shows", iv.panelShown)
        iv.showPanel(null)
        assertFalse("panel close reaches GONE (the leaving panel's exit runs then the keyboard returns)", iv.panelShown)
    }

    @Test fun copy_bar_swap_in_and_out_reaches_the_end_state() {
        val iv = inputView()
        iv.showCopyBar("hello")
        assertTrue("copy bar swaps in", iv.copyBarShown)
        iv.hideCopyBar()
        assertFalse("copy bar swaps back out to the candidate strip", iv.copyBarShown)
    }

    @Test fun panel_to_panel_switch_reaches_the_incoming_end_state() {
        val iv = inputView()
        val a = View(ctx)
        val b = View(ctx)
        iv.showPanel(a)
        assertTrue(iv.isPanelShowing(a))
        iv.showPanel(b)
        assertTrue("the outgoing panel's exit hands over to the incoming reveal", iv.isPanelShowing(b))
        assertFalse(iv.isPanelShowing(a))
    }

    @Test fun preedit_appear_and_disappear_reach_symmetric_end_states() {
        animationsOff()
        val pv = PreeditView(ctx)
        pv.setText("ni")
        assertEquals("appear lands shown", "ni", pv.shownTextForTest())
        assertEquals(1f, pv.alpha, 0f)
        pv.setText("")
        assertEquals("disappear lands cleared (symmetric exit, not an instant-only cut on one side)", "", pv.shownTextForTest())
        assertEquals("the band never leaves a half-faded rest state", 1f, pv.alpha, 0f)
        assertEquals(View.VISIBLE, pv.visibility)
    }
}
