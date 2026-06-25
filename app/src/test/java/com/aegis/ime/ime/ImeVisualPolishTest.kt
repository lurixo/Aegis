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

import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeVisualPolishTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun the_candidate_bar_shares_the_board_floor_without_a_bottom_rule() {
        val palette = ImePalette.STATIC_LIGHT.copy(
            keyboardBg = android.graphics.Color.WHITE,
            gridLine = android.graphics.Color.RED,
        )
        val v = CandidateView(ctx).apply {
            applyPalette(palette)
            setContent(listOf("\u4f60", "\u597d"), "ni")
        }
        val density = ctx.resources.displayMetrics.density
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))

        assertEquals("the bar shares the board floor", palette.keyboardBg, bmp.getPixel((2 * density).toInt(), (2 * density).toInt()))
        for (x in 0 until v.width) {
            assertNotEquals("no rule closes the candidate bar at x=$x", palette.gridLine, bmp.getPixel(x, v.height - 1))
        }

        val idle = CandidateView(ctx).apply { applyPalette(palette) }
        idle.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        idle.layout(0, 0, idle.measuredWidth, idle.measuredHeight)
        val idleBmp = android.graphics.Bitmap.createBitmap(idle.width, idle.height, android.graphics.Bitmap.Config.ARGB_8888)
        idle.draw(android.graphics.Canvas(idleBmp))
        assertEquals("the toolbar shares the board floor", palette.keyboardBg, idleBmp.getPixel((2 * density).toInt(), (2 * density).toInt()))
        for (x in 0 until idle.width) {
            assertNotEquals("no rule closes the toolbar at x=$x", palette.gridLine, idleBmp.getPixel(x, idle.height - 1))
        }
    }

    @Test fun candidate_toolbar_press_radius_is_smaller_than_key_radius() {
        val v = CandidateView(ctx)
        assertEquals(ImeShapes.toolbarFeedbackRadiusDp, v.taskbarPressRadiusDpForTest(), 0f)
        assertTrue("toolbar press shape must not read as a capsule", v.taskbarPressRadiusDpForTest() < v.keyPressRadiusDpForTest())
    }

    @Test fun shared_aegis_surface_radii_keep_the_taskbar_capsule() {
        assertEquals(10f, ImeShapes.keyRadiusDp, 0f)
        assertEquals(6f, ImeShapes.toolbarFeedbackRadiusDp, 0f)
        assertEquals(8f, ImeShapes.cardRadiusDp, 0f)
        assertEquals(8f, ImeShapes.inputRadiusDp, 0f)
        assertEquals(8f, ImeShapes.chipRadiusDp, 0f)
        assertEquals(999f, ImeShapes.toolbarPillRadiusDp, 0f)
    }

    @Test fun tap_feedback_helper_installs_a_rounded_ripple_foreground() {
        val v = View(ctx)
        Motion.applyTapFeedback(v, ImePalette.STATIC_LIGHT.keyLabel)
        assertTrue("clickable helper uses RippleDrawable feedback", v.foreground is RippleDrawable)
    }
}
