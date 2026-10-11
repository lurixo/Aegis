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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictEngineFuzzyAtomicTest {
    private val rows = listOf(
        EngineFixture.Row("zong", "总", 800),
        EngineFixture.Row("guo", "过", 800),
        EngineFixture.Row("zhong", "中", 800),
        EngineFixture.Row("guo", "国", 700),
        EngineFixture.Row("zhongguo", "中国", 900),
    )

    private fun engine(rows: List<EngineFixture.Row>, rules: Set<String>): DictEngine = DictEngine(
        EngineFixture.build(rows),
        EngineFixture.build(rows.map { it.copy(key = T9Pinyin.toT9(it.key)) }),
        null,
        fuzzyRules = rules,
    )

    private fun cutWords(e: DictEngine): Map<String, List<String>> = mapOf(
        "letters with a separator" to e.candidatesCovered("zong'guo", false, emptySet(), "").map { it.word },
        "letters with a forced cut" to e.candidatesCovered("zongguo", false, setOf(4), "").map { it.word },
        "digits with a forced cut" to e.candidatesCovered("9664486", true, setOf(4), "").map { it.word },
        "locked reading" to e.candidatesForLockedReadingCovered("zongguo", setOf(4), "").map { it.word },
    )

    @Test fun a_cut_or_locked_reading_keeps_fuzzy_words_reachable_on_both_layouts() {
        val e = engine(rows, setOf("zh"))
        for ((path, words) in cutWords(e)) assertTrue("$path: $words", "中国" in words)
        e.setFuzzyRules(emptySet())
        for ((path, words) in cutWords(e)) assertFalse("$path without rules: $words", "中国" in words)
    }

    @Test fun a_fuzzy_word_under_a_locked_reading_carries_its_dictionary_reading() {
        val e = engine(rows, setOf("zh"))
        val hit = e.candidatesForLockedReadingCovered("zongguo", setOf(4), "").first { it.word == "中国" }
        assertEquals(7, hit.coveredLen)
        assertEquals("zhongguo", hit.correctedReading)
    }

    @Test fun exact_words_stay_ahead_of_fuzzy_words_under_a_cut() {
        val e = engine(rows + EngineFixture.Row("zongguo", "总过", 900), setOf("zh"))
        for ((path, words) in cutWords(e)) {
            assertEquals("$path: $words", "总过", words.first())
            assertTrue("$path: $words", "中国" in words)
        }
    }

    @Test fun a_fuzzy_word_that_straddles_the_cut_stays_out() {
        val straddling = listOf(
            EngineFixture.Row("fang", "方", 800),
            EngineFixture.Row("an", "安", 800),
            EngineFixture.Row("fan", "返", 800),
            EngineFixture.Row("gang", "港", 800),
            EngineFixture.Row("fangang", "返港", 900),
        )
        val e = engine(straddling, setOf("ang"))
        assertTrue("返港" in e.candidates("fangan", false))
        assertTrue("返港" in e.candidates(T9Pinyin.toT9("fangan"), true))
        assertFalse("返港" in e.candidatesCovered("fang'an", false, emptySet(), "").map { it.word })
        assertFalse("返港" in e.candidatesCovered(T9Pinyin.toT9("fangan"), true, setOf(4), "").map { it.word })
        assertFalse("返港" in e.candidatesForLockedReadingCovered("fangan", setOf(4), "").map { it.word })
    }
}
