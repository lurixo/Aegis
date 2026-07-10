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

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land-xxhdpi")
class LandscapeKeyboardDockTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    @Test fun landscape_surface_is_portrait_width_and_physically_right_docked() {
        val iv = InputView(ctx)
        val widthPx = dp(891)
        layout(iv, widthPx)

        val expectedSurfaceWidth = dp(411)
        val expectedPortraitKeyboardWidth = dp(411) - 2 * dp(4)
        assertEquals("the opaque surface uses the short-axis outer width", expectedSurfaceWidth, iv.dockSurfaceWidthPx())
        assertEquals("the surface leaves the host-visible gutter on the left", widthPx - expectedSurfaceWidth, iv.dockSurfaceLeftPx())
        assertEquals("the surface is physically right-docked", widthPx, iv.dockSurfaceRightPx())
        assertTrue("this wide landscape window uses floating geometry", iv.isCompactLandscapeDock())
        assertEquals("keyboard keeps portrait content width", expectedPortraitKeyboardWidth, iv.keyboardVisualWidthPx())
        assertEquals("keyboard starts after the surface padding", iv.dockSurfaceLeftPx() + dp(4), iv.keyboardVisualLeftPx())
        assertEquals("keyboard ends before the surface padding", iv.dockSurfaceRightPx() - dp(4), iv.keyboardVisualRightPx())
        assertTrue("keyboard still has its normal measured height", iv.keyboardHeightPx() > 0)
    }

    @Test fun landscape_toolbar_preedit_edit_bar_and_panel_stay_inside_the_same_surface() {
        val iv = InputView(ctx)
        val widthPx = dp(891)
        layout(iv, widthPx)
        val contentLeft = iv.keyboardVisualLeftPx()
        val contentRight = iv.keyboardVisualRightPx()
        val contentWidth = iv.keyboardVisualWidthPx()

        iv.showEditBar(true)
        layout(iv, widthPx)

        assertEquals("toolbar keeps the same compact width as the keyboard", contentWidth, iv.toolbarVisualWidthPx())
        assertEquals("toolbar right edge aligns with the compact keyboard", contentRight, iv.toolbarVisualRightPx())
        assertEquals("toolbar left edge aligns with the compact keyboard", contentLeft, iv.toolbarVisualLeftPx())
        assertEquals("edit bar shares the content left edge", contentLeft, iv.editBarVisualLeftPx())
        assertEquals("edit bar shares the content right edge", contentRight, iv.editBarVisualRightPx())

        iv.showPanel(View(ctx))
        layout(iv, widthPx)
        assertEquals("panel shares the content left edge", contentLeft, iv.panelVisualLeftPx())
        assertEquals("panel shares the content right edge", contentRight, iv.panelVisualRightPx())
        assertEquals("preedit outer dock follows the opaque surface left", iv.dockSurfaceLeftPx(), iv.preeditVisualLeftPx())
        assertEquals("preedit outer dock follows the opaque surface right", iv.dockSurfaceRightPx(), iv.preeditVisualRightPx())
    }

    @Test fun measured_window_narrower_than_the_short_axis_falls_back_to_full_width() {
        val iv = InputView(ctx)
        val narrowWidth = dp(320)
        layout(iv, narrowWidth)

        assertEquals(0, iv.dockSurfaceLeftPx())
        assertEquals(narrowWidth, iv.dockSurfaceWidthPx())
        assertFalse("no synthetic pass-through gutter in a narrow multi-window", iv.isCompactLandscapeDock())
    }

    private fun layout(iv: InputView, widthPx: Int) {
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(411), View.MeasureSpec.AT_MOST),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
