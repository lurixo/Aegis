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

import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.*
import org.junit.Test

class NineDigitChoiceTest {
    private class Host : ImeHost {
        val commits = ArrayList<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private val words = linkedMapOf("women" to "我们", "wo" to "我", "men" to "们", "en" to "恩", "o" to "哦")
    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
            words.entries.filter { (reading, _) -> composing == if (t9) T9Pinyin.toT9(reading) else reading }
                .map { Cand(it.value, composing.length) }
        override fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int>, context: CharSequence) =
            candidatesCovered(letters, false, cuts, context)
    }

    private fun controller(host: Host, input: String = "96636") = KeyboardController(host, engine).apply {
        switchTextLayoutForTest(true)
        input.forEach { onKey(Key(it.toString())) }
    }

    private fun KeyboardController.digit() = nineLeftColumn().lastOrNull()?.takeIf { it.action == KeyAction.PICK_DIGIT }
    private fun KeyboardController.pick(reading: String) = onKey(nineLeftColumn().first {
        it.action == KeyAction.PICK_READING && it.output == reading
    })

    @Test fun women_shows_nine_then_six_after_locking_wo() {
        val host = Host()
        val c = controller(host)
        assertEquals("9", c.digit()?.label)
        c.pick("wo")
        assertEquals("6", c.digit()?.label)
        c.onKey(c.digit()!!)
        assertEquals("wo'6'en", c.preeditForTest())
        assertEquals("我6恩", c.candidateWords().first())
        assertTrue(host.commits.isEmpty())
        assertEquals("3", c.digit()?.label)
        c.onPickCandidate(0)
        assertEquals(listOf("我6恩"), host.commits)
    }

    @Test fun each_digit_choice_consumes_one_code_without_appending_or_committing() {
        val host = Host()
        val c = controller(host)
        for (digit in "96636") {
            assertEquals(digit.toString(), c.digit()?.output)
            c.onKey(c.digit()!!)
            assertTrue(host.commits.isEmpty())
        }
        assertNull(c.digit())
        assertEquals("96636", c.preeditForTest())
        assertEquals(listOf("96636"), c.candidateWords())
        c.onPickCandidate(0)
        assertEquals(listOf("96636"), host.commits)
    }

    @Test fun remaining_readings_can_be_locked_after_a_literal_digit() {
        val host = Host()
        val c = controller(host)
        c.onKey(c.digit()!!)
        c.pick("o")
        assertEquals("9'o'men", c.preeditForTest())
        assertEquals("6", c.digit()?.label)
        c.pick("men")
        assertEquals("9'o'men", c.preeditForTest())
        assertNull(c.digit())
        assertTrue(host.commits.isEmpty())
    }

    @Test fun backspace_undoes_a_digit_choice_and_restores_the_locked_prefix() {
        val c = controller(Host())
        c.pick("wo")
        val before = c.preeditForTest()
        c.onKey(c.digit()!!)
        c.onKey(Key(action = KeyAction.BACKSPACE))
        assertEquals(before, c.preeditForTest())
        assertEquals("6", c.digit()?.label)
    }

    @Test fun choosing_a_literal_prefix_preserves_remaining_reading_locks_and_can_be_undone() {
        val host = Host()
        val c = controller(host)
        c.onKey(c.digit()!!)
        c.pick("o")
        c.onPickCandidate(c.candidateWords().indexOf("9"))
        assertEquals("9o'men", c.preeditForTest())
        assertEquals("6", c.digit()?.label)
        assertTrue(host.commits.isEmpty())
        c.onKey(Key(action = KeyAction.BACKSPACE))
        assertEquals("9'o'men", c.preeditForTest())
        assertEquals("6", c.digit()?.label)
    }

    @Test fun a_selected_chinese_prefix_remains_pending_when_a_digit_is_chosen() {
        val host = Host()
        val prefixEngine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = emptyList<String>()
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
                if (composing == "96636") listOf(Cand("我", 2)) else emptyList()
        }
        val c = KeyboardController(host, prefixEngine)
        c.switchTextLayoutForTest(true)
        "96636".forEach { c.onKey(Key(it.toString())) }
        c.onPickCandidate(0)
        c.onKey(c.digit()!!)
        assertTrue(c.preeditForTest().startsWith("我6"))
        assertTrue(host.commits.isEmpty())
    }

    @Test fun caret_editing_and_stale_digit_keys_do_not_append_digits_or_submit_text() {
        val host = Host()
        val c = controller(host)
        val stale = c.digit()!!
        c.pick("wo")
        c.onKey(stale)
        assertEquals("wo'men", c.preeditForTest())
        c.onPreeditCaret(3)
        c.onKey(c.digit()!!)
        assertEquals("wo'6'en", c.preeditForTest())
        assertEquals(5, c.preeditModelForTest()!!.rawLength)
        assertTrue(host.commits.isEmpty())
    }

    @Test fun no_digit_choice_is_exposed_after_all_readings_are_locked_or_in_alpha_layout() {
        val c = controller(Host())
        c.pick("wo")
        c.pick("men")
        assertNull(c.digit())
        c.reset()
        c.switchTextLayoutForTest(false)
        "women".forEach { c.onKey(Key(it.toString())) }
        assertNull(c.digit())
    }
}
