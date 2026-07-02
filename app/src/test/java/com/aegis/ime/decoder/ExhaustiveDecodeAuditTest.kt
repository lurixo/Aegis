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

class ExhaustiveDecodeAuditTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val jianpinFile = File("src/main/assets/aegis_jianpin.bin")

    private fun letterDecoder(): PinyinDecoder {
        val initials = if (jianpinFile.exists()) BinaryDict.fromFile(jianpinFile) else null
        return PinyinDecoder(BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile), initialsDict = initials)
    }

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1
    private fun dictSingles(key: String): Set<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()
    private fun allSingles(cands: List<Cand>): Set<String> =
        cands.filter { isSingleChar(it.word) }.map { it.word }.toSet()

    private fun sample(s: Collection<String>, n: Int = 8): String =
        s.take(n).joinToString(" ") + if (s.size > n) " …(${s.size})" else ""

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        val set = f.get(T9Pinyin) as Set<String>
        return set.toList()
    }

    private data class Fail(
        val input: String, val layout: String, val inv: String,
        val expectedReading: String, val shownReading: String,
        val expectedChars: String, val shownChars: String, val detail: String,
    )

    private fun outDir(): File {
        val p = System.getenv("AEGIS_AUDIT_DIR") ?: System.getProperty("aegis.audit.dir") ?: "build/decode-audit"
        val d = File(p); d.mkdirs(); return d
    }
    private fun fullEnabled(): Boolean =
        (System.getenv("AEGIS_AUDIT_FULL") ?: System.getProperty("aegis.audit.full")) == "1"

    private fun writeTsv(file: File, fails: List<Fail>) {
        file.bufferedWriter().use { w ->
            w.write("input\tlayout\tinvariant\texpectedReading\tshownReading\texpectedCharsSample\tshownCharsSample\tdetail\n")
            for (f in fails) w.write(
                "${f.input}\t${f.layout}\t${f.inv}\t${f.expectedReading}\t${f.shownReading}\t${f.expectedChars}\t${f.shownChars}\t${f.detail}\n"
            )
        }
    }

    private fun sweepN1(d: PinyinDecoder, syls: List<String>): List<Fail> {
        val fails = ArrayList<Fail>()
        for (s in syls) {
            val oracle = dictSingles(s)
            val digits = T9Pinyin.toT9(s)

            val seg26 = d.syllables(s).map { it.reading }
            if (seg26 != listOf(s)) {
                fails += Fail(s, "26key", "I1", s, seg26.joinToString("+"),
                    sample(oracle), "-", "syllables(S) mis-segments")
            }
            val col9 = T9Pinyin.leftColumnReadings(digits, 26)
            val lock = T9Pinyin.lockFirstReading(digits, s)
            if (s !in col9) {
                fails += Fail(s, "9key", "I1", s, sample(col9),
                    sample(oracle), "-", "leftColumnReadings(toT9(S)) omits S")
            }
            if (lock == null || lock.display != s) {
                fails += Fail(s, "9key", "I1", s, lock?.display ?: "<null>",
                    sample(oracle), "-", "lockFirstReading(toT9(S),S).display != S")
            }

            val homo = d.homophonesAt(s, 0).toSet()
            val homoLeak = homo - oracle
            if (homoLeak.isNotEmpty()) {
                fails += Fail(s, "26key", "I2", s, seg26.firstOrNull() ?: "-",
                    sample(oracle), sample(homoLeak), "homophonesAt(S,0) has chars not reading S")
            }
            if (oracle.isNotEmpty() && homo.isEmpty()) {
                fails += Fail(s, "26key", "I2", s, seg26.firstOrNull() ?: "-",
                    sample(oracle), "<empty>", "homophonesAt(S,0) empty though dict.exact(S) non-empty")
            }

            val atom26 = allSingles(d.decodeCoveredAtomic(s, 30))
            val leak26 = atom26 - oracle
            if (leak26.isNotEmpty()) {
                fails += Fail(s, "26key", "I3", s, seg26.joinToString("+"),
                    sample(oracle), sample(leak26), "decodeCoveredAtomic(S) singles not reading S")
            }
            if (lock != null) {
                val lockedSingles = allSingles(d.decodeCoveredAtomic(lock.letters, 30))
                val leak9 = lockedSingles - oracle
                if (leak9.isNotEmpty()) {
                    fails += Fail(s, "9key", "I3", s, lock.display,
                        sample(oracle), sample(leak9),
                        "9-key lock '${s}' (letters='${lock.letters}') yields chars not reading S")
                }
            }
        }
        return fails
    }

    @Test fun exhaustiveN1_bothLayouts_writesReport() {
        assumeTrue("assets present", dictFile.exists() && lmFile.exists() && t9File.exists())
        val syls = runtimeSyllables()
        assertTrue("runtime SYLLABLES set looks like ~415 (drift guard): ${syls.size}",
            syls.size in 400..430 && syls.isNotEmpty())

        val d = letterDecoder()
        val fails = sweepN1(d, syls)

        writeTsv(File(outDir(), "levelA_n1.tsv"), fails.sortedWith(compareBy({ it.inv }, { it.layout }, { it.input })))
        val byInvLayout = fails.groupingBy { it.inv to it.layout }.eachCount()
        val failedInputs = fails.map { it.input }.toSet()
        File(outDir(), "levelA_n1_summary.txt").writeText(buildString {
            appendLine("Level A — n=1 exhaustive syllable audit")
            appendLine("syllables tested: ${syls.size}")
            appendLine("distinct offending syllables: ${failedInputs.size}")
            appendLine("total invariant violations: ${fails.size}")
            appendLine("per invariant×layout:")
            byInvLayout.toSortedMap(compareBy { it.first + it.second }).forEach { (k, v) ->
                appendLine("  ${k.first} ${k.second}: $v")
            }
        })

        assertTrue("audit must flag deng I1 on 26-key (reading label shows de, not deng)",
            fails.any { it.input == "deng" && it.layout == "26key" && it.inv == "I1" })
        assertTrue("audit must flag deng I3 on 9-key (locking deng yields de-chars)",
            fails.any { it.input == "deng" && it.layout == "9key" && it.inv == "I3" })
        assertTrue("report written", File(outDir(), "levelA_n1.tsv").length() > 0)
    }

    @Test fun exhaustiveN2_allPairs_writesReport() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue("assets present", dictFile.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val d = letterDecoder()
        val fails = ArrayList<Fail>()
        var done = 0
        val total = syls.size.toLong() * syls.size
        for (s1 in syls) {
            val fd1 = T9Pinyin.toT9(s1)
            for (s2 in syls) {
                val input = s1 + s2
                val seg = d.syllables(input).map { it.reading }
                if (seg != listOf(s1, s2)) {
                    fails += Fail(input, "26key", "I1", "$s1+$s2", seg.joinToString("+"),
                        "-", "-", "pair mis-segments")
                }
                val lock = T9Pinyin.lockFirstReading(fd1 + T9Pinyin.toT9(s2), s1)
                if (lock == null || !lock.display.startsWith(s1)) {
                    fails += Fail(input, "9key", "I1", s1, lock?.display ?: "<null>",
                        "-", "-", "lockFirstReading first label != s1 for pair")
                }
            }
            done += syls.size
            if (s1 == syls[syls.size / 4] || s1 == syls[syls.size / 2] || s1 == syls[3 * syls.size / 4]) {
                println("[audit n2] progress ~${done}/${total}")
            }
        }
        writeTsv(File(outDir(), "levelA_n2.tsv"), fails)
        val distinct = fails.map { it.input }.toSet().size
        File(outDir(), "levelA_n2_summary.txt").writeText(
            "Level A — n=2 all ordered pairs\npairs tested: $total\noffending pairs: $distinct\ntotal violations: ${fails.size}\n" +
                "I1 26key: ${fails.count { it.inv == "I1" && it.layout == "26key" }}\n" +
                "I1 9key:  ${fails.count { it.inv == "I1" && it.layout == "9key" }}\n"
        )
        assertTrue("n=2 report written", File(outDir(), "levelA_n2.tsv").exists())
    }

    @Test fun coveringN3_writesReport() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue("assets present", dictFile.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val d = letterDecoder()
        val fails = ArrayList<Fail>()
        for (i in syls.indices) {
            val a = syls[i]
            for (j in syls.indices) {
                val b = syls[j]
                val c = syls[(i + j) % syls.size]
                val input = a + b + c
                val seg = d.syllables(input).map { it.reading }
                if (seg != listOf(a, b, c)) {
                    fails += Fail(input, "26key", "I1", "$a+$b+$c", seg.joinToString("+"),
                        "-", "-", "triple mis-segments")
                }
            }
        }
        writeTsv(File(outDir(), "levelA_n3.tsv"), fails)
        File(outDir(), "levelA_n3_summary.txt").writeText(
            "Level A — n=3 complete-covering sweep (~415² triples; n>=4 NOT enumerated)\n" +
                "triples tested: ${syls.size.toLong() * syls.size}\noffending triples: ${fails.map { it.input }.toSet().size}\n" +
                "total violations: ${fails.size}\n"
        )
        assertTrue("n=3 report written", File(outDir(), "levelA_n3.tsv").exists())
    }
}
