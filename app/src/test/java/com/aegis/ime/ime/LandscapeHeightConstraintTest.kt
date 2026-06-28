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
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w853dp-h388dp-land-hdpi")
class LandscapeHeight388ConstraintTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    @Test fun open_panel_remeasures_with_the_hidden_keyboard_and_compressed_actions_stay_touchable() {
        var backspaces = 0
        var clears = 0
        val candidates = (1..120).map { "候选$it" }
        val readings = (1..40).map { "reading$it" }
        val iv = InputView(ctx).apply {
            simulateNavInsetForTest(24)
            showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
            showCandidates(candidates, "nihao", readings)
            showEditBar(true)
            onPanelBackspace = { backspaces++ }
            onPanelClear = { clears++ }
        }
        val activity = attachToActivity(iv)
        layoutAtMost(iv, dp(853), dp(388))
        iv.showExpandedCandidates()
        layoutAtMost(iv, dp(853), dp(388))
        settleUiAnimations()
        val tallPanelHeight = iv.panelHeightPx()

        val tallSpec = iv.dockHeightSpecForTest()!!
        assertEquals(tallSpec.keyboardHeight + tallSpec.barHeight, tallPanelHeight)
        assertPanelControlsInside(iv)
        assertTrue("long candidate content scrolls in the compressed panel", iv.expandedGridForTest().gridCanScrollForwardForTest())
        assertTrue("long reading content scrolls in the compressed panel", iv.expandedGridForTest().readingCanScrollForwardForTest())

        layoutAtMost(iv, dp(853), dp(291))
        assertTrue(iv.panelHeightPx() < tallPanelHeight)
        val compressedSpec = iv.dockHeightSpecForTest()!!
        assertEquals(compressedSpec.keyboardHeight + compressedSpec.barHeight, iv.panelHeightPx())
        assertEquals(iv.height, iv.dockSurfaceBottomPx())
        assertPanelControlsInside(iv)

        val controls = iv.expandedPanelControlBoundsForTest()
        val grid = iv.expandedGridForTest()
        assertTrue(dispatchRootTap(iv, controls[1]))
        assertTrue(dispatchRootTap(iv, controls[2], grid.clearButtonForTest()))
        assertTrue("return remains reachable at the compressed top rail", dispatchRootTap(iv, controls[0], grid.returnButtonForTest()))
        flushPostedClicks()
        assertEquals(1, backspaces)
        assertEquals("clear callback missing; controls=$controls panel=[${iv.panelVisualLeftPx()},${iv.panelVisualTopPx()}..${iv.panelVisualRightPx()},${iv.panelVisualBottomPx()}]", 1, clears)
        assertFalse(iv.isPanelShowing(grid))
        activity.pause().stop().destroy()
    }

    @Test fun bottom_display_cutout_joins_the_hard_height_budget_and_keeps_enter_above_it() {
        val emitted = mutableListOf<KeyAction>()
        val iv = InputView(ctx).apply {
            showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
            showEditBar(true)
            onKey = { emitted += it.action }
        }
        ViewCompat.dispatchApplyWindowInsets(
            iv,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 0, 0, 48))
                .build(),
        )
        layoutAtMost(iv, dp(853), dp(388))

        assertEquals("overlapping nav/cutout use the larger hard safe bottom", 48, iv.dockHeightSpecForTest()!!.navBottom)
        assertTrue(iv.bodyBottomPaddingPx() >= 48)
        assertEquals(iv.height, iv.dockSurfaceBottomPx())
        val enter = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.ENTER))
        assertRectInsideSurface(iv, enter)
        assertTrue(enter.bottom <= iv.height - 48)
        assertTrue(iv.tapKeyboardActionForTest(KeyAction.ENTER))
        assertEquals(listOf(KeyAction.ENTER), emitted)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h291dp-land-mdpi")
class MinimumHeight291ConstraintTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun alpha_and_nine_keep_minimum_faces_with_edit_nav_and_real_root_dispatch() {
        val emitted = mutableListOf<String>()
        val iv = InputView(ctx).apply {
            simulateNavInsetForTest(24)
            showEditBar(true)
            onKey = { emitted += it.label.ifEmpty { it.action.name } }
        }

        iv.showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
        layoutAtMost(iv, 640, 291)
        assertEquals(291, iv.measuredHeight)
        assertEquals(291, iv.dockSurfaceBottomPx())
        assertFalse(iv.dockHeightSpecForTest()!!.emergency)
        val alphaFirst = requireNotNull(iv.keyboardLabelBoundsForTest("q"))
        val alphaEnter = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.ENTER))
        assertTrue(alphaFirst.height() >= 28f)
        assertTrue("alpha Enter height=${alphaEnter.height()} spec=${iv.dockHeightSpecForTest()}", alphaEnter.height() >= 27.99f)
        assertRectInsideSurface(iv, alphaFirst)
        assertRectInsideSurface(iv, alphaEnter)
        assertTrue(iv.tapKeyboardLabelForTest("q"))
        assertTrue(iv.tapKeyboardActionForTest(KeyAction.ENTER))

        iv.showKeyboard(Layouts.nine(Layouts.ninePunctuation()), false, false, Lang.CN)
        layoutAtMost(iv, 640, 291)
        assertEquals(291, iv.measuredHeight)
        assertEquals(291, iv.dockSurfaceBottomPx())
        assertFalse(iv.dockHeightSpecForTest()!!.emergency)
        val nineFirst = requireNotNull(iv.keyboardLabelBoundsForTest("ABC"))
        val nineEnter = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.ENTER))
        assertTrue("fractional NINE cells retain a 32dp face", nineFirst.height() >= 32f)
        assertTrue(nineEnter.height() >= 32f)
        assertRectInsideSurface(iv, nineFirst)
        assertRectInsideSurface(iv, nineEnter)
        assertTrue(iv.tapKeyboardLabelForTest("ABC"))
        assertTrue(iv.tapKeyboardActionForTest(KeyAction.ENTER))
        assertEquals(4, emitted.size)
        assertVerticalBounds(iv)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w720dp-h360dp-land-xhdpi")
class DensityHeight360ConstraintTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun xhdpi_alpha_and_nine_fit_the_720px_height_cap_with_edit_and_nav() {
        val iv = InputView(ctx).apply {
            simulateNavInsetForTest(32)
            showEditBar(true)
        }
        val width = 1440
        val height = 720

        for (layout in listOf(
            Layouts.forId(LayoutId.ALPHA, Lang.CN),
            Layouts.nine(Layouts.ninePunctuation()),
        )) {
            iv.showKeyboard(layout, false, false, Lang.CN)
            layoutAtMost(iv, width, height)
            assertEquals(height, iv.measuredHeight)
            assertEquals(height, iv.dockSurfaceBottomPx())
            assertTrue(iv.keyboardVisualBottomPx() <= iv.dockSurfaceBottomPx())
            assertTrue(iv.dockHeightSpecForTest()!!.keyboardHeight > 0)
            assertVerticalBounds(iv)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w853dp-h388dp-land-hdpi")
class WideToNarrowInsetResizeTest {

    @Test fun compact_to_full_width_resize_reapplies_left_inset_before_the_new_measure() {
        val iv = InputView(RuntimeEnvironment.getApplication())
        ViewCompat.dispatchApplyWindowInsets(
            iv,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(24, 0, 16, 24))
                .build(),
        )
        layoutAtMost(iv, 1280, 582)
        assertTrue(iv.isCompactLandscapeDock())
        assertEquals("left inset is outside the old remote dock", 6, iv.bodyLeftPaddingPxForTest())

        try {
            RuntimeEnvironment.setQualifiers("w320dp-h200dp-land-hdpi")
            layoutAtMost(iv, 480, 300)
            assertFalse(iv.isCompactLandscapeDock())
            assertEquals(0, iv.dockSurfaceLeftPx())
            assertEquals(480, iv.dockSurfaceRightPx())
            assertEquals("incoming full-width pass must protect the persistent left system inset", 24, iv.bodyLeftPaddingPxForTest())
            assertEquals(16, iv.bodyRightPaddingPxForTest())
        } finally {
            RuntimeEnvironment.setQualifiers("w853dp-h388dp-land-hdpi")
        }
    }
}

private fun layoutAtMost(iv: InputView, widthPx: Int, heightPx: Int) {
    iv.measure(
        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST),
    )
    iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
}

private fun attachToActivity(view: View) = Robolectric.buildActivity(Activity::class.java).setup().also {
    it.get().setContentView(view)
}

private fun settleUiAnimations() {
    shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
}

private fun assertVerticalBounds(iv: InputView) {
    assertTrue(iv.preeditVisualTopPx() >= 0)
    assertTrue(iv.preeditVisualBottomPx() <= iv.height)
    assertTrue(iv.toolbarVisualTopPx() >= 0)
    assertTrue(iv.toolbarVisualBottomPx() <= iv.height)
    if (iv.isEditBarShowing()) {
        assertTrue(iv.editBarVisualTopPx() >= 0)
        assertTrue(iv.editBarVisualBottomPx() <= iv.height)
    }
    if (iv.panelShown) {
        assertTrue(iv.panelVisualTopPx() >= 0)
        assertTrue(iv.panelVisualBottomPx() <= iv.height)
    } else {
        assertTrue(iv.keyboardVisualTopPx() >= 0)
        assertTrue(iv.keyboardVisualBottomPx() <= iv.height)
    }
    assertTrue(iv.dockSurfaceTopPx() >= 0)
    assertTrue(iv.dockSurfaceBottomPx() <= iv.height)
}

private fun assertRectInsideSurface(iv: InputView, rect: RectF) {
    assertTrue(rect.left >= iv.dockSurfaceLeftPx())
    assertTrue(rect.top >= iv.dockSurfaceTopPx())
    assertTrue(rect.right <= iv.dockSurfaceRightPx())
    assertTrue(rect.bottom <= iv.dockSurfaceBottomPx())
}

private fun assertPanelControlsInside(iv: InputView) {
    val panel = Rect(
        iv.panelVisualLeftPx(),
        iv.panelVisualTopPx(),
        iv.panelVisualRightPx(),
        iv.panelVisualBottomPx(),
    )
    for (control in iv.expandedPanelControlBoundsForTest()) {
        assertTrue("panel control $control must be non-empty", !control.isEmpty)
        assertTrue("panel control $control must stay inside $panel", panel.contains(control))
    }
}

private fun dispatchRootTap(root: View, rect: Rect, expectedTarget: View? = null): Boolean {
    val x = rect.exactCenterX()
    val y = rect.exactCenterY()
    val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
    val up = MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, x, y, 0)
    return try {
        val accepted = root.dispatchTouchEvent(down)
        if (expectedTarget != null) {
            assertTrue("expected target must own a click handler: bounds=$rect", expectedTarget.isClickable)
        }
        root.dispatchTouchEvent(up) && accepted
    } finally {
        down.recycle()
        up.recycle()
    }
}

private fun flushPostedClicks() {

    shadowOf(Looper.getMainLooper()).idle()
}
