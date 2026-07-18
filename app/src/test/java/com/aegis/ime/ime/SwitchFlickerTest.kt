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
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.ime.theme.ImePalette
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private fun floorColor(bg: android.graphics.drawable.Drawable?): Int? = (bg as? ColorDrawable)?.color

    @Test fun panel_slot_carries_an_opaque_keyboard_floor() {
        val iv = InputView(ctx)
        iv.applyPalette(light)
        val floor = iv.panelFloorColorForTest()
        assertEquals("the panel slot must be painted the keyboard-floor colour", light.keyboardBg, floor)
        assertEquals("…and it must be fully opaque so an alpha-0 panel never reveals the window", 0xFF, Color.alpha(floor!!))
    }

    @Test fun clipboard_open_starts_fully_opaque_with_current_content() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val host = FrameLayout(activity)
            val input = InputView(activity)
            host.addView(input)
            activity.setContentView(host)
            val clipboard = ClipboardView(activity).apply {
                historyProvider = { listOf("current clip") }
                applyPalette(light)
            }

            input.showPanelImmediately(clipboard)

            assertEquals(1f, clipboard.alpha, 0f)
            assertEquals(0f, clipboard.translationY, 0f)
            assertEquals(listOf("current clip"), clipboard.listRowTextsForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun composing_dismisses_the_copy_bar_once_during_its_animated_exit() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val input = InputView(activity)
            var dismissals = 0
            input.onCopyDismiss = { dismissals++ }
            input.showCopyBar("copied")
            val host = FrameLayout(activity)
            host.addView(input)
            activity.setContentView(host)
            assertTrue(input.copyBarShown)

            input.showCandidates(listOf("你"), "ni", listOf("ni"))

            assertEquals(1, dismissals)
            assertTrue(input.copyBarShown)
            input.showCandidates(listOf("你好", "你"), "nihao", listOf("ni"))
            assertEquals(1, dismissals)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun every_panel_root_carries_an_opaque_floor() {
        for ((name, bg) in listOf(
            "emoji" to floorColor(EmojiView(ctx).apply { applyPalette(light) }.background),
            "symbols" to floorColor(SymbolsView(ctx).apply { applyPalette(light) }.background),
        )) {
            assertEquals("$name panel floor colour", light.keyboardBg, bg)
            assertEquals("$name panel floor must be opaque", 0xFF, Color.alpha(bg!!))
        }
    }

    private fun <T : View> attached(activity: Activity, view: T): T {
        val host = FrameLayout(activity)
        host.addView(view)
        activity.setContentView(host)
        return view
    }

    private fun flushMotion() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    @Test fun emoji_category_switch_defers_the_swap_to_the_fade_trough_when_animated() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attached(activity, EmojiView(activity).apply { applyPalette(light) })
            v.openCategoryForTest(1)
            flushMotion()
            val first = v.gridCellTextsForTest()
            assertTrue("the opened category grid is populated (never left blank)", first.isNotEmpty())
            v.openCategoryForTest(2)
            assertEquals("the selected category updates synchronously", 2, v.selectedCategoryForTest())
            assertEquals("the animated switch keeps the outgoing grid until the fade trough", first, v.gridCellTextsForTest())
            flushMotion()
            assertNotEquals("the trough swap re-renders the newly selected category", first, v.gridCellTextsForTest())
            assertTrue("the switched-to grid is populated (never blank)", v.gridCellTextsForTest().isNotEmpty())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun emoji_category_switch_swaps_immediately_under_reduced_motion() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attached(activity, EmojiView(activity).apply { applyPalette(light) })
            v.openCategoryForTest(1)
            val first = v.gridCellTextsForTest()
            assertTrue("the opened category grid is populated (never left blank)", first.isNotEmpty())
            v.openCategoryForTest(2)
            assertEquals("the selected category updates", 2, v.selectedCategoryForTest())
            assertNotEquals("reduced motion swaps the grid content in place immediately", first, v.gridCellTextsForTest())
            assertTrue("the switched-to grid is populated in place (never blank)", v.gridCellTextsForTest().isNotEmpty())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun symbol_category_switch_defers_the_swap_to_the_fade_trough_when_animated() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attached(activity, SymbolsView(activity).apply { applyPalette(light) })
            v.openCategoryForTest(1)
            flushMotion()
            val first = v.gridCellTextsForTest()
            assertTrue("the opened category grid is populated (never left blank)", first.isNotEmpty())
            v.openCategoryForTest(2)
            assertEquals("the selected category updates synchronously", 2, v.selectedCategoryForTest())
            assertEquals("the animated switch keeps the outgoing tiles until the fade trough", first, v.gridCellTextsForTest())
            flushMotion()
            assertNotEquals("the trough swap re-renders the newly selected category", first, v.gridCellTextsForTest())
            assertTrue("the switched-to grid is populated (never blank)", v.gridCellTextsForTest().isNotEmpty())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun symbol_category_switch_swaps_immediately_under_reduced_motion() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attached(activity, SymbolsView(activity).apply { applyPalette(light) })
            v.openCategoryForTest(1)
            val first = v.gridCellTextsForTest()
            assertTrue("the opened category grid is populated (never left blank)", first.isNotEmpty())
            v.openCategoryForTest(2)
            assertEquals("switching categories updates the selection", 2, v.selectedCategoryForTest())
            assertNotEquals("reduced motion swaps the tile content in place immediately", first, v.gridCellTextsForTest())
            assertTrue("the switched-to grid is populated in place (never blank)", v.gridCellTextsForTest().isNotEmpty())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
