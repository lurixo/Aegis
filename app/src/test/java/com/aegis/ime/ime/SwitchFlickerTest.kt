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
import org.junit.Assert.assertFalse
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

    @Test fun composing_dismisses_the_copy_bar_once_and_synchronously() {
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
            assertFalse("the dismissal lands in the same call", input.copyBarShown)
            input.showCandidates(listOf("你好", "你"), "nihao", listOf("ni"))
            assertEquals("composing updates never re-dismiss", 1, dismissals)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun repeated_field_switch_copy_bar_hides_are_no_ops_and_never_dip_the_candidate_bar() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val input = attached(activity, InputView(activity).apply { applyPalette(light) })
            layoutInput(input)
            val candidates = descendant<CandidateView>(input)
            val bar = descendant<CopyBarView>(input)
            assertEquals(View.VISIBLE, candidates.visibility)

            repeat(3) {
                input.hideCopyBar()
                assertEquals("a field switch with no copy bar leaves the candidate bar alone", View.VISIBLE, candidates.visibility)
                assertEquals("the candidate bar never leaves full opacity", 1f, candidates.alpha, 0f)
                assertFalse("an already-settled hide starts no animation", Motion.coverActiveForTest(candidates))
            }

            input.showCopyBar("copied")
            assertEquals("the slot is never empty mid-swap", View.VISIBLE, bar.visibility)
            assertEquals(View.GONE, candidates.visibility)
            flushMotion()
            layoutInput(input)

            input.hideCopyBar()
            assertEquals("the candidate bar returns in the same call", View.VISIBLE, candidates.visibility)
            assertEquals(1f, candidates.alpha, 0f)
            repeat(3) {
                input.hideCopyBar()
                assertEquals("repeated field-switch hides never dip the candidate bar", 1f, candidates.alpha, 0f)
                assertEquals(View.VISIBLE, candidates.visibility)
            }
            flushMotion()
            assertFalse(Motion.coverActiveForTest(candidates))
            assertEquals(1f, candidates.alpha, 0f)
            assertEquals(View.VISIBLE, candidates.visibility)
            assertEquals(View.GONE, bar.visibility)
        } finally {
            controller.pause().stop().destroy()
        }
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
            flushMotion()
            input.showEditBar(true)
            assertTrue("a genuine reopen still animates", bar.alpha < 1f)
            flushMotion()
            assertEquals(1f, bar.alpha, 0f)
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

    @Test fun emoji_category_switch_swaps_synchronously_at_full_opacity_when_animated() {
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
            assertNotEquals("the animated switch swaps the grid content synchronously", first, v.gridCellTextsForTest())
            assertTrue("the swapped-in grid is populated (never blank)", v.gridCellTextsForTest().isNotEmpty())
            assertEquals("the viewport never leaves full opacity", 1f, v.gridViewportForTest().alpha, 0f)
            flushMotion()
            assertEquals("the viewport settles fully opaque", 1f, v.gridViewportForTest().alpha, 0f)
            assertTrue("the settled grid stays populated", v.gridCellTextsForTest().isNotEmpty())
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
            assertEquals("reduced motion keeps the viewport fully opaque", 1f, v.gridViewportForTest().alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun symbol_category_switch_swaps_synchronously_at_full_opacity_when_animated() {
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
            assertNotEquals("the animated switch swaps the tile content synchronously", first, v.gridCellTextsForTest())
            assertTrue("the swapped-in grid is populated (never blank)", v.gridCellTextsForTest().isNotEmpty())
            assertEquals("the viewport never leaves full opacity", 1f, v.gridViewportForTest().alpha, 0f)
            flushMotion()
            assertEquals("the viewport settles fully opaque", 1f, v.gridViewportForTest().alpha, 0f)
            assertTrue("the settled grid stays populated", v.gridCellTextsForTest().isNotEmpty())
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
            assertEquals("reduced motion keeps the viewport fully opaque", 1f, v.gridViewportForTest().alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
