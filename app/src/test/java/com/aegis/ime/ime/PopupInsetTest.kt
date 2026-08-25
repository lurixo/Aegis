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

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PopupInsetTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT

    private fun inset(): Int =
        (2 * TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.body, ctx.resources.displayMetrics)).roundToInt()

    private fun layout(v: View, w: Int = 480, h: Int = 320) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun allViews(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }

    private fun textViews(root: View): List<TextView> = allViews(root).filterIsInstance<TextView>()
    private fun text(root: View, label: String): TextView = textViews(root).first { it.text?.toString() == label }

    private fun assertPaddedTwoCharacters(name: String, tv: TextView) {
        assertEquals("$name starts two characters in", inset(), tv.paddingLeft)
        assertEquals("$name ends two characters in", inset(), tv.paddingRight)
    }

    @Test fun translate_mode_choices_sit_two_characters_in() {
        val bar = TranslateBarView(ctx).apply { applyPalette(pal) }
        layout(bar, 480, 200)
        bar.modeButtonForTest().performClick()
        val card = requireNotNull(bar.dialogForTest()).contentView as ViewGroup
        val choices = (0 until card.childCount).map { card.getChildAt(it) as TextView }
        assertTrue(choices.isNotEmpty())
        for (choice in choices) assertPaddedTwoCharacters("translate choice ${choice.text}", choice)
        bar.dismissModeDialog()
    }
}
