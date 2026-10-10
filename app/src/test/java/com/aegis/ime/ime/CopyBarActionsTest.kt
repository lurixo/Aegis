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
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CopyBarActionsTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val locales = listOf("en", "zh")

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun lay(v: View) {
        val w = ctx.resources.displayMetrics.widthPixels
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(44), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, w, dp(44))
    }

    private fun bar(source: String): CopyBarView =
        CopyBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT); show(source) }.also { lay(it) }

    private fun copied(v: CopyBarView): TextView =
        requireNotNull(v.contentScrollerForTest()).getChildAt(0) as TextView

    private fun action(v: CopyBarView, label: String): TextView {
        val out = ArrayList<View>()
        fun walk(x: View) { out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(v)
        return out.filterIsInstance<TextView>().single { it.text?.toString() == label && it.hasOnClickListeners() }
    }

    @Test fun split_label_is_the_size_of_the_copied_text() {
        val measured = HashSet<String>()
        for (locale in locales) {
            RuntimeEnvironment.setQualifiers("+$locale")
            val v = bar("one two")
            val label = ctx.getString(R.string.copybar_split)
            measured.add(label)
            assertEquals("$locale: $label", copied(v).textSize, action(v, label).textSize, 0f)
        }
        assertEquals("each locale shows its own label", locales.size, measured.size)
    }

    @Test fun collapse_label_is_the_size_of_the_copied_text() {
        val measured = HashSet<String>()
        for (locale in locales) {
            RuntimeEnvironment.setQualifiers("+$locale")
            val v = bar("one two")
            val size = copied(v).textSize
            v.toggleSplitForTest()
            lay(v)
            val label = ctx.getString(R.string.copybar_collapse)
            measured.add(label)
            assertEquals("$locale: $label", size, action(v, label).textSize, 0f)
            assertEquals("$locale: split blocks share that size", size, action(v, "one").textSize, 0f)
        }
        assertEquals("each locale shows its own label", locales.size, measured.size)
    }

    @Test fun close_glyph_keeps_its_size_and_width() {
        val v = bar("one two")
        val close = action(v, "×")
        val title = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.title, ctx.resources.displayMetrics)
        assertEquals(title, close.textSize, 0f)
        assertEquals(dp(34), close.width)
    }

    @Test fun add_word_label_is_the_size_of_the_copied_text() {
        val measured = HashSet<String>()
        for (locale in locales) {
            RuntimeEnvironment.setQualifiers("+$locale")
            val v = bar("词库")
            val label = ctx.getString(R.string.copybar_add_word)
            measured.add(label)
            assertEquals("$locale: $label", copied(v).textSize, action(v, label).textSize, 0f)
        }
        assertEquals("each locale shows its own label", locales.size, measured.size)
    }

    @Test fun add_word_and_split_labels_sit_twelve_dp_apart() {
        for (locale in locales) {
            RuntimeEnvironment.setQualifiers("+$locale")
            val v = bar("词库")
            val add = action(v, ctx.getString(R.string.copybar_add_word))
            val split = action(v, ctx.getString(R.string.copybar_split))
            assertEquals("$locale: the two actions are neighbours", add.right, split.left)
            val addInkRight = add.left + add.compoundPaddingLeft + add.layout.getLineRight(0)
            val splitInkLeft = split.left + split.compoundPaddingLeft + split.layout.getLineLeft(0)
            assertEquals("$locale: gap between the two labels", dp(12).toFloat(), splitInkLeft - addInkRight, 1f)
        }
    }

    @Test fun split_stays_at_least_thirty_two_dp_wide_beside_add_word() {
        for (locale in locales) {
            RuntimeEnvironment.setQualifiers("+$locale")
            val v = bar("词库")
            val split = action(v, ctx.getString(R.string.copybar_split))
            assertTrue("$locale: split is ${split.width}px wide", split.width >= dp(32))
            val close = action(v, "×")
            assertEquals("$locale: split keeps its gap to the close glyph", dp(10), split.paddingRight)
            assertEquals("$locale: split and close are neighbours", split.right, close.left)
        }
    }
}
