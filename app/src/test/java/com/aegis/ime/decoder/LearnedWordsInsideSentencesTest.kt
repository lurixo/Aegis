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
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.engine.T9_FUZZY_PENALTY
import com.aegis.ime.engine.T9_GRAMMAR_CHAR_PENALTY
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LearnedWordsInsideSentencesTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)

    private val clock = 1_700_000_000_000L

    private val picked = listOf(
        "的" to 330, "我" to 90, "了" to 90, "在" to 80, "是" to 75, "他" to 60, "你" to 50, "和" to 40,
        "我们" to 30, "人" to 30, "有" to 27, "吗" to 26, "为" to 25, "与" to 20, "一个" to 18, "去" to 18,
        "也" to 17, "她" to 16, "会" to 15, "没有" to 15, "他们" to 15, "都" to 14, "说" to 12, "啊" to 8,
    )

    private val sentences = listOf(
        "wojintianhengaoxing" to "我今天很高兴",
        "tamenzaijialikandianshi" to "他们在家里看电视",
        "mingtianwomenyiqiquchifan" to "明天我们一起去吃饭",
    )

    private fun history() = UserModel { clock }.apply {
        var prev: String? = null
        for ((word, times) in picked) repeat(times) { record(prev, word, clock); prev = word }
    }

    private fun grammar(): OctagramReader? {
        val path = System.getenv("AEGIS_GRAM")
        return if (!path.isNullOrEmpty() && File(path).exists()) OctagramReader.fromFile(File(path)) else null
    }

    private fun letters(um: UserModel, gram: OctagramReader?) =
        PinyinDecoder(BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile), userModel = um, octagram = gram)

    private fun digits(um: UserModel, gram: OctagramReader?) = PinyinDecoder(
        BinaryDict.fromFile(t9File),
        CharBigramLM.fromFile(lmFile),
        userModel = um,
        octagram = gram,
        aliasDict = BinaryDict.fromFile(dictFile),
        fuzzyVariants = { s, rules -> T9Pinyin.fuzzyVariants(s, rules) },
        fuzzyPenalty = T9_FUZZY_PENALTY,
        grammarCharPenalty = T9_GRAMMAR_CHAR_PENALTY,
    )

    private fun check(gram: OctagramReader?) {
        val um = history()
        for ((reading, sentence) in sentences) {
            val onLetters = letters(um, gram).decodeCovered(reading, 10).first().word
            assertEquals("26-key sentence after many word-by-word picks", sentence, onLetters)
            val onDigits = digits(um, gram).decodeCovered(T9Pinyin.toT9(reading), 10).first().word
            assertEquals("9-key sentence after many word-by-word picks", sentence, onDigits)
        }
    }

    @Test fun commonWordsPickedManyTimesDoNotBreakSentencesApart() {
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile))
        check(null)
    }

    @Test fun commonWordsPickedManyTimesDoNotBreakSentencesApartWithTheGrammar() {
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile))
        val gram = grammar()
        assumeTrue("grammar model present", gram != null)
        check(gram)
    }
}
