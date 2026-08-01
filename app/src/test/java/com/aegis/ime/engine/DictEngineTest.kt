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
import kotlin.math.ln

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
    fun exactCandidatesContinueInThirtyItemPagesUntilTheReferenceOrderIsExhausted() {
        val rows = (0 until 75).map { index -> EngineFixture.Row("ce", "候选$index", 10_000 - index) }
        val engine = DictEngine(EngineFixture.build(rows), null, null)
        val actual = ArrayList<Cand>()
        val pageSizes = ArrayList<Int>()
        var page = engine.candidatesCoveredPage("ce", t9 = false, inputEpoch = 31L)
        while (true) {
            pageSizes.add(page.items.size)
            actual.addAll(page.items)
            val continuation = page.continuation ?: break
            page = engine.continuePage(continuation, inputEpoch = 31L)
        }

        assertEquals(listOf(30, 30, 15), pageSizes)
        assertEquals(rows.map { Cand(it.word, 2) }, actual)
    }

    @Test
    fun lockedAtomicPagesExhaustEveryPathInTheSameGlobalOrder() {
        val firstWords = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛")
        val secondWords = listOf("天", "地", "玄", "黄", "宇", "宙", "洪", "荒")
        val first = firstWords.mapIndexed { index, word ->
            EngineFixture.Row("a", word, 1 shl (8 - index))
        }
        val second = secondWords.mapIndexed { index, word ->
            EngineFixture.Row("ba", word, pow(3, 8 - index))
        }
        val engine = DictEngine(EngineFixture.build(first + second), null, null)
        val actual = ArrayList<Cand>()
        val pageSizes = ArrayList<Int>()
        var page = engine.candidatesForLockedReadingCoveredPage(
            "aba",
            inputEpoch = 32L,
            cuts = setOf(1),
        )
        while (true) {
            pageSizes.add(page.items.size)
            actual.addAll(page.items)
            val continuation = page.continuation ?: break
            page = engine.continuePage(continuation, inputEpoch = 32L)
        }
        val sentences = first.flatMap { left ->
            second.map { right -> Triple(left.word + right.word, left.freq.toLong() * right.freq, 3) }
        }.sortedByDescending { it.second }
        val reference = sentences.map { Cand(it.first, it.third) } + first.map { Cand(it.word, 1) }

        assertEquals(listOf(30, 30, 12), pageSizes)
        assertEquals(reference, actual)
    }

    @Test
    fun predictionsContinuePastTheFormerEightItemWindow() {
        val model = UserModel { 1_000L }
        repeat(75) { index -> model.record("前", "预测$index", 1_000L) }
        val engine = DictEngine(null, null, null, model)
        val reference = engine.predict("前")
        val actual = ArrayList<String>()
        val pageSizes = ArrayList<Int>()
        var page = engine.predictPage("前", inputEpoch = 33L)
        while (true) {
            pageSizes.add(page.items.size)
            actual.addAll(page.items)
            val continuation = page.continuation ?: break
            page = engine.continuePage(continuation, inputEpoch = 33L)
        }

        assertEquals(75, reference.size)
        assertEquals(listOf(30, 30, 15), pageSizes)
        assertEquals(reference, actual)
    }

    @Test
    fun prefixPagesKeepOneGlobalRankingWhenALateDictionaryWordIsLearned() {
        val rows = (0 until 75).map { index ->
            EngineFixture.Row("ce${index.toString(36).padStart(2, '0')}", "前缀候选$index", 5_000 - index * 40)
        }
        val model = UserModel { 1_000L }
        repeat(120) { model.record(null, rows.last().word, 1_000L) }
        val engine = DictEngine(EngineFixture.build(rows), null, null, model)
        val reference = rows.sortedByDescending { row -> ln(row.freq.toDouble()) + model.wordBoost(row.word) }
        val actual = ArrayList<Cand>()
        val pageSizes = ArrayList<Int>()
        var page = engine.candidatesCoveredPage("ce", t9 = false, inputEpoch = 34L)
        while (true) {
            pageSizes.add(page.items.size)
            actual.addAll(page.items)
            val continuation = page.continuation ?: break
            page = engine.continuePage(continuation, inputEpoch = 34L)
        }

        assertEquals(listOf(30, 30, 15), pageSizes)
        assertEquals(reference.map { Cand(it.word, 2) }, actual)
        assertEquals(rows.last().word, actual.first().word)
    }

    private fun pow(base: Int, exponent: Int): Int {
        var value = 1
        repeat(exponent) { value *= base }
        return value
    }
}
