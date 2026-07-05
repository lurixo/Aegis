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

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
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
class TranslateBarHostingTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun editBar(v: View): EditBarView? {
        if (v is EditBarView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) editBar(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun input(): InputView = InputView(ctx).apply {
        showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
    }

    private fun layout(iv: InputView) {
        iv.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
    }

    @Test fun the_edit_bar_covers_the_translate_bar_and_hands_it_back() {
        val iv = input()
        iv.showTranslateBar(true)
        iv.setTranslateText("hello")
        layout(iv)
        val bar = iv.translateBarForTest()

        iv.showEditBar(true)
        layout(iv)
        assertEquals(View.GONE, bar.visibility)
        assertTrue(iv.isEditBarShowing())
        assertTrue("the bar stays logically open under the edit bar", iv.isTranslateBarActive())
        assertTrue(editBar(iv)!!.fieldForTest().isFocused)
        assertEquals("only one extra row is ever counted", editBar(iv)!!.height + (44 * density).toInt() + iv.dockHeightSpecForTest()!!.keyboardHeight +
            iv.dockHeightSpecForTest()!!.preeditHeight + iv.dockHeightSpecForTest()!!.bottomExtra + iv.dockHeightSpecForTest()!!.navBottom, iv.measuredHeight)

        iv.showEditBar(false)
        layout(iv)
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals("hello", iv.translateText())
        assertTrue(bar.fieldForTest().isFocused)

        iv.showEditBar(true)
        iv.dismissEditBarForPanelReturn()
        assertEquals(View.VISIBLE, bar.visibility)
        assertTrue(bar.fieldForTest().isFocused)
    }

    @Test fun an_editor_switch_clears_the_text_but_keeps_the_bar_open() {
        val iv = input()
        iv.showTranslateBar(true)
        iv.setTranslateText("stale")
        iv.showEditBar(true)
        iv.clearEditorTransientUiImmediately()
        layout(iv)
        assertEquals("", iv.translateText())
        assertTrue(iv.isTranslateBarActive())
        assertEquals(View.VISIBLE, iv.translateBarForTest().visibility)
        assertFalse(iv.isEditBarShowing())
    }

    @Test fun the_mode_dialog_never_outlives_its_bar() {
        val iv = input()
        iv.showTranslateBar(true)
        layout(iv)
        val bar = iv.translateBarForTest()
        bar.modeButtonForTest().performClick()
        iv.showEditBar(true)
        assertFalse("covering the bar folds the dialog", bar.isModeDialogShowing())

        iv.showEditBar(false)
        bar.modeButtonForTest().performClick()
        assertTrue(bar.isModeDialogShowing())
        iv.clearEditorTransientUiImmediately()
        assertFalse("an editor switch folds the dialog", bar.isModeDialogShowing())
    }

    @Test fun the_bar_follows_the_keyboard_palette() {
        val iv = input()
        iv.applyPalette(ImePalette.STATIC_DARK)
        assertEquals(ImePalette.STATIC_DARK.keyboardBg, (iv.translateBarForTest().background as ColorDrawable).color)
    }
}
