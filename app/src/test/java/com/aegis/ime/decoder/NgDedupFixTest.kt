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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NgDedupFixTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val jianpinFile = File("src/main/assets/aegis_jianpin.bin")

    private fun decoder(): PinyinDecoder {
        assertTrue("assets present", dictFile.exists() && lmFile.exists() && jianpinFile.exists())
        return PinyinDecoder(
            BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile),
            initialsDict = BinaryDict.fromFile(jianpinFile),
        )
    }

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }
    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1
    private fun singlesOf(d: BinaryDict, key: String): Set<String> =
        d.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        val syls = (f.get(T9Pinyin) as Set<String>).toList().sorted()
        assertTrue("runtime SYLLABLES ~415 (drift guard): ${syls.size}", syls.size in 400..430)
        return syls
    }

    private val gInitials: List<String> by lazy { runtimeSyllables().filter { it.startsWith("g") } }

    @Test fun nPlusGInitial_offersNCoveringCandidate() {
        val d = decoder()
        val nChars = singlesOf(dict, "n")
        assertTrue("dict has n singles", nChars.isNotEmpty())
        assertTrue("expected the 19 g-initial syllables, got ${gInitials.size}", gInitials.size == 19)
        val bad = ArrayList<String>()
        for (g in gInitials) {
            val input = "n$g"
            val covers = d.decodeCovered(input, 30).filter { it.coveredLen == 1 && isSingleChar(it.word) }
            when {
                covers.isEmpty() -> bad.add("$input: no candidate covering exactly 'n'")
                !covers.all { it.word in nChars } ->
                    bad.add("$input: n-covering chars not reading n: ${covers.map { it.word }}")
                covers.none { it.word == "嗯" } ->
                    bad.add("$input: 嗯 missing from coverage-1 candidates: ${covers.map { it.word }}")
                d.syllables(g).map { it.reading } != listOf(g) ->
                    bad.add("$input: remainder '$g' mis-segments")
            }
        }
        assertTrue("n+g* pairs still broken: $bad", bad.isEmpty())
    }

    @Test fun singleSyllableCandidates_noWordAtMultipleCoverages() {
        val d = decoder()
        val offenders = ArrayList<String>()
        for (s in runtimeSyllables()) {
            val perWord = HashMap<String, MutableSet<Int>>()
            for (c in d.decodeCovered(s, 30)) if (isSingleChar(c.word)) {
                perWord.getOrPut(c.word) { HashSet() }.add(c.coveredLen)
            }
            val dup = perWord.filterValues { it.size > 1 }
            if (dup.isNotEmpty()) offenders.add("$s: ${dup.entries.take(3)}")
        }
        assertTrue("single-syllable candidate lists must stay duplicate-free: ${offenders.take(6)}",
            offenders.isEmpty())
    }

    @Test fun dumpN1CandidateLists() {
        val path = System.getenv("AEGIS_DUMP_N1")
        assumeTrue("dump only when AEGIS_DUMP_N1 is set", !path.isNullOrEmpty())
        val d = decoder()
        File(path!!).bufferedWriter().use { w ->
            for (s in runtimeSyllables()) {
                val cands = d.decodeCovered(s, 30).joinToString(" ") { "${it.word}@${it.coveredLen}" }
                w.write("$s\t$cands\n")
            }
        }
        assertTrue(File(path).length() > 0)
    }

    @Test fun fullDictAndGram_nPlusGInitial_targetedCheck() {
        val dir = System.getenv("AEGIS_FULLDICT_DIR")
        assumeTrue("full-dict check only when AEGIS_FULLDICT_DIR is set", !dir.isNullOrEmpty())
        val fDict = File(dir!!, "aegis_dict.bin")
        val fJp = File(dir, "aegis_jianpin.bin")
        assumeTrue("full dict bins present", fDict.exists() && fJp.exists() && lmFile.exists())
        val gramPath = System.getenv("AEGIS_GRAM")
        val gram = if (!gramPath.isNullOrEmpty() && File(gramPath).exists())
            OctagramReader.fromFile(File(gramPath)) else null

        val fullDict = BinaryDict.fromFile(fDict)
        val d = PinyinDecoder(
            fullDict, CharBigramLM.fromFile(lmFile),
            initialsDict = BinaryDict.fromFile(fJp), octagram = gram,
        )
        val nChars = singlesOf(fullDict, "n")
        assertTrue("full dict has n singles", nChars.isNotEmpty())
        val bad = ArrayList<String>()
        val fullGaps = ArrayList<String>()
        for (g in gInitials) {
            val input = "n$g"
            val covers = d.decodeCovered(input, 30).filter { it.coveredLen == 1 && isSingleChar(it.word) }
            if (covers.isEmpty()) bad.add("$input: no n-covering candidate (full dict)")
            else if (!covers.all { it.word in nChars })
                bad.add("$input: full-dict n-covering chars not reading n: ${covers.map { it.word }.take(4)}")
            else if (covers.none { it.word == "嗯" })
                fullGaps.add("$input	嗯-missing-at-cov1	cov1=${covers.map { "${it.word}" }}")
        }
        for (s in runtimeSyllables()) {
            val perWord = HashMap<String, MutableSet<Int>>()
            for (c in d.decodeCovered(s, 30)) if (isSingleChar(c.word)) {
                perWord.getOrPut(c.word) { HashSet() }.add(c.coveredLen)
            }
            val dup = perWord.filterValues { it.size > 1 }
            if (dup.isNotEmpty()) bad.add("$s: full-config duplicate coverages ${dup.entries.take(2)}")
        }
        run {
            val p = System.getenv("AEGIS_AUDIT_DIR") ?: System.getProperty("aegis.audit.dir")
            if (!p.isNullOrEmpty()) {
                File(p).mkdirs()
                File(p, "fullconfig_known_gaps.tsv")
                    .writeText("input\tgap\tdetail\n" + fullGaps.joinToString("\n") + if (fullGaps.isNotEmpty()) "\n" else "")
            }
            if (fullGaps.isNotEmpty()) println("[full-config] gaps recorded: ${fullGaps.size}")
        }
        assertTrue("full-config 嗯-at-coverage-1 gaps must be zero after the rescue: ${fullGaps.take(6)}", fullGaps.isEmpty())
        assertTrue("full-config targeted check failed: ${bad.take(8)}", bad.isEmpty())

        val bloat = ArrayList<String>()
        for (s in listOf("liang", "shuo", "zhuo", "xian", "ng", "n", "deng", "ma")) {
            val perWord = HashMap<String, MutableSet<Int>>()
            for (c in d.decodeCovered(s, 30)) if (isSingleChar(c.word)) {
                perWord.getOrPut(c.word) { HashSet() }.add(c.coveredLen)
            }
            val dup = perWord.filterValues { it.size > 1 }
            if (dup.isNotEmpty()) bloat.add("$s: ${dup.entries.take(3)}")
        }
        val liang = d.decodeCovered("liang", 30).filter { it.word == "俩" }
        if (liang.map { it.coveredLen }.toSet().size > 1) bloat.add("liang re-emits 俩 at two coverages")
        for ((pair, cap) in listOf("nga" to 40, "ngou" to 40)) {
            val cov1 = d.decodeCovered(pair, 40).count { it.coveredLen == 1 }
            if (cov1 > cap) bloat.add("$pair coverage-1 tail exploded: $cov1")
        }
        assertTrue("full-config anti-bloat failed: $bloat", bloat.isEmpty())
    }
}
