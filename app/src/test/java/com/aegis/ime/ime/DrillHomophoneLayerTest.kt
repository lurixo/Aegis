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

package com.aegis.ime.ime

import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrillHomophoneLayerTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val assets = FullDictTestAssets.directory

    private val dictFile = File(assets, FullDictTestAssets.DICT)
    private val t9File = File(assets, FullDictTestAssets.T9)
    private val lmFile = File(assets, FullDictTestAssets.LM)
    private val jianpinFile = File(assets, FullDictTestAssets.JIANPIN)

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }

    private class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private fun assumeAssets() =
        assumeTrue("full dictionary assets present", FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))

    private fun engine() = DictEngine(
        BinaryDict.fromFile(dictFile),
        BinaryDict.fromFile(t9File),
        CharBigramLM.fromFile(lmFile),
        initialsDict = BinaryDict.fromFile(jianpinFile),
    )

    private fun controller() =
        KeyboardController(Host(), engine()).apply { attachView(InputView(ctx)) }

    private fun drilled(layout: KeyAction, reading: String): List<String> {
        val c = controller()
        c.onKey(Key("", action = layout))
        val typed = if (layout == KeyAction.SWITCH_NINE) T9Pinyin.toT9(reading) else reading
        typed.forEach { c.onKey(Key(it.toString(), output = it.toString())) }
        val index = c.expandedReadings().indexOf(reading)
        assertTrue("$reading must be lockable on $layout, was ${c.expandedReadings()}", index >= 0)
        c.onPickReadingIndex(index)
        c.onPickReadingIndex(c.expandedReadings().indexOf(reading))
        assertTrue("$layout $reading must open the drill grid", c.drilledSyllableForTest() >= 0)
        return c.candidateWords()
    }

    private fun bothKeyboards(reading: String): List<Pair<String, List<String>>> {
        val nine = drilled(KeyAction.SWITCH_NINE, reading)
        val alpha = drilled(KeyAction.SWITCH_ALPHA, reading)
        assertEquals("both keyboards must drill the same $reading grid", alpha, nine)
        return listOf("9-key" to nine, "26-key" to alpha)
    }

    private fun rank(grid: List<String>, word: String): Int {
        val at = grid.indexOf(word)
        assertTrue("$word must stay reachable in the drill grid", at >= 0)
        return at
    }

    private fun codePoint(word: String) = word.codePointAt(0)

    private fun isCoreIdeograph(word: String) =
        word.codePointCount(0, word.length) == 1 && codePoint(word) in 0x4E00..0x9FFF

    private fun frequencies(key: String): Map<String, Int> =
        dict.exact(key).filter { it.word.codePointCount(0, it.word.length) == 1 }
            .associate { it.word to it.freq }

    private val commonAnchors = mapOf(
        "xie" to listOf("写", "些", "谢", "卸", "叶"),
        "pi" to listOf("皮", "批", "匹", "坏"),
        "bang" to listOf("帮", "棒", "蚌"),
    )

    private val nonSimplifiedForms = listOf("脇", "缷", "冩", "擕", "爕")

    @Test fun everyHomophoneStaysReachableOnBothKeyboards() {
        assumeAssets()
        val decoder = PinyinDecoder(BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile))
        for (reading in commonAnchors.keys) {
            val supplied = decoder.homophoneFreqs(reading).map { it.first }
            for ((layout, grid) in bothKeyboards(reading)) {
                assertEquals("$layout $reading drill must not drop a character", supplied.size, grid.size)
                assertEquals("$layout $reading drill must reorder, never filter", supplied.toSet(), grid.toSet())
            }
        }
    }

    @Test fun extensionAreaFormsNeverPrecedeCommonSimplifiedCharacters() {
        assumeAssets()
        for ((reading, anchors) in commonAnchors) {
            val freq = frequencies(reading)
            for ((layout, grid) in bothKeyboards(reading)) {
                val lastAnchor = anchors.maxOf { rank(grid, it) }
                val early = grid.take(lastAnchor).filter { !isCoreIdeograph(it) && (freq[it] ?: 0) > 1 }
                assertEquals(
                    "$layout $reading drill puts extension-area forms among the common simplified run: $early",
                    emptyList<String>(),
                    early,
                )
            }
        }
    }

    @Test fun nonSimplifiedFormsNeverPrecedeCommonSimplifiedCharacters() {
        assumeAssets()
        for ((layout, grid) in bothKeyboards("xie")) {
            val lastAnchor = commonAnchors.getValue("xie").maxOf { rank(grid, it) }
            for (variant in nonSimplifiedForms) {
                assertTrue(
                    "$layout xie drill must rank $variant after every common simplified form",
                    rank(grid, variant) > lastAnchor,
                )
            }
        }
    }

    @Test fun theInjectionWallStaysBehindEveryAttestedCharacter() {
        assumeAssets()
        for (reading in commonAnchors.keys) {
            val freq = frequencies(reading)
            for ((layout, grid) in bothKeyboards(reading)) {
                val firstWall = grid.indexOfFirst { (freq[it] ?: 0) in 1..1 }
                if (firstWall < 0) continue
                val strays = grid.drop(firstWall).filter { (freq[it] ?: 0) > 1 }
                assertEquals(
                    "$layout $reading drill mixes attested characters into the injected wall: $strays",
                    emptyList<String>(),
                    strays,
                )
                for (anchor in commonAnchors.getValue(reading)) {
                    assertTrue("$layout $reading drill must keep $anchor ahead of the wall", rank(grid, anchor) < firstWall)
                }
            }
        }
    }

    @Test fun commonCharactersOutrankExtensionAreaFormsOnEverySyllableKey() {
        assumeAssets()
        val t9Dict = BinaryDict.fromFile(t9File)
        val lm = CharBigramLM.fromFile(lmFile)
        val keys = T9Pinyin.SYLLABLES.sorted()
        val mismatches = ArrayList<String>()
        for ((layout, source, decoder) in listOf(
            Triple("26-key", dict, PinyinDecoder(dict, lm)),
            Triple("9-key", t9Dict, PinyinDecoder(t9Dict, lm, aliasDict = dict)),
        )) {
            val readings = if (layout == "26-key") keys else keys.map { T9Pinyin.toT9(it) }.distinct()
            val entries = readings.associateWith { key ->
                source.exact(key).filter { it.word.codePointCount(0, it.word.length) == 1 }
                    .associate { it.word to it.freq }
            }
            val everywhere = HashMap<String, Int>()
            for (perKey in entries.values) for ((word, freq) in perKey) {
                if (freq > (everywhere[word] ?: 0)) everywhere[word] = freq
            }
            for (key in readings) {
                val freq = entries.getValue(key)
                val grid = decoder.homophonesOf(key)
                val firstExtension = grid.indexOfFirst { !isCoreIdeograph(it) && (freq[it] ?: 0) > 1 }
                if (firstExtension < 0) continue
                val late = grid.drop(firstExtension).filter {
                    isCoreIdeograph(it) && (freq[it] ?: 0) > 1 &&
                        (everywhere[it] ?: 0) >= PinyinDecoder.ORDERING_COMMON_FREQ
                }
                if (late.isNotEmpty()) mismatches.add("$layout $key: ${late.take(4)} behind ${grid[firstExtension]}")
            }
        }
        assertEquals(
            "extension-area forms outrank common simplified characters: ${mismatches.take(8)}",
            emptyList<String>(),
            mismatches,
        )
    }
}
