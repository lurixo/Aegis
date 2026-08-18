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
import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
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

    private fun inkCenter(drawable: Drawable): Pair<Float, Float> {
        val bitmap = Bitmap.createBitmap(drawable.bounds.width(), drawable.bounds.height(), Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) ushr 24 != 0) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        bitmap.recycle()
        assertTrue(right >= left && bottom >= top)
        return (left + right + 1) / 2f to (top + bottom + 1) / 2f
    }

    private fun assertControlsUseKeySurfaces(controls: List<TextView>, name: String) {
        for (control in controls) {
            assertNotNull("$name control keeps a resting key surface", control.background)
            assertFalse("$name control does not fall back to a platform ripple", control.background is android.graphics.drawable.RippleDrawable)
            assertNull("$name control uses its shared animated surface instead of a foreground ripple", control.foreground)
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
        assertEquals(view.width / 4, backBounds.width())
        assertEquals(backBounds.width(), clearBounds.width())
        assertEquals(backBounds.width(), lockBounds.width())
        assertEquals(backBounds.width(), backspaceBounds.width())
        assertEquals(backBounds.height(), clearBounds.height())
        assertEquals(backBounds.height(), lockBounds.height())
        assertEquals(backBounds.height(), backspaceBounds.height())
        assertTrue(backBounds.width() >= (48 * view.resources.displayMetrics.density).toInt())
        assertTrue(backBounds.height() >= (48 * view.resources.displayMetrics.density).toInt())
        controls.forEach {
            val bitmap = Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
            it.draw(Canvas(bitmap))
            bitmap.recycle()
        }
        assertTrue("$name controls keep independent click targets", controls.all { it.hasOnClickListeners() })
        assertTrue("$name controls keep centered content", controls.all { it.gravity == Gravity.CENTER })
        assertNotNull("$name clear keeps its delete glyph", clear.compoundDrawables[0])
        assertNull("$name clear has no right-anchored glyph", clear.compoundDrawables[2])
        assertNotNull("$name delete keeps its glyph", backspace.compoundDrawables[0])
        assertNull("$name delete has no right-anchored glyph", backspace.compoundDrawables[2])
        for (control in listOf(clear, backspace)) {
            val glyph = requireNotNull(control.compoundDrawables[0])
            val center = inkCenter(glyph)
            assertEquals(glyph.bounds.exactCenterX(), center.first, 0.6f)
            assertEquals(glyph.bounds.exactCenterY(), center.second, 0.6f)
        }
    }

    @Test fun symbols_bottom_controls_follow_the_rail_center_and_content_columns_in_ltr_and_rtl() {
        for (layoutDirection in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            for (width in listOf(360, 480)) {
                val view = SymbolsView(ctx).apply {
                    this.layoutDirection = layoutDirection
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
                assertControlsUseKeySurfaces(controls, "SymbolsView")
                view.applyPalette(ImePalette.STATIC_DARK)
                assertControlsUseKeySurfaces(controls, "SymbolsView")
            }
        }
    }

    @Test fun emoji_bottom_controls_follow_the_rail_center_and_content_columns_in_ltr_and_rtl() {
        for (layoutDirection in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            for (width in listOf(360, 480)) {
                val view = EmojiView(ctx).apply {
                    this.layoutDirection = layoutDirection
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
                assertControlsUseKeySurfaces(controls, "EmojiView")
                view.applyPalette(ImePalette.STATIC_DARK)
                assertControlsUseKeySurfaces(controls, "EmojiView")
            }
        }
    }
}
