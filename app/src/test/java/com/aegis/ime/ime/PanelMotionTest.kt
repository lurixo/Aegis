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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import java.time.Duration
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
class PanelMotionTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT

    private fun animationsOn() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    private fun animationsOff() = Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

    private fun <T : View> attach(activity: Activity, view: T): T {
        val host = FrameLayout(activity)
        host.addView(view)
        activity.setContentView(host)
        return view
    }

    private fun flushMotion() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private inline fun <reified T : View> soleDescendant(root: View): T {
        val found = ArrayList<T>()
        val stack = ArrayDeque<View>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (v is T) found.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
        }
        return found.single()
    }

    private fun clipboardView(activity: Activity, history: () -> List<String>): ClipboardView =
        ClipboardView(activity).apply {
            historyProvider = history
            categoriesProvider = { listOf("默认", "工作") }
            phrasesInProvider = { listOf("短语") }
            applyPalette(light)
        }

    @Test fun clipboard_content_fades_only_on_tab_mode_and_category_changes() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            var clips = listOf("clip-a", "clip-b")
            val v = attach(activity, clipboardView(activity) { clips })
            assertEquals(0, v.contentFadesForTest())

            v.switchTabForTest(toClipboard = false)
            assertEquals("a tab switch runs one content fade", 1, v.contentFadesForTest())
            assertEquals("the tab swap lands synchronously", listOf("短语"), v.listRowTextsForTest())
            assertEquals("the viewport entrance fade starts transparent", 0f, v.listViewportForTest().alpha, 0f)
            flushMotion()
            assertEquals("the entrance fade settles fully opaque", 1f, v.listViewportForTest().alpha, 0f)
            assertEquals(listOf("短语"), v.listRowTextsForTest())

            v.selectPhraseCategoryForTest("工作")
            assertEquals("a category selection runs one content fade", 2, v.contentFadesForTest())
            assertEquals(0f, v.listViewportForTest().alpha, 0f)
            flushMotion()
            assertEquals(1f, v.listViewportForTest().alpha, 0f)

            v.enterSelectForTest()
            assertEquals("a mode switch runs one content fade", 3, v.contentFadesForTest())
            assertEquals(0f, v.listViewportForTest().alpha, 0f)
            flushMotion()
            v.exitSelectForTest()
            assertEquals(4, v.contentFadesForTest())
            assertEquals(0f, v.listViewportForTest().alpha, 0f)
            flushMotion()

            v.switchTabForTest(toClipboard = true)
            assertEquals(5, v.contentFadesForTest())
            assertEquals("the tab swap lands synchronously", listOf("clip-a", "clip-b"), v.listRowTextsForTest())
            assertEquals(0f, v.listViewportForTest().alpha, 0f)
            flushMotion()
            assertEquals(1f, v.listViewportForTest().alpha, 0f)

            clips = listOf("clip-a")
            v.refresh()
            assertEquals("a store-driven refresh stays instant", 5, v.contentFadesForTest())
            assertEquals(listOf("clip-a"), v.listRowTextsForTest())

            v.expandForTest("clip-a")
            assertEquals("row expansion stays instant", 5, v.contentFadesForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun clipboard_content_swaps_immediately_under_reduced_motion() {
        animationsOff()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, clipboardView(activity) { listOf("clip-a") })
            v.switchTabForTest(toClipboard = false)
            assertEquals("the logical fade is still counted", 1, v.contentFadesForTest())
            assertEquals("reduced motion rebuilds the content immediately", listOf("短语"), v.listRowTextsForTest())
            assertEquals("reduced motion keeps the viewport fully opaque", 1f, v.listViewportForTest().alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun clipboard_overlay_dismiss_reaches_gone_through_the_animated_exit() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, clipboardView(activity) { listOf("你好，世界") })
            v.showSplitForTest("你好，世界")
            assertTrue(v.overlayVisibleForTest())
            v.hideOverlayForTest()
            assertTrue("the dismissal animates (still visible during the exit)", v.overlayVisibleForTest())
            flushMotion()
            assertFalse("the animated exit reaches GONE", v.overlayVisibleForTest())

            v.showSplitForTest("你好，世界")
            v.hideOverlayForTest()
            v.showSplitForTest("你好，世界")
            flushMotion()
            assertTrue("a reopen during the exit wins and settles visible", v.overlayVisibleForTest())
            v.hideOverlayForTest()
            flushMotion()
            assertFalse(v.overlayVisibleForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun clipboard_overlay_dismiss_is_immediate_under_reduced_motion() {
        animationsOff()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, clipboardView(activity) { listOf("你好，世界") })
            v.showSplitForTest("你好，世界")
            assertTrue(v.overlayVisibleForTest())
            v.hideOverlayForTest()
            assertFalse("reduced motion jumps straight to GONE", v.overlayVisibleForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun clear_confirmation_dismiss_reaches_gone_through_the_animated_exit() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, EmojiView(activity).apply { applyPalette(light) })
            v.clearBtnForTest().performClick()
            assertTrue(v.clearDialogVisibleForTest())
            assertTrue(v.cancelClearForTest())
            assertTrue("cancel animates the card out", v.clearDialogVisibleForTest())
            flushMotion()
            assertFalse("the animated cancel reaches GONE", v.clearDialogVisibleForTest())

            v.clearBtnForTest().performClick()
            assertTrue(v.confirmClearForTest())
            assertTrue("confirm animates the card out", v.clearDialogVisibleForTest())
            flushMotion()
            assertFalse("the animated confirm reaches GONE", v.clearDialogVisibleForTest())

            v.clearBtnForTest().performClick()
            assertTrue(v.cancelClearForTest())
            v.clearBtnForTest().performClick()
            flushMotion()
            assertTrue("a reopen during the exit wins and settles visible", v.clearDialogVisibleForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun clear_confirmation_dismiss_is_immediate_under_reduced_motion() {
        animationsOff()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, EmojiView(activity).apply { applyPalette(light) })
            v.clearBtnForTest().performClick()
            assertTrue(v.clearDialogVisibleForTest())
            assertTrue(v.cancelClearForTest())
            assertFalse("reduced motion jumps straight to GONE", v.clearDialogVisibleForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun emoji_variant_popup_dismiss_reaches_gone_through_the_animated_exit() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, EmojiView(activity).apply { applyPalette(light) })
            v.openVariantsForTest("👋")
            assertTrue(v.variantVisibleForTest())
            val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
            assertTrue("the dismissing tap is consumed", v.variantBackdropForTest().dispatchTouchEvent(down))
            down.recycle()
            assertTrue("the dismissal animates (still visible during the exit)", v.variantVisibleForTest())
            flushMotion()
            assertFalse("the animated exit reaches GONE", v.variantVisibleForTest())

            v.openVariantsForTest("👋")
            flushMotion()
            assertTrue("a reopen settles visible", v.variantVisibleForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun emoji_variant_popup_ignores_taps_during_the_animated_exit() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, EmojiView(activity).apply { applyPalette(light) })
            var commits = 0
            v.onEmoji = { commits++ }
            v.openVariantsForTest("👋")
            flushMotion()
            assertTrue(v.tapVariantSkinForTest(1))
            assertEquals("the first tap commits once and starts the dismissal", 1, commits)
            assertTrue("the popup is still fading out", v.variantVisibleForTest())

            v.tapVariantSkinForTest(2)
            assertEquals("a tap on the fading card commits nothing", 1, commits)
            val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
            assertTrue("a scrim touch mid-exit is consumed, never passed through", v.variantBackdropForTest().dispatchTouchEvent(down))
            down.recycle()
            assertEquals(1, commits)
            flushMotion()
            assertFalse("the exit still reaches GONE", v.variantVisibleForTest())
            assertEquals(1, commits)

            v.openVariantsForTest("👋")
            flushMotion()
            assertTrue(v.tapVariantSkinForTest(1))
            assertEquals("a reopened popup commits again", 2, commits)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun edit_panel_selection_tint_crossfades_and_repeat_calls_are_free() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, EditPanelView(activity).apply { applyPalette(light) })
            val copy = v.actionViewForTest(EditAction.COPY) as TextView
            assertEquals(light.disabled, copy.currentTextColor)

            v.setHasSelection(true)
            assertTrue("copy enables synchronously", copy.isEnabled)
            assertTrue("the tint change cross-fades", v.selectionTintAnimatingForTest())
            flushMotion()
            assertEquals(light.keyLabel, copy.currentTextColor)
            assertFalse(v.selectionTintAnimatingForTest())

            v.setHasSelection(true)
            assertFalse("a same-state call does not restart the fade", v.selectionTintAnimatingForTest())
            assertEquals(light.keyLabel, copy.currentTextColor)

            v.setHasSelection(false)
            assertFalse("copy disables synchronously", copy.isEnabled)
            assertTrue(v.selectionTintAnimatingForTest())
            flushMotion()
            assertEquals(light.disabled, copy.currentTextColor)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun edit_panel_selection_tint_is_immediate_under_reduced_motion() {
        animationsOff()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, EditPanelView(activity).apply { applyPalette(light) })
            val copy = v.actionViewForTest(EditAction.COPY) as TextView
            v.setHasSelection(true)
            assertFalse(v.selectionTintAnimatingForTest())
            assertEquals("reduced motion jumps straight to the target tint", light.keyLabel, copy.currentTextColor)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun edit_panel_palette_reapply_applies_the_selection_tint_instantly() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, EditPanelView(activity).apply { applyPalette(light) })
            val copy = v.actionViewForTest(EditAction.COPY) as TextView
            val cut = v.actionViewForTest(EditAction.CUT) as TextView
            v.setHasSelection(false)
            flushMotion()
            assertEquals(light.disabled, copy.currentTextColor)

            val dark = ImePalette.STATIC_DARK
            v.applyPalette(dark)
            assertFalse("a palette reapply must not start a tint fade", v.selectionTintAnimatingForTest())
            assertEquals("copy is already at the final disabled tint", dark.disabled, copy.currentTextColor)
            assertEquals("cut is already at the final disabled tint", dark.disabled, cut.currentTextColor)
            assertFalse(copy.isEnabled)
            flushMotion()
            assertEquals(dark.disabled, copy.currentTextColor)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun edit_panel_select_label_swap_defers_to_the_trough_when_animated() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, EditPanelView(activity).apply { applyPalette(light) })
            val start = activity.getString(R.string.edit_start_select)
            val end = activity.getString(R.string.edit_end_select)
            assertEquals(start, v.selectingLabelForTest().toString())
            v.setSelecting(true)
            assertEquals("the label swap defers to the fade trough", start, v.selectingLabelForTest().toString())
            flushMotion()
            assertEquals(end, v.selectingLabelForTest().toString())
            v.setSelecting(true)
            assertEquals("a same-state call re-renders without a fade", end, v.selectingLabelForTest().toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun copy_bar_split_toggle_defers_the_re_render_to_the_trough() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, CopyBarView(activity).apply { applyPalette(light); show("你好，世界") })
            assertFalse(v.splitRenderedForTest())
            v.toggleSplitForTest()
            assertTrue("the split state flips synchronously", v.splitModeForTest())
            assertFalse("the re-render defers to the fade trough", v.splitRenderedForTest())
            flushMotion()
            assertTrue(v.splitRenderedForTest())
            v.toggleSplitForTest()
            assertFalse(v.splitModeForTest())
            assertTrue(v.splitRenderedForTest())
            flushMotion()
            assertFalse(v.splitRenderedForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun copy_bar_split_toggle_is_immediate_under_reduced_motion() {
        animationsOff()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, CopyBarView(activity).apply { applyPalette(light); show("你好，世界") })
            v.toggleSplitForTest()
            assertTrue(v.splitModeForTest())
            assertTrue("reduced motion re-renders immediately", v.splitRenderedForTest())
            v.toggleSplitForTest()
            assertFalse(v.splitRenderedForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun copy_bar_split_toggle_during_the_animated_dismiss_still_restores_the_candidate_bar() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val iv = attach(activity, InputView(activity).apply { applyPalette(light) })
            val bar = soleDescendant<CopyBarView>(iv)
            val candidates = soleDescendant<CandidateView>(iv)
            iv.showCopyBar("你好，世界")
            flushMotion()
            assertTrue(iv.copyBarShown)
            assertEquals(View.GONE, candidates.visibility)

            iv.hideCopyBar()
            assertEquals("the bar animates its exit (still visible)", View.VISIBLE, bar.visibility)
            bar.toggleSplitForTest()
            assertTrue("the split state still flips synchronously", bar.splitModeForTest())
            flushMotion()
            assertEquals("the mid-dismiss toggle must not strand the bar visible", View.GONE, bar.visibility)
            assertEquals("the candidate bar returns at the trough", View.VISIBLE, candidates.visibility)
            assertFalse(iv.copyBarShown)

            iv.showCopyBar("你好，世界")
            flushMotion()
            assertTrue("a later re-show still works", iv.copyBarShown)
            assertEquals(View.GONE, candidates.visibility)
            assertFalse("the re-shown bar renders the un-split content", bar.splitModeForTest())
            assertFalse(bar.splitRenderedForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun custom_symbol_refresh_defers_the_rebuild_to_the_trough() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val added = mutableListOf<String>()
            val v = attach(
                activity,
                CustomSymbolPanel(activity).apply {
                    addPalette = listOf("★", "☆")
                    current = { added.toList() }
                    applyPalette(light)
                },
            )
            assertNotNull(v.paletteChipForTest("★"))
            added.add("★")
            v.refresh()
            assertNotNull("the rebuild defers to the fade trough", v.paletteChipForTest("★"))
            flushMotion()
            assertNull("the trough rebuild moves the chip out of the palette", v.paletteChipForTest("★"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun custom_symbol_refresh_is_immediate_under_reduced_motion() {
        animationsOff()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val added = mutableListOf<String>()
            val v = attach(
                activity,
                CustomSymbolPanel(activity).apply {
                    addPalette = listOf("★", "☆")
                    current = { added.toList() }
                    applyPalette(light)
                },
            )
            added.add("★")
            v.refresh()
            assertNull("reduced motion rebuilds immediately", v.paletteChipForTest("★"))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
