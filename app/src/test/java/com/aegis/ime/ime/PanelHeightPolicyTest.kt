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

import android.view.View
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelHeightPolicyTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private val host = object : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }
    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun laidOut(switchToNine: Boolean): InputView {
        val iv = InputView(ctx)
        val c = KeyboardController(host, engine)
        c.attachView(iv)
        if (switchToNine) c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        val w = View.MeasureSpec.makeMeasureSpec((1080 * density).toInt() / 3, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        iv.measure(w, h)
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
        return iv
    }

    @Test fun opening_a_panel_matches_the_keyboard_height_not_a_fixed_slot() {
        val iv = laidOut(switchToNine = true)
        val kb = iv.keyboardHeightPx()
        assertTrue("keyboard laid out with a real height", kb > 0)
        iv.showPanel(View(ctx))
        assertEquals("panel slot tracks the keyboard footprint (no expand/shrink)", kb, iv.panelHeightPx())
    }

    @Test fun the_qwerty_and_nine_key_each_get_a_matching_panel_height() {
        val nine = laidOut(switchToNine = true)
        val qwerty = laidOut(switchToNine = false)
        val hNine = nine.keyboardHeightPx()
        val hQwerty = qwerty.keyboardHeightPx()
        assertTrue("both keyboards measured", hNine > 0 && hQwerty > 0)

        nine.showPanel(View(ctx))
        qwerty.showPanel(View(ctx))
        assertEquals("9-key panel matches the 9-key", hNine, nine.panelHeightPx())
        assertEquals("26-key panel matches the 26-key", hQwerty, qwerty.panelHeightPx())
    }
}
