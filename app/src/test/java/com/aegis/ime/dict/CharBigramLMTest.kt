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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.ln

class CharBigramLMTest {

    private val ni = "你".codePointAt(0)
    private val hao = "好".codePointAt(0)
    private val de = "的".codePointAt(0)
    private val below = "世".codePointAt(0)
    private val between = 21000
    private val above = 0x9FA5
    private val backoff = ln(0.4)

    private fun roundTripFile(lines: List<String>): File {
        val dir = File.createTempFile("lm_src", "").let { it.delete(); it.mkdirs(); it }
        val src = File(dir, "dict.txt")
        val header = listOf("# tiny dict", "name: test", "...")
        src.writeText((header + lines).joinToString("\n") + "\n")
        val out = File(dir, "test_lm.bin")
        LmBuilder.build(arrayOf("--out", out.path, src.path))
        return out
    }

    private val sampleLines = listOf(
        "你好\tnihao\t100",
        "你\tni\t50",
        "好\thao\t40",
        "好的\thaode\t30",
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

    @Test fun truncated_bigram_region_degrades_to_backoff_and_never_reads_out_of_bounds() {
        val full = roundTripFile(sampleLines).readBytes()
        val truncated = File.createTempFile("lm_trunc", ".bin")
        truncated.deleteOnExit()
        truncated.writeBytes(full.copyOf(full.size - 8))

        val lm = CharBigramLM.fromFile(truncated)
        val total = 550.0
        assertEquals(backoff + ln(170.0) - ln(total), lm.logCond(ni, hao), 1e-9)
        assertEquals(backoff + ln(230.0) - ln(total), lm.logCond(hao, de), 1e-9)
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

    @Test fun builder_clamps_non_positive_source_freq_so_uni_prob_stays_finite() {
        val lm = CharBigramLM.fromFile(roundTripFile(listOf("世界\tshijie\t0", "你\tni\t5")))
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

    private companion object {
        const val PinyinBos = -1
    }
}
