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
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.translate.TranslateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslateBarViewTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun field(v: TranslateBarView): EditText = v.fieldForTest()

    private fun layout(v: View, w: Int = 480) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun bg(v: View): Int = (v.background as GradientDrawable).color!!.defaultColor

    private fun caption(v: TranslateBarView): TextView =
        (v.fieldBoxForTest() as ViewGroup).getChildAt(0) as TextView

    @Test fun the_translate_caption_sits_inside_the_field_above_the_text() {
        val v = TranslateBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT); setText("你好") }
        layout(v)
        val box = v.fieldBoxForTest()
        val caption = caption(v)
        assertEquals(ctx.getString(com.aegis.ime.R.string.translate_caption), caption.text.toString())
        assertTrue("the editor lives in the same field box", field(v).parent === box)
        assertTrue("the caption sits above the editor", caption.bottom <= field(v).top)
        val density = ctx.resources.displayMetrics.density
        assertEquals((TranslateBarView.CAPTION_ROW_DP * density).toInt(), caption.height)
        val f = field(v)
        assertTrue(f.height >= f.lineHeight + f.paddingTop + f.paddingBottom)
        assertEquals(maxOf((TranslateBarView.LABELED_FIELD_HEIGHT_DP * density).toInt(), caption.height + f.height), box.height)
        assertEquals(box.height + 2 * (ImeShapes.toolbarCapsuleMarginDp * density).toInt(), v.height)
    }

    @Test fun a_one_line_budget_drops_the_caption_and_restores_the_compact_field() {
        val v = TranslateBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT); setText("你好") }
        val caption = caption(v)
        val density = ctx.resources.displayMetrics.density
        v.setFieldLineBudget(1)
        layout(v)
        assertEquals(View.GONE, caption.visibility)
        val f = field(v)
        val compact = maxOf((TranslateBarView.COMPACT_FIELD_HEIGHT_DP * density).toInt(), f.height)
        assertEquals(compact, v.fieldBoxForTest().height)
        v.setFieldLineBudget(TranslateBarView.MAX_FIELD_LINES)
        layout(v)
        assertEquals(View.VISIBLE, caption.visibility)
        val labelled = maxOf((TranslateBarView.LABELED_FIELD_HEIGHT_DP * density).toInt(), caption.height + f.height)
        assertEquals(labelled, v.fieldBoxForTest().height)
        assertTrue(labelled > compact)
    }

    private fun radio(v: TranslateBarView, m: TranslateMode): LayerDrawable =
        v.choiceForTest(m).compoundDrawablesRelative[0] as LayerDrawable

    @Test fun the_caret_follows_the_palette_accent_across_palette_changes() {
        val v = TranslateBarView(ctx)
        v.applyPalette(ImePalette.STATIC_LIGHT)
        val f = v.fieldForTest()
        val caret = f.textCursorDrawable as GradientDrawable
        assertEquals(ImePalette.STATIC_LIGHT.accentBottom, caret.color!!.defaultColor)
        v.applyPalette(ImePalette.STATIC_DARK)
        assertEquals("the cached caret instance takes the new accent", ImePalette.STATIC_DARK.accentBottom, caret.color!!.defaultColor)
        assertTrue("the field still holds the same caret instance", f.textCursorDrawable === caret)
    }

    @Test fun the_field_is_a_real_editor_that_never_summons_another_ime() {
        val v = TranslateBarView(ctx)
        val f = field(v)
        assertTrue(f.isCursorVisible)
        assertFalse("the in-keyboard field must not raise a second keyboard", f.showSoftInputOnFocus)
        assertTrue(f.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
        assertTrue(f.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        v.setText("ab")
        assertEquals(2, f.selectionStart)
        v.editable().replace(1, 1, "X")
        assertEquals("aXb", v.text())
        assertEquals(2, v.editable().selectionStart())
    }

    @Test fun typing_reports_text_changes_but_programmatic_seeding_stays_silent() {
        val v = TranslateBarView(ctx)
        val seen = ArrayList<String>()
        v.onTextChanged = { seen += it }
        v.setText("seed")
        assertTrue(seen.isEmpty())
        v.editable().replace(4, 4, "!")
        assertEquals(listOf("seed!"), seen)
    }

    @Test fun the_mode_capsule_opens_a_dialog_with_the_modes_stacked_top_to_bottom() {
        val v = TranslateBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        layout(v)
        val changes = ArrayList<TranslateMode>()
        v.onModeChanged = { changes += it }
        assertEquals(TranslateMode.AUTO, v.mode())
        assertEquals("Auto", v.modeButtonForTest().text.toString())
        assertEquals(null, v.dialogForTest())

        v.modeButtonForTest().performClick()
        val dialog = v.dialogForTest()!!
        val card = dialog.contentView as android.widget.LinearLayout
        assertEquals(android.widget.LinearLayout.VERTICAL, card.orientation)
        assertEquals(TranslateMode.entries.size, card.childCount)
        for ((i, m) in TranslateMode.entries.withIndex()) {
            assertEquals("row " + i + " keeps top-to-bottom order", v.choiceForTest(m), card.getChildAt(i))
        }
        assertEquals("Auto", v.choiceForTest(TranslateMode.AUTO).text.toString())
        assertEquals("Chinese \u21c4 Japanese", v.choiceForTest(TranslateMode.ZH_JA).text.toString())
        assertEquals(ImePalette.STATIC_LIGHT.keyLabel, v.choiceForTest(TranslateMode.AUTO).currentTextColor)
        assertEquals(ImePalette.STATIC_LIGHT.keyLabel, v.choiceForTest(TranslateMode.ZH_JA).currentTextColor)
        assertEquals("the current mode row carries the filled radio dot", 2, radio(v, TranslateMode.AUTO).numberOfLayers)
        assertEquals("an idle row keeps an empty radio ring", 1, radio(v, TranslateMode.ZH_JA).numberOfLayers)
        val density = ctx.resources.displayMetrics.density
        assertEquals("the ring stays compact beside the row text", (16 * density).toInt(), radio(v, TranslateMode.AUTO).intrinsicWidth)
        assertEquals((16 * density).toInt(), radio(v, TranslateMode.ZH_JA).intrinsicWidth)

        v.choiceForTest(TranslateMode.ZH_JA).performClick()
        assertEquals(TranslateMode.ZH_JA, v.mode())
        assertEquals(listOf(TranslateMode.ZH_JA), changes)
        assertEquals("choosing dismisses the dialog", null, v.dialogForTest())
        assertTrue("the field takes focus back after choosing", field(v).isFocused)
        assertEquals("Chinese \u21c4 Japanese", v.modeButtonForTest().text.toString())
        assertEquals("the radio dot follows the chosen mode", 2, radio(v, TranslateMode.ZH_JA).numberOfLayers)
        assertEquals(1, radio(v, TranslateMode.AUTO).numberOfLayers)

        v.modeButtonForTest().performClick()
        v.choiceForTest(TranslateMode.ZH_JA).performClick()
        assertEquals("re-choosing the same mode is not a change", 1, changes.size)
        assertEquals(null, v.dialogForTest())

        v.modeButtonForTest().performClick()
        v.modeButtonForTest().performClick()
        assertEquals("the capsule toggles the dialog closed again", null, v.dialogForTest())
    }

    @Test fun the_bar_keeps_the_toolbar_capsule_margin_above_its_row() {
        val v = TranslateBarView(ctx)
        val density = ctx.resources.displayMetrics.density
        val margin = (ImeShapes.toolbarCapsuleMarginDp * density).toInt()
        val flp = v.fieldBoxForTest().layoutParams as android.view.ViewGroup.MarginLayoutParams
        assertEquals("the field keeps the toolbar capsule top margin", margin, flp.topMargin)
        assertEquals("the field keeps the toolbar capsule bottom margin", margin, flp.bottomMargin)
        for (side in listOf(v.modeButtonForTest(), v.closeButtonForTest())) {
            val lp = side.layoutParams as android.view.ViewGroup.MarginLayoutParams
            assertEquals("a side capsule keeps the toolbar capsule top margin", margin, lp.topMargin)
            assertEquals("a side capsule keeps the toolbar capsule bottom margin", margin, lp.bottomMargin)
        }
    }

    @Test fun the_mode_selector_is_bold_body_text_on_the_right_without_a_face() {
        val v = TranslateBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        layout(v)
        val mode = v.modeButtonForTest()
        assertEquals(null, mode.background)
        assertTrue("the selector is emphasised by weight instead of a face", mode.typeface.isBold)
        assertEquals(ImePalette.STATIC_LIGHT.keyLabel, mode.currentTextColor)
        assertTrue("the selector sits right of the field", mode.left >= v.fieldBoxForTest().right)
        val ripple = mode.foreground as? android.graphics.drawable.RippleDrawable
            ?: throw AssertionError("the selector keeps rounded tap feedback")
        assertTrue((ripple.findDrawableByLayerId(android.R.id.mask) as GradientDrawable).cornerRadius > 0f)
    }

    @Test fun refocusing_the_field_keeps_the_dialog_until_a_choice_or_dismissal() {
        val v = TranslateBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        layout(v)
        v.modeButtonForTest().performClick()
        assertTrue(v.isModeDialogShowing())
        v.focusField()
        assertTrue("a session restore refocusing the field keeps the dialog", v.isModeDialogShowing())
        v.dismissModeDialog()
        assertFalse(v.isModeDialogShowing())
    }

    @Test fun the_dialog_never_reshapes_the_bar_row() {
        val v = TranslateBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        layout(v, w = 360)
        val fieldHeight = v.measuredHeight
        v.modeButtonForTest().performClick()
        layout(v, w = 360)
        assertEquals("the bar keeps its row while the dialog is up", fieldHeight, v.measuredHeight)
        assertEquals(android.view.View.VISIBLE, field(v).visibility)
    }

    @Test fun set_mode_relabels_the_capsule_without_announcing_a_change() {
        val v = TranslateBarView(ctx)
        var announced = 0
        v.onModeChanged = { announced++ }
        v.setMode(TranslateMode.ZH_EN)
        assertEquals("Chinese ⇄ English", v.modeButtonForTest().text.toString())
        assertEquals(0, announced)
    }

    @Test fun the_back_control_leads_the_bar_and_only_asks_the_host_to_close() {
        val v = TranslateBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        var closed = 0
        v.onClose = { closed++ }
        v.setText("keep")
        layout(v)
        val back = v.closeButtonForTest()
        assertTrue("the bar leads with the shared panel back control", back is PanelHeaderBackControl)
        assertEquals(ctx.getString(com.aegis.ime.R.string.panel_back), back.text.toString())
        assertTrue("the back control carries the back glyph", back.compoundDrawables[0] != null)
        assertTrue("the back control is the first control in the bar", v.getChildAt(0) === back)
        assertTrue("the back control sits left of the field", back.right <= v.fieldBoxForTest().left)
        assertTrue("the back control keeps a 44dp hit height", back.height >= (44 * ctx.resources.displayMetrics.density).toInt())
        back.performClick()
        assertEquals(1, closed)
        assertEquals("keep", v.text())
    }

    @Test fun the_palette_paints_the_bar_like_the_edit_bar() {
        val v = TranslateBarView(ctx)
        val p = ImePalette.STATIC_DARK
        v.applyPalette(p)
        assertEquals(p.keyboardBg, (v.background as android.graphics.drawable.ColorDrawable).color)
        assertEquals(p.keySurface, bg(v.fieldBoxForTest()))
        assertEquals("the editor paints no second surface inside the box", null, field(v).background)
        assertEquals(p.keyLabel, field(v).currentTextColor)
        assertEquals(p.keyHint, caption(v).currentTextColor)
        assertEquals(null, v.modeButtonForTest().background)
        assertEquals(p.keyLabel, v.modeButtonForTest().currentTextColor)
        assertEquals(p.keyLabel, v.closeButtonForTest().currentTextColor)
    }

    @Test fun the_line_budget_caps_the_field_height() {
        val v = TranslateBarView(ctx)
        val f = field(v)
        v.setFieldLineBudget(1)
        assertEquals(f.lineHeight + f.paddingTop + f.paddingBottom, f.maxHeight)
        v.setFieldLineBudget(99)
        assertEquals(f.lineHeight * TranslateBarView.MAX_FIELD_LINES + f.paddingTop + f.paddingBottom, f.maxHeight)
    }
}
