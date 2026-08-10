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

import android.content.Context
import android.view.View
import com.aegis.ime.engine.CandidateEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CopyBarUnderPanelTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private val host = object : ImeHost {
        val committed = StringBuilder()
        override fun commitText(text: CharSequence) { committed.append(text) }
        override fun deleteBackward() {}
        override fun performEnter() {}
    }
    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private class CoveringPanel(c: Context) : View(c), CoversToolbar

    private fun laidOut(): InputView {
        val iv = InputView(ctx)
        KeyboardController(host, engine).attachView(iv)
        return measured(iv)
    }

    private fun measured(iv: InputView): InputView {
        val w = View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        iv.measure(w, h)
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
        return iv
    }

    @Test fun a_panel_opened_over_the_copy_bar_leaves_the_keyboard_where_it_was() {
        val plain = laidOut()
        plain.showPanel(CoveringPanel(ctx))
        val expected = measured(plain).measuredHeight

        val withBar = laidOut()
        withBar.showCopyBar("something that was copied")
        assertEquals(
            "the copy bar alone must not change the height either",
            expected,
            measured(withBar).measuredHeight,
        )

        withBar.showPanel(CoveringPanel(ctx))
        assertEquals(
            "a panel that covers the toolbar must sit at the same height whether the bar " +
                "showing underneath is the candidate bar or the copy bar",
            expected,
            measured(withBar).measuredHeight,
        )
    }

    @Test fun the_copy_bar_steps_aside_for_a_panel_that_covers_the_toolbar() {
        val iv = laidOut()
        iv.showCopyBar("something that was copied")
        assertEquals(View.VISIBLE, iv.copyBarForTest().visibility)

        iv.showPanel(CoveringPanel(ctx))
        assertEquals(
            "the panel already claims the toolbar row; the copy bar must not keep its own",
            View.GONE,
            iv.copyBarForTest().visibility,
        )
        assertTrue(
            "stepping aside is not the same as being dismissed",
            iv.copyBarActiveForTest(),
        )
    }

    @Test fun closing_the_panel_gives_the_copy_bar_back() {
        val iv = laidOut()
        iv.showCopyBar("something that was copied")
        iv.showPanel(CoveringPanel(ctx))
        iv.showPanel(null)

        assertEquals(
            "what was copied is still there, so the bar comes back with the panel gone",
            View.VISIBLE,
            iv.copyBarForTest().visibility,
        )
        assertEquals(View.GONE, iv.candidateBarForTest().visibility)
    }

    @Test fun a_panel_that_leaves_the_toolbar_alone_keeps_the_copy_bar_on_screen() {
        val iv = laidOut()
        iv.showCopyBar("something that was copied")
        iv.showPanel(View(ctx))

        assertEquals(
            "only a panel that takes the toolbar row makes the copy bar step aside",
            View.VISIBLE,
            iv.copyBarForTest().visibility,
        )
    }

    @Test fun opening_the_symbols_panel_commits_nothing() {
        val iv = laidOut()
        iv.showCopyBar("something that was copied")
        iv.showPanel(CoveringPanel(ctx))
        assertEquals("", host.committed.toString())
    }

    @Test fun the_bar_the_panel_covers_is_the_one_on_screen() {
        val iv = laidOut()
        assertEquals(iv.candidateBarForTest(), iv.coveredBarForTest())

        iv.showCopyBar("something that was copied")
        assertEquals(
            "with the copy bar up it is the copy bar the panel slides over, not the candidate bar",
            iv.copyBarForTest(),
            iv.coveredBarForTest(),
        )
        assertNotEquals(iv.candidateBarForTest(), iv.coveredBarForTest())
    }
}
