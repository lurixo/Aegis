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
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreeditFadeTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun animationsOn() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    private fun animationsOff() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

    private fun attach(activity: Activity, view: PreeditView): PreeditView {
        val host = FrameLayout(activity)
        host.addView(view)
        activity.setContentView(host)
        return view
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)

    @Test fun clearing_lands_the_emptied_band_in_the_same_call() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pv = attach(controller.get(), PreeditView(ctx))
            pv.setText("ni")
            settle()
            assertEquals(1f, pv.alpha, 0f)

            pv.setText("")

            assertEquals("the clear lands in the same call — no ghost text lingers", "", pv.shownTextForTest())
            assertEquals("the slot must not collapse on the clear", View.VISIBLE, pv.visibility)
            assertEquals("alpha stays at rest for the next appear", 1f, pv.alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun clearing_under_reduced_motion_is_instant() {
        animationsOff()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pv = attach(controller.get(), PreeditView(ctx))
            pv.setText("ni")
            pv.setText("")
            assertEquals("reduced motion clears in the same call", "", pv.shownTextForTest())
            assertEquals(1f, pv.alpha, 0f)
            assertEquals(View.VISIBLE, pv.visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun clearing_while_detached_is_instant() {
        animationsOn()
        val pv = PreeditView(ctx)
        pv.setText("ni")
        pv.setText("")
        assertEquals("", pv.shownTextForTest())
        assertEquals(1f, pv.alpha, 0f)
    }

    @Test fun new_text_right_after_a_clear_appears_in_the_same_call() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pv = attach(controller.get(), PreeditView(ctx))
            pv.setText("ni")
            settle()
            pv.setText("")
            assertEquals("", pv.shownTextForTest())

            pv.setText("hao")

            assertEquals("the new text shows in the same call", "hao", pv.shownTextForTest())
            settle()
            assertEquals("hao", pv.shownTextForTest())
            assertEquals("the earlier clear never dims the newly shown text", 1f, pv.alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun updates_between_non_empty_texts_swap_in_place_without_fading() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pv = attach(controller.get(), PreeditView(ctx))
            pv.setText("ni")
            settle()
            pv.setText("ni'hao")
            assertEquals("a composing update repaints in place", "ni'hao", pv.shownTextForTest())
            assertEquals("no fade restarts on an update (fluidity, no strobe)", 1f, pv.alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun detach_after_a_clear_leaves_no_ghost_tab() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val pv = attach(controller.get(), PreeditView(ctx))
            pv.setText("ni")
            settle()
            pv.setText("")
            assertEquals("", pv.shownTextForTest())

            (pv.parent as FrameLayout).removeView(pv)

            assertEquals("no ghost tab survives a detach", "", pv.shownTextForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
