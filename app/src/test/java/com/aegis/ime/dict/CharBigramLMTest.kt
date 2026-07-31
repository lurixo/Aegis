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

package com.aegis.ime.dict

import com.aegis.tools.LmBuilder
import com.aegis.tools.T2SMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.ln

class CharBigramLMTest {

    private val ni = "你".codePointAt(0)
    private val hao = "好".codePointAt(0)
    private val de = "的".codePointAt(0)
    private val below = "世".codePointAt(0)
    private val between = 21000
    private val above = 0x9FA5
    private val backoff = ln(0.4)

    private fun roundTripFile(lines: List<String>, normalize: Boolean = false): File {
        val dir = File.createTempFile("lm_src", "").let { it.delete(); it.mkdirs(); it }
        val src = File(dir, "dict.txt")
        val header = listOf("# tiny dict", "name: test", "...")
        src.writeText((header + lines).joinToString("\n") + "\n")
        val out = File(dir, "test_lm.bin")
        val args = arrayListOf("--out", out.path)
        if (normalize) args.addAll(listOf("--t2s-data", "../tools/t2s-data"))
        args.add(src.path)
        LmBuilder.build(args.toTypedArray())
        return out
    }

    private val sampleLines = listOf(
        "你好\tni hao\t100",
        "你\tni\t50",
        "好\thao\t40",
        "好的\thao de\t30",
        "的\tde\t200",
    )

    @Test fun round_trip_bigram_and_backoff_values_match_the_source_counts() {
        val lm = CharBigramLM.fromFile(roundTripFile(sampleLines))

        val total = 550.0
        assertEquals(ln(100.0) - ln(100.0), lm.logCond(ni, hao), 1e-9)
        assertEquals(ln(30.0) - ln(30.0), lm.logCond(hao, de), 1e-9)

        assertEquals(backoff + ln(230.0) - ln(total), lm.logCond(ni, de), 1e-9)
        assertEquals(backoff + ln(150.0) - ln(total), lm.logCond(hao, ni), 1e-9)
        assertEquals(backoff + ln(170.0) - ln(total), lm.logCond(de, hao), 1e-9)
        assertEquals(backoff + ln(170.0) - ln(total), lm.logCond(below, hao), 1e-9)
        assertEquals(backoff - ln(total), lm.logCond(ni, below), 1e-9)
    }

    @Test fun char_id_binary_search_covers_the_boundaries() {
        val lm = CharBigramLM.fromFile(roundTripFile(sampleLines))
        assertEquals(0, lm.charId(ni))
        assertEquals(1, lm.charId(hao))
        assertEquals(2, lm.charId(de))
        assertEquals(-1, lm.charId(below))
        assertEquals(-1, lm.charId(between))
        assertEquals(-1, lm.charId(above))
    }

    @Test fun log_cond_by_id_is_the_exact_decomposition_of_log_cond() {
        val lm = CharBigramLM.fromFile(roundTripFile(sampleLines))
        val cps = listOf(ni, hao, de, below, between, above, PinyinBos)
        for (a in cps) for (b in cps) {
            assertEquals(
                "logCond($a,$b)",
                lm.logCond(a, b),
                lm.logCondById(lm.charId(a), lm.charId(b)),
                0.0,
            )
        }
    }

    @Test fun empty_bigram_model_forces_backoff_without_throwing() {
        val lm = CharBigramLM.fromFile(roundTripFile(listOf("你\tni\t50", "好\thao\t40")))
        assertEquals(backoff + ln(50.0) - ln(90.0), lm.logCond(hao, ni), 1e-9)
        assertEquals(backoff + ln(40.0) - ln(90.0), lm.logCond(ni, hao), 1e-9)
    }

    @Test fun builder_uses_the_dictionary_normalization_and_duplicate_merge_stream() {
        val lm = CharBigramLM.fromFile(
            roundTripFile(
                listOf(
                    "电脑\tdian nao\t10",
                    "電腦\tdian nao\t20",
                    "电脑\tdian nao\t5",
                ),
                normalize = true,
            ),
        )
        assertEquals(-1, lm.charId("電".codePointAt(0)))
        assertEquals(-1, lm.charId("腦".codePointAt(0)))
        assertTrue(lm.charId("电".codePointAt(0)) >= 0)
        assertTrue(lm.charId("脑".codePointAt(0)) >= 0)
        assertEquals(0.0, lm.logCond("电".codePointAt(0), "脑".codePointAt(0)), 1e-9)
    }

    @Test fun truncated_bigram_region_is_rejected_before_queries_can_read_out_of_bounds() {
        val full = roundTripFile(sampleLines).readBytes()
        val truncated = File.createTempFile("lm_trunc", ".bin")
        truncated.deleteOnExit()
        truncated.writeBytes(full.copyOf(full.size - 8))

        assertThrows(IllegalArgumentException::class.java) { CharBigramLM.fromFile(truncated) }
    }

    @Test fun header_truncated_before_the_bigram_count_is_rejected_cleanly() {
        val full = roundTripFile(sampleLines).readBytes()
        val stub = File.createTempFile("lm_stub", ".bin")
        stub.deleteOnExit()
        stub.writeBytes(full.copyOf(48))
        assertThrows(IllegalArgumentException::class.java) { CharBigramLM.fromFile(stub) }
    }

    @Test fun corrupt_headers_are_rejected_instead_of_overflowing_offsets() {
        val full = roundTripFile(sampleLines).readBytes()

        val badMagic = full.copyOf(); badMagic[0] = 'X'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) { CharBigramLM.fromFile(writeTemp(badMagic)) }

        val badVersion = full.copyOf(); putLeInt(badVersion, 4, 2)
        assertThrows(IllegalArgumentException::class.java) { CharBigramLM.fromFile(writeTemp(badVersion)) }

        val hugeChars = full.copyOf(); putLeInt(hugeChars, 8, Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { CharBigramLM.fromFile(writeTemp(hugeChars)) }

        val negChars = full.copyOf(); putLeInt(negChars, 8, -3)
        assertThrows(IllegalArgumentException::class.java) { CharBigramLM.fromFile(writeTemp(negChars)) }
    }

    @Test fun corrupt_internal_indexes_counts_and_denominators_are_rejected_at_load() {
        val full = roundTripFile(sampleLines).readBytes()
        val offsets = offsets(full)
        val firstRow = (0 until offsets.numChars).first {
            getLeInt(full, offsets.rowStart + it * 4) < getLeInt(full, offsets.rowStart + (it + 1) * 4)
        }
        val firstBigram = getLeInt(full, offsets.rowStart + firstRow * 4)

        val unsortedChars = full.copyOf()
        putLeInt(unsortedChars, 24, getLeInt(unsortedChars, 20))
        assertThrows(IllegalArgumentException::class.java) { CharBigramLM.fromFile(writeTemp(unsortedChars)) }

        val badRows = full.copyOf()
        putLeInt(badRows, offsets.rowStart, 1)
        assertThrows(IllegalArgumentException::class.java) { CharBigramLM.fromFile(writeTemp(badRows)) }

        val badTarget = full.copyOf()
        putLeInt(badTarget, offsets.biC2 + firstBigram * 4, offsets.numChars)
        assertThrows(IllegalArgumentException::class.java) { CharBigramLM.fromFile(writeTemp(badTarget)) }

        val badCount = full.copyOf()
        putLeLong(badCount, offsets.biCount + firstBigram * 8, 0L)
        assertThrows(IllegalArgumentException::class.java) { CharBigramLM.fromFile(writeTemp(badCount)) }

        val badDenominator = full.copyOf()
        putLeLong(badDenominator, offsets.rowTotal + firstRow * 8, 0L)
        assertThrows(IllegalArgumentException::class.java) { CharBigramLM.fromFile(writeTemp(badDenominator)) }

    }

    @Test fun packaged_asset_identity_matchesAndItsInternalInvariantsValidate() {
        val asset = File("src/main/assets/aegis_lm.bin")
        val identity = File("src/main/assets/aegis_lm.bin.sha256")
        assertEquals(identity.readText().trim(), sha256(asset))
        CharBigramLM.fromFile(asset)
    }

    @Test fun packaged_asset_excludes_forms_mapped_away_by_dictionary_normalization() {
        val lm = CharBigramLM.fromFile(File("src/main/assets/aegis_lm.bin"))
        val mapped = T2SMerge.load(File("../tools/t2s-data")).mappedSourceForms()
        val leaked = mapped.filter {
            it.codePointCount(0, it.length) == 1 && lm.charId(it.codePointAt(0)) >= 0
        }
        assertTrue("mapped-away forms remain in the packaged LM: ${leaked.take(20)}", leaked.isEmpty())
    }

    @Test fun assetInstallRefreshesStaleContentAndRepairsCorruptionWithoutRecopyingAValidModel() {
        val old = roundTripFile(listOf("你\tni\t5", "好\thao\t4"))
        val current = roundTripFile(sampleLines)
        val dir = File.createTempFile("lm-install", "").let { it.delete(); it.mkdirs(); it }
        val installed = File(dir, "model.bin").apply { writeBytes(old.readBytes()) }
        val expected = sha256(current)
        var opens = 0

        CharBigramLM.fromAsset(dir, installed.name, expected) {
            opens++
            current.inputStream()
        }
        assertTrue(installed.readBytes().contentEquals(current.readBytes()))
        assertEquals(1, opens)

        CharBigramLM.fromAsset(dir, installed.name, expected) {
            throw AssertionError("valid model must not be recopied")
        }

        installed.writeBytes(installed.readBytes().also { it[0] = 'X'.code.toByte() })
        CharBigramLM.fromAsset(dir, installed.name, expected) {
            opens++
            current.inputStream()
        }
        assertTrue(installed.readBytes().contentEquals(current.readBytes()))
        assertEquals(2, opens)
    }

    @Test fun builder_clamps_non_positive_source_freq_so_uni_prob_stays_finite() {
        val lm = CharBigramLM.fromFile(roundTripFile(listOf("世界\tshi jie\t0", "你\tni\t5")))
        val world = "世".codePointAt(0)
        val cond = lm.logCond(ni, world)
        assertTrue("uni prob for a clamped-freq char must be finite", cond.isFinite())
        assertNotEquals(Double.NEGATIVE_INFINITY, cond, 0.0)
    }

    private fun writeTemp(bytes: ByteArray): File {
        val f = File.createTempFile("lm_patch", ".bin")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f
    }

    private fun putLeInt(bytes: ByteArray, off: Int, v: Int) {
        bytes[off] = (v and 0xFF).toByte()
        bytes[off + 1] = ((v ushr 8) and 0xFF).toByte()
        bytes[off + 2] = ((v ushr 16) and 0xFF).toByte()
        bytes[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun getLeInt(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or
            ((bytes[off + 3].toInt() and 0xFF) shl 24)

    private fun putLeLong(bytes: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) bytes[off + i] = ((value ushr (i * 8)) and 0xFF).toByte()
    }

    private data class Offsets(
        val numChars: Int,
        val rowTotal: Int,
        val rowStart: Int,
        val biC2: Int,
        val biCount: Int,
    )

    private fun offsets(bytes: ByteArray): Offsets {
        val numChars = getLeInt(bytes, 8)
        val rowTotal = 20 + numChars * 4 + numChars * 8
        val rowStart = rowTotal + numChars * 8
        val numBigramsOffset = rowStart + (numChars + 1) * 4
        val numBigrams = getLeInt(bytes, numBigramsOffset)
        val biC2 = numBigramsOffset + 4
        return Offsets(numChars, rowTotal, rowStart, biC2, biC2 + numBigrams * 4)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PinyinBos = -1
    }
}
