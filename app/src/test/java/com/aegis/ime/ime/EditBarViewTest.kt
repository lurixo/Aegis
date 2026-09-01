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

import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditBarViewTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun textViews(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(x: View) { if (x is TextView) out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    private fun field(v: EditBarView): EditText = v.fieldForTest()
    private fun action(v: EditBarView, text: String): TextView = textViews(v).single { it.text?.toString() == text }

    private fun layout(v: View, w: Int = 480) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    @Test fun the_bar_keeps_the_toolbar_capsule_margin_above_its_row() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        val density = ctx.resources.displayMetrics.density
        val margin = (com.aegis.ime.ime.theme.ImeShapes.toolbarCapsuleMarginDp * density).toInt()
        val sides = textViews(v).filterNot { it === field(v) }.filter { it.isClickable }
        assertTrue("the bar has side controls", sides.isNotEmpty())
        val flp = field(v).layoutParams as android.view.ViewGroup.MarginLayoutParams
        assertEquals("the field keeps the toolbar capsule top margin", margin, flp.topMargin)
        assertEquals("the field keeps the toolbar capsule bottom margin", margin, flp.bottomMargin)
        for (side in sides) {
            val lp = side.layoutParams as android.view.ViewGroup.MarginLayoutParams
            assertEquals("a side control keeps the toolbar capsule top margin", margin, lp.topMargin)
            assertEquals("a side control keeps the toolbar capsule bottom margin", margin, lp.bottomMargin)
        }
    }

    @Test fun the_caret_follows_the_palette_accent_across_palette_changes() {
        val v = EditBarView(ctx)
        v.applyPalette(ImePalette.STATIC_LIGHT)
        val f = v.fieldForTest()
        val caret = f.textCursorDrawable as GradientDrawable
        assertEquals(ImePalette.STATIC_LIGHT.accentBottom, caret.color!!.defaultColor)
        v.applyPalette(ImePalette.STATIC_DARK)
        assertEquals("the cached caret instance takes the new accent", ImePalette.STATIC_DARK.accentBottom, caret.color!!.defaultColor)
        assertTrue("the field still holds the same caret instance", f.textCursorDrawable === caret)
    }

    @Test fun the_field_is_a_real_editor_that_never_summons_another_ime() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        val f = field(v)
        assertTrue("a caret can blink only in an editable field", f.isCursorVisible)
        assertTrue("tapping the field must be able to move the caret", f.isFocusableInTouchMode)
        assertFalse("focusing it must not open a second keyboard", f.showSoftInputOnFocus)
        assertNotEquals(
            "the field accepts line breaks",
            0,
            f.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )
    }

    @Test fun the_field_carries_no_placeholder_caret_character() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        v.setText("工作")
        assertEquals("工作", field(v).text.toString())
    }

    @Test fun seeding_the_field_parks_the_caret_at_the_end() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        v.setText("家庭住址")
        assertEquals(4, field(v).selectionStart)
        assertEquals(4, field(v).selectionEnd)
    }

    @Test fun the_exposed_editable_reads_and_writes_the_real_field() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        v.setText("abcd")
        val e = v.editable()
        e.setSelection(1, 3)
        assertEquals("abcd", e.snapshot())
        assertEquals(1, e.selectionStart())
        assertEquals(3, e.selectionEnd())
        e.replace(1, 3, "XY")
        assertEquals("aXYd", field(v).text.toString())
        assertEquals("the caret lands after the replacement", 3, field(v).selectionStart)
    }

    @Test fun edits_report_out_but_seeding_does_not() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        val seen = ArrayList<String>()
        v.onTextChanged = { seen.add(it) }
        v.setText("seed")
        assertTrue("seeding the field is not a user edit", seen.isEmpty())
        v.editable().replace(4, 4, "!")
        assertEquals(listOf("seed!"), seen)
    }

    @Test fun field_viewport_is_capped_at_four_lines() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT); setTitle("编辑常用语"); setText("短") }
        val f = field(v)
        assertEquals("the viewport is capped at 4 lines (maxHeight, not maxLines)",
            f.lineHeight * 4 + f.paddingTop + f.paddingBottom, f.maxHeight)
    }

    @Test fun a_one_line_budget_keeps_the_bar_at_a_single_row() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT); setTitle("编辑常用语") }
        v.setFieldLineBudget(1)
        v.setText((1..20).joinToString("\n") { "第${it}行内容" })
        layout(v)
        val f = field(v)
        assertEquals("a constrained bar shows exactly one line",
            f.lineHeight + f.paddingTop + f.paddingBottom, f.maxHeight)
        val tall = f.height
        v.setText("一行")
        layout(v)
        assertEquals("twenty lines take no more room than one", tall, field(v).height)
    }

    @Test fun restoring_the_budget_lets_the_field_grow_again() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        v.setFieldLineBudget(1)
        v.setFieldLineBudget(EditBarView.MAX_FIELD_LINES)
        val f = field(v)
        assertEquals(f.lineHeight * 4 + f.paddingTop + f.paddingBottom, f.maxHeight)
    }

    @Test fun long_content_overflows_four_lines_and_becomes_scrollable() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT); setTitle("编辑常用语") }
        v.setText((1..20).joinToString("\n") { "第${it}行内容" })
        layout(v)
        val f = field(v)
        assertTrue("the full text lays out to more than 4 lines", f.lineCount > 4)
        assertTrue("the viewport is capped near 4 lines, not the full content",
            f.height <= f.lineHeight * 4 + f.paddingTop + f.paddingBottom + 1)
        val contentHeight = f.layout.height + f.paddingTop + f.paddingBottom
        assertTrue("the content overflows the capped 4-line viewport (so it scrolls)", contentHeight > f.height)
    }

    @Test fun short_content_does_not_scroll() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT); setTitle("新建分类") }
        v.setText("工作")
        layout(v)
        val f = field(v)
        assertFalse("a short name needs no scrolling", f.canScrollVertically(1) || f.canScrollVertically(-1))
    }

    @Test fun confirm_accents_and_cancel_stays_neutral_in_both_palettes() {
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val view = EditBarView(ctx).apply { applyPalette(palette) }
            val cancel = action(view, ctx.getString(com.aegis.ime.R.string.editbar_cancel))
            val confirm = action(view, ctx.getString(com.aegis.ime.R.string.editbar_confirm))
            assertEquals(palette.keyLabel, cancel.currentTextColor)
            assertEquals(palette.keySurface, (cancel.background as GradientDrawable).color?.defaultColor)
            assertEquals(palette.accentBottom, confirm.currentTextColor)
            assertEquals(Motion.withAlpha(palette.accentBottom, 0x22), (confirm.background as GradientDrawable).color?.defaultColor)
        }
    }
}
