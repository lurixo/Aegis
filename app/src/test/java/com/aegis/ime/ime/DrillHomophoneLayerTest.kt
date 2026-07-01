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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrillHomophoneLayerTest {

    private val assets = FullDictTestAssets.directory

    private val dictFile = File(assets, FullDictTestAssets.DICT)
    private val t9File = File(assets, FullDictTestAssets.T9)
    private val lmFile = File(assets, FullDictTestAssets.LM)
    private val jianpinFile = File(assets, FullDictTestAssets.JIANPIN)

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }

    private fun assumeAssets() =
        assumeTrue("full dictionary assets present", FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))

    private fun codePoint(word: String) = word.codePointAt(0)

    private fun isCoreIdeograph(word: String) =
        word.codePointCount(0, word.length) == 1 && codePoint(word) in 0x4E00..0x9FFF

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
