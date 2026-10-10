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

import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SharedSentencePathsTest {

    private val clock = 1_700_000_000_000L

    private fun readings(): List<String> =
        File("src/test/resources/eval_set.tsv").readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab)
        }.filter { reading -> reading.all { it in 'a'..'z' } }

    private fun taught(): UserModel = UserModel { clock }.apply {
        repeat(50) { recordWord("tianqi", "天气", clock, incrementCount = true) }
        repeat(50) { recordWord("842674", "天气", clock, incrementCount = true) }
        repeat(50) { recordWord("dianying", "电影", clock, incrementCount = true) }
    }

    private fun chained(): UserLearning = UserLearning { clock }.apply {
        repeat(64) {
            observeCommit(null, "我们", "women", clock)
            observeCommit("我们", "一起", "yiqi", clock)
        }
    }

    @Test fun oneDecodeYieldsTheCandidatesAndTheRankedPathsOfSeparateRuns() {
        val gram = System.getenv("AEGIS_GRAM")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("AEGIS_GRAM points to a current grammar snapshot", gram?.isFile == true)
        val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
        val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
        val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
        val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)
        assumeTrue(
            "production dictionary, T9 table, language model and jianpin table present",
            FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
        )
        val octagram = OctagramReader.fromFile(requireNotNull(gram))
        val dict = BinaryDict.fromFile(dictFile)
        val t9 = BinaryDict.fromFile(t9File)
        val lm = CharBigramLM.fromFile(lmFile)
        val jianpin = BinaryDict.fromFile(jianpinFile)
        val corpus = readings()
        val configs = listOf(
            Triple("plain", "", false),
            Triple("learned", "今天我们", true),
        )
        val mismatches = ArrayList<String>()
        var compared = 0
        val perLayout = HashMap<String, Int>()
        for ((name, context, learned) in configs) {
            fun engine() = if (learned) {
                DictEngine(dict, t9, lm, userModel = taught(), initialsDict = jianpin, octagram = octagram, userLearning = chained())
            } else {
                DictEngine(dict, t9, lm, initialsDict = jianpin, octagram = octagram)
            }
            val separate = engine()
            val shared = engine()
            for (nine in listOf(false, true)) {
                for (reading in corpus) {
                    val keys = if (nine) T9Pinyin.toT9(reading) else reading
                    for (k in 1..keys.length) {
                        val input = keys.take(k)
                        val candidates = separate.candidatesCovered(input, nine, emptySet(), context)
                        val ranked = separate.rankedSentences(input, nine, context, RANKED_LIMIT)
                        val (sharedCandidates, sharedRanked) =
                            shared.candidatesCoveredWithSentences(input, nine, context, RANKED_LIMIT)
                        if (candidates != sharedCandidates) mismatches.add("$name/$nine/$input candidates")
                        if (ranked != sharedRanked) mismatches.add("$name/$nine/$input paths")
                        compared++
                        perLayout.merge(if (nine) "9key" else "26key", 1, Int::plus)
                    }
                }
            }
        }
        println("shared sentence paths: compared=$compared perLayout=$perLayout mismatches=${mismatches.size}")
        assertTrue(compared > 0)
        assertEquals(emptyList<String>(), mismatches.take(20))
    }

    private companion object {
        const val RANKED_LIMIT = 20
    }
}
