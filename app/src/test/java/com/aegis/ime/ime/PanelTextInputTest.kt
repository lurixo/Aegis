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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * debug.16 Option A — the in-IME text-capture redirect. The host writes every ImeHost output as
 * `if (panelInput.<op>()) return; <editor>`, so these consume-Boolean contracts ARE the routing guarantee.
 *
 * 🔴 RED LINE: normal typing must be 100% unaffected outside edit mode. That reduces to: when INACTIVE every
 * op returns false (→ the host's editor path runs), and after [end] it returns false AGAIN (the redirect is
 * fully torn down). [inactive_*] and [end_restores_*] are those guards.
 *
 * Robolectric-backed: [backspace] now deletes a whole grapheme cluster via [GraphemeText], which uses
 * ICU's character break iterator, so the buffer's ⌫ removes a ZWJ / flag / keycap emoji cleanly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelTextInputTest {

    @Test fun inactive_consumes_nothing_so_normal_typing_reaches_the_editor() {
        val p = PanelTextInput()
        assertFalse(p.active)
        assertFalse("commit must NOT be consumed when inactive", p.commit("a"))
        assertFalse("backspace must NOT be consumed when inactive", p.backspace())
        assertFalse("replaceBefore must NOT be consumed when inactive", p.replaceBefore(1, "x"))
        assertNull("textBefore null → host queries the real editor", p.textBefore(5))
        assertEquals("", p.text())
    }

    @Test fun active_captures_commit_backspace_and_queries_into_the_buffer() {
        val p = PanelTextInput()
        val seen = ArrayList<String>()
        p.onChange = { seen.add(it) }
        p.begin("ab")
        assertTrue(p.active); assertEquals("ab", p.text())
        assertTrue("commit consumed while active", p.commit("c")); assertEquals("abc", p.text())
        assertTrue("backspace consumed while active", p.backspace()); assertEquals("ab", p.text())
        assertEquals("ab", p.textBefore(5))
        assertTrue("onChange fired", seen.isNotEmpty())
    }

    @Test fun end_restores_normal_routing_zero_regression() {
        val p = PanelTextInput()
        p.begin("x"); assertTrue(p.commit("y")); assertEquals("xy", p.text())
        p.end()
        assertFalse(p.active)
        assertFalse("after end, commit must fall through to the editor", p.commit("z"))
        assertFalse("after end, backspace must fall through", p.backspace())
        assertNull("after end, textBefore is null again", p.textBefore(3))
        assertEquals("", p.text())
    }

    @Test fun backspace_removes_a_whole_grapheme_cluster() {
        // Each of these emoji is a single cluster of 2+ UTF-16 code units; ⌫ must leave "a", never half of it.
        for (emoji in listOf("😀", "🇨🇳", "0️⃣", "❤️", "👨‍👩‍👧‍👦", "👋🏽", "🏳️‍🌈")) {
            val p = PanelTextInput()
            p.begin("a$emoji")
            assertTrue(p.backspace())
            assertEquals("$emoji must delete whole, not half a surrogate", "a", p.text())
        }
        val p = PanelTextInput()
        p.begin("a😀")
        assertTrue(p.backspace()); assertEquals("a", p.text())
        assertTrue(p.backspace()); assertEquals("", p.text())
        assertTrue("backspace on empty buffer still consumed", p.backspace())
    }

    @Test fun begin_resets_the_buffer_so_swipe_up_clears_it() {
        // Chinese IME behavior note.
        val p = PanelTextInput()
        p.begin("abc"); assertEquals("abc", p.text())
        p.begin("") // Chinese IME behavior note.
        assertTrue(p.active); assertEquals("", p.text())
    }

    @Test fun replace_before_edits_the_buffer_tail() {
        val p = PanelTextInput()
        p.begin("1+1")
        assertTrue(p.replaceBefore(3, "1+1=2"))
        assertEquals("1+1=2", p.text())
    }
}
