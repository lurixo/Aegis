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

package com.aegis.ime.engine

import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictEngineTest {

    @Test
    fun empty_engine_reports_no_chinese_support() {
        assertFalse(DictEngine(null, null, null).supportsChinese)
    }

    @Test
    fun empty_engine_returns_no_candidates_and_never_throws() {
        val engine: CandidateEngine = DictEngine(null, null, null)
        assertEquals(emptyList<Cand>(), engine.candidatesCovered("nihao", false))
        assertEquals(emptyList<Cand>(), engine.candidatesCovered("236", true))
        assertEquals(emptyList<Cand>(), engine.candidatesForLockedReadingCovered("nihao"))
        assertEquals(emptyList<String>(), engine.candidates("nihao", false))
    }

    @Test
    fun a_language_model_alone_does_not_unlock_chinese() {
        val lm = EngineFixture.buildLm(mapOf('你'.code to 10L, '好'.code to 8L), emptyMap())
        val engine: CandidateEngine = DictEngine(null, null, lm)
        assertFalse(engine.supportsChinese)
        assertEquals(emptyList<Cand>(), engine.candidatesForLockedReadingCovered("nihao"))
        assertTrue(engine.candidatesCovered("ni", false).isEmpty())
    }

    @Test
    fun a_pinyin_dictionary_unlocks_chinese_support() {
        val dict = EngineFixture.build(listOf(EngineFixture.Row("ni", "你", 900)))
        assertTrue(DictEngine(dict, null, null).supportsChinese)
    }

    @Test
    fun a_t9_dictionary_unlocks_chinese_support() {
        val dict = EngineFixture.build(listOf(EngineFixture.Row("ni", "你", 900)))
        assertTrue(DictEngine(null, dict, null).supportsChinese)
    }

    @Test
    fun learnedWordsRefreshThroughBothDecoders() {
        val reading = "zici"
        val target = "自词"
        val rows = listOf(
            EngineFixture.Row(reading, "常词", 5_000),
            EngineFixture.Row(reading, target, 10),
        )
        val letters = EngineFixture.build(rows)
        val digits = EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) })
        val learning = UserLearning { 1_000L }
        val engine = DictEngine(letters, digits, null, userLearning = learning)

        assertEquals("常词", engine.candidates(reading, false).first())
        assertEquals("常词", engine.candidates(T9Pinyin.toT9(reading), true).first())
        repeat(3) {
            learning.observeCommit(null, "自", "zi", 1_000L)
            learning.observeCommit("自", "词", "ci", 1_000L)
            learning.observeBreak()
        }
        assertEquals(target, engine.candidates(reading, false).first())
        assertEquals(target, engine.candidates(T9Pinyin.toT9(reading), true).first())
    }

    @Test
    fun learnedPredictionsPrecedeModelPredictionsAndStayDeduplicated() {
        val learning = UserLearning { 1_000L }.apply {
            repeat(3) { observeCommit("前", "甲", "", 1_000L) }
            repeat(2) { observeCommit("前", "乙", "", 1_000L) }
        }
        val model = UserModel { 1_000L }.apply {
            record("前", "乙", 1_000L)
            record("前", "丙", 1_000L)
        }
        val engine = DictEngine(null, null, null, model, userLearning = learning)

        assertEquals(listOf("甲", "乙", "丙"), engine.predict("前"))
    }

    @Test
    fun an_exact_entry_of_the_installed_dictionary_is_a_dictionary_word() {
        val rows = listOf(EngineFixture.Row("ciku", "词库", 850), EngineFixture.Row("ci", "词", 800))
        val engine: CandidateEngine = DictEngine(EngineFixture.build(rows), null, null)
        assertTrue(engine.isDictionaryWord("词库", "ciku"))
        assertTrue(engine.isDictionaryWord("词", "ci"))
    }

    @Test
    fun a_word_missing_from_the_dictionary_is_not_a_dictionary_word() {
        val engine: CandidateEngine = DictEngine(EngineFixture.build(listOf(EngineFixture.Row("ciku", "词库", 850))), null, null)
        assertFalse(engine.isDictionaryWord("此库", "ciku"))
        assertFalse(engine.isDictionaryWord("词库好", "cikuhao"))
    }

    @Test
    fun a_dictionary_word_under_another_reading_is_not_an_exact_entry() {
        val engine: CandidateEngine = DictEngine(EngineFixture.build(listOf(EngineFixture.Row("ciku", "词库", 850))), null, null)
        assertFalse(engine.isDictionaryWord("词库", "cigu"))
        assertFalse(engine.isDictionaryWord("词库", "ci'ku"))
        assertFalse(engine.isDictionaryWord("词库", ""))
    }

    @Test
    fun without_an_installed_dictionary_nothing_is_a_dictionary_word() {
        assertFalse(DictEngine(null, null, null).isDictionaryWord("词库", "ciku"))
        assertFalse(object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }.isDictionaryWord("词库", "ciku"))
    }

    @Test
    fun nine_key_learning_asks_the_full_pinyin_dictionary_by_spelled_reading() {
        val rows = listOf(EngineFixture.Row("ciku", "词库", 850))
        val digits = EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) })
        val engine: CandidateEngine = DictEngine(EngineFixture.build(rows), digits, null)
        assertTrue(engine.isDictionaryWord("词库", "ciku"))
        assertFalse(engine.isDictionaryWord("词库", T9Pinyin.toT9("ciku")))
        assertFalse(DictEngine(null, digits, null).isDictionaryWord("词库", "ciku"))
    }
}
