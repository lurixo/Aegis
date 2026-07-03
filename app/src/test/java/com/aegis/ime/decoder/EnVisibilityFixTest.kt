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

/**
 * `en` must actually OFFER 嗯, not merely be allowed to: typing en never showed 嗯,
 * only ng did — while en and ng should both offer it.
 *
 * Two faults made the en→ng alias invisible:
 *  A. 9-key: the alias was letter-keyed only; the T9 decoder sees digit substrings ("36"), so NO
 *     digit input ever hit it — 嗯 was unreachable on the 9-key without locking the reading first.
 *     Fix: the alias also matches its digit group (toT9(en)="36"), resolved against the LETTER dict
 *     ([PinyinDecoder]'s aliasDict) so only the ng-READING words join, never the whole digit-64
 *     group (米…).
 *  B. 26-key: alias words were appended AFTER every exact word (lattice: [edgesFor]'s exact layer
 *     early-returns at edgeN; lists: rerank ordered exact before alias) — the full dict's 16 en
 *     entries buried 嗯 behind ten freq-1 rares, and an exact("en") ≥ EDGE_N starves it entirely.
 *     Fix: alias edges are floor-guaranteed in the lattice, and the whole-input rerank scores exact
 *     and alias entries together (alias carries ALIAS_PENALTY = a ÷e^3.5 freq discount), so
 *     嗯@434,765 lands right after the common natives 恩/摁 and above the rare tail.
 *
 * Presence is asserted on BOTH layouts; the full-pack config re-runs the same assertions when
 * `AEGIS_FULLDICT_DIR` (and optionally `AEGIS_GRAM`) is set.
 */
class EnVisibilityFixTest {

    private val assets = File("src/main/assets")
    private val lmFile = File(assets, "aegis_lm.bin")

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    private fun letterDecoder(dict: BinaryDict, jianpin: BinaryDict?, gram: OctagramReader?) =
        PinyinDecoder(dict, CharBigramLM.fromFile(lmFile), initialsDict = jianpin, octagram = gram)

    // Mirrors DictEngine's T9 wiring: the letter dict resolves the alias readings.
    private fun t9Decoder(t9: BinaryDict, letter: BinaryDict, gram: OctagramReader?) =
        PinyinDecoder(t9, CharBigramLM.fromFile(lmFile), octagram = gram, aliasDict = letter)

    /** The presence matrix for one (letter dict, t9 dict) config — both layouts, both drill paths. */
    private fun assertEnOffers嗯(letter: BinaryDict, t9: BinaryDict, jianpin: BinaryDict?, gram: OctagramReader?, cfg: String) {
        val d = letterDecoder(letter, jianpin, gram)
        val d9 = t9Decoder(t9, letter, gram)
        val bad = ArrayList<String>()

        fun checkStrip(tag: String, cands: List<Cand>, coverage: Int, nativeTop: String) {
            val words = cands.map { it.word }
            val i = words.indexOf("嗯")
            when {
                i < 0 -> bad.add("$tag: 嗯 missing from candidates ${words.take(12)}")
                cands[i].coveredLen != coverage -> bad.add("$tag: 嗯 coverage ${cands[i].coveredLen} != $coverage")
                // ranking: the borrowed reading must not outrank the input's own top native (恩)
                words.indexOf(nativeTop) !in 0 until i -> bad.add("$tag: 嗯@$i not after native $nativeTop@${words.indexOf(nativeTop)}")
            }
        }
        checkStrip("$cfg 26k decodeCovered(en)", d.decodeCovered("en", 30), 2, "恩")
        checkStrip("$cfg 9k decodeCovered(36)", d9.decodeCovered("36", 30), 2, "恩")
        if ("嗯" !in d.decode("en", 30)) bad.add("$cfg 26k decode(en) misses 嗯")

        // drill paths (homophone grid): presence, after the natives
        if ("嗯" !in d.homophonesAt("en", 0)) bad.add("$cfg 26k homophonesAt(en,0) misses 嗯")
        if ("嗯" !in d9.homophonesAt("36", 0)) bad.add("$cfg 9k homophonesAt(36,0) misses 嗯")

        // ng must keep offering 嗯 exactly as before, and 36 keeps its own readings
        if (d9.decodeCovered("64", 30).none { it.word == "嗯" }) bad.add("$cfg 9k 64(ng) lost 嗯")
        if (d.decodeCovered("ng", 30).none { it.word == "嗯" }) bad.add("$cfg 26k ng lost 嗯")
        if (d9.decodeCovered("36", 30).none { it.word == "佛" }) bad.add("$cfg 9k 36 lost its fo reading (佛)")

        // no flood: the digit alias resolves the ng READING via the letter dict — digit-64 words of
        // other readings (米 = mi) must NOT surface for 36
        if (d9.decodeCovered("36", 30).any { it.word == "米" }) bad.add("$cfg 9k 36 floods with 米 (digit-64 group leaked)")

        // the reading column is untouched: 36 still labels itself en (fo…), never ng
        val col = T9Pinyin.leftColumnReadings("36", 26)
        if ("en" !in col) bad.add("$cfg 9k leftColumnReadings(36) lost en: $col")
        if ("ng" in col) bad.add("$cfg 9k leftColumnReadings(36) gained ng: $col")

        assertTrue("en-visibility failures: $bad", bad.isEmpty())
    }

    // ---------- seed config (bundled assets) ----------
    @Test fun seed_bothLayouts_enOffers嗯() {
        assumeTrue("assets present", File(assets, "aegis_dict.bin").exists() && lmFile.exists())
        assertEnOffers嗯(
            BinaryDict.fromFile(File(assets, "aegis_dict.bin")),
            BinaryDict.fromFile(File(assets, "aegis_t9.bin")),
            BinaryDict.fromFile(File(assets, "aegis_jianpin.bin")),
            gram = null,
            cfg = "seed",
        )
    }

    // ---------- full-pack config (gated) ----------
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

    // ---------- structural: only en→ng exists, on both key spaces ----------
    @Test fun aliasMap_isExactlyEnToNg() {
        assertEquals(mapOf("en" to listOf("ng")), PinyinDecoder.INPUT_ALIASES)
        assertEquals(mapOf("36" to listOf("ng")), PinyinDecoder.T9_INPUT_ALIASES)
    }

    // ---------- structural: an exact layer that fills every EDGE_N slot cannot starve the alias ----------
    // The real full pack holds 16 en entries (< EDGE_N = 20), so fault B's terminal form — 嗯 never
    // reaching the lattice at all — is pinned with a synthetic dict of 25 en-keyed singles.
    @Test fun syntheticFullExactLayer_aliasStillReachesLatticeAndList() {
        assumeTrue("lm asset present (edgeN = EDGE_N needs an LM)", lmFile.exists())
        val rows = ArrayList<EngineFixture.Row>()
        // 恩 + a 24-char freq-1 rare tail (the full dict's real shape, scaled past EDGE_N);
        // 嗯@800 discounted by ALIAS_PENALTY (÷e^3.5 ≈ 24) must land between them.
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
