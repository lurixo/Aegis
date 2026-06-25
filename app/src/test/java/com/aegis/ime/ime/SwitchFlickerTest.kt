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
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.ime.theme.ImePalette
import java.time.Duration
import org.junit.Assert.assertEquals
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
class SwitchFlickerTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT

    @Test fun panel_slot_carries_an_opaque_keyboard_floor() {
        val iv = InputView(ctx)
        iv.applyPalette(light)
        val floor = iv.panelFloorColorForTest()
        assertEquals("the panel slot must be painted the keyboard-floor colour", light.keyboardBg, floor)
        assertEquals("…and it must be fully opaque so an alpha-0 panel never reveals the window", 0xFF, Color.alpha(floor!!))
    }

    @Test fun repeated_editor_restores_keep_the_settled_edit_bar_opaque() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val input = attached(activity, InputView(activity).apply { applyPalette(light) })
            layoutInput(input)
            val bar = descendant<EditBarView>(input)
            input.showEditBar(true)
            flushMotion()
            assertEquals(View.VISIBLE, bar.visibility)
            assertEquals(1f, bar.alpha, 0f)

            repeat(3) {
                input.showEditBar(true)
                assertEquals("a repeated show never knocks the settled bar transparent", 1f, bar.alpha, 0f)
                assertEquals(View.VISIBLE, bar.visibility)
            }
            flushMotion()
            assertEquals(1f, bar.alpha, 0f)
            assertTrue(input.isEditBarShowing())

            input.showEditBar(false)
            assertEquals("the close lands GONE in the same call", View.GONE, bar.visibility)
            input.showEditBar(true)
            assertEquals("a genuine reopen shows the bar instantly at full opacity", 1f, bar.alpha, 0f)
            assertEquals(View.VISIBLE, bar.visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun layoutInput(input: InputView) {
        val host = input.parent as FrameLayout
        val density = input.resources.displayMetrics.density
        val width = (360 * density).toInt()
        val height = (560 * density).toInt()
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, width, height)
    }

    private inline fun <reified T : View> descendant(root: View): T {
        val found = ArrayList<T>()
        val stack = ArrayDeque<View>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (v is T) found.add(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
        }
        return found.single()
    }

    private fun <T : View> attached(activity: Activity, view: T): T {
        val host = FrameLayout(activity)
        host.addView(view)
        activity.setContentView(host)
        return view
    }

    private fun flushMotion() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
}
