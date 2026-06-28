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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

internal object FullDictTestAssets {
    const val DICT = "aegis_dict.bin"
    const val T9 = "aegis_t9.bin"
    const val LM = "aegis_lm.bin"
    const val JIANPIN = "aegis_jianpin.bin"

    val productionNames = listOf(DICT, T9, LM, JIANPIN)
    private val configuredDirectory = System.getenv("AEGIS_FULLDICT_DIR")
        ?.let { File(it) }
    val directory: File = configuredDirectory ?: File("src/main/assets")

    fun file(name: String): File = File(directory, name)

    fun available(vararg required: File): Boolean =
        available(required.map { it.name }, configuredDirectory != null) { file(it).exists() }

    fun available(
        required: Collection<String>,
        configured: Boolean,
        exists: (String) -> Boolean,
    ): Boolean {
        if (configured) {
            val missing = productionNames.filterNot(exists)
            if (missing.isNotEmpty()) {
                throw AssertionError("AEGIS_FULLDICT_DIR missing production assets: ${missing.joinToString()}")
            }
        }
        return required.all(exists)
    }
}

class LossFixTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private fun letterDecoder(): PinyinDecoder {
        assumeTrue(
            "26-key dict + LM + jianpin assets present",
            FullDictTestAssets.available(dictFile, lmFile, jianpinFile),
        )
        return PinyinDecoder(
            BinaryDict.fromFile(dictFile),
            CharBigramLM.fromFile(lmFile),
            initialsDict = BinaryDict.fromFile(jianpinFile),
        )
    }

    private fun t9Decoder(): PinyinDecoder {
        assumeTrue("T9 dict + LM assets present", FullDictTestAssets.available(t9File, lmFile))
        return PinyinDecoder(BinaryDict.fromFile(t9File), CharBigramLM.fromFile(lmFile))
    }

    private fun isSingleChar(word: String): Boolean = word.codePointCount(0, word.length) == 1

    private fun dictSingles(dict: BinaryDict, key: String): Set<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()

    private fun allSingles(cands: List<Cand>): Set<String> =
        cands.filter { isSingleChar(it.word) }.map { it.word }.toSet()

    @Test fun explicitFullDictDirectoryFailsClosedForEveryMissingAsset() {
        for (missing in FullDictTestAssets.productionNames) {
            val error = assertThrows(AssertionError::class.java) {
                FullDictTestAssets.available(
                    FullDictTestAssets.productionNames,
                    configured = true,
                ) { it != missing }
            }
            assertTrue(error.message.orEmpty().contains(missing))
        }
        assertFalse(
            FullDictTestAssets.available(
                FullDictTestAssets.productionNames,
                configured = false,
            ) { false },
        )
    }


    @Test fun ambiguousLeadingSyllablesAllReachable() {
        assumeTrue("dict asset present", FullDictTestAssets.available(dictFile))
        val dict = BinaryDict.fromFile(dictFile)
        val d = letterDecoder()

        val xian = allSingles(d.decodeCovered("xian", 30))
        assertTrue("all xian 同音字 reachable", xian.containsAll(dictSingles(dict, "xian")))
        assertTrue("all xi 同音字 reachable (the xi'an reading)", xian.containsAll(dictSingles(dict, "xi")))
        assertTrue("现 reachable", "现" in xian); assertTrue("西 reachable — lost if keyed only off the split", "西" in xian)
        assertTrue("西 tagged coveredLen=2", d.decodeCovered("xian", 30).any { it.word == "西" && it.coveredLen == 2 })

        val fangan = allSingles(d.decodeCovered("fangan", 30))
        assertTrue("all fang 同音字 reachable", fangan.containsAll(dictSingles(dict, "fang")))
        assertTrue("all fan 同音字 reachable (反感)", fangan.containsAll(dictSingles(dict, "fan")))
        assertTrue("all fa 同音字 reachable", fangan.containsAll(dictSingles(dict, "fa")))
        for (c in listOf("方", "反", "发")) assertTrue("$c reachable", c in fangan)
    }


    @Test fun singleCharLayerIsUncapped_mutationGuard() {
        assumeTrue("dict asset present", FullDictTestAssets.available(dictFile))
        val dict = BinaryDict.fromFile(dictFile)
        val he = dictSingles(dict, "he")
        assumeTrue("full dict present", he.size > 8)
        val got = allSingles(letterDecoder().decodeCovered("heshui", 30))
        assertTrue("must exceed the old PREFIX_PER_LEN=8 cap (mutation guard)", got.count { it in he } > 8)
        assertTrue("must contain the dict's ENTIRE he set — a re-imposed cap fails here", got.containsAll(he))
    }


    @Test fun t9WordLayerIsUncapped_mutationGuard() {
        assumeTrue("t9 asset present", FullDictTestAssets.available(t9File))
        val t9 = BinaryDict.fromFile(t9File)
        val d = t9Decoder()
        val digits = "943943"
        val words = t9.exact(digits).filterNot { isSingleChar(it.word) }.map { it.word }.toSet()
        assumeTrue("full dict present", words.size > 8)
        val shown = d.decodeCovered(digits, 30).map { it.word }
        val missing = words - shown.toSet()
        assertTrue("every word the dict holds for '$digits' is reachable; missing $missing", missing.isEmpty())
        assertTrue("写者 reachable", "写者" in shown)
        val firstSingleIdx = shown.indexOfFirst { isSingleChar(it) }
        for (w in words) assertTrue(
            "$w precedes the appended 单字 layer (at ${shown.indexOf(w)}, singles start at $firstSingleIdx)",
            shown.indexOf(w) in 0 until firstSingleIdx,
        )
    }

    @Test fun letterWordLayerIsUncapped_mutationGuard() {
        assumeTrue("dict asset present", FullDictTestAssets.available(dictFile))
        val dict = BinaryDict.fromFile(dictFile)
        val d = letterDecoder()
        val key = "jishi"
        val words = dict.exact(key).filterNot { isSingleChar(it.word) }.map { it.word }.toSet()
        assumeTrue("full dict present", words.size > 8)
        val shown = d.decodeCovered(key, 30).map { it.word }
        val missing = words - shown.toSet()
        assertTrue("every word the dict holds for '$key' is reachable; missing $missing", missing.isEmpty())
        val firstSingleIdx = shown.indexOfFirst { isSingleChar(it) }
        for (w in words) assertTrue(
            "$w precedes the appended 单字 layer (at ${shown.indexOf(w)}, singles start at $firstSingleIdx)",
            shown.indexOf(w) in 0 until firstSingleIdx,
        )
    }

    @Test fun robustOnEmptyAndNonPinyin() {
        val d = letterDecoder()
        assertTrue(d.syllables("").isEmpty())
        assertTrue(d.homophonesAt("", 0).isEmpty())
        assertTrue(d.homophonesAt("he", 5).isEmpty())
        assertTrue("a non-pinyin run has no syllables", d.syllables("zzz").isEmpty())
        assertFalse("decoding a non-pinyin run must not throw", d.decodeCovered("zzz", 30).any { it.word == "他" })
    }
}
