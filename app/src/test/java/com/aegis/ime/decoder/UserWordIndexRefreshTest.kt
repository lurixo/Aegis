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

import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserWordIndexRefreshTest {

    private val clock = 1_700_000_000_000L

    private fun words(d: PinyinDecoder, input: String) = d.decodeCovered(input, 30).map { it.word }

    @Test fun aWordLearnedAfterTheFirstDecodeIsOfferedOnTheNextOne() {
        val um = UserModel { clock }
        val d = PinyinDecoder(EngineFixture.dict(), userModel = um)
        assertFalse("丢仔" in words(d, "diuzi"))
        um.recordWord("diuzi", "丢仔", clock, incrementCount = true)
        assertTrue("丢仔" in words(d, "diuzi"))
        um.removeWord("diuzi", "丢仔")
        assertFalse("丢仔" in words(d, "diuzi"))
        um.addManualWord("diuzi", "丢籽", clock)
        assertTrue("丢籽" in words(d, "diuzi"))
    }

    @Test fun aWordFormedAfterTheFirstDecodeIsOfferedOnTheNextOne() {
        val learning = UserLearning { clock }
        val d = PinyinDecoder(EngineFixture.dict(), userLearning = learning)
        assertFalse("丢仔" in words(d, "diuzi"))
        repeat(4) {
            learning.observeCommit(null, "丢", "diu", clock)
            learning.observeCommit("丢", "仔", "zi", clock)
            learning.observeBreak()
        }
        assertTrue("the chain formed the word", "丢仔" in learning.formedWordsFor("diuzi"))
        assertTrue("丢仔" in words(d, "diuzi"))
    }

    private val rankRows = listOf(
        EngineFixture.Row("ce", "测", 5000), EngineFixture.Row("ce", "侧", 500), EngineFixture.Row("ce", "策", 500),
        EngineFixture.Row("shi", "试", 5000), EngineFixture.Row("shi", "视", 500), EngineFixture.Row("shi", "式", 500),
    )
    private val letterDict by lazy { EngineFixture.build(rankRows) }
    private val digitDict by lazy { EngineFixture.build(rankRows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) }) }

    private fun decoder(nine: Boolean, um: UserModel? = null, learning: UserLearning? = null) =
        if (nine) {
            PinyinDecoder(
                digitDict,
                userModel = um,
                userLearning = learning,
                aliasDict = letterDict,
                fuzzyVariants = { s, rules -> T9Pinyin.fuzzyVariants(s, rules) },
            )
        } else {
            PinyinDecoder(letterDict, userModel = um, userLearning = learning)
        }

    private fun input(nine: Boolean) = if (nine) T9Pinyin.toT9("ceshi") else "ceshi"

    private fun decodedOrder(d: PinyinDecoder, nine: Boolean) = d.decodeCovered(input(nine), 30).map { it.word }

    private fun relative(order: List<String>) = order.filter { it == "侧视" || it == "策式" }

    private fun countOnlyChangeKeepsUserWordOrderFresh(nine: Boolean) {
        val um = UserModel { clock }
        um.recordWord("ceshi", "侧视", clock, incrementCount = true)
        um.recordWord("ceshi", "策式", clock, incrementCount = true)
        um.record(null, "策式", clock)
        um.record(null, "策式", clock)
        um.recordWord("afshi", "阿福诗", clock, incrementCount = true)
        val live = decoder(nine, um)
        val before = decodedOrder(live, nine)
        assertEquals("the busier word leads before the change", listOf("策式", "侧视"), relative(before))
        val indexed = um.readingsVersion
        um.record(null, "侧视", clock)
        um.record(null, "侧视", clock)
        assertEquals("only the use count moved", indexed, um.readingsVersion)
        val after = decodedOrder(live, nine)
        val fresh = decodedOrder(decoder(nine, um), nine)
        assertEquals("the next decode ranks user words like a freshly built index", fresh, after)
        assertNotEquals("the fixture really reorders the tied words", relative(before), relative(fresh))
    }

    @Test fun aCountOnlyChangeReordersUserWordsOn26Key() = countOnlyChangeKeepsUserWordOrderFresh(nine = false)

    @Test fun aCountOnlyChangeReordersUserWordsOn9Key() = countOnlyChangeKeepsUserWordOrderFresh(nine = true)

    private fun observeWord(learning: UserLearning, chars: String) {
        learning.observeCommit(null, chars.substring(0, 1), "ce", clock)
        learning.observeCommit(chars.substring(0, 1), chars.substring(1), "shi", clock)
        learning.observeBreak()
    }

    private fun formedUseKeepsOrderFresh(nine: Boolean) {
        val learning = UserLearning { clock }
        repeat(4) { observeWord(learning, "策式") }
        repeat(3) { observeWord(learning, "侧视") }
        val live = decoder(nine, learning = learning)
        val before = decodedOrder(live, nine)
        assertEquals(listOf("策式", "侧视"), relative(before))
        val formed = learning.formedVersion
        observeWord(learning, "侧视")
        assertEquals("touching a formed word leaves the formed index version alone", formed, learning.formedVersion)
        val after = decodedOrder(live, nine)
        val fresh = decodedOrder(decoder(nine, learning = learning), nine)
        assertEquals(fresh, after)
        assertNotEquals(relative(before), relative(fresh))
    }

    @Test fun aFormedWordUsedAgainIsReorderedOn26Key() = formedUseKeepsOrderFresh(nine = false)

    @Test fun aFormedWordUsedAgainIsReorderedOn9Key() = formedUseKeepsOrderFresh(nine = true)
}
