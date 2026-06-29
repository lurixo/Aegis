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

package com.aegis.ime.layout

import android.graphics.Paint
import android.util.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NineNumpadAlignmentTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val dm = ctx.resources.displayMetrics
    private val wPx = dm.widthPixels
    private val gapPx = 6f * dm.density

    private fun cells(l: KeyboardLayout) = l.cells!!

    @Test
    fun longestSyllableShowsFullyInTheLeftReadoutColumn() {
        val readout = listOf("zhuang", "shuang", "chuang", "zhu", "yi", "zhua")
            .map { Key(it, output = it, action = KeyAction.PICK_READING) }
        val sc = Layouts.nine(Lang.CN, readout, composing = true).scrollColumn!!
        val colWpx = sc.w * wPx - 2 * gapPx
        val avail = colWpx - 12f * dm.density
        val base = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17f, dm)
        fun widthAt(size: Float) = Paint().apply { textSize = size }.measureText("zhuang")
        val w0 = widthAt(base)
        val fitted = if (w0 > avail) (base * avail / w0).coerceAtLeast(11f * dm.density) else base
        assertTrue(
            "zhuang (${w0}px @17sp) must show fully in the ${avail}px left column (fitted ${widthAt(fitted)}px)",
            widthAt(fitted) <= avail + 0.5f,
        )
        assertTrue("the read-out stays legible (>=13sp) — the widening does the work, not an aggressive shrink",
            fitted >= 13f * dm.density)
    }

    @Test
    fun numpadSharesThePinyinNineKeyMetrics() {
        val nine = Layouts.nine(Lang.CN, Layouts.ninePunctuation())
        val numpad = Layouts.numpad()

        val numpadScroll = numpad.scrollColumn!!
        assertEquals("numpad op-column width == pinyin left-column width",
            nine.scrollColumn!!.w, numpadScroll.w, 1e-5f)
        assertEquals("op column is the leftmost strip", 0f, numpadScroll.x, 1e-5f)

        val abc = cells(nine).first { it.key.label == "ABC" }
        val d5 = cells(numpad).first { it.key.label == "5" }
        assertEquals("digit cell width == letter cell width", abc.w, d5.w, 1e-5f)
        assertEquals("digit cell height == letter cell height (row height)", abc.h, d5.h, 1e-5f)
        assertEquals("digit grid x aligns with the letter grid x", abc.x, d5.x, 1e-5f)

        assertTrue("numpad has no @ key", cells(numpad).none { it.key.label == "@" })

        val numEnter = cells(numpad).first { it.key.action == KeyAction.ENTER }
        val nineEnter = cells(nine).first { it.key.action == KeyAction.ENTER }
        assertEquals("numpad enter x == pinyin enter x (right column)", nineEnter.x, numEnter.x, 1e-5f)
        assertEquals("numpad enter y == pinyin enter y", nineEnter.y, numEnter.y, 1e-5f)
        assertEquals("numpad enter spans two rows (h=0.5)", 0.5f, numEnter.h, 1e-5f)
        assertEquals("numpad enter height == pinyin enter height", nineEnter.h, numEnter.h, 1e-5f)
        assertTrue("numpad enter is the green accent key", numEnter.key.accent)

        val labels = cells(numpad).map { it.key.label }
        assertTrue("digits 0-9 all present", (0..9).all { it.toString() in labels })
        val zero = cells(numpad).first { it.key.label == "0" }
        assertEquals("0 sits under the 2/5/8 column (aligned, not misplaced)", abc.x, zero.x, 1e-5f)
    }
}
