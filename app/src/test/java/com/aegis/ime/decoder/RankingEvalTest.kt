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
import java.io.File

class RankingEvalTest {

    private val dictFile = File(System.getenv("AEGIS_DICT") ?: "src/main/assets/aegis_dict.bin")
    private val lmFile = File(System.getenv("AEGIS_LM") ?: "src/main/assets/aegis_lm.bin")
    private val evalFile = File("src/test/resources/eval_set.tsv")

    @Test
    fun reportRankingQuality() {
        assumeTrue("assets + eval set present", dictFile.exists() && lmFile.exists() && evalFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val lm = CharBigramLM.fromFile(lmFile)

        val pairs = evalFile.readLines()
            .mapNotNull { line ->
                val t = line.indexOf('\t')
                if (t <= 0) null else line.substring(0, t) to line.substring(t + 1)
            }
        assumeTrue("eval set non-empty", pairs.isNotEmpty())

        val configs = listOf(
            "unigram" to null,
            "bigram λ=0.5" to 0.5,
            "bigram λ=1.0" to 1.0,
            "bigram λ=2.0" to 2.0,
            "bigram λ=3.0" to 3.0,
        )

        val sb = StringBuilder()
        sb.append("P5 ranking eval — ${pairs.size} pairs\n")
        sb.append(String.format("%-14s %8s %8s%n", "config", "top1", "top5"))
        var unigramTop1 = 0.0
        var bestBigramTop1 = 0.0
        for ((name, lambda) in configs) {
            val decoder = if (lambda == null) PinyinDecoder(dict) else PinyinDecoder(dict, lm, lambda)
            var top1 = 0
            var top5 = 0
            for ((py, expected) in pairs) {
                val cands = decoder.decode(py, 5)
                if (cands.isNotEmpty() && cands[0] == expected) top1++
                if (cands.contains(expected)) top5++
            }
            val a1 = 100.0 * top1 / pairs.size
            val a5 = 100.0 * top5 / pairs.size
            sb.append(String.format("%-14s %7.1f%% %7.1f%%%n", name, a1, a5))
            if (lambda == null) unigramTop1 = a1 else bestBigramTop1 = maxOf(bestBigramTop1, a1)
        }
        val report = sb.toString()
        println(report)
        File("build/eval_report.txt").apply { parentFile?.mkdirs(); writeText(report) }

        assertTrue("bigram should not hurt top-1", bestBigramTop1 >= unigramTop1 - 0.5)
    }
}
