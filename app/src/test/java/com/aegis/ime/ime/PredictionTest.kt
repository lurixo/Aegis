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
import com.aegis.ime.ui.ASSOCIATIONS_DEFAULT_ON
import com.aegis.ime.user.UserLearning
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

    private fun echoPredictsItselfEngine() = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
            if (composing.isEmpty()) emptyList() else listOf(Cand("echo", composing.length))
        override fun predict(prevWord: String?): List<String> =
            if (prevWord == "echo") listOf("echo") else emptyList()
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

    @Test fun picking_a_prediction_records_the_follow_relation() {
        val h = EditorHost()
        val learning = UserLearning()
        val c = KeyboardController(h, niHaoEngine())
        c.userLearning = learning
        commitNiHao(c)
        c.onPickCandidate(c.candidateWords().indexOf("世界"))
        assertEquals(listOf("世界"), learning.follows("你好").map { it.first })
    }

    @Test fun a_visible_prediction_does_not_learn_after_the_field_becomes_blocked() {
        val h = EditorHost()
        val learning = UserLearning()
        val c = KeyboardController(h, niHaoEngine())
        c.userLearning = learning
        commitNiHao(c)
        c.setLearningBlocked(true)
        c.onPickCandidate(c.candidateWords().indexOf("世界"))
        assertTrue(learning.follows("你好").isEmpty())
    }

    @Test fun picking_a_prediction_retires_previous_candidate_undo() {
        val h = EditorHost()
        val c = KeyboardController(h, echoPredictsItselfEngine())
        "echo".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(c.candidateWords().indexOf("echo"))
        assertEquals("echo", h.text)
        assertEquals(listOf("echo"), c.candidateWords())

        c.onPickCandidate(c.candidateWords().indexOf("echo"))
        assertEquals("echoecho", h.text)
        assertEquals("prediction chaining remains available", listOf("echo"), c.candidateWords())

        c.onKey(Key("", action = KeyAction.BACKSPACE))

        assertEquals("Backspace is a normal editor delete after a prediction pick", "echoech", h.text)
        assertEquals("stale candidate undo must not restore the previous preedit", "", c.preeditForTest())
    }

    @Test fun associations_ship_off_by_default() {
        assertFalse("联想 must ship OFF by default (debug.17)", ASSOCIATIONS_DEFAULT_ON)
        val h = EditorHost()
        val c = KeyboardController(h, niHaoEngine())
        c.setAssociationsEnabled(ASSOCIATIONS_DEFAULT_ON)
        commitNiHao(c)
        assertEquals("你好 still committed", "你好", h.text)
        assertTrue("default-off → no 联想 predictions on the empty buffer", c.candidateWords().isEmpty())
    }

    @Test fun association_toggle_off_hides_predictions() {
        val h = EditorHost()
        val c = KeyboardController(h, niHaoEngine())
        c.setAssociationsEnabled(false)
        commitNiHao(c)
        assertEquals("你好 still committed", "你好", h.text)
        assertTrue("联想 off → no predictions", c.candidateWords().isEmpty())
    }

    @Test fun association_toggle_off_clears_visible_predictions_immediately() {
        val h = EditorHost()
        val c = KeyboardController(h, niHaoEngine())
        commitNiHao(c)
        assertEquals(listOf("世界", "啊"), c.candidateWords())

        c.setAssociationsEnabled(false)

        assertTrue("turning associations off must clear already visible predictions", c.candidateWords().isEmpty())
        c.onKey(Key("", action = KeyAction.SPACE))
        assertEquals("space remains a literal editor space", "你好 ", h.text)
        assertTrue("space after hot-off must not regenerate predictions", c.candidateWords().isEmpty())
    }

    @Test fun association_toggle_off_stays_empty_after_candidate_commit_space_punctuation_and_reset() {
        val h = EditorHost()
        val c = KeyboardController(h, niHaoEngine())
        c.setAssociationsEnabled(false)
        commitNiHao(c)
        assertEquals("你好 still committed", "你好", h.text)
        assertTrue("off after commit -> no prediction", c.candidateWords().isEmpty())

        c.onKey(Key("", action = KeyAction.SPACE))
        assertEquals("space commits normally", "你好 ", h.text)
        assertTrue("space must not surface predictions while off", c.candidateWords().isEmpty())

        c.onKey(Key("，", output = "，", direct = true))
        assertEquals("punctuation commits normally", "你好 ，", h.text)
        assertTrue("punctuation must not surface predictions while off", c.candidateWords().isEmpty())

        c.reset()
        assertTrue("reset must not restore predictions while off", c.candidateWords().isEmpty())
    }

    @Test fun predictions_hidden_when_personalization_is_blocked() {
        val h = EditorHost()
        val c = KeyboardController(h, niHaoEngine())
        c.setLearningBlocked(true)
        commitNiHao(c)
        assertTrue("no personalized predictions in a secure field", c.candidateWords().isEmpty())
    }

    @Test fun reentry_dismisses_a_lingering_prediction() {
        val h = EditorHost()
        val c = KeyboardController(h, niHaoEngine())
        commitNiHao(c)
        assertEquals(listOf("世界", "啊"), c.candidateWords())
        c.onKey(Key("", action = KeyAction.CLEAR_COMPOSING))
        assertTrue("重输 clears the prediction and it does not regenerate", c.candidateWords().isEmpty())
    }

    @Test fun backspace_after_a_committed_candidate_deletes_text_without_restoring_predictions() {
        val h = EditorHost()
        val c = KeyboardController(h, niHaoEngine())
        commitNiHao(c)
        assertEquals(listOf("世界", "啊"), c.candidateWords())
        c.onKey(Key("", action = KeyAction.BACKSPACE))
        assertEquals("Backspace deletes one committed editor character", "你", h.text)
        assertEquals("full editor commits must not restore preedit", "", c.preeditForTest())
        assertTrue("stale predictions must not return after normal Backspace", c.candidateWords().isEmpty())
    }

    @Test fun calculator_takes_priority_over_prediction() {
        val h = EditorHost()
        val c = KeyboardController(h, alwaysPredictEngine())
        "2+2".forEach { c.onKey(digit(it.toString())) }
        assertEquals("the calculator result wins the empty-buffer slot", listOf("=4"), c.candidateWords())
        assertFalse("the prediction must not appear while an expression is present", "预测" in c.candidateWords())
    }
}
