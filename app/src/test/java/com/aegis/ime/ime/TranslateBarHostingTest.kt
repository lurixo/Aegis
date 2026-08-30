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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
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

    private fun topInParent(v: View, root: View): Int {
        var y = 0
        var cur: View? = v
        while (cur != null && cur !== root) { y += cur.top; cur = cur.parent as? View }
        return y
    }

    private fun leftInParent(v: View, root: View): Int {
        var x = 0
        var cur: View? = v
        while (cur != null && cur !== root) { x += cur.left; cur = cur.parent as? View }
        return x
    }

    private fun press(iv: InputView, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(0L, if (action == MotionEvent.ACTION_UP) 16L else 0L, action, x, y, 0)
        try {
            iv.dispatchTouchEvent(e)
        } finally {
            e.recycle()
        }
    }

    @Test fun the_translate_bar_sits_directly_above_the_candidate_bar_and_adds_one_bar_height() {
        val iv = input()
        layout(iv)
        val closedHeight = iv.measuredHeight
        iv.showTranslateBar(true)
        layout(iv)
        val bar = iv.translateBarForTest()
        val candidates = iv.candidateBarForTest()
        assertEquals(View.VISIBLE, bar.visibility)
        assertTrue(iv.isTranslateBarShowing())
        assertEquals("the bar closes onto the candidate bar", topInParent(candidates, iv), topInParent(bar, iv) + bar.height)
        assertEquals(bar.fieldBoxForTest().height + 2 * (ImeShapes.toolbarCapsuleMarginDp * density).toInt(), bar.height)
        assertEquals(closedHeight + bar.height, iv.measuredHeight)
        assertTrue("the field owns focus while the bar is up", bar.fieldForTest().isFocused)
        assertFalse("the persistent bar is not a back-key overlay", iv.hasOverlay())

        iv.showTranslateBar(false)
        layout(iv)
        assertEquals(View.GONE, bar.visibility)
        assertEquals(closedHeight, iv.measuredHeight)
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

    @Test fun the_capsule_toggles_its_dialog_and_only_other_in_keyboard_taps_collapse_it() {
        val iv = input()
        iv.showTranslateBar(true)
        layout(iv)
        val bar = iv.translateBarForTest()
        val btn = bar.modeButtonForTest()
        btn.performClick()
        assertTrue(bar.isModeDialogShowing())
        assertTrue("the open dialog is a back-key overlay", iv.hasOverlay())

        val bx = leftInParent(btn, iv) + btn.width / 2f
        val by = topInParent(btn, iv) + btn.height / 2f
        press(iv, MotionEvent.ACTION_DOWN, bx, by)
        assertTrue("a press landing on the capsule leaves the toggle to its click", bar.isModeDialogShowing())
        press(iv, MotionEvent.ACTION_UP, bx, by)
        btn.performClick()
        assertFalse("the capsule click completes the toggle without reopening", bar.isModeDialogShowing())

        btn.performClick()
        assertTrue(bar.isModeDialogShowing())
        press(iv, MotionEvent.ACTION_DOWN, iv.width / 2f, iv.height - 5f)
        assertFalse("a keyboard tap away from the capsule collapses the dialog", bar.isModeDialogShowing())
        assertFalse(iv.hasOverlay())
    }

    @Test fun the_back_key_closes_the_mode_dialog_before_anything_else() {
        val iv = input()
        iv.showTranslateBar(true)
        layout(iv)
        val bar = iv.translateBarForTest()
        bar.modeButtonForTest().performClick()
        assertTrue(bar.isModeDialogShowing())
        assertTrue(iv.closeTopOverlay())
        assertFalse(bar.isModeDialogShowing())
        assertFalse(iv.hasOverlay())
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
