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

import com.aegis.ime.user.asClipEntries
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
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
            historyProvider = { history().asClipEntries() }
            categoriesProvider = { listOf("默认", "工作") }
            phrasesInProvider = { listOf("短语") }
            applyPalette(light)
        }

    @Test fun clipboard_content_covers_only_on_tab_mode_and_category_changes() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            var clips = listOf("clip-a", "clip-b")
            val v = attach(activity, clipboardView(activity) { clips })
            assertEquals(0, v.contentFadesForTest())

            v.switchTabForTest(toClipboard = false)
            assertEquals("a tab switch runs one content cover", 1, v.contentFadesForTest())
            assertEquals("the tab swap lands synchronously", listOf("短语"), v.listRowTextsForTest())
            assertEquals("the viewport never leaves full opacity", 1f, v.listViewportForTest().alpha, 0f)
            flushMotion()
            assertEquals("the viewport settles fully opaque", 1f, v.listViewportForTest().alpha, 0f)
            assertEquals(listOf("短语"), v.listRowTextsForTest())

            v.selectPhraseCategoryForTest("工作")
            assertEquals("a category selection runs one content cover", 2, v.contentFadesForTest())
            assertEquals(1f, v.listViewportForTest().alpha, 0f)
            flushMotion()
            assertEquals(1f, v.listViewportForTest().alpha, 0f)

            v.enterSelectForTest()
            assertEquals("a mode switch runs one content cover", 3, v.contentFadesForTest())
            assertEquals(1f, v.listViewportForTest().alpha, 0f)
            flushMotion()
            v.exitSelectForTest()
            assertEquals(4, v.contentFadesForTest())
            assertEquals(1f, v.listViewportForTest().alpha, 0f)
            flushMotion()

            v.switchTabForTest(toClipboard = true)
            assertEquals(5, v.contentFadesForTest())
            assertEquals("the tab swap lands synchronously", listOf("clip-a", "clip-b"), v.listRowTextsForTest())
            assertEquals(1f, v.listViewportForTest().alpha, 0f)
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
            assertEquals("the logical cover is still counted", 1, v.contentFadesForTest())
            assertEquals("reduced motion rebuilds the content immediately", listOf("短语"), v.listRowTextsForTest())
            assertEquals("reduced motion keeps the viewport fully opaque", 1f, v.listViewportForTest().alpha, 0f)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun clipboard_overlay_dismiss_reaches_gone_in_the_same_call() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, clipboardView(activity) { listOf("你好，世界") })
            v.showSplitForTest("你好，世界")
            assertTrue(v.overlayVisibleForTest())
            v.hideOverlayForTest()
            assertFalse("the dismissal lands GONE in the same call", v.overlayVisibleForTest())

            v.showSplitForTest("你好，世界")
            v.hideOverlayForTest()
            v.showSplitForTest("你好，世界")
            flushMotion()
            assertTrue("a reopen right after a dismiss wins and settles visible", v.overlayVisibleForTest())
            v.hideOverlayForTest()
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

    @Test fun clear_confirmation_dismiss_reaches_gone_in_the_same_call() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, EmojiView(activity).apply { applyPalette(light) })
            v.clearBtnForTest().performClick()
            assertTrue(v.clearDialogVisibleForTest())
            assertTrue(v.cancelClearForTest())
            assertFalse("cancel lands GONE in the same call", v.clearDialogVisibleForTest())

            v.clearBtnForTest().performClick()
            assertTrue(v.confirmClearForTest())
            assertFalse("confirm lands GONE in the same call", v.clearDialogVisibleForTest())

            v.clearBtnForTest().performClick()
            assertTrue(v.cancelClearForTest())
            v.clearBtnForTest().performClick()
            assertTrue("a reopen right after a cancel settles visible", v.clearDialogVisibleForTest())
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

    @Test fun emoji_variant_popup_dismiss_reaches_gone_in_the_same_call() {
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
            assertFalse("the dismissal lands GONE in the same call", v.variantVisibleForTest())

            v.openVariantsForTest("👋")
            assertTrue("a reopen settles visible", v.variantVisibleForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun emoji_variant_popup_commits_once_per_open() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, EmojiView(activity).apply { applyPalette(light) })
            var commits = 0
            v.onEmoji = { commits++ }
            v.openVariantsForTest("👋")
            assertTrue(v.tapVariantSkinForTest(1))
            assertEquals("the first tap commits once and dismisses", 1, commits)
            assertFalse("the popup leaves in the same call", v.variantVisibleForTest())

            v.tapVariantSkinForTest(2)
            assertEquals("a tap on the dismissed card commits nothing", 1, commits)

            v.openVariantsForTest("👋")
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
            assertTrue("copy stays tappable while selected", copy.isEnabled)
            assertTrue("the tint change cross-fades", v.selectionTintAnimatingForTest())
            flushMotion()
            assertEquals(light.keyLabel, copy.currentTextColor)
            assertFalse(v.selectionTintAnimatingForTest())

            v.setHasSelection(true)
            assertFalse("a same-state call does not restart the fade", v.selectionTintAnimatingForTest())
            assertEquals(light.keyLabel, copy.currentTextColor)

            v.setHasSelection(false)
            assertFalse("copy is disabled when the host reports no selection", copy.isEnabled)
            assertFalse("copy is not clickable when the host reports no selection", copy.isClickable)
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
            assertFalse("a palette reapply preserves the disabled key state", copy.isEnabled)
            assertFalse("a palette reapply preserves the non-clickable key state", copy.isClickable)
            flushMotion()
            assertEquals(dark.disabled, copy.currentTextColor)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun edit_panel_select_label_swap_lands_synchronously_when_animated() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, EditPanelView(activity).apply { applyPalette(light) })
            val start = activity.getString(R.string.edit_start_select)
            val end = activity.getString(R.string.edit_end_select)
            assertEquals(start, v.selectingLabelForTest().toString())
            v.setSelecting(true)
            assertEquals("the label swap lands in the same call", end, v.selectingLabelForTest().toString())
            flushMotion()
            assertEquals(end, v.selectingLabelForTest().toString())
            v.setSelecting(true)
            assertEquals("a same-state call re-renders without a fade", end, v.selectingLabelForTest().toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun copy_bar_split_toggle_re_renders_synchronously() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, CopyBarView(activity).apply { applyPalette(light); show("你好，世界") })
            assertFalse(v.splitRenderedForTest())
            v.toggleSplitForTest()
            assertTrue("the split state flips synchronously", v.splitModeForTest())
            assertTrue("the re-render lands in the same call", v.splitRenderedForTest())
            flushMotion()
            assertTrue(v.splitRenderedForTest())
            v.toggleSplitForTest()
            assertFalse(v.splitModeForTest())
            assertFalse("the collapse re-render lands in the same call", v.splitRenderedForTest())
            flushMotion()
            assertFalse(v.splitRenderedForTest())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun long_copied_content_scrolls_horizontally_instead_of_being_ellipsized() {
        val v = CopyBarView(ctx).apply { applyPalette(light); show("很长的复制内容".repeat(40)) }
        assertFalse("a freshly copied passage is not in split mode", v.splitModeForTest())
        val scroller = requireNotNull(v.contentScrollerForTest()) { "copied content should sit inside a horizontal scroller" }
        assertFalse("the scroller hides its scrollbar chrome like the clipboard scrollers", scroller.isHorizontalScrollBarEnabled)
        val text = scroller.getChildAt(0) as TextView
        assertEquals("copied content stays a single line so it pans left/right", 1, text.maxLines)
        assertNull("the remainder is revealed by scrolling, not cut off with an ellipsis", text.ellipsize)
    }

    @Test fun copy_bar_split_toggle_residue_matches_the_bar_backdrop() {
        animationsOn()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val v = attach(activity, CopyBarView(activity).apply { applyPalette(light); show("你好，世界") })
            v.measure(
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(44, View.MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, 720, 44)
            v.toggleSplitForTest()
            assertTrue("the toggle leaves a residue on the whole bar", Motion.coverActiveForTest(v))
            val frame = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(frame))
            assertEquals(
                "the residue strip above the capsule shows the bar backdrop",
                light.keyboardBg,
                frame.getPixel(v.width / 2, 2),
            )
            flushMotion()
            assertFalse(Motion.coverActiveForTest(v))
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

    @Test fun copy_bar_dismiss_is_synchronous_and_a_toggle_after_it_cannot_resurface_the_bar() {
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
            assertEquals("the bar leaves in the same call", View.GONE, bar.visibility)
            assertEquals("the candidate bar returns in the same call", View.VISIBLE, candidates.visibility)
            assertEquals(1f, candidates.alpha, 0f)
            bar.toggleSplitForTest()
            assertTrue("the split state still flips synchronously", bar.splitModeForTest())
            flushMotion()
            assertEquals("the toggle on the dismissed bar must not resurface it", View.GONE, bar.visibility)
            assertEquals(View.VISIBLE, candidates.visibility)
            assertEquals(1f, candidates.alpha, 0f)
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

    @Test fun custom_symbol_refresh_rebuilds_instantly_even_when_animated() {
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
            assertNull("a chip change rebuilds in the same call, with no animation at all", v.paletteChipForTest("★"))
            assertEquals(1f, v.contentViewportForTest().alpha, 0f)
            flushMotion()
            assertNull(v.paletteChipForTest("★"))
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
