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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Fuzzy-recall: feed each eval sentence as its collapsed/sloppy form (zh→z, ang→an, …) and measure
 * how often the correct sentence is still the top candidate, with the fuzzy index off vs on.
 */
class FuzzyEvalTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val evalFile = File("src/test/resources/eval_set.tsv")

    @Test
    fun fuzzyRecall() {
        assumeTrue(dictFile.exists() && lmFile.exists() && evalFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val lm = CharBigramLM.fromFile(lmFile)

        val pairs = evalFile.readLines().mapNotNull {
            val t = it.indexOf('\t'); if (t <= 0) null else it.substring(0, t) to it.substring(t + 1)
        }
        // only the sentences whose pinyin actually changes under fuzzy collapse
        val relevant = pairs.filter { Fuzzy.normalize(it.first) != it.first }
        assumeTrue("have fuzzy-relevant sentences", relevant.isNotEmpty())

        val off = PinyinDecoder(dict, lm)
        // fuzzy ON = all rules enabled, matched by query-time variant expansion against the exact dict.
        val on = PinyinDecoder(dict, lm, fuzzyRules = Fuzzy.RULES.mapTo(LinkedHashSet()) { it.key })

        // two scenarios: a single fuzzy slip (realistic) and every fuzzy point collapsed (worst case)
        fun recall(perturb: (String) -> String): Pair<Int, Int> {
            var o = 0; var n = 0
            for ((py, expected) in relevant) {
                val sloppy = perturb(py)
                if (off.decode(sloppy, 5).firstOrNull() == expected) o++
                if (on.decode(sloppy, 5).firstOrNull() == expected) n++
            }
            return o to n
        }
        val (singleOff, singleOn) = recall(::oneFuzzySlip)
        val (maxOff, maxOn) = recall(Fuzzy::normalize)
        val total = relevant.size

        // exact-input precision must survive fuzzy being always-on in the app
        var exactOff = 0
        var exactOn = 0
        for ((py, expected) in pairs) {
            if (off.decode(py, 5).firstOrNull() == expected) exactOff++
            if (on.decode(py, 5).firstOrNull() == expected) exactOn++
        }

        val report = buildString {
            append("P8 fuzzy recall — $total fuzzy-relevant sentences\n")
            append("scenario            fuzzy OFF   fuzzy ON\n")
            append(String.format("single slip      %8.1f%% %8.1f%%%n", 100.0 * singleOff / total, 100.0 * singleOn / total))
            append(String.format("all collapsed    %8.1f%% %8.1f%%%n", 100.0 * maxOff / total, 100.0 * maxOn / total))
            append(String.format("exact input      %8.1f%% %8.1f%%  (all ${pairs.size}; precision guard)%n",
                100.0 * exactOff / pairs.size, 100.0 * exactOn / pairs.size))
        }
        println(report)
        File("build/fuzzy_report.txt").apply { parentFile.mkdirs(); writeText(report) }

        assertTrue("fuzzy must not reduce recall", singleOn >= singleOff && maxOn >= maxOff)
        assertTrue("fuzzy must not erode exact-input precision much",
            exactOn >= exactOff - pairs.size / 20) // within ~5 pts
    }

    /** Chinese IME behavior note. */
    private fun oneFuzzySlip(s: String): String {
        val pats = listOf("zh" to "z", "ch" to "c", "sh" to "s", "ang" to "an", "eng" to "en", "ing" to "in")
        var bestIdx = Int.MAX_VALUE
        var best: Pair<String, String>? = null
        for (p in pats) {
            val i = s.indexOf(p.first)
            if (i in 0 until bestIdx) { bestIdx = i; best = p }
        }
        val b = best ?: return s
        return s.substring(0, bestIdx) + b.second + s.substring(bestIdx + b.first.length)
    }
}
