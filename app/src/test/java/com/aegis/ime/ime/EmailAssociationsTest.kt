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
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class EmailAssociationsTest {
    private class Host(initial: String = "") : ImeHost {
        val text = StringBuilder(initial)
        var selected = false
        override fun commitText(text: CharSequence) { this.text.append(text) }
        override fun deleteBackward() { if (text.isNotEmpty()) text.setLength(text.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = text.takeLast(n)
        override fun hasSelection() = selected
    }

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean) = listOf("你")
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
            listOf(Cand("你", composing.length))
        override fun predict(prevWord: String?) = if (prevWord == "你") listOf("好") else emptyList()
        override fun englishCompletions(typed: String) = listOf("hello").filter { it.startsWith(typed) }
    }
    private fun key(text: String) = Key(text, output = text, direct = true)
    private fun action(action: KeyAction) = Key("", action = action)
    private fun controller(host: Host, layout: String, domains: EmailDomains = EmailDomains()): KeyboardController =
        KeyboardController(host, engine, emailDomains = domains).apply {
            switchTextLayoutForTest(layout == "nine")
            if (layout == "english") onKey(action(KeyAction.TOGGLE_LANG))
            setEmailAssociationsEnabled(true)
        }

    @Test fun all_three_layouts_append_only_the_selected_suffix() {
        for (layout in listOf("alpha", "nine", "english")) {
            val h = Host("name")
            val c = controller(h, layout)
            c.onKey(key("@"))
            assertEquals("name@", h.text.toString())
            assertEquals(EmailDomains().suggestions(), c.candidateWords())
            c.onPickCandidate(c.candidateWords().indexOf("gmail.com"))
            assertEquals("name@gmail.com", h.text.toString())
            assertTrue(c.candidateWords().isEmpty())
        }
    }

    @Test fun missing_prefix_whitespace_repeated_at_and_non_trailing_at_do_not_suggest() {
        for (layout in listOf("alpha", "nine", "english")) {
            for (text in listOf("@", " @", "x @", "x\n@", "x@@", "name@x", "name＠")) {
                val c = controller(Host(text), layout)
                assertTrue("$layout $text", c.candidateWords().isEmpty())
            }
            assertTrue(controller(Host("名字@"), layout).candidateWords().contains("qq.com"))
        }
    }

    @Test fun email_switch_is_independent_from_chinese_and_english_associations() {
        for (layout in listOf("alpha", "nine", "english")) {
            val h = Host("name")
            val c = controller(h, layout)
            c.setCnAssociationsEnabled(false)
            c.setEnAssociationsEnabled(false)
            c.onKey(key("@"))
            assertTrue(c.candidateWords().contains("qq.com"))
            c.setEmailAssociationsEnabled(false)
            assertTrue(c.candidateWords().isEmpty())
            c.setCnAssociationsEnabled(true)
            c.setEnAssociationsEnabled(true)
            assertTrue(c.candidateWords().isEmpty())
            c.setEmailAssociationsEnabled(true)
            assertTrue(c.candidateWords().contains("qq.com"))
        }
    }

    @Test fun external_text_changes_trigger_and_remove_email_candidates() {
        for (layout in listOf("alpha", "nine", "english")) {
            val h = Host()
            val c = controller(h, layout)
            h.commitText("name@")
            c.onEditorContextChanged()
            assertTrue(c.candidateWords().contains("qq.com"))
            h.commitText(" ")
            c.onEditorContextChanged()
            assertTrue(c.candidateWords().isEmpty())
            c.setEmailAssociationsEnabled(false)
            h.commitText("other@")
            c.onEditorContextChanged()
            assertTrue(c.candidateWords().isEmpty())
        }
    }

    @Test fun moving_the_cursor_or_selecting_text_rejects_stale_email_candidates() {
        for (selection in listOf(false, true)) {
            val h = Host("first@")
            val domains = EmailDomains()
            val c = controller(h, "alpha", domains)
            val index = c.candidateWords().indexOf("gmail.com")
            if (selection) h.selected = true else { h.text.setLength(0); h.text.append("second@") }
            val before = h.text.toString()
            c.onPickCandidate(index)
            assertEquals(before, h.text.toString())
            assertEquals("qq.com", domains.suggestions().first())
        }
    }

    @Test fun selecting_a_domain_reorders_future_suggestions_across_languages() {
        val domains = EmailDomains()
        val cn = controller(Host("name@"), "nine", domains)
        cn.onPickCandidate(cn.candidateWords().indexOf("gmail.com"))
        val en = controller(Host("other@"), "english", domains)
        assertEquals("gmail.com", en.candidateWords().first())
        en.onPickCandidate(en.candidateWords().indexOf("163.com"))
        val again = controller(Host("third@"), "alpha", domains)
        again.onPickCandidate(again.candidateWords().indexOf("163.com"))
        assertEquals(listOf("163.com", "gmail.com", "qq.com"), domains.suggestions().take(3))
    }

    @Test fun blocked_fields_hide_email_candidates_and_do_not_learn_an_old_selection() {
        val h = Host("name@")
        val domains = EmailDomains()
        val c = controller(h, "alpha", domains)
        val index = c.candidateWords().indexOf("gmail.com")
        c.setLearningBlocked(true)
        c.onPickCandidate(index)
        assertEquals("name@", h.text.toString())
        assertTrue(c.candidateWords().isEmpty())
        assertEquals("qq.com", domains.suggestions().first())
    }

    @Test fun direct_at_flushes_preedit_before_email_completion() {
        for (layout in listOf("alpha", "nine", "english")) {
            val h = Host()
            val c = controller(h, layout)
            val typed = when (layout) { "nine" -> "64"; "english" -> "he"; else -> "ni" }
            typed.forEach { c.onKey(Key(it.toString())) }
            c.onKey(key("@"))
            assertTrue(h.text.toString().endsWith("@"))
            assertTrue(h.text.length > 1)
            if (layout != "nine") assertEquals(typed + "@", h.text.toString())
            assertFalse(c.hasComposingToClear())
            assertTrue(c.candidateWords().contains("qq.com"))
        }
    }

    @Test fun chinese_predictions_and_english_completions_keep_separate_switches() {
        val h = Host()
        val c = controller(h, "alpha")
        c.setEmailAssociationsEnabled(false)
        c.setEnAssociationsEnabled(false)
        "ni".forEach { c.onKey(Key(it.toString())) }
        c.onPickCandidate(0)
        assertEquals(listOf("好"), c.candidateWords())
        c.setCnAssociationsEnabled(false)
        assertTrue(c.candidateWords().isEmpty())
        c.onKey(action(KeyAction.TOGGLE_LANG))
        c.setEnAssociationsEnabled(true)
        "he".forEach { c.onKey(Key(it.toString())) }
        assertEquals(listOf("he", "hello"), c.candidateWords())
        c.setCnAssociationsEnabled(true)
        assertEquals("he", c.englishWordForTest())
    }

    @Test fun stale_email_candidate_is_not_committed_while_a_new_word_is_decoding() {
        val worker = ArrayDeque<Runnable>()
        val main = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { worker.add(it) }, Executor { main.add(it) })
        fun drain() {
            while (worker.isNotEmpty() || main.isNotEmpty()) {
                while (worker.isNotEmpty()) worker.removeFirst().run()
                while (main.isNotEmpty()) main.removeFirst().run()
            }
        }
        val h = Host("name@")
        val c = KeyboardController(h, engine, lane)
        c.setEmailAssociationsEnabled(true)
        drain()
        val index = c.candidateWords().indexOf("gmail.com")
        c.onKey(Key("n"))
        c.onPickCandidate(index)
        assertEquals("name@", h.text.toString())
        assertEquals("n", c.rawComposingForTest())
        drain()
        assertFalse(c.candidateWords().contains("gmail.com"))
    }

    @Test fun pending_decode_cannot_restore_email_candidates_after_hot_off() {
        val worker = ArrayDeque<Runnable>()
        val main = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { worker.add(it) }, Executor { main.add(it) })
        val h = Host("name@")
        val c = KeyboardController(h, engine, lane)
        c.setEmailAssociationsEnabled(true)
        c.setEmailAssociationsEnabled(false)
        while (worker.isNotEmpty() || main.isNotEmpty()) {
            while (worker.isNotEmpty()) worker.removeFirst().run()
            while (main.isNotEmpty()) main.removeFirst().run()
        }
        assertTrue(c.candidateWords().isEmpty())
    }
}
