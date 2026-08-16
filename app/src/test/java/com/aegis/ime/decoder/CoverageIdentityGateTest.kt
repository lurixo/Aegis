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

class CoverageIdentityGateTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }
    private val t9Dict: BinaryDict by lazy { BinaryDict.fromFile(t9File) }
    private val lm: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }

    private val contexts = listOf("", "我", "我们")

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        val syls = (f.get(T9Pinyin) as Set<String>).toList().sorted()
        assertTrue("runtime SYLLABLES set looks like ~415 (drift guard): ${syls.size}", syls.size in 400..430)
        return syls
    }

    private fun letterDecoder() =
        PinyinDecoder(dict, lm, initialsDict = BinaryDict.fromFile(jianpinFile))

    private fun digitDecoder() = PinyinDecoder(t9Dict, lm, aliasDict = dict)

    private fun cutsOf(keys: List<String>): Set<Int> {
        val out = HashSet<Int>()
        var acc = 0
        for (k in 0 until keys.size - 1) { acc += keys[k].length; out.add(acc) }
        return out
    }

    private fun probes(): List<Triple<String, List<String>, String>> {
        val syls = runtimeSyllables()
        val tails = listOf("shi", "de", "hao", "jian", "zhong", "guo")
        val out = ArrayList<Triple<String, List<String>, String>>()
        for (s in syls) {
            val tail = tails[s.length % tails.size]
            for (context in contexts) {
                out.add(Triple("free", listOf(s), context))
                out.add(Triple("free", listOf(s, tail), context))
                out.add(Triple("free", listOf(s, "guo"), context))
                out.add(Triple("locked", listOf(s, tail), context))
                out.add(Triple("locked", listOf(s, "de", "shi"), context))
            }
        }
        return out
    }

    private val letterDecoderCache: PinyinDecoder by lazy { letterDecoder() }
    private val digitDecoderCache: PinyinDecoder by lazy { digitDecoder() }

    private fun decode(letters: Boolean, mode: String, syls: List<String>, context: String): List<Cand> {
        val decoder = if (letters) letterDecoderCache else digitDecoderCache
        val keys = if (letters) syls else syls.map { T9Pinyin.toT9(it) }
        val input = keys.joinToString("")
        return if (mode == "free") decoder.decodeCovered(input, 30, emptySet(), context)
        else decoder.decodeCoveredAtomic(input, 30, cutsOf(keys), context)
    }

    private fun groupsOf(): Sequence<Pair<String, List<Cand>>> = sequence {
        for ((mode, syls, context) in probes()) {
            for (letters in listOf(true, false)) {
                val keys = if (letters) syls else syls.map { T9Pinyin.toT9(it) }
                val layout = if (letters) "26" else "9"
                val head = "$layout\t$mode\t${keys.joinToString("")}\t${if (context.isEmpty()) "-" else context}"
                yield(head to decode(letters, mode, syls, context))
            }
        }
    }

    private fun rowsOf(): Sequence<String> = sequence {
        for ((head, cands) in groupsOf()) for (c in cands) yield("$head\t${c.word}\t${c.coveredLen}")
    }

    private fun dumpPath(): String? = System.getenv("AEGIS_COVERAGE_DUMP")

    private fun referencePath(): String? = System.getenv("AEGIS_COVERAGE_BASELINE")

    @Test fun writeCoverageDumpWhenAsked() {
        val target = dumpPath()
        assumeTrue("set AEGIS_COVERAGE_DUMP to write the reference dump", target != null)
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        File(target!!).bufferedWriter().use { w ->
            for (row in rowsOf()) { w.write(row); w.write("\n") }
        }
        assertTrue("coverage dump written", File(target).length() > 0)
    }

    @Test fun everyCandidateKeepsTheKeyCountItAteInTheBaseline() {
        val reference = referencePath()
        assumeTrue("set AEGIS_COVERAGE_BASELINE to the 7907381b dump", reference != null)
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        val drifted = ArrayList<String>()
        val added = ArrayList<String>()
        val dropped = ArrayList<String>()
        var probesRead = 0
        File(reference!!).bufferedReader().use { reader ->
            var pending: String? = reader.readLine()
            for ((head, cands) in groupsOf()) {
                val was = HashMap<String, String>()
                while (pending != null && pending!!.startsWith("$head\t")) {
                    val row = pending!!
                    val at = row.lastIndexOf('\t')
                    was[row.substring(row.lastIndexOf('\t', at - 1) + 1, at)] = row.substring(at + 1)
                    pending = reader.readLine()
                }
                if (was.isEmpty()) continue
                probesRead++
                val here = HashSet<String>()
                for (c in cands) {
                    here.add(c.word)
                    val before = was[c.word]
                    when {
                        before == null -> added.add("$head\t${c.word}@${c.coveredLen}")
                        before != c.coveredLen.toString() ->
                            drifted.add("$head\t${c.word} covers ${c.coveredLen}, baseline $before")
                    }
                }
                for (word in was.keys) if (word !in here) dropped.add("$head\t$word")
            }
        }
        assertTrue("baseline dump lines up with the sweep, matched $probesRead probes", probesRead > 1000)
        val out = File(System.getenv("AEGIS_AUDIT_DIR") ?: "build/decode-audit").apply { mkdirs() }
        File(out, "coverage_identity.tsv").writeText(
            buildString {
                appendLine("kind\tdetail")
                drifted.forEach { appendLine("drift\t$it") }
                added.forEach { appendLine("added\t$it") }
                dropped.forEach { appendLine("dropped\t$it") }
            },
        )
        assertTrue(
            "candidates must keep the baseline (word, coveredLen) pairs: " +
                "${drifted.size} drifted, ${added.size} added, ${dropped.size} dropped; " +
                "first: ${drifted.take(4)} ${added.take(4)} ${dropped.take(4)}",
            drifted.isEmpty() && added.isEmpty() && dropped.isEmpty(),
        )
    }
}
