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
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Does the wanxiang octagram improve top-1 over the bundled char-bigram? (.gram via AEGIS_GRAM.) */
class OctagramEvalTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val evalFile = File("src/test/resources/eval_set.tsv")
    private val gram = System.getenv("AEGIS_GRAM")?.let { File(it) }

    @Test
    fun octagramRanking() {
        assumeTrue(dictFile.exists() && lmFile.exists() && evalFile.exists() && gram != null && gram!!.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val lm = CharBigramLM.fromFile(lmFile)
        val oct = OctagramReader.fromFile(gram!!)
        val pairs = evalFile.readLines().mapNotNull {
            val t = it.indexOf('\t'); if (t <= 0) null else it.substring(0, t) to it.substring(t + 1)
        }

        fun top1(decoder: PinyinDecoder): Double {
            var hit = 0
            for ((py, expected) in pairs) if (decoder.decode(py, 5).firstOrNull() == expected) hit++
            return 100.0 * hit / pairs.size
        }

        val sb = StringBuilder("octagram eval — ${pairs.size} sentences (top-1)\n")
        sb.append(String.format("char-bigram only      : %.1f%%%n", top1(PinyinDecoder(dict, lm))))
        for (w in listOf(0.1, 0.3, 0.5, 1.0)) {
            val d = PinyinDecoder(dict, lm, octagram = oct, octagramWeight = w)
            sb.append(String.format("+octagram (w=%.1f)     : %.1f%%%n", w, top1(d)))
        }
        println(sb)
        File("build/octagram_eval.txt").apply { parentFile.mkdirs(); writeText(sb.toString()) }
    }
}
