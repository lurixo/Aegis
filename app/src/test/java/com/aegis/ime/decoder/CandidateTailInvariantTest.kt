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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CandidateTailInvariantTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private fun assetsPresent() = FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile)

    private val letterDict by lazy { BinaryDict.fromFile(dictFile) }
    private val digitDict by lazy { BinaryDict.fromFile(t9File) }
    private val lm by lazy { CharBigramLM.fromFile(lmFile) }

    private class Keyboard(
        val name: String,
        val decoder: PinyinDecoder,
        val dict: BinaryDict,
        val keys: (String) -> String,
    )

    private val boards by lazy {
        listOf(
            Keyboard(
                "26-key",
                PinyinDecoder(letterDict, lm, initialsDict = BinaryDict.fromFile(jianpinFile)),
                letterDict,
            ) { it },
            Keyboard("9-key", PinyinDecoder(digitDict, lm, aliasDict = letterDict), digitDict) { T9Pinyin.toT9(it) },
        )
    }

    private fun isSingleChar(word: String) = word.codePointCount(0, word.length) == 1

    private class Run(val label: String, val board: Keyboard, val input: String, val cands: List<Cand>) {
        val words = cands.map { it.word }
    }

    private fun runsFor(board: Keyboard, first: String, second: String, context: String): List<Run> {
        val head = board.keys(first)
        val input = head + board.keys(second)
        val cuts = setOf(head.length)
        val tag = "${board.name} $first'$second ctx=$context"
        return listOf(
            Run("$tag typed straight", board, input, board.decoder.decodeCovered(input, LIMIT, emptySet(), context)),
            Run("$tag separated", board, input, board.decoder.decodeCovered(input, LIMIT, cuts, context)),
            Run("$tag locked", board, input, board.decoder.decodeCoveredAtomic(input, LIMIT, cuts, context)),
        )
    }

    private fun exposedEntries(board: Keyboard, input: String, words: Collection<String>): List<String> {
        val out = ArrayList<String>()
        for (word in words) {
            for (q in 1 until input.length) {
                if (board.dict.exactWordFreq(input.substring(0, q), word) != null) { out.add(word); break }
            }
        }
        return out
    }

    @Test fun frequentEntriesKeepTheirSlotUnderLongerInputsOnBothKeyboards() {
        assumeTrue(assetsPresent())
        val missing = ArrayList<String>()
        for ((entry, reading) in FREQUENT_PREFIX_ENTRIES) {
            for (board in boards) {
                val input = board.keys(reading)
                if (exposedEntries(board, input, listOf(entry)).isEmpty()) {
                    missing.add("${board.name} $input no longer exposes $entry")
                    continue
                }
                if (board.decoder.decodeCovered(input, LIMIT).none { it.word == entry }) {
                    missing.add("${board.name} $input drops $entry")
                }
            }
        }
        assertTrue("a frequent dictionary entry must survive the tail gate, wrong: $missing", missing.isEmpty())
    }

    @Test fun typedStraightKeepsEveryDictionaryEntryOnBothKeyboards() {
        assumeTrue(assetsPresent())
        var entries = 0
        val lost = ArrayList<String>()
        for (board in boards) {
            for (reading in ZERO_LOSS_READINGS) {
                val input = board.keys(reading)
                val offered = board.decoder.decodeCovered(input, LIMIT).mapTo(HashSet()) { it.word }
                for (q in 1..input.length) {
                    for (wf in board.dict.exact(input.substring(0, q))) {
                        if (isSingleChar(wf.word)) continue
                        entries++
                        if (wf.word !in offered) lost.add("${board.name} $input drops ${wf.word}@${wf.freq} at q$q")
                    }
                }
            }
        }
        assertTrue("the sweep must cover dictionary entries, saw $entries", entries > 0)
        assertTrue("no dictionary entry may leave the candidate list, lost: $lost", lost.isEmpty())
    }

    @Test fun dictionaryEntriesAndSinglesSurviveOnBothKeyboards() {
        assumeTrue(assetsPresent())
        val lost = ArrayList<String>()
        for ((_, first, second) in LEADING) {
            for (board in boards) {
                val head = board.keys(first)
                for (run in runsFor(board, first, second, "")) {
                    val seen = run.words.toSet()
                    for (wf in board.dict.exact(run.input)) {
                        if (!isSingleChar(wf.word) && wf.word !in seen) lost.add("${run.label} lost entry ${wf.word}")
                    }
                    for (wf in board.dict.exact(head)) {
                        if (isSingleChar(wf.word) && wf.word !in seen) lost.add("${run.label} lost single ${wf.word}")
                    }
                }
            }
        }
        assertTrue("no dictionary entry and no single may be lost, lost: $lost", lost.isEmpty())
    }

    @Test fun prefixEntriesStayOfferedUnderLongerInputsOnBothKeyboards() {
        assumeTrue(assetsPresent())
        val missing = ArrayList<String>()
        for ((word, reading) in PREFIX_ENTRIES) {
            for (board in boards) {
                val input = board.keys(reading)
                if (exposedEntries(board, input, listOf(word)).isEmpty()) {
                    missing.add("${board.name} $input no longer exposes $word")
                    continue
                }
                if (board.decoder.decodeCovered(input, LIMIT).none { it.word == word }) {
                    missing.add("${board.name} $input drops $word")
                }
            }
        }
        assertTrue("a prefix dictionary entry must stay offered, wrong: $missing", missing.isEmpty())
    }

    private companion object {
        const val LIMIT = 30

        val ZERO_LOSS_READINGS = listOf(
            "kuaile", "piliang", "xiezhe", "silianxi", "qijiaren", "lidan", "limin", "lilian",
            "silian", "sili", "qijia",
        )

        val FREQUENT_PREFIX_ENTRIES = listOf("李大" to "lidan", "李密" to "limin", "李丽" to "lilian")

        val PREFIX_ENTRIES = listOf(
            "死练" to "silianxi",
            "思力" to "silianxi",
            "弃家" to "qijiaren",
            "七价" to "qijiaren",
        )

        val LEADING = listOf(
            Triple("快乐", "kuai", "le"),
            Triple("批量", "pi", "liang"),
        )
    }
}
