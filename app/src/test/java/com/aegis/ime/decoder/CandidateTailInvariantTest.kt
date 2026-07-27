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
import org.junit.Assert.assertEquals
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

    private fun rarity(board: Keyboard, input: String, cands: List<Cand>): List<Boolean> {
        val cache = HashMap<Int, Map<String, Double>>()
        return cands.map { cand ->
            if (!isSingleChar(cand.word)) {
                false
            } else {
                val covered = cand.coveredLen.coerceIn(1, input.length)
                val freqs = cache.getOrPut(covered) {
                    board.decoder.homophoneFreqs(input.substring(0, covered)).toMap()
                }
                val freq = freqs[cand.word]
                freq != null && board.decoder.homophoneLayer(cand.word, freq) >= PinyinDecoder.LAYER_RARE
            }
        }
    }

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

    private fun anchorRuns(): List<Run> {
        val out = ArrayList<Run>()
        for (board in boards) {
            for ((first, second) in SPLIT_ANCHORS) {
                for (context in CONTEXTS) out.addAll(runsFor(board, first, second, context))
            }
            for (context in CONTEXTS) {
                val input = board.keys("xie")
                out.add(
                    Run("${board.name} xie ctx=$context typed straight", board, input,
                        board.decoder.decodeCovered(input, LIMIT, emptySet(), context)),
                )
                out.add(
                    Run("${board.name} xie ctx=$context locked", board, input,
                        board.decoder.decodeCoveredAtomic(input, LIMIT, emptySet(), context)),
                )
            }
            for (reading in PREFIX_EXPOSURE) {
                val input = board.keys(reading)
                out.add(
                    Run("${board.name} $reading typed straight", board, input,
                        board.decoder.decodeCovered(input, LIMIT)),
                )
            }
        }
        return out
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

    @Test fun rareCharactersFormTheClosingRunOnBothKeyboards() {
        assumeTrue(assetsPresent())
        var checked = 0
        val runs = anchorRuns()
        for (run in runs) {
            val rare = rarity(run.board, run.input, run.cands)
            val firstRare = rare.indexOfFirst { it }
            if (firstRare < 0) continue
            checked++
            val lastWord = run.cands.indexOfLast { !isSingleChar(it.word) }
            assertTrue(
                "${run.label}: a multi-character candidate follows the rare run at $lastWord, rare starts at $firstRare",
                lastWord < firstRare,
            )
            assertTrue(
                "${run.label}: the rare run must close the list, broken at " +
                    "${rare.drop(firstRare).indexOfFirst { !it } + firstRare}",
                rare.drop(firstRare).all { it },
            )
        }
        assertEquals("every anchor run must carry a rare closing run", runs.size, checked)
    }

    @Test fun assembledCombinationsStayInTheListOnBothKeyboards() {
        assumeTrue(assetsPresent())
        val offeredOn = HashMap<String, MutableSet<String>>()
        for (run in anchorRuns()) {
            for (word in ASSEMBLED) if (word in run.words) offeredOn.getOrPut(word) { HashSet() }.add(run.board.name)
        }
        assertEquals(
            "no assembled combination may leave the list, absent: ${ASSEMBLED - offeredOn.keys}",
            ASSEMBLED.toSet(), offeredOn.keys,
        )
        assertEquals(
            "the assembled anchors must be exercised on both keyboards",
            boards.map { it.name }.toSet(), offeredOn.values.flatten().toSet(),
        )
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

    @Test fun supportedSentencesKeepTheirSlotOnBothKeyboards() {
        assumeTrue(assetsPresent())
        val missing = ArrayList<String>()
        for ((sentence, reading) in SUPPORTED) {
            for (board in boards) {
                val input = board.keys(reading)
                val cands = board.decoder.decodeCoveredAtomic(input, LIMIT)
                val at = cands.indexOfFirst { it.word == sentence }
                if (at < 0) {
                    missing.add("${board.name} $input drops $sentence")
                    continue
                }
                val rare = rarity(board, input, cands)
                val firstRare = rare.indexOfFirst { it }
                if (firstRare in 0 until at) missing.add("${board.name} $input sinks $sentence below the rare run")
            }
        }
        assertTrue("supported sentences must stay ahead of the rare run, wrong: $missing", missing.isEmpty())
    }

    @Test fun theLeadingSentenceKeepsTheFirstSlotOnBothKeyboards() {
        assumeTrue(assetsPresent())
        for ((leading, first, second) in LEADING) {
            for (board in boards) {
                for (run in runsFor(board, first, second, "")) {
                    if (run.label.endsWith("typed straight")) continue
                    assertEquals("${run.label} must lead with $leading", leading, run.words.first())
                }
            }
        }
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

        val CONTEXTS = listOf("", "他说")

        val SPLIT_ANCHORS = listOf("kuai" to "le", "pi" to "liang", "xie" to "zhe")

        val PREFIX_EXPOSURE = listOf("silianxi", "qijiaren")

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

        val ASSEMBLED = listOf("块了", "筷了", "脍了", "哙了", "快勒", "皮两", "皮量")

        val SUPPORTED = listOf(
            "伤害很大" to "shanghaihenda",
            "去超市买东西" to "quchaoshimaidongxi",
        )

        val LEADING = listOf(
            Triple("快乐", "kuai", "le"),
            Triple("批量", "pi", "liang"),
        )
    }
}
