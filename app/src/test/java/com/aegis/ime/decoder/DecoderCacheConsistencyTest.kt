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

package com.aegis.ime.decoder

import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderCacheConsistencyTest {

    private val rows = listOf(
        EngineFixture.Row("shi", "是", 950),
        EngineFixture.Row("shi", "时", 920),
        EngineFixture.Row("shi", "十", 820),
        EngineFixture.Row("shi", EngineFixture.supplementary(0), 820),
        EngineFixture.Row("si", "四", 900),
        EngineFixture.Row("si", "思", 650),
        EngineFixture.Row("xi", "西", 850),
        EngineFixture.Row("an", "安", 900),
        EngineFixture.Row("xian", "现", 900),
        EngineFixture.Row("xian", "先", 780),
        EngineFixture.Row("xian", "西安", 5000),
        EngineFixture.Row("xiang", "想", 930),
        EngineFixture.Row("xiang", "向", 980),
        EngineFixture.Row("zhong", "中", 950),
        EngineFixture.Row("zong", "总", 800),
        EngineFixture.Row("guo", "国", 900),
        EngineFixture.Row("ku", "哭", 800),
        EngineFixture.Row("shixian", "实现", 880),
        EngineFixture.Row("sixiang", "思想", 860),
        EngineFixture.Row("shixiang", "事项", 700),
        EngineFixture.Row("zhongguo", "中国", 900),
        EngineFixture.Row("xiangku", "想哭", 870),
    )
    private val initialRows = listOf(
        EngineFixture.Row("sx", "实现", 880),
        EngineFixture.Row("sx", "思想", 860),
        EngineFixture.Row("zg", "中国", 900),
        EngineFixture.Row("xian", "鲜", 400),
        EngineFixture.Row("xk", "想哭", 870),
    )
    private val dict = EngineFixture.build(rows)
    private val initials = EngineFixture.build(initialRows)
    private val t9 = EngineFixture.build(rows.map { it.copy(key = T9Pinyin.toT9(it.key)) })
    private val lm = EngineFixture.buildLm(
        mapOf('实'.code to 300L, '现'.code to 200L, '思'.code to 100L, '想'.code to 250L, '中'.code to 400L, '国'.code to 350L, '哭'.code to 50L),
        mapOf(('实'.code to '现'.code) to 90L, ('中'.code to '国'.code) to 120L, ('想'.code to '哭'.code) to 20L),
    )
    private val userModel = UserModel { 0L }.apply {
        recordWord("shixiang", "实像", 1L, incrementCount = true)
        recordWord("xian", "仙", 1L, incrementCount = false)
    }
    private val readings = listOf("shixian", "sixiang", "zhongguo", "zongguo", "xiangku", "sxk")

    private fun letters(rules: Set<String>) =
        PinyinDecoder(dict, lm, userModel = userModel, fuzzyRules = rules, initialsDict = initials)

    private fun digits(rules: Set<String>) =
        PinyinDecoder(
            t9,
            lm,
            userModel = userModel,
            fuzzyRules = rules,
            aliasDict = dict,
            fuzzyVariants = { s, r -> T9Pinyin.fuzzyVariants(s, r) },
        )

    @Test fun warmDecodersAnswerLikeFreshOnesAcrossRepeatsAndRuleChanges() {
        val warmLetters = letters(emptySet())
        val warmDigits = digits(emptySet())
        for (rules in listOf(emptySet(), Fuzzy.DEFAULT_RULE_KEYS, setOf("zh"), emptySet())) {
            warmLetters.setFuzzyRules(rules)
            warmDigits.setFuzzyRules(rules)
            repeat(2) {
                for (reading in readings) for (end in 1..reading.length) {
                    val input = reading.substring(0, end)
                    val keys = T9Pinyin.toT9(input)
                    for (context in listOf("", "实现")) {
                        assertEquals(
                            "$rules letters $input after $context",
                            letters(rules).decodeCovered(input, LIMIT, context = context),
                            warmLetters.decodeCovered(input, LIMIT, context = context),
                        )
                        assertEquals(
                            "$rules digits $keys after $context",
                            digits(rules).decodeCovered(keys, LIMIT, context = context),
                            warmDigits.decodeCovered(keys, LIMIT, context = context),
                        )
                    }
                    val cuts = T9Pinyin.segmentLetters(input)?.dropLast(1)?.runningFold(0) { at, s -> at + s.length }
                        ?.drop(1)?.toSet().orEmpty()
                    assertEquals(
                        "$rules locked $input",
                        letters(rules).decodeCoveredAtomic(input, LIMIT, cuts),
                        warmLetters.decodeCoveredAtomic(input, LIMIT, cuts),
                    )
                    assertEquals("$rules homophones $input", letters(rules).homophonesAt(input, 0), warmLetters.homophonesAt(input, 0))
                }
            }
        }
    }

    private companion object {
        const val LIMIT = 30
    }
}
