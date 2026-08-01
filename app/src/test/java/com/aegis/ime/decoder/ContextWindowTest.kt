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

import com.aegis.ime.dict.OctagramFixture
import com.aegis.ime.user.UserLearning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowTest {

    private fun fixtureDict() = EngineFixture.build(
        listOf(
            EngineFixture.Row("mai", "买", 100),
            EngineFixture.Row("mai", "卖", 200),
            EngineFixture.Row("yi", "一", 500),
            EngineFixture.Row("qi", "起", 500),
            EngineFixture.Row("qu", "去", 300),
            EngineFixture.Row("qu", "趣", 400),
            EngineFixture.Row("wo", "我", 500),
            EngineFixture.Row("men", "们", 500),
            EngineFixture.Row("yiqi", "一起", 500),
            EngineFixture.Row("women", "我们", 500),
        ),
    )

    @Test fun fixtureRoundTripsLongAndSupplementaryEntries() {
        val supp = EngineFixture.supplementary(0)
        val reader = OctagramFixture.reader(
            mapOf(
                "你好" to 11.2,
                "中华人民共和国" to 22.3,
                "好$supp" to 15.7,
            ),
        )
        assertEquals(11.2, reader.rawScore("你好")!!, 1e-4)
        assertEquals(22.3, reader.rawScore("中华人民共和国")!!, 1e-4)
        assertEquals(15.7, reader.rawScore("好$supp")!!, 1e-4)
        assertEquals(null, reader.rawScore("中华人民"))
    }

    @Test fun contextTailKeepsOnlyWhatTheActiveModelsNeed() {
        val decoder = PinyinDecoder(fixtureDict())
        assertEquals("五", decoder.parseContext("一二三四五").tail)
        assertEquals("市", decoder.parseContext("abc去超市").tail)
        assertEquals(PinyinDecoder.BOS, decoder.parseContext("你好。").cp)
        assertEquals("", decoder.parseContext("你好。").tail)
    }

    @Test fun contextTailWalksSupplementaryHanByCodePoint() {
        val decoder = PinyinDecoder(fixtureDict())
        val a = EngineFixture.supplementary(0)
        val b = EngineFixture.supplementary(1)
        val c = EngineFixture.supplementary(2)
        val d = EngineFixture.supplementary(3)
        val e = EngineFixture.supplementary(4)
        val ctx = decoder.parseContext("好$a$b$c$d$e")
        assertEquals(e, ctx.tail)
        assertEquals(e.codePointAt(0), ctx.cp)
    }

    @Test fun octagramContextExtendsBeyondTheFormerFourCharacterTail() {
        val grammar = OctagramFixture.reader(mapOf("一二三四五六买" to 30.0))
        val decoder = PinyinDecoder(fixtureDict(), octagram = grammar)
        assertEquals(7, decoder.requiredContextCodePoints())
        assertEquals("零一二三四五六", decoder.parseContext("前零一二三四五六").tail)
        assertEquals("买", decoder.decodeCovered("mai", 30, context = "一二三四五六").first().word)
        assertEquals("买", decoder.decodeCoveredAtomic("mai", 30, context = "一二三四五六").first().word)
    }

    @Test fun learnedContextUsesTheActualLongestCollocationKey() {
        val context = "甲乙丙丁戊己庚辛壬癸"
        val learning = UserLearning { 1_000L }
        repeat(2) { learning.observeCommit(context, "买", "", 1_000L) }
        val decoder = PinyinDecoder(fixtureDict(), userLearning = learning)

        assertEquals(10, decoder.requiredContextCodePoints())
        assertEquals(context, decoder.parseContext("前$context").tail)
        assertTrue(learning.followBoost(context, "买") > 0.0)
        assertEquals("买", decoder.decodeCovered("mai", 30, context = context).first().word)
        assertEquals("买", decoder.decodeCoveredAtomic("mai", 30, context = context).first().word)
    }

    @Test fun everyNonemptyContextSuffixCanReachTheGrammar() {
        val grammar = OctagramFixture.reader(mapOf("超市买" to 18.0))
        val decoder = PinyinDecoder(fixtureDict(), octagram = grammar)
        decoder.setFuzzyRules(emptySet())
        assertEquals("卖", decoder.decodeCovered("mai", 30).first().word)
        assertEquals("买", decoder.decodeCovered("mai", 30, context = "昨天去超市").first().word)
        assertEquals("买", decoder.decodeCoveredAtomic("mai", 30, context = "昨天去超市").first().word)
    }

    @Test fun contextTailPropagatesAcrossMultipleDecodedWords() {
        val grammar = OctagramFixture.reader(mapOf("我们一起去" to 22.3))
        val dict = fixtureDict()
        assertEquals(
            "我们一起趣",
            PinyinDecoder(dict).decodeCoveredAtomic("womenyiqiqu", 30).first().word,
        )
        val decoder = PinyinDecoder(dict, octagram = grammar)
        assertEquals("一起去", decoder.decodeCoveredAtomic("yiqiqu", 30, context = "我们").first().word)
        assertEquals("我们一起去", decoder.decodeCoveredAtomic("womenyiqiqu", 30).first().word)
        assertEquals("我们一起去", decoder.decodeCovered("womenyiqiqu", 30).first().word)
    }

    @Test fun fourCharacterTailCanReachARealSixCharacterEdge() {
        val dict = EngineFixture.build(
            listOf(
                EngineFixture.Row("gonghe", "共和", 100),
                EngineFixture.Row("gonghe", "任和", 200),
            ),
        )
        val grammar = OctagramFixture.reader(mapOf("一二三四共和" to 30.0))
        val decoder = PinyinDecoder(dict, octagram = grammar)
        assertEquals("任和", PinyinDecoder(dict).decodeCovered("gonghe", 30, context = "一二三四").first().word)
        assertEquals("共和", decoder.decodeCovered("gonghe", 30, context = "一二三四").first().word)
        assertEquals("共和", decoder.decodeCoveredAtomic("gonghe", 30, context = "一二三四").first().word)
    }

    @Test fun bestSentenceBeamKeepsDifferentTailsEndingInTheSameCodePoint() {
        val prefixes = listOf("甲", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸", "子", "丑", "乙", "寅")
        val dict = EngineFixture.build(
            prefixes.mapIndexed { index, prefix ->
                EngineFixture.Row("yi", "${prefix}同", 300 - index * 10)
            } + EngineFixture.Row("qu", "去", 100),
        )
        val grammar = OctagramFixture.reader(mapOf("乙同去" to 30.0))
        assertEquals("甲同去", PinyinDecoder(dict).decodeCovered("yiqu", 30).first().word)
        assertEquals("乙同去", PinyinDecoder(dict, octagram = grammar).decodeCovered("yiqu", 30).first().word)
    }

    @Test fun absentGrammarLeavesContextOutOfRanking() {
        val decoder = PinyinDecoder(fixtureDict())
        for (input in listOf("mai", "yiqiqu", "womenyiqiqu")) {
            assertEquals(
                decoder.decodeCovered(input, 30),
                decoder.decodeCovered(input, 30, context = "昨天我们去超市"),
            )
            assertEquals(
                decoder.decodeCoveredAtomic(input, 30),
                decoder.decodeCoveredAtomic(input, 30, context = "昨天我们去超市"),
            )
        }
    }
}
