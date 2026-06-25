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
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.Layouts
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
class LockedReadingHighlightParityTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val palette = ImePalette.STATIC_LIGHT

    private fun laidOut(kb: KeyboardView): KeyboardView {
        kb.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        kb.layout(0, 0, kb.measuredWidth, kb.measuredHeight)
        return kb
    }

    private fun frame(kb: KeyboardView): Bitmap {
        val bitmap = Bitmap.createBitmap(kb.width, kb.height, Bitmap.Config.ARGB_8888)
        kb.draw(Canvas(bitmap))
        return bitmap
    }

    private fun cellRect(kb: KeyboardView, index: Int): RectF {
        val region = kb.scrollRegionForTest()
        val cell = kb.scrollCellHeightForTest()
        val top = region.top - kb.scrollOffsetForTest() + index * cell
        return RectF(
            region.left,
            maxOf(top, region.top),
            region.right,
            minOf(top + cell, region.bottom),
        )
    }

    private fun pixels(bitmap: Bitmap, rect: RectF, color: Int): Int {
        var found = 0
        for (y in rect.top.toInt() until rect.bottom.toInt()) {
            for (x in rect.left.toInt() until rect.right.toInt()) {
                if (bitmap.getPixel(x, y) == color) found++
            }
        }
        return found
    }

    @Test fun nine_key_left_column_leaves_unlocked_readings_in_the_plain_key_label_color() {
        val readings = listOf("ni", "nu", "ne", "na")
        val column = readings.mapIndexed { i, r ->
            Key(r, output = r, action = KeyAction.PICK_READING, weight = 0.85f, accent = i == readings.lastIndex)
        }
        val kb = laidOut(
            KeyboardView(ctx).apply {
                applyPalette(palette)
                setLayout(Layouts.nine(column, composing = true), false, false, Lang.CN)
            },
        )

        repeat(2) { pass ->
            val bitmap = frame(kb)
            assertTrue(
                "pass $pass: the marked reading keeps the mark color",
                pixels(bitmap, cellRect(kb, readings.lastIndex), palette.lockedReading) > 0,
            )
            for (i in 0 until readings.lastIndex) {
                assertTrue(
                    "pass $pass: plain reading ${readings[i]} still renders its label",
                    pixels(bitmap, cellRect(kb, i), palette.keyLabel) > 0,
                )
                assertEquals(
                    "pass $pass: plain reading ${readings[i]} must not borrow the mark color",
                    0,
                    pixels(bitmap, cellRect(kb, i), palette.lockedReading),
                )
            }
        }
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    private fun contrast(foreground: Int, background: Int): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    @Test fun the_marked_reading_stays_readable_on_the_rail_in_both_static_themes() {
        for ((name, theme) in listOf("light" to ImePalette.STATIC_LIGHT, "dark" to ImePalette.STATIC_DARK)) {
            val marked = contrast(theme.lockedReading, theme.functionSurface)
            val plain = contrast(theme.candidateText, theme.functionSurface)
            assertTrue(
                "$name marks the locked reading at ${"%.2f".format(marked)}:1, under the 4.5:1 a reader needs",
                marked >= 4.5,
            )
            assertTrue(
                "$name draws the plain readings at ${"%.2f".format(plain)}:1, under the 4.5:1 a reader needs",
                plain >= 4.5,
            )
            assertNotEquals("$name must still tell the marked reading apart", theme.candidateText, theme.lockedReading)
        }
    }
}
