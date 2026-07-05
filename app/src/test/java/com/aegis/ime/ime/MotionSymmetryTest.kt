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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * §3 — SYMMETRIC EXITS. Every overlay that opened with an animation now closes with the matching one
 * (the formerly-dead [Motion.hide] is revived + made a true inverse of [Motion.revealIn]; [Motion.swapIn] is a
 * symmetric cross-fade, not an instant pop). Two rulings per interaction: (1) when attached + animated the
 * close DEFERS (it animates, it doesn't snap); (2) under reduced motion / detached it reaches the exact end
 * state immediately (and runs its end action), so the IME closes synchronously. Plus the InputView-level end
 * states for the edit-bar, the extras panel, and the candidate↔copy-bar swap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotionSymmetryTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun animationsOn() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    private fun animationsOff() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

    private fun host(): FrameLayout {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = FrameLayout(activity)
        activity.setContentView(host)
        return host
    }

    // ---- Motion.hide: revived + symmetric --------------------------------------------------------------

    @Test fun hide_under_reduced_motion_reaches_gone_and_runs_end_action() {
        animationsOff()
        val host = host()
        val v = View(ctx).also { host.addView(it) }
        var ended = false
        Motion.hide(v, toward = Motion.EnterFrom.TOP) { ended = true }
        assertEquals("reduced motion jumps straight to GONE", View.GONE, v.visibility)
        assertTrue("the end action still runs (the caller closes synchronously)", ended)
        assertEquals("and the transform is reset to rest", 0f, v.translationY, 0f)
        assertEquals(1f, v.alpha, 0f)
    }

    @Test fun hide_when_detached_reaches_end_state_immediately() {
        animationsOn()
        val v = View(ctx) // never attached — no frame loop
        var ended = false
        Motion.hide(v) { ended = true }
        assertEquals(View.GONE, v.visibility)
        assertTrue(ended)
    }

    @Test fun hide_when_attached_and_animated_defers_the_gone_until_the_fade_ends() {
        animationsOn()
        val host = host()
        val v = View(ctx).also { host.addView(it) }
        var ended = false
        Motion.hide(v) { ended = true }
        assertEquals("the fade defers GONE (it animates, never snaps)", View.VISIBLE, v.visibility)
        assertFalse("the end action waits for the fade to finish", ended)
    }

    // ---- Motion.swapIn: symmetric cross-fade -----------------------------------------------------------

    @Test fun swapIn_under_reduced_motion_swaps_to_the_end_state_immediately() {
        animationsOff()
        val host = host()
        val outgoing = View(ctx).apply { visibility = View.VISIBLE }.also { host.addView(it) }
        val incoming = View(ctx).apply { visibility = View.GONE }.also { host.addView(it) }
        Motion.swapIn(incoming, outgoing)
        assertEquals(View.GONE, outgoing.visibility)
        assertEquals(View.VISIBLE, incoming.visibility)
        assertEquals(1f, incoming.alpha, 0f)
    }

    @Test fun swapIn_when_attached_and_animated_defers_showing_the_incoming() {
        animationsOn()
        val host = host()
        val outgoing = View(ctx).apply { visibility = View.VISIBLE }.also { host.addView(it) }
        val incoming = View(ctx).apply { visibility = View.GONE }.also { host.addView(it) }
        Motion.swapIn(incoming, outgoing)
        assertEquals("outgoing fades out first (still visible)", View.VISIBLE, outgoing.visibility)
        assertEquals("incoming only appears at the trough — never both at full height at once", View.GONE, incoming.visibility)
    }

    // ---- InputView overlay end states (detached ⇒ the reduced-motion end state) ------------------------

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
}
