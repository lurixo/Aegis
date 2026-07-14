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

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomBarSymmetryTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun layout(view: View, width: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun centerX(root: View, descendant: View): Float {
        var current = descendant
        var x = current.width / 2f
        while (current !== root) {
            x += current.left
            current = current.parent as View
        }
        return x
    }

    private fun assertControlBackgrounds(controls: List<TextView>, palette: ImePalette, name: String) {
        for (control in controls) {
            val background = control.background
            assertTrue("$name control has a rounded rectangle background", background is GradientDrawable)
            background as GradientDrawable
            assertEquals(palette.keySurface, background.color?.defaultColor)
            assertEquals(ImeShapes.keyRadiusDp * control.resources.displayMetrics.density, background.cornerRadius, 0f)
        }
    }

    private fun assertAxes(
        view: View,
        rail: View,
        back: TextView,
        lock: TextView,
        backspace: TextView,
        lastCell: View,
        name: String,
    ) {
        assertEquals((60 * view.resources.displayMetrics.density).toInt(), back.width)
        assertEquals(centerX(view, rail), centerX(view, back), 1f)
        assertEquals(view.width / 2f, centerX(view, lock), 1f)
        assertEquals(centerX(view, lastCell), centerX(view, backspace), 1f)
        assertEquals(Gravity.CENTER, back.gravity)
        assertEquals(Gravity.CENTER, backspace.gravity)
        assertNotNull("$name delete keeps its glyph", backspace.compoundDrawables[0])
        assertNull("$name delete has no right-anchored glyph", backspace.compoundDrawables[2])
    }

    @Test fun symbols_bottom_controls_follow_the_rail_center_and_content_columns() {
        for (width in listOf(360, 480)) {
            val view = SymbolsView(ctx).apply {
                recentProvider = { (1..7).map(Int::toString) }
                applyPalette(ImePalette.STATIC_LIGHT)
                refresh()
            }
            layout(view, width)
            val back = view.backBtnForTest()
            val backspace = view.backspaceBtnForTest()
            val controls = listOf(back, view.lockBtnForTest(), backspace)
            assertAxes(
                view,
                view.railTabForTest(0),
                back,
                view.lockBtnForTest(),
                backspace,
                requireNotNull(view.gridGlyphForTest("7")),
                "SymbolsView",
            )
            assertControlBackgrounds(controls, ImePalette.STATIC_LIGHT, "SymbolsView")
            view.applyPalette(ImePalette.STATIC_DARK)
            assertControlBackgrounds(controls, ImePalette.STATIC_DARK, "SymbolsView")
        }
    }

    @Test fun emoji_bottom_controls_follow_the_rail_center_and_content_columns() {
        for (width in listOf(360, 480)) {
            val view = EmojiView(ctx).apply {
                recentProvider = { (1..7).map(Int::toString) }
                applyPalette(ImePalette.STATIC_LIGHT)
            }
            layout(view, width)
            val back = view.backBtnForTest()
            val backspace = view.backspaceBtnForTest()
            val controls = listOf(back, view.lockBtnForTest(), backspace)
            assertAxes(
                view,
                view.railTabForTest(0),
                back,
                view.lockBtnForTest(),
                backspace,
                requireNotNull(view.gridCellForTest(6)),
                "EmojiView",
            )
            assertControlBackgrounds(controls, ImePalette.STATIC_LIGHT, "EmojiView")
            view.applyPalette(ImePalette.STATIC_DARK)
            assertControlBackgrounds(controls, ImePalette.STATIC_DARK, "EmojiView")
        }
    }
}
