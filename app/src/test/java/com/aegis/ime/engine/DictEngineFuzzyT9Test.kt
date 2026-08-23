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

class DictEngineFuzzyT9Test {

    private fun rows(vararg pairs: Pair<String, String>) =
        pairs.map { (reading, word) -> EngineFixture.Row(reading, word, 900) }

    private fun engine(rules: Set<String>): DictEngine {
        val readings = listOf("zhong" to "中", "zong" to "总", "shang" to "上", "sang" to "桑")
        val pinyin = EngineFixture.build(rows(*readings.toTypedArray()))
        val t9 = EngineFixture.build(
            readings.map { (reading, word) -> EngineFixture.Row(T9Pinyin.toT9(reading), word, 900) },
        )
        return DictEngine(pinyin, t9, null, fuzzyRules = rules)
    }

    private fun nineKey(engine: DictEngine, reading: String): List<String> =
        engine.candidates(T9Pinyin.toT9(reading), t9 = true)

    @Test fun a_rule_reaches_nothing_outside_its_own_class() {
        val readings = listOf("zhong" to "中", "zong" to "总", "wo" to "我", "xin" to "心", "yin" to "因")
        val pinyin = EngineFixture.build(rows(*readings.toTypedArray()))
        val t9 = EngineFixture.build(
            readings.map { (reading, word) -> EngineFixture.Row(T9Pinyin.toT9(reading), word, 900) },
        )
        val e = DictEngine(pinyin, t9, null, fuzzyRules = setOf("zh"))
        val offered = nineKey(e, "wo")
        assertFalse("zh fuzzy has no business with the keys of wo", "心" in offered)
        assertFalse("nor with yin", "因" in offered)
        assertTrue("wo itself still reaches its word", "我" in offered)
    }

    @Test fun a_retroflex_rule_reaches_the_retroflex_word_from_the_flat_digits() {
        assertTrue("without the rule the flat reading only finds its own word",
            nineKey(engine(emptySet()), "zong") == listOf("总"))
        assertTrue("zh fuzzy must reach 中 from the digits of zong",
            "中" in nineKey(engine(setOf("zh")), "zong"))
    }

    @Test fun a_nasal_rule_reaches_the_long_final_from_the_short_one() {
        assertFalse("without the rule the short final stays put",
            "上" in nineKey(engine(emptySet()), "sang"))
        assertTrue("ang fuzzy must reach 上 from the digits of sang",
            "上" in nineKey(engine(setOf("sh", "ang")), "sang"))
    }

    @Test fun turning_a_rule_on_at_runtime_reaches_the_nine_key_decoder_too() {
        val e = engine(emptySet())
        assertFalse("中" in nineKey(e, "zong"))
        e.setFuzzyRules(setOf("zh"))
        assertTrue("a hot toggle must apply to the nine-key layout as well",
            "中" in nineKey(e, "zong"))
        e.setFuzzyRules(emptySet())
        assertFalse("turning it back off must stop reaching it", "中" in nineKey(e, "zong"))
    }

    @Test fun the_twentysix_key_layout_keeps_working_the_same_way() {
        val e = engine(setOf("zh"))
        assertTrue("中" in e.candidates("zong", t9 = false))
        assertFalse("中" in engine(emptySet()).candidates("zong", t9 = false))
    }
}
