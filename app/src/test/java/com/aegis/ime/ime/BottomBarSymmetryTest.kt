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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

    private fun bounds(root: ViewGroup, descendant: View): Rect = Rect(0, 0, descendant.width, descendant.height).also {
        root.offsetDescendantRectToMyCoords(descendant, it)
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
        view: ViewGroup,
        back: TextView,
        clear: TextView,
        lock: TextView,
        backspace: TextView,
        name: String,
    ) {
        val controls = listOf(back, clear, lock, backspace)
        val centers = controls.map { centerX(view, it) }
        assertEquals(controls, controls.sortedBy { centerX(view, it) })
        assertEquals(centers[1] - centers[0], centers[2] - centers[1], 1f)
        assertEquals(centers[2] - centers[1], centers[3] - centers[2], 1f)

        val backBounds = bounds(view, back)
        val clearBounds = bounds(view, clear)
        val lockBounds = bounds(view, lock)
        val backspaceBounds = bounds(view, backspace)
        assertEquals((60 * view.resources.displayMetrics.density).toInt(), backBounds.width())
        assertEquals(backBounds.width(), clearBounds.width())
        assertEquals(backBounds.width(), lockBounds.width())
        assertEquals(backBounds.width(), backspaceBounds.width())
        assertEquals(backBounds.height(), clearBounds.height())
        assertEquals(backBounds.height(), lockBounds.height())
        assertEquals(backBounds.height(), backspaceBounds.height())
        controls.forEach {
            val bitmap = Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
            it.draw(Canvas(bitmap))
            bitmap.recycle()
        }
        assertEquals(Rect(0, 0, back.width, back.height), back.background.bounds)
        assertEquals(Rect(0, 0, backspace.width, backspace.height), backspace.background.bounds)
        assertTrue("$name controls keep independent click targets", controls.all { it.hasOnClickListeners() })
        assertTrue("$name controls keep centered content", controls.all { it.gravity == Gravity.CENTER })
        assertNotNull("$name clear keeps its delete glyph", clear.compoundDrawables[0])
        assertNull("$name clear has no right-anchored glyph", clear.compoundDrawables[2])
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
            val clear = view.clearBtnForTest()
            val backspace = view.backspaceBtnForTest()
            val controls = listOf(back, clear, view.lockBtnForTest(), backspace)
            assertAxes(
                view,
                back,
                clear,
                view.lockBtnForTest(),
                backspace,
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
            val clear = view.clearBtnForTest()
            val backspace = view.backspaceBtnForTest()
            val controls = listOf(back, clear, view.lockBtnForTest(), backspace)
            assertAxes(
                view,
                back,
                clear,
                view.lockBtnForTest(),
                backspace,
                "EmojiView",
            )
            assertControlBackgrounds(controls, ImePalette.STATIC_LIGHT, "EmojiView")
            view.applyPalette(ImePalette.STATIC_DARK)
            assertControlBackgrounds(controls, ImePalette.STATIC_DARK, "EmojiView")
        }
    }
}
