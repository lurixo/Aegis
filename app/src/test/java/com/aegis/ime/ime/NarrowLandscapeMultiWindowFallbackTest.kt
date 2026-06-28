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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h200dp-land-mdpi")
class NarrowLandscapeMultiWindowFallbackTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test fun full_width_emergency_shrinks_horizontal_gaps_before_crossing_the_20dp_key_floor() {
        val view = InputView(context)
        ViewCompat.dispatchApplyWindowInsets(
            view,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(24, 0, 24, 0))
                .build(),
        )
        view.showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
        layoutWithRealWindowCap(view)

        assertFullWidthFallback(view)
        assertEquals(24, view.bodyLeftPaddingPxForTest())
        assertEquals(24, view.bodyRightPaddingPxForTest())
        assertEquals(272, view.keyboardVisualWidthPx())
        assertTrue(
            "a physically full fallback adapts the nominal 6dp gaps to retain 20dp ALPHA faces",
            view.keyboardMinimumKeyWidthPxForTest() >= MIN_KEY_WIDTH_DP,
        )
        assertTrue(view.tapKeyboardActionForTest(KeyAction.ENTER))
    }

    private fun layoutWithRealWindowCap(view: InputView) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WINDOW_WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(WINDOW_HEIGHT_PX, View.MeasureSpec.AT_MOST),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertEquals(WINDOW_WIDTH_PX, view.measuredWidth)
        assertEquals("natural content is taller, so the real AT_MOST cap must be consumed exactly", WINDOW_HEIGHT_PX, view.measuredHeight)
    }

    private fun assertFullWidthFallback(view: InputView) {
        assertFalse("near-square multi-window cannot safely float", view.isCompactLandscapeDock())
        assertEquals("no fake transparent host gutter", 0, view.dockSurfaceLeftPx())
        assertEquals(WINDOW_WIDTH_PX, view.dockSurfaceWidthPx())
        assertEquals(WINDOW_WIDTH_PX, view.dockSurfaceRightPx())
        assertEquals("preedit uses the same full fallback surface", 0, view.preeditVisualLeftPx())
        assertEquals(WINDOW_WIDTH_PX, view.preeditVisualRightPx())
        assertEquals(0, view.preeditVisualTopPx())
        assertTrue(view.preeditVisualBottomPx() <= view.measuredHeight)
        assertTrue(view.dockSurfaceTopPx() >= view.preeditVisualBottomPx())
        assertEquals("the opaque surface reaches, but never crosses, the capped root bottom", view.measuredHeight, view.dockSurfaceBottomPx())
        assertTrue(view.toolbarVisualBottomPx() <= view.measuredHeight)
        assertTrue(view.keyboardVisualBottomPx() <= view.measuredHeight)
        assertTrue(view.keyboardVisualTopPx() >= view.dockSurfaceTopPx())
    }

    private companion object {
        private const val WINDOW_WIDTH_PX = 320
        private const val WINDOW_HEIGHT_PX = 200
        private const val MIN_KEY_WIDTH_DP = 20f
    }
}
