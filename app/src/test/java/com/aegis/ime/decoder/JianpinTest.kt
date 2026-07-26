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

import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class JianpinTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val jianpinFile = File("src/main/assets/aegis_jianpin.bin")

    private val completions = List(24) { "德" + String(Character.toChars(0x4E00 + it)) }

    private fun crowdedDict(): BinaryDict {
        val rows = ArrayList<EngineFixture.Row>()
        rows.add(EngineFixture.Row("d", "地", 6000))
        rows.add(EngineFixture.Row("de", "的", 5000))
        completions.forEachIndexed { i, w -> rows.add(EngineFixture.Row("de" + ('a' + i), w, 900 - i)) }
        return EngineFixture.build(rows)
    }

    private fun crowdedT9Dict(): BinaryDict {
        val rows = ArrayList<EngineFixture.Row>()
        rows.add(EngineFixture.Row(T9Pinyin.toT9("d"), "地", 6000))
        rows.add(EngineFixture.Row(T9Pinyin.toT9("de"), "的", 5000))
        completions.forEachIndexed { i, w ->
            rows.add(EngineFixture.Row(T9Pinyin.toT9("de" + ('a' + i)), w, 900 - i))
        }
        return EngineFixture.build(rows)
    }

    private fun initialsDict(): BinaryDict = EngineFixture.build(
        listOf(EngineFixture.Row("de", "第二", 300), EngineFixture.Row("d", "第二", 300)),
    )

    @Test
    fun jianpinCandidates() {
        assumeTrue(dictFile.exists() && lmFile.exists() && jianpinFile.exists())
        val d = PinyinDecoder(
            BinaryDict.fromFile(dictFile),
            CharBigramLM.fromFile(lmFile),
            initialsDict = BinaryDict.fromFile(jianpinFile),
        )
        assertTrue("zg -> 中国", d.decode("zg", 12).contains("中国"))
        assertTrue("bjdx -> 北京大学", d.decode("bjdx", 12).contains("北京大学"))
        assertTrue("wm -> 我们", d.decode("wm", 12).contains("我们"))
    }

    @Test
    fun abbreviationSurvivesOnKeysThatAreAlsoPinyin() {
        assumeTrue(dictFile.exists() && lmFile.exists() && jianpinFile.exists())
        val d = PinyinDecoder(
            BinaryDict.fromFile(dictFile),
            CharBigramLM.fromFile(lmFile),
            initialsDict = BinaryDict.fromFile(jianpinFile),
        )
        fun words(key: String) = d.decodeCovered(key, 30).map { it.word }
        assertTrue("de -> 第二", words("de").contains("第二"))
        assertTrue("re -> 然而", words("re").contains("然而"))
        assertTrue("ba -> 不爱", words("ba").contains("不爱"))
    }

    @Test
    fun reservedAbbreviationTakesOneSlotAtTheEndOfTheCover() {
        val dict = crowdedDict()
        val without = PinyinDecoder(dict).decodeCovered("de", 30).map { it.word }
        val (with, remainderStart) =
            PinyinDecoder(dict, initialsDict = initialsDict()).decodeCoveredLayered("de", 30)
        val words = with.map { it.word }

        assertFalse("without an initials dictionary 第二 cannot appear", without.contains("第二"))
        assertTrue("第二 is reserved a slot", words.contains("第二"))
        assertEquals("reserved word sits last in the cover", remainderStart - 1, words.indexOf("第二"))
        assertEquals(
            "exactly one completion gives up its slot",
            without.count { it in completions } - 1,
            words.count { it in completions },
        )
    }

    @Test
    fun singleLetterInputReservesNothing() {
        val words = PinyinDecoder(crowdedDict(), initialsDict = initialsDict())
            .decodeCovered("d", 30).map { it.word }
        assertFalse("no slot is reserved for a one-letter input", words.contains("第二"))
    }

    @Test
    fun nineKeyGetsNoInitialsDictionary() {
        val engine = DictEngine(
            crowdedDict(),
            crowdedT9Dict(),
            null,
            initialsDict = initialsDict(),
        )
        assertTrue("26-key reserves the abbreviation", engine.candidates("de", t9 = false).contains("第二"))
        assertFalse("9-key has no abbreviation layer", engine.candidates(T9Pinyin.toT9("de"), t9 = true).contains("第二"))
    }
}
