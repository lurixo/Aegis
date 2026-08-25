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

import android.app.Activity
import android.view.View
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeToastDrawTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    private fun keyboard(): InputView = InputView(ctx).apply {
        showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
        showCandidates(listOf("你"), "ni", emptyList())
    }

    private fun attach(view: View) = Robolectric.buildActivity(Activity::class.java).setup().also {
        it.get().setContentView(view)
    }

    private fun layoutAtMost(iv: InputView, widthPx: Int, heightPx: Int) {
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
    }

    @Test fun the_notice_is_really_drawn_inside_the_keyboard_surface() {
        val iv = keyboard()
        attach(iv)
        layoutAtMost(iv, dp(411), dp(700))
        assertNull("nothing is drawn before a notice is asked for", iv.toastBoundsForTest())

        iv.showToast("已复制")
        val r = iv.toastBoundsForTest()
        assertNotNull("the notice must reach the canvas, not just the model", r)
        r!!
        assertTrue("wider than nothing", r.width() > 0f)
        assertTrue("taller than nothing", r.height() > 0f)
        assertTrue("left of the keyboard surface", r.left >= iv.dockSurfaceLeftPx())
        assertTrue("right of the keyboard surface", r.right <= iv.dockSurfaceRightPx())
        assertTrue("below the keyboard top", r.top >= iv.keyboardVisualTopPx())
        assertTrue("above the keyboard bottom", r.bottom <= iv.keyboardVisualBottomPx())
    }

    @Test fun the_notice_is_centred_on_the_keyboard_not_on_the_window() {
        val iv = keyboard()
        attach(iv)
        layoutAtMost(iv, dp(411), dp(700))
        iv.showToast("已粘贴")
        val r = iv.toastBoundsForTest()!!
        val surfaceCentre = (iv.dockSurfaceLeftPx() + iv.dockSurfaceRightPx()) / 2f
        assertEquals("centred on the keyboard surface", surfaceCentre, r.centerX(), 1f)
    }

    @Test
    @Config(qualifiers = "w853dp-h420dp-land-hdpi")
    fun a_docked_landscape_keyboard_keeps_the_notice_over_the_keys() {
        val iv = keyboard()
        attach(iv)
        layoutAtMost(iv, dp(853), dp(420))
        iv.showToast("已剪切")
        val r = iv.toastBoundsForTest()!!
        val left = iv.dockSurfaceLeftPx()
        val right = iv.dockSurfaceRightPx()
        assertTrue("precondition: the keyboard is docked away from the window edge", left > 0)
        assertTrue("the notice never spills onto the host application", r.left >= left)
        assertTrue(r.right <= right)
        assertEquals("centred on the dock, not on the window", (left + right) / 2f, r.centerX(), 1f)
    }

    @Test fun the_notice_lands_on_the_third_of_four_key_rows() {
        val iv = keyboard()
        attach(iv)
        layoutAtMost(iv, dp(411), dp(700))
        iv.showToast("已全选")
        val r = iv.toastBoundsForTest()!!
        val top = iv.keyboardVisualTopPx().toFloat()
        val height = (iv.keyboardVisualBottomPx() - iv.keyboardVisualTopPx()).toFloat()
        assertTrue("below the halfway line", r.centerY() > top + height * 0.5f)
        assertTrue("above the three-quarter line", r.centerY() < top + height * 0.75f)
    }

    @Test fun leaving_the_editor_takes_the_notice_with_it() {
        val iv = keyboard()
        attach(iv)
        layoutAtMost(iv, dp(411), dp(700))
        iv.showToast("已复制")
        assertNotNull(iv.toastBoundsForTest())

        iv.clearEditorTransientUiImmediately()
        layoutAtMost(iv, dp(411), dp(700))
        assertNull("a notice must not follow the user into the next editor", iv.toastBoundsForTest())
        assertNull(iv.toastTextForTest())
    }

    @Test fun a_notice_with_nothing_to_anchor_to_reports_no_bounds() {
        val iv = keyboard()
        iv.showToast("已复制")
        assertNull("nothing was drawn, so no stale rectangle may come back", iv.toastBoundsForTest())
    }
}
