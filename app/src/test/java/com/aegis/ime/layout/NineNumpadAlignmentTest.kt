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

import android.view.View
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.ime.KeyboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NineNumpadAlignmentTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val dm = ctx.resources.displayMetrics
    private val wPx = dm.widthPixels
    private val gapPx = 3f * dm.density

    private fun cells(l: KeyboardLayout) = l.cells!!

    private fun actual(layout: KeyboardLayout): KeyboardView = KeyboardView(ctx).apply {
        setLayout(layout, false, false, Lang.CN)
        measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun longestSyllableShowsFullyInTheLeftReadoutColumn() {
        val displayed = T9Pinyin.SYLLABLES.filter {
            it in T9Pinyin.leftColumnReadings(T9Pinyin.toT9(it), 24)
        }
        assertEquals(T9Pinyin.SYLLABLES, displayed.toSet())
        assertEquals(6, displayed.maxOf(String::length))
        val readout = displayed.map { Key(it, output = it, action = KeyAction.PICK_READING) }
        val layout = Layouts.nine(Lang.CN, readout, composing = true)
        val sc = layout.scrollColumn!!
        assertEquals(0.85f / 4.7f, sc.w, 1e-5f)
        val view = actual(layout)
        val floor = view.scrollLabelMinTextSizeForTest()
        assertEquals(11f * dm.density, floor, 1e-5f)
        val avail = sc.w * wPx - 2 * gapPx - 12f * dm.density
        for (syllable in displayed) {
            val fitted = view.scrollLabelTextSizeForTest(syllable)
            assertTrue(
                "$syllable is shrunk to ${fitted}px, at or under the ${floor}px floor where the column starts cutting glyphs",
                fitted > floor,
            )
            assertTrue(
                "$syllable draws ${view.scrollLabelWidthForTest(syllable)}px at ${fitted}px, past the ${avail}px the column leaves it",
                view.scrollLabelWidthForTest(syllable) <= avail + 0.5f,
            )
        }
        val unshrunk = view.scrollLabelTextSizeForTest("n")
        val firstShrunk = requireNotNull(
            (1..200).map { "n".repeat(it) }.firstOrNull { view.scrollLabelTextSizeForTest(it) < unshrunk },
        ) { "no label was ever shrunk, so the ${avail}px the column leaves is not what triggers shrinking" }
        assertTrue(
            "the first shrunk label draws ${view.scrollLabelWidthForTest(firstShrunk)}px, so shrinking starts past the ${avail}px the column leaves",
            view.scrollLabelWidthForTest(firstShrunk) <= avail + 0.5f,
        )
        val overlong = displayed.joinToString("").take(64)
        assertEquals(
            "a label the column cannot fit at any readable size stops shrinking at the floor",
            floor,
            view.scrollLabelTextSizeForTest(overlong),
            1e-5f,
        )
        val right = cells(layout).first { it.key.action == KeyAction.BACKSPACE }
        assertEquals(sc.w, right.w, 1e-5f)
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

    @Test
    fun pinyinAndNumpadUseTheSameCompactActualGapsAndHitRectangles() {
        val nine = actual(Layouts.nine(Lang.CN, Layouts.ninePunctuation()))
        val numpad = actual(Layouts.numpad())
        val nineFaces = nine.keyBoundsForTest().associate { it.first.label to it.second }
        val numpadFaces = numpad.keyBoundsForTest().associate { it.first.label to it.second }
        val nineHits = nine.keyHitBoundsForTest().associate { it.first.label to it.second }
        val numpadHits = numpad.keyHitBoundsForTest().associate { it.first.label to it.second }
        val aligned = listOf(
            "1" to "@#", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL",
            "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
        )
        for ((digit, letters) in aligned) {
            assertEquals(nineFaces.getValue(letters), numpadFaces.getValue(digit))
            assertEquals(nineHits.getValue(letters), numpadHits.getValue(digit))
            val hit = numpadHits.getValue(digit)
            assertEquals(digit, numpad.keyAtForTest(hit.left + 0.01f, hit.centerY())?.label)
        }
        val expectedGap = 6f * dm.density
        val nineHorizontal = nineFaces.getValue("DEF").left - nineFaces.getValue("ABC").right
        val nineVertical = nineFaces.getValue("JKL").top - nineFaces.getValue("ABC").bottom
        val numpadHorizontal = numpadFaces.getValue("3").left - numpadFaces.getValue("2").right
        val numpadVertical = numpadFaces.getValue("5").top - numpadFaces.getValue("2").bottom
        assertEquals(expectedGap, nineHorizontal, 0.01f)
        assertEquals(expectedGap, nineVertical, 0.01f)
        assertEquals(nineHorizontal, numpadHorizontal, 0.01f)
        assertEquals(nineVertical, numpadVertical, 0.01f)
    }
}
