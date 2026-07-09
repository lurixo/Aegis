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

    @Test fun landscape_keyboard_is_portrait_width_and_right_aligned_in_the_full_slot() {
        val iv = InputView(ctx)
        val widthPx = dp(891)
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)

        val expectedPortraitKeyboardWidth = dp(411) - 2 * dp(4)
        assertTrue("landscape slot is wider than the capped keyboard", iv.keyboardDockWidthPx() > expectedPortraitKeyboardWidth)
        assertEquals("keyboard keeps portrait content width", expectedPortraitKeyboardWidth, iv.keyboardVisualWidthPx())
        assertEquals("keyboard is right-aligned in its slot", iv.keyboardDockWidthPx(), iv.keyboardVisualRightPx())
        assertEquals(
            "left edge is the remaining landscape gutter",
            iv.keyboardDockWidthPx() - expectedPortraitKeyboardWidth,
            iv.keyboardVisualLeftPx(),
        )
        assertTrue("keyboard still has its normal measured height", iv.keyboardHeightPx() > 0)
    }

    @Test fun landscape_toolbar_matches_the_compact_keyboard_width_and_right_edge() {
        val iv = InputView(ctx)
        val widthPx = dp(891)
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)

        assertTrue("landscape toolbar slot stays full-width", iv.toolbarDockWidthPx() > iv.toolbarVisualWidthPx())
        assertEquals("toolbar keeps the same compact width as the keyboard", iv.keyboardVisualWidthPx(), iv.toolbarVisualWidthPx())
        assertEquals("toolbar right edge aligns with the compact keyboard", iv.keyboardVisualRightPx(), iv.toolbarVisualRightPx())
        assertEquals("toolbar left edge aligns with the compact keyboard", iv.keyboardVisualLeftPx(), iv.toolbarVisualLeftPx())
    }

    private fun dp(v: Int): Int = (v * density).toInt()
}
