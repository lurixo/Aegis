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
import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.engine.T9_FUZZY_PENALTY
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class FuzzyEvalT9Test {

    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val evalFile = File("src/test/resources/eval_set.tsv")

    @Test
    fun nineKeyFuzzyRecallOnTheSameCorpus() {
        assumeTrue(t9File.exists() && dictFile.exists() && lmFile.exists() && evalFile.exists())
        val t9 = BinaryDict.fromFile(t9File)
        val alias = BinaryDict.fromFile(dictFile)
        val lm = CharBigramLM.fromFile(lmFile)

        val pairs = evalFile.readLines().mapNotNull {
            val t = it.indexOf('\t')
            if (t <= 0) null else it.substring(0, t) to it.substring(t + 1)
        }
        val relevant = pairs.filter { Fuzzy.normalize(it.first) != it.first }
        assumeTrue(relevant.isNotEmpty())

        fun decoder(rules: Set<String>) = PinyinDecoder(
            t9,
            lm,
            fuzzyRules = rules,
            aliasDict = alias,
            fuzzyVariants = { s, r -> T9Pinyin.fuzzyVariants(s, r) },
            fuzzyPenalty = T9_FUZZY_PENALTY,
        )
        val off = decoder(emptySet())
        val on = decoder(Fuzzy.RULES.mapTo(LinkedHashSet()) { it.key })

        var slipOff = 0
        var slipOn = 0
        for ((py, expected) in relevant) {
            val digits = T9Pinyin.toT9(Fuzzy.normalize(py))
            if (off.decodeCovered(digits, 30).firstOrNull()?.word == expected) slipOff++
            if (on.decodeCovered(digits, 30).firstOrNull()?.word == expected) slipOn++
        }
        var exactOff = 0
        var exactOn = 0
        for ((py, expected) in pairs) {
            val digits = T9Pinyin.toT9(py)
            if (off.decodeCovered(digits, 30).firstOrNull()?.word == expected) exactOff++
            if (on.decodeCovered(digits, 30).firstOrNull()?.word == expected) exactOn++
        }
        println("T9-FUZZY-EVAL relevant=${relevant.size} slipOff=$slipOff slipOn=$slipOn " +
            "pairs=${pairs.size} exactOff=$exactOff exactOn=$exactOn")
        assertTrue("nine-key fuzzy must not reduce recall on the shared corpus", slipOn >= slipOff)
        assertTrue(
            "nine-key fuzzy must not erode exact-digit precision much",
            exactOn >= exactOff - pairs.size / 20,
        )
    }
}
