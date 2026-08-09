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
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScrollColumnInkCenterTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val gap = 3f * density
    private val u = 1f / 4.7f
    private val pal = ImePalette.STATIC_LIGHT

    private fun render(v: KeyboardView, hPx: Int): Bitmap {
        v.applyPalette(pal)
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    private fun inkBox(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): IntArray? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = bmp.getPixel(x, y)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if ((r + g + b) / 3 < 110) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        return if (maxX < minX) null else intArrayOf(minX, minY, maxX, maxY)
    }

    private class Cell(val label: String, val inkCx: Float, val inkCy: Float, val cellCx: Float, val cellCy: Float, val cellH: Float, val cellW: Float)

    private fun measureColumn(v: KeyboardView, labels: List<String>, hPx: Int, regionH: Float): List<Cell> {
        val bmp = render(v, hPx)
        val h = bmp.height.toFloat()
        val regTop = gap
        val regBottom = regionH * h - gap
        val cellH = (regBottom - regTop) / 4f
        val left = gap
        val right = u * bmp.width - gap
        val cellCx = (left + right) / 2f
        val pad = (3f * density).toInt()
        val scrollbar = (6f * density).toInt()
        val out = ArrayList<Cell>()
        val visible = minOf(labels.size, 4)
        for (i in 0 until visible) {
            val cellTop = regTop + cellH * i
            val cellBottom = cellTop + cellH
            val box = inkBox(
                bmp,
                (left + pad).toInt(), (cellTop + pad).toInt(),
                (right - scrollbar - pad).toInt(), (cellBottom - pad).toInt(),
            ) ?: continue
            out.add(
                Cell(
                    labels[i],
                    (box[0] + box[2]) / 2f, (box[1] + box[3]) / 2f,
                    cellCx, cellTop + cellH / 2f, cellH, right - left,
                ),
            )
        }
        return out
    }

    private fun nineCustom(labels: List<String>): KeyboardView {
        val col = labels.map { Key(it, output = it, action = KeyAction.PICK_READING) }
        return KeyboardView(ctx).apply { setLayout(Layouts.nine(Lang.CN, col, composing = true), false, false, Lang.CN) }
    }

    @Test fun discriminating_latin_glyphs_are_ink_centred_on_both_axes() {
        val labels = listOf(".", "g", "-", "|")
        val cells = measureColumn(nineCustom(labels), labels, (230 * density).toInt(), 0.75f)
        assertTrue("all four discriminating cells rendered ink (${cells.size})", cells.size == 4)
        val fails = ArrayList<String>()
        for (c in cells) {
            if (kotlin.math.abs(c.inkCy - c.cellCy) > c.cellH * 0.16f) fails.add("${c.label} Y off by ${c.inkCy - c.cellCy} (cellH=${c.cellH})")
            if (kotlin.math.abs(c.inkCx - c.cellCx) > c.cellW * 0.16f) fails.add("${c.label} X off by ${c.inkCx - c.cellCx}")
        }
        assertTrue("scroll-column marks not ink-centred: $fails", fails.isEmpty())
    }

    @Test fun pinyin_punctuation_column_marks_are_ink_centred() {
        val labels = Layouts.ninePunctuation().map { it.label }
        val v = KeyboardView(ctx).apply {
            setLayout(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = false), false, false, Lang.CN)
        }
        val cells = measureColumn(v, labels, (230 * density).toInt(), 0.75f)
        assertTrue("punctuation cells rendered ink (${cells.size})", cells.size >= 3)
        val fails = cells.filter { kotlin.math.abs(it.inkCy - it.cellCy) > it.cellH * 0.22f }
            .map { "${it.label} Y off ${it.inkCy - it.cellCy}" }
        assertTrue("pinyin column marks not ink-centred: $fails", fails.isEmpty())
    }

    @Test fun numpad_operator_column_marks_are_ink_centred() {
        val labels = Layouts.defaultNumpadOperators
        val v = KeyboardView(ctx).apply { setLayout(Layouts.numpad(Layouts.numpadOperators()), false, false, Lang.CN) }
        val cells = measureColumn(v, labels, (230 * density).toInt(), 1.0f)
        assertTrue("operator cells rendered ink (${cells.size})", cells.size >= 3)
        val fails = cells.filter { kotlin.math.abs(it.inkCy - it.cellCy) > it.cellH * 0.20f }
            .map { "${it.label} Y off ${it.inkCy - it.cellCy}" }
        assertTrue("numpad operator column marks not ink-centred: $fails", fails.isEmpty())
    }
}
