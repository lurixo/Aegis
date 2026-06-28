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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionTest {

    private class EditorHost : ImeHost {
        val sb = StringBuilder()
        override fun commitText(text: CharSequence) { sb.append(text) }
        override fun deleteBackward() { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = sb.takeLast(n)
        override fun replaceBeforeCursor(length: Int, text: CharSequence) {
            repeat(length) { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
            sb.append(text)
        }
        val text get() = sb.toString()
    }

    private fun niHaoEngine() = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
            if (composing.isEmpty()) emptyList() else listOf(Cand("你好", composing.length))
        override fun predict(prevWord: String?): List<String> =
            if (prevWord == "你好") listOf("世界", "啊") else emptyList()
    }

    private fun alwaysPredictEngine() = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean) = emptyList<String>()
        override fun predict(prevWord: String?): List<String> = listOf("预测")
    }

    private fun out(s: String) = Key(s, output = s)
    private fun digit(s: String) = Key(s, output = s, direct = true)

    private fun commitNiHao(c: KeyboardController) {
        "nihao".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(c.candidateWords().indexOf("你好"))
    }

    @Test fun prediction_appears_on_empty_buffer_after_a_commit() {
        val h = EditorHost()
        val c = KeyboardController(h, niHaoEngine())
        commitNiHao(c)
        assertEquals("你好 committed", "你好", h.text)
        assertEquals("predictions for 你好 fill the empty buffer", listOf("世界", "啊"), c.candidateWords())
    }

    @Test fun picking_a_prediction_commits_it_and_chains_last_word() {
        val h = EditorHost()
        val c = KeyboardController(h, niHaoEngine())
        commitNiHao(c)
        c.onPickCandidate(c.candidateWords().indexOf("世界"))
        assertEquals("the prediction is committed after 你好", "你好世界", h.text)
        assertTrue("no prediction after 世界", c.candidateWords().isEmpty())
    }

    @Test fun association_toggle_off_hides_predictions() {
        val h = EditorHost()
        val c = KeyboardController(h, niHaoEngine())
        c.setAssociationsEnabled(false)
        commitNiHao(c)
        assertEquals("你好 still committed", "你好", h.text)
        assertTrue("联想 off → no predictions", c.candidateWords().isEmpty())
    }

    @Test fun predictions_hidden_when_personalization_is_blocked() {
        val h = EditorHost()
        val c = KeyboardController(h, niHaoEngine())
        c.setLearningBlocked(true)
        commitNiHao(c)
        assertTrue("no personalized predictions in a secure field", c.candidateWords().isEmpty())
    }

    @Test fun calculator_takes_priority_over_prediction() {
        val h = EditorHost()
        val c = KeyboardController(h, alwaysPredictEngine())
        "2+2".forEach { c.onKey(digit(it.toString())) }
        assertEquals("the calculator result wins the empty-buffer slot", listOf("=4"), c.candidateWords())
        assertFalse("the prediction must not appear while an expression is present", "预测" in c.candidateWords())
    }
}
