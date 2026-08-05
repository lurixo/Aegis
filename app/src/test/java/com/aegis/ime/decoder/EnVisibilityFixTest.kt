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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class EnVisibilityFixTest {

    private val assets = File("src/main/assets")
    private val lmFile = File(assets, "aegis_lm.bin")

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    private fun letterDecoder(dict: BinaryDict, jianpin: BinaryDict?, gram: OctagramReader?) =
        PinyinDecoder(dict, CharBigramLM.fromFile(lmFile), initialsDict = jianpin, octagram = gram)

    private fun t9Decoder(t9: BinaryDict, letter: BinaryDict, gram: OctagramReader?) =
        PinyinDecoder(t9, CharBigramLM.fromFile(lmFile), octagram = gram, aliasDict = letter)

    private fun assertEnOffers嗯(letter: BinaryDict, t9: BinaryDict, jianpin: BinaryDict?, gram: OctagramReader?, cfg: String) {
        assumeTrue("LM present", lmFile.exists())
        val d = letterDecoder(letter, jianpin, gram)
        val d9 = t9Decoder(t9, letter, gram)
        val bad = ArrayList<String>()

        fun checkStrip(tag: String, cands: List<Cand>, coverage: Int, nativeTop: String) {
            val words = cands.map { it.word }
            val i = words.indexOf("嗯")
            when {
                i < 0 -> bad.add("$tag: 嗯 missing from candidates ${words.take(12)}")
                cands[i].coveredLen != coverage -> bad.add("$tag: 嗯 coverage ${cands[i].coveredLen} != $coverage")
                words.indexOf(nativeTop) !in 0 until i -> bad.add("$tag: 嗯@$i not after native $nativeTop@${words.indexOf(nativeTop)}")
            }
        }
        checkStrip("$cfg 26k decodeCovered(en)", d.decodeCovered("en", 30), 2, "恩")
        checkStrip("$cfg 9k decodeCovered(36)", d9.decodeCovered("36", 30), 2, "恩")

        if ("嗯" !in d.homophonesAt("en", 0)) bad.add("$cfg 26k homophonesAt(en,0) misses 嗯")
        if ("嗯" !in d9.homophonesAt("36", 0)) bad.add("$cfg 9k homophonesAt(36,0) misses 嗯")

        if (d9.decodeCovered("64", 30).none { it.word == "嗯" }) bad.add("$cfg 9k 64(ng) lost 嗯")
        if (d.decodeCovered("ng", 30).none { it.word == "嗯" }) bad.add("$cfg 26k ng lost 嗯")
        if (d9.decodeCovered("36", 30).none { it.word == "佛" }) bad.add("$cfg 9k 36 lost its fo reading (佛)")

        if (d9.decodeCovered("36", 30).any { it.word == "米" }) bad.add("$cfg 9k 36 floods with 米 (digit-64 group leaked)")

        val col = T9Pinyin.leftColumnReadings("36", 26)
        if ("en" !in col) bad.add("$cfg 9k leftColumnReadings(36) lost en: $col")
        if ("ng" in col) bad.add("$cfg 9k leftColumnReadings(36) gained ng: $col")

        assertTrue("en-visibility failures: $bad", bad.isEmpty())
    }

    @Test fun assets_bothLayouts_enOffers嗯() {
        assumeTrue("assets present", File(assets, "aegis_dict.bin").exists() && lmFile.exists())
        assertEnOffers嗯(
            BinaryDict.fromFile(File(assets, "aegis_dict.bin")),
            BinaryDict.fromFile(File(assets, "aegis_t9.bin")),
            BinaryDict.fromFile(File(assets, "aegis_jianpin.bin")),
            gram = null,
            cfg = "assets",
        )
    }

    @Test fun fullConfig_bothLayouts_enOffers嗯() {
        val dir = System.getenv("AEGIS_FULLDICT_DIR")
        assumeTrue("full-dict check only when AEGIS_FULLDICT_DIR is set", !dir.isNullOrEmpty())
        val gramPath = System.getenv("AEGIS_GRAM")
        val gram = if (!gramPath.isNullOrEmpty() && File(gramPath).exists())
            OctagramReader.fromFile(File(gramPath)) else null
        assertEnOffers嗯(
            BinaryDict.fromFile(File(dir!!, "aegis_dict.bin")),
            BinaryDict.fromFile(File(dir, "aegis_t9.bin")),
            BinaryDict.fromFile(File(dir, "aegis_jianpin.bin")),
            gram = gram,
            cfg = "full",
        )
    }

    @Test fun aliasMap_isExactlyEnToNg() {
        assertEquals(mapOf("en" to listOf("ng")), PinyinDecoder.INPUT_ALIASES)
        assertEquals(mapOf("36" to listOf("ng")), PinyinDecoder.T9_INPUT_ALIASES)
    }

    @Test fun syntheticFullExactLayer_aliasStillReachesLatticeAndList() {
        assumeTrue("lm asset present (edgeN = EDGE_N needs an LM)", lmFile.exists())
        val rows = ArrayList<EngineFixture.Row>()
        rows.add(EngineFixture.Row("en", "恩", 900))
        for (i in 0 until 24) rows.add(EngineFixture.Row("en", EngineFixture.supplementary(300 + i), 1))
        rows.add(EngineFixture.Row("ng", "嗯", 800))
        val dict = EngineFixture.build(rows)
        val d = PinyinDecoder(dict, CharBigramLM.fromFile(lmFile))

        val m = PinyinDecoder::class.java.getDeclaredMethod("edgesFor", String::class.java)
        m.isAccessible = true
        val edges = (m.invoke(d, "en") as List<*>).map { e ->
            e!!.javaClass.getDeclaredField("word").apply { isAccessible = true }.get(e) as String
        }
        assertTrue("alias word must reach the lattice past a full exact layer (edges=${edges.size})", "嗯" in edges)

        val words = d.decodeCovered("en", 30).map { it.word }
        val i = words.indexOf("嗯")
        assertTrue("嗯 must survive the completion budget: ${words.take(8)}", i >= 0)
        assertTrue("嗯 ranks under the top native but above the freq-1 tail: $i", words.indexOf("恩") < i && i <= 4)
    }
}
