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

import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.PinyinSyllables
import org.junit.Assert.*
import org.junit.Test

class DictEngineFuzzyMatrixTest {
    private val cases = listOf(
        Triple("zh", "zhang", "zang"), Triple("ch", "chang", "cang"),
        Triple("sh", "shang", "sang"), Triple("n_l", "nan", "lan"),
        Triple("f_h", "han", "fan"), Triple("l_r", "ran", "lan"),
        Triple("k_g", "kan", "gan"), Triple("ang", "gang", "gan"),
        Triple("eng", "feng", "fen"), Triple("ing", "xing", "xin"),
        Triple("iang", "xiang", "xian"), Triple("uang", "huang", "huan"),
        Triple("un_ong", "dun", "dong"), Triple("on_ong", "dong", "don"),
        Triple("un_iong", "jun", "jiong"), Triple("eng_ong", "neng", "nong"),
        Triple("an_ai", "ban", "bai"), Triple("hui_fei", "hui", "fei"),
        Triple("hu_fu", "hu", "fu"), Triple("huang_wang", "huang", "wang"),
    )


    private fun engine(reading: String, word: String): DictEngine = DictEngine(
        EngineFixture.build(listOf(EngineFixture.Row(reading, word, 900))),
        EngineFixture.build(listOf(EngineFixture.Row(T9Pinyin.toT9(reading), word, 900))),
        null,
    )

    @Test fun every_rule_reaches_dictionary_candidates_in_both_directions_and_layouts() {
        for ((rule, a, b) in cases) for ((source, target) in listOf(a to b, b to a)) {
            if (target !in PinyinSyllables.ALL) continue
            val e = engine(target, "字")
            for (nine in listOf(false, true)) {
                val input = if (nine) T9Pinyin.toT9(source) else source
                e.setFuzzyRules(emptySet())
                val disabled = e.candidates(input, nine)
                e.setFuzzyRules(setOf(rule))
                assertTrue("$rule $source -> $target nine=$nine", "字" in e.candidates(input, nine))
                e.setFuzzyRules(emptySet())
                assertEquals("hot toggle restores candidates", disabled, e.candidates(input, nine))
            }
        }
    }

    @Test fun combined_rules_reach_full_words_and_preserve_exact_priority() {
        for ((source, target, rules) in listOf(
            Triple("zhang", "zan", setOf("zh", "ang")),
            Triple("nanning", "lanling", setOf("n_l")),
            Triple("huifei", "feihui", setOf("hui_fei")),
        )) for (nine in listOf(false, true)) {
            val rows = listOf(EngineFixture.Row(source, "原词", 900), EngineFixture.Row(target, "模糊", 900))
            val e = DictEngine(EngineFixture.build(rows),
                EngineFixture.build(rows.map { it.copy(key = T9Pinyin.toT9(it.key)) }), null, fuzzyRules = rules)
            val words = e.candidates(if (nine) T9Pinyin.toT9(source) else source, nine)
            assertEquals("$source nine=$nine", "原词", words.first())
            assertTrue("$target nine=$nine", "模糊" in words)
        }
    }
    @Test fun fuzzy_candidates_carry_the_dictionary_reading_on_both_layouts() {
        val rows = listOf(EngineFixture.Row("zhongguo", "中国", 900),
            EngineFixture.Row("zhong", "中", 900), EngineFixture.Row("guo", "国", 900))
        val e = DictEngine(EngineFixture.build(rows),
            EngineFixture.build(rows.map { it.copy(key = T9Pinyin.toT9(it.key)) }), null, fuzzyRules = setOf("zh"))
        for (nine in listOf(false, true)) {
            val candidate = e.candidatesCovered(if (nine) "9664486" else "zongguo", nine).first { it.word == "中国" }
            assertEquals("zhongguo", candidate.correctedReading)
            val partial = e.candidatesCovered(if (nine) "9664" else "zong", nine).first { it.word == "中" }
            assertEquals("zhong", partial.correctedReading)
        }
    }

    @Test fun exact_fuzzy_matches_remain_reachable_beyond_the_completion_budget() {
        val rows = (0 until 40).map { EngineFixture.Row("feiyi", "原" + ('一'.code + it).toChar(), 9000 - it) } +
            listOf(EngineFixture.Row("huiyi", "会议", 100), EngineFixture.Row("hui", "会", 900), EngineFixture.Row("yi", "议", 900))
        val e = DictEngine(EngineFixture.build(rows),
            EngineFixture.build(rows.map { it.copy(key = T9Pinyin.toT9(it.key)) }), null, fuzzyRules = setOf("hui_fei"))
        for (nine in listOf(false, true)) {
            val candidates = e.candidatesCovered(if (nine) "33494" else "feiyi", nine)
            assertEquals("原一", candidates.first().word)
            assertTrue(candidates.indexOfFirst { it.word == "会议" } >= 40)
            assertEquals("huiyi", candidates.first { it.word == "会议" }.correctedReading)
        }
    }

}
