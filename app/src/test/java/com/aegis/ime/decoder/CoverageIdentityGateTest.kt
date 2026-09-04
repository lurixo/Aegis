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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class CoverageIdentityGateTest {

    private companion object {
        const val BASELINE_RESOURCE = "/coverage-identity-7907381b.tsv"
        const val BASELINE_COMMIT = "7907381b7eda9e11c48bd08a5b5ad532df11d71c"
        const val DICTIONARY_PACK_SHA256 = "00985ca708cf016f8c03a943536c38663dae2b2e1f38637c409c641469e9f047"
        val ASSET_SHA256 = linkedMapOf(
            "aegis_dict.bin" to "c73daad8c7d7ddd81f2049191eecdba0e870b75c0255a179769e89dabce1e5c1",
            "aegis_t9.bin" to "7bcbb3acd35b4a187c5ffb5292edafdf59397fd5c58ed57a711e29b17969bf6e",
            "aegis_jianpin.bin" to "99f330ba45d8ac0e850b5c3e563cdf4a3a9069fdb75127760bd637515399ff1c",
            "aegis_lm.bin" to "eddb4e0aac0598bd8084db6061a81d4736391854fadcfdf6d44bf57c14c57929",
        )
        const val CANONICAL =
            "last coveredLen per word; words sorted by UTF-16; SHA-256 over BE32 UTF-8-length, UTF-8 word, BE32 coveredLen"
    }

    private data class ProbeDigest(
        val ordinal: Int,
        val head: String,
        val uniqueCandidates: Int,
        val sha256: String,
    ) {
        fun line(): String = "$ordinal\t$head\t$uniqueCandidates\t$sha256"
    }

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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun verifyAssetIdentity() {
        for ((name, expected) in ASSET_SHA256) {
            val file = FullDictTestAssets.file(name)
            assertTrue("coverage baseline asset exists: $name", file.isFile)
            assertEquals("coverage baseline asset SHA-256: $name", expected, sha256(file))
        }
    }

    private fun digest(cands: List<Cand>): Pair<Int, String> {
        val byWord = HashMap<String, Int>()
        for (cand in cands) {
            val previous = byWord.put(cand.word, cand.coveredLen)
            check(previous == null || previous == cand.coveredLen) {
                "candidate ${cand.word} carries conflicting covered lengths $previous and ${cand.coveredLen}"
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateInt(value: Int) {
            digest.update(
                byteArrayOf(
                    (value ushr 24).toByte(),
                    (value ushr 16).toByte(),
                    (value ushr 8).toByte(),
                    value.toByte(),
                ),
            )
        }
        for ((word, coveredLen) in byWord.toSortedMap()) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            updateInt(bytes.size)
            digest.update(bytes)
            updateInt(coveredLen)
        }
        val encoded = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return byWord.size to encoded
    }

    private fun probeDigests(): List<ProbeDigest> = groupsOf().mapIndexed { ordinal, (head, cands) ->
        val (count, digest) = digest(cands)
        ProbeDigest(ordinal, head, count, digest)
    }.toList()

    private fun header(probes: Int): List<String> = buildList {
        add("# coverage-identity-per-probe-sha256-v1")
        add("# baseline-commit=$BASELINE_COMMIT")
        add("# dictionary-pack-sha256=$DICTIONARY_PACK_SHA256")
        ASSET_SHA256.forEach { (name, digest) -> add("# asset-sha256=$name:$digest") }
        add("# canonical=$CANONICAL")
        add("# probes=$probes")
        add("ordinal\tlayout\tmode\tinput\tcontext\tuniqueCandidates\tsha256")
    }

    private fun baselineLines(): List<String> {
        val configured = System.getenv("AEGIS_COVERAGE_DIGEST_BASELINE")?.takeIf { it.isNotBlank() }
        if (configured != null) {
            val file = File(configured)
            assertTrue("AEGIS_COVERAGE_DIGEST_BASELINE points to a file: $configured", file.isFile)
            return file.readLines()
        }
        return requireNotNull(javaClass.getResourceAsStream(BASELINE_RESOURCE)) {
            "missing $BASELINE_RESOURCE"
        }.bufferedReader().use { it.readLines() }
    }

    private fun baselineDigests(): List<ProbeDigest> {
        val lines = baselineLines()
        val probeLine = lines.getOrNull(8)
        assertTrue("coverage baseline carries a probe count", probeLine?.startsWith("# probes=") == true)
        val declared = probeLine!!.substringAfter('=').toIntOrNull()
        assertTrue("coverage baseline probe count is numeric", declared != null)
        assertEquals("coverage baseline provenance and schema", header(declared!!), lines.take(10))
        val rows = lines.drop(10)
        assertEquals("coverage baseline row count", declared, rows.size)
        return rows.mapIndexed { expectedOrdinal, line ->
            val fields = line.split('\t')
            assertEquals("coverage baseline row ${expectedOrdinal + 11} field count", 7, fields.size)
            val ordinal = fields[0].toIntOrNull()
            assertEquals("coverage baseline row ${expectedOrdinal + 11} ordinal", expectedOrdinal, ordinal)
            val count = fields[5].toIntOrNull()
            assertTrue("coverage baseline row ${expectedOrdinal + 11} candidate count", count != null && count >= 0)
            assertTrue(
                "coverage baseline row ${expectedOrdinal + 11} digest",
                fields[6].matches(Regex("[0-9a-f]{64}")),
            )
            ProbeDigest(
                expectedOrdinal,
                fields.subList(1, 5).joinToString("\t"),
                count!!,
                fields[6],
            )
        }
    }

    @Test fun writeCoverageDigestWhenAsked() {
        val target = System.getenv("AEGIS_COVERAGE_DIGEST_DUMP")?.takeIf { it.isNotBlank() }
        assumeTrue("set AEGIS_COVERAGE_DIGEST_DUMP to write the reference digest", target != null)
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        verifyAssetIdentity()
        val probes = probeDigests()
        val file = File(target!!)
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { writer ->
            for (line in header(probes.size)) writer.appendLine(line)
            for (probe in probes) writer.appendLine(probe.line())
        }
        assertTrue("coverage digest written", file.length() > 0)
        println("Coverage identity baseline generated: probes=${probes.size}, bytes=${file.length()}")
    }

    @Test fun everyCandidateKeepsTheKeyCountItAteInTheBaseline() {
        assumeTrue(
            "coverage identity gate runs only in the dictionary-release verification",
            System.getenv("AEGIS_DICTIONARY_RELEASE_VERIFY") == "1",
        )
        assertTrue(
            "dictionary-release verification sets AEGIS_COVERAGE_DIGEST_BASELINE",
            System.getenv("AEGIS_COVERAGE_DIGEST_BASELINE")?.isNotBlank() == true,
        )
        assertTrue(
            "dictionary-release verification provides every decoder asset",
            FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
        )
        verifyAssetIdentity()
        val baseline = baselineDigests()
        val current = probeDigests()
        val drifted = ArrayList<String>()
        val added = ArrayList<String>()
        val dropped = ArrayList<String>()
        val shared = minOf(baseline.size, current.size)
        for (index in 0 until shared) {
            val before = baseline[index]
            val here = current[index]
            if (before.head != here.head) {
                dropped += "#$index ${before.head}"
                added += "#$index ${here.head}"
            } else if (before.uniqueCandidates < here.uniqueCandidates) {
                added += "#$index ${here.head}: ${before.uniqueCandidates} -> ${here.uniqueCandidates}"
            } else if (before.uniqueCandidates > here.uniqueCandidates) {
                dropped += "#$index ${here.head}: ${before.uniqueCandidates} -> ${here.uniqueCandidates}"
            } else if (before.sha256 != here.sha256) {
                drifted += "#$index ${here.head}: ${before.sha256} -> ${here.sha256}"
            }
        }
        for (index in shared until baseline.size) dropped += "#$index ${baseline[index].head}"
        for (index in shared until current.size) added += "#$index ${current[index].head}"
        assertTrue("baseline digest covers a non-trivial sweep: ${baseline.size}", baseline.size > 1000)
        val out = File(System.getenv("AEGIS_AUDIT_DIR") ?: "build/decode-audit").apply { mkdirs() }
        File(out, "coverage_identity.tsv").writeText(
            buildString {
                appendLine(
                    "summary\tbaseline=${baseline.size}\tcurrent=${current.size}\t" +
                        "drift=${drifted.size}\tadded=${added.size}\tdropped=${dropped.size}",
                )
                appendLine("kind\tdetail")
                drifted.forEach { appendLine("drift\t$it") }
                added.forEach { appendLine("added\t$it") }
                dropped.forEach { appendLine("dropped\t$it") }
            },
        )
        println(
            "Coverage identity gate: baseline=${baseline.size}, current=${current.size}, " +
                "drift=${drifted.size}, added=${added.size}, dropped=${dropped.size}",
        )
        assertTrue(
            "candidate groups must keep the 7907381b per-probe (word, coveredLen) digest: " +
                "${drifted.size} drifted, ${added.size} added, ${dropped.size} dropped; " +
                "first: ${drifted.take(4)} ${added.take(4)} ${dropped.take(4)}",
            drifted.isEmpty() && added.isEmpty() && dropped.isEmpty(),
        )
    }
}
