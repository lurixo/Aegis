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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
