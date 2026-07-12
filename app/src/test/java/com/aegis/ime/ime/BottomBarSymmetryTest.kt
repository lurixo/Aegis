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
import android.view.Gravity
import android.view.View
import android.widget.TextView
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BottomBarSymmetryTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun assertBackspaceHugsRightSymmetrically(back: TextView, backspace: TextView, name: String) {
        assertNull("$name: ⌫ must NOT be a LEFT compound drawable (that anchors it to the button's left edge)", backspace.compoundDrawables[0])
        assertNotNull("$name: ⌫ must be the END/right compound drawable so it hugs the right edge", backspace.compoundDrawables[2])
        assertTrue("$name: 返回 must have a left inset", back.paddingLeft > 0)
        assertEquals("$name: ⌫ right inset must mirror 返回's left inset", back.paddingLeft, backspace.paddingRight)
        assertEquals("$name: 返回 must not also be right-inset", 0, back.paddingRight)
        assertEquals("$name: ⌫ must not also be left-inset", 0, backspace.paddingLeft)
    }

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

    private fun inkCenter(view: View): Pair<Float, Float> {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        var left = view.width
        var top = view.height
        var right = -1
        var bottom = -1
        for (y in 0 until view.height) {
            for (x in 0 until view.width) {
                if (bitmap.getPixel(x, y) ushr 24 != 0) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue(right >= left && bottom >= top)
        return (left + right) / 2f to (top + bottom) / 2f
    }

    @Test fun symbols_back_boundary_and_backspace_alignment_match_layout() {
        for (width in listOf(360, 480)) {
            val view = SymbolsView(ctx).apply {
                recentProvider = { (1..7).map(Int::toString) }
                refresh()
            }
            layout(view, width)
            val seventh = requireNotNull(view.gridGlyphForTest("7"))
            val back = view.backBtnForTest()
            val backspace = view.backspaceBtnForTest()
            assertEquals(width / 3, back.width)
            assertEquals(0f, centerX(view, back) - back.width / 2f, 0f)
            assertEquals(Gravity.START or Gravity.CENTER_VERTICAL, back.gravity)
            assertEquals((20 * view.resources.displayMetrics.density).toInt(), back.paddingLeft)
            assertEquals(0, back.paddingTop)
            assertEquals(0, back.paddingRight)
            assertEquals(0, back.paddingBottom)
            assertTrue(abs(centerX(view, backspace) - centerX(view, seventh)) <= 1f)
            assertEquals(Gravity.CENTER, backspace.gravity)
            val (inkX, inkY) = inkCenter(backspace)
            assertEquals(backspace.width / 2f, inkX, 1f)
            assertEquals(backspace.height / 2f, inkY, 1f)
        }
    }

    @Test fun emoji_bottom_bar_is_left_right_symmetric() {
        val v = EmojiView(ctx)
        assertBackspaceHugsRightSymmetrically(v.backBtnForTest(), v.backspaceBtnForTest(), "EmojiView")
    }
}
