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
        val ASSET_NAMES = listOf("aegis_dict.bin", "aegis_t9.bin", "aegis_jianpin.bin", "aegis_lm.bin")
        const val COLUMNS = "ordinal\tlayout\tmode\tinput\tcontext\tuniqueCandidates\tsha256"
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

    private fun actualAssetHashes(): Map<String, String> = ASSET_NAMES.associateWith { name ->
        val file = FullDictTestAssets.file(name)
        assertTrue("coverage baseline asset exists: $name", file.isFile)
        sha256(file)
    }

    private fun verifyAssetIdentity(expected: Map<String, String>) {
        assertEquals("coverage baseline asset SHA-256 identities", expected, actualAssetHashes())
    }

    private fun probeHeads(): List<String> = probes().flatMap { (mode, syls, context) ->
        listOf(true, false).map { letters ->
            val keys = if (letters) syls else syls.map { T9Pinyin.toT9(it) }
            val layout = if (letters) "26" else "9"
            "$layout\t$mode\t${keys.joinToString("")}\t${if (context.isEmpty()) "-" else context}"
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

    private fun header(probes: Int, assets: Map<String, String>): List<String> = buildList {
        add("# aegis-coverage-identity-v2")
        ASSET_NAMES.forEach { name -> add("# asset-sha256 $name ${assets.getValue(name)}") }
        add("# canonical $CANONICAL")
        add("# probes $probes")
        add(COLUMNS)
    }

    private fun baselineLines(): List<String> {
        val configured = System.getenv("AEGIS_COVERAGE_DIGEST_BASELINE")?.takeIf { it.isNotBlank() }
        assertTrue("AEGIS_COVERAGE_DIGEST_BASELINE is required", configured != null)
        val file = File(configured!!)
        assertTrue("AEGIS_COVERAGE_DIGEST_BASELINE points to a file: $configured", file.isFile)
        return file.readLines()
    }

    private data class Baseline(val assets: Map<String, String>, val probes: List<ProbeDigest>)

    private fun baselineDigests(lines: List<String> = baselineLines()): Baseline {
        assertEquals("coverage baseline schema", "# aegis-coverage-identity-v2", lines.firstOrNull())
        val assets = linkedMapOf<String, String>()
        var index = 1
        while (lines.getOrNull(index)?.startsWith("# asset-sha256 ") == true) {
            val fields = lines[index].split(' ')
            assertEquals("coverage asset field count", 4, fields.size)
            val name = fields[2]
            assertTrue("known coverage asset: $name", name in ASSET_NAMES)
            assertTrue("unique coverage asset: $name", name !in assets)
            assertTrue("coverage asset digest: $name", fields[3].matches(Regex("[0-9a-f]{64}")))
            assets[name] = fields[3]
            index++
        }
        assertEquals("all coverage assets are present", ASSET_NAMES.toSet(), assets.keys)
        assertEquals("coverage canonicalization", "# canonical $CANONICAL", lines.getOrNull(index++))
        val countLine = lines.getOrNull(index++)
        assertTrue("coverage probe count", countLine?.matches(Regex("# probes [1-9][0-9]*")) == true)
        val declared = countLine!!.substringAfterLast(' ').toIntOrNull()
        assertTrue("coverage probe count is a supported integer", declared != null)
        assertEquals("coverage columns", COLUMNS, lines.getOrNull(index++))
        val rows = lines.drop(index)
        val expectedHeads = probeHeads()
        assertEquals("coverage baseline row count", declared, rows.size)
        assertEquals("coverage baseline preserves the full probe sweep", expectedHeads.size, rows.size)
        val probes = rows.mapIndexed { expectedOrdinal, line ->
            val fields = line.split('\t')
            assertEquals("coverage row $expectedOrdinal field count", 7, fields.size)
            assertEquals("coverage row $expectedOrdinal unique ordinal", expectedOrdinal.toString(), fields[0])
            val head = fields.subList(1, 5).joinToString("\t")
            assertEquals("coverage row $expectedOrdinal head and multiplicity", expectedHeads[expectedOrdinal], head)
            val count = fields[5].toIntOrNull()
            assertTrue("coverage row $expectedOrdinal candidate count", count != null && count >= 0)
            assertTrue("coverage row $expectedOrdinal digest", fields[6].matches(Regex("[0-9a-f]{64}")))
            ProbeDigest(expectedOrdinal, head, count!!, fields[6])
        }
        return Baseline(assets, probes)
    }

    private fun parserFixture(): List<String> {
        val heads = probeHeads()
        return header(heads.size, ASSET_NAMES.associateWith { "0".repeat(64) }) +
            heads.mapIndexed { ordinal, head -> "$ordinal\t$head\t0\t${"0".repeat(64)}" }
    }

    private fun assertInvalidBaseline(lines: List<String>) {
        val failure = runCatching { baselineDigests(lines) }.exceptionOrNull()
        assertTrue("malformed baseline must fail validation", failure is AssertionError)
    }

    @Test fun baselineParserPreservesRepeatedHeadsAtTheirOriginalOrdinals() {
        val parsed = baselineDigests(parserFixture())
        assertEquals(probeHeads(), parsed.probes.map { it.head })
        assertTrue(parsed.probes.map { it.head }.distinct().size < parsed.probes.size)
    }

    @Test fun baselineParserRejectsMissingDuplicateAndMalformedAssets() {
        val lines = parserFixture()
        assertInvalidBaseline(lines.toMutableList().apply { removeAt(1) })
        assertInvalidBaseline(lines.toMutableList().apply { add(1, lines[1]) })
        assertInvalidBaseline(lines.toMutableList().apply { this[1] = "# asset-sha256 aegis_dict.bin bad" })
    }

    @Test fun baselineParserRejectsCanonicalAndCountDrift() {
        val lines = parserFixture()
        assertInvalidBaseline(lines.toMutableList().apply { this[5] = "# canonical different" })
        assertInvalidBaseline(lines.toMutableList().apply { this[6] = "# probes 1" })
        assertInvalidBaseline(lines.dropLast(1))
    }

    @Test fun baselineParserRejectsRepeatedOrdinalsAndMovedHeads() {
        val lines = parserFixture()
        assertInvalidBaseline(lines.toMutableList().apply { this[9] = lines[8] })
        assertInvalidBaseline(lines.toMutableList().apply { this[9] = "1" + lines[8].substringAfter('0') })
    }

    @Test fun baselineParserRejectsMalformedCandidateDigests() {
        assertInvalidBaseline(parserFixture().toMutableList().apply {
            this[8] = this[8].substringBeforeLast('\t') + "\tbad"
        })
    }

    @Test fun writeCoverageDigestWhenAsked() {
        val target = System.getenv("AEGIS_COVERAGE_DIGEST_DUMP")?.takeIf { it.isNotBlank() }
        assumeTrue("set AEGIS_COVERAGE_DIGEST_DUMP to write the reference digest", target != null)
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        val assets = actualAssetHashes()
        val probes = probeDigests()
        val file = File(target!!)
        assertTrue("coverage export never overwrites an existing file", !file.exists())
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { writer ->
            for (line in header(probes.size, assets)) writer.appendLine(line)
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
        val parsed = baselineDigests()
        verifyAssetIdentity(parsed.assets)
        val baseline = parsed.probes
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
            "candidate groups must keep the reviewed per-probe (word, coveredLen) digest: " +
                "${drifted.size} drifted, ${added.size} added, ${dropped.size} dropped; " +
                "first: ${drifted.take(4)} ${added.take(4)} ${dropped.take(4)}",
            drifted.isEmpty() && added.isEmpty() && dropped.isEmpty(),
        )
    }
}
