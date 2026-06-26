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

import android.view.MotionEvent
import android.view.View
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.ui.LetterCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterCaseDisplayTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density
    private val letters = ('a'..'z').toList()

    private fun alphaView(shifted: Boolean, case: LetterCase, lang: Lang = Lang.EN): KeyboardView =
        KeyboardView(context).apply {
            setLayout(Layouts.forId(LayoutId.ALPHA, lang), shifted, false, lang)
            caseMode = case
            measure(
                View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }


    @Test fun always_upper_shows_every_letter_uppercase_regardless_of_shift() {
        for (shifted in listOf(false, true)) {
            val v = alphaView(shifted, LetterCase.UPPER)
            for (c in letters) assertEquals("UPPER/$c (shift=$shifted)", c.uppercase(), v.displayLabelForTest(Key(c.toString())))
        }
    }

    @Test fun always_lower_shows_every_letter_lowercase_regardless_of_shift() {
        for (shifted in listOf(false, true)) {
            val v = alphaView(shifted, LetterCase.LOWER)
            for (c in letters) assertEquals("LOWER/$c (shift=$shifted)", c.lowercase(), v.displayLabelForTest(Key(c.toString())))
        }
    }

    @Test fun auto_follows_shift_lower_at_rest_upper_when_shifted() {
        val rest = alphaView(shifted = false, LetterCase.AUTO)
        val shift = alphaView(shifted = true, LetterCase.AUTO)
        for (c in letters) {
            assertEquals("AUTO rest/$c", c.lowercase(), rest.displayLabelForTest(Key(c.toString())))
            assertEquals("AUTO shifted/$c", c.uppercase(), shift.displayLabelForTest(Key(c.toString())))
        }
    }


    @Test fun the_preview_bubble_reflects_the_case_setting() {
        val v = alphaView(shifted = false, LetterCase.UPPER).apply { previewAlphaEnabled = true }
        val (x, y) = v.centerOfLabelForTest("q")!!
        v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        assertEquals("the bubble shows the UPPER-cased letter", "Q", v.previewLabelForTest())
        v.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0))
    }


    @Test fun the_case_setting_never_changes_the_committed_character() {
        for (case in LetterCase.entries) {
            val emitted = mutableListOf<String>()
            val v = alphaView(shifted = false, case).apply { onKey = { emitted.add(it.output) } }
            val (x, y) = v.centerOfLabelForTest("q")!!
            v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
            v.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0))
            assertEquals("$case: typing still commits the plain letter output", listOf("q"), emitted)
        }
    }


    @Test fun every_nine_key_letter_block_follows_the_case_setting_display_only() {
        val blocks = listOf(
            "ABC" to "2", "DEF" to "3", "GHI" to "4", "JKL" to "5",
            "MNO" to "6", "PQRS" to "7", "TUV" to "8", "WXYZ" to "9",
        )
        for ((face, digit) in blocks) {
            val block = Key(face, output = digit)
            for (shifted in listOf(false, true)) {
                assertEquals("$face UPPER (shift=$shifted)", face.uppercase(), alphaView(shifted, LetterCase.UPPER).displayLabelForTest(block))
                assertEquals("$face LOWER (shift=$shifted)", face.lowercase(), alphaView(shifted, LetterCase.LOWER).displayLabelForTest(block))
                assertEquals("$face AUTO (shift=$shifted) keeps the authored caps", face, alphaView(shifted, LetterCase.AUTO).displayLabelForTest(block))
            }
            assertEquals("$face: the emitted T9 digit is untouched by the display setting", digit, block.output)
        }
    }


    @Test fun in_cn_always_upper_still_commits_the_lowercase_pinyin_letter() {
        val emitted = mutableListOf<String>()
        val v = alphaView(shifted = false, LetterCase.UPPER, lang = Lang.CN).apply { onKey = { emitted.add(it.output) } }
        val (x, y) = v.centerOfLabelForTest("q")!!
        assertEquals("the face shows uppercase", "Q", v.displayLabelForTest(Key("q")))
        v.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0))
        assertEquals("but pinyin still receives lowercase", listOf("q"), emitted)
    }
}
