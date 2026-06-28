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

import android.graphics.Rect
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
class ImeSurfaceTopCornersTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun layout(iv: InputView) {
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
    }

    private fun assertTopRounded(iv: InputView, state: String) {
        val expected = ImeShapes.surfaceTopRadiusDp * density
        assertTrue("$state: the surface must clip its top corners", iv.surfaceClipsTopCornersForTest())
        assertEquals("$state: surface corner radius", expected, iv.surfaceTopRadiusPxForTest(), 0.01f)

        val outline = iv.surfaceTopOutlineForTest()
        assertEquals("$state: outline is a round rect at the surface radius", expected, outline.radius, 0.5f)
        val rect = Rect()
        assertTrue("$state: outline exposes its round rect", outline.getRect(rect))
        assertEquals("$state: rounding starts at the very top of the surface", 0, rect.top)
        assertEquals("$state: rounding spans the full surface width", iv.dockSurfaceWidthPx(), rect.width())
        assertTrue(
            "$state: bottom corners stay square — the outline runs past the surface bottom",
            rect.bottom > iv.surfaceContainerHeightForTest(),
        )
    }

    @Test fun surface_top_corners_rounded_at_container_level_in_every_bar_state() {
        val iv = InputView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        layout(iv)
        assertTopRounded(iv, "idle pill")

        iv.showCandidates(listOf("你好", "你", "尼", "拟"), "ni'hao", emptyList())
        layout(iv)
        assertTopRounded(iv, "candidates")

        iv.showCopyBar("这是一段被复制的内容")
        layout(iv)
        assertTopRounded(iv, "copy bar")

        iv.hideCopyBar()
        iv.showEditBar(true)
        layout(iv)
        assertTopRounded(iv, "edit bar")
    }

    @Test fun the_preedit_tab_starts_where_the_top_corners_end() = assertPreeditTabClearsTheCorners()

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-port-420dpi")
    fun the_preedit_tab_starts_where_the_top_corners_end_at_a_fractional_density() = assertPreeditTabClearsTheCorners()

    private fun assertPreeditTabClearsTheCorners() {
        for ((id, raw) in listOf(LayoutId.NINE to "64426", LayoutId.ALPHA to "nihao")) {
            val iv = InputView(ctx).apply {
                applyPalette(ImePalette.STATIC_LIGHT)
                showKeyboard(Layouts.forId(id, Lang.CN, true), false, false, Lang.CN)
                showCandidates(listOf("你好", "你"), "ni'hao", emptyList())
            }
            layout(iv)
            val radius = iv.surfaceTopRadiusPxForTest()
            val left = iv.dockSurfaceLeftPx() + radius
            val right = iv.dockSurfaceRightPx() - radius
            val origin = iv.preeditVisualLeftPx()
            val idle = iv.preeditTabForTest()
            assertTrue("$id: idle tab left ${origin + idle.left} clears the corner arc ending at $left", origin + idle.left >= left)
            iv.showCandidates(
                listOf("你好", "你"),
                "ni'hao",
                emptyList(),
                preeditModel = PreeditModel.align("ni'hao", 0, raw, emptyList(), emptyList(), 5),
            )
            layout(iv)
            val editing = iv.preeditTabForTest()
            assertTrue("$id: editing tab left ${origin + editing.left} clears the corner arc ending at $left", origin + editing.left >= left)
            assertTrue("$id: editing tab right ${origin + editing.right} clears the corner arc starting at $right", origin + editing.right <= right)
        }
    }

    @Test fun surface_top_radius_sits_within_the_toolbar_pill() {
        assertEquals("surface top radius constant", 8f, ImeShapes.surfaceTopRadiusDp, 0f)
        val pillEffectiveRadiusDp = ((44f - 5f * 2f) / 2f)
        assertTrue(
            "surface corner must not out-round the toolbar pill end (${pillEffectiveRadiusDp}dp)",
            ImeShapes.surfaceTopRadiusDp <= pillEffectiveRadiusDp,
        )
    }
}
