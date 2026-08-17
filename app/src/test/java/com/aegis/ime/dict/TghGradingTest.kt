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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class TghGradingTest {

    private val authority = File("src/main/assets-src/tongyong-guifan-hanzi-8105.tsv")

    private val first = 0x4E00

    private fun authorityLevels(): Map<Int, Int> =
        authority.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val fields = line.split('\t')
                fields[2].removePrefix("U+").toInt(16) to fields[3].toInt()
            }

    private fun band(index: Int): Int = when {
        index < TghGrading.LEVEL1_COUNT -> 1
        index < TghGrading.LEVEL1_COUNT + TghGrading.LEVEL2_COUNT -> 2
        else -> 3
    }

    private fun table(
        magic: String = "AEGT",
        version: Int = 1,
        count: Int = TghGrading.ENTRY_COUNT,
        delta: (Int) -> Int = { if (it == 0) first else 1 },
        level: (Int) -> Int = ::band,
        padding: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(magic.toByteArray(Charsets.US_ASCII))
        writeInt(out, version)
        writeInt(out, count)
        for (i in 0 until count) writeVarint(out, delta(i))
        val packed = ByteArray((count + 3) / 4)
        for (i in 0 until count) {
            packed[i ushr 2] = (packed[i ushr 2].toInt() or ((level(i) - 1) shl (2 * (i and 3)))).toByte()
        }
        out.write(packed)
        repeat(padding) { out.write(0) }
        return out.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        for (shift in 0 until 32 step 8) out.write((value ushr shift) and 0xFF)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var rest = value
        while (rest >= 0x80) {
            out.write((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        out.write(rest)
    }

    private fun refused(bytes: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { TghGrading.parse(bytes) }
    }

    @Test fun the_bundled_table_grades_every_authority_character_at_its_published_level() {
        val levels = authorityLevels()
        assertEquals(TghGrading.ENTRY_COUNT, levels.size)
        val grading = TghGrading.bundled
        for ((codePoint, level) in levels) {
            assertEquals("U+%04X".format(codePoint), level, grading.level(codePoint))
        }
    }

    @Test fun characters_outside_the_authority_table_are_graded_out() {
        val levels = authorityLevels()
        val grading = TghGrading.bundled
        val missing = (0x4E00..0x9FFF).first { it !in levels }
        assertEquals(TghGrading.LEVEL_OUT, grading.level(missing))
        assertEquals(TghGrading.LEVEL_OUT, grading.level('A'.code))
        assertEquals(TghGrading.LEVEL_OUT, grading.level(0x20000))
    }

    @Test fun a_well_formed_table_answers_each_band_and_misses_outside_it() {
        val grading = TghGrading.parse(table())
        assertEquals(1, grading.level(first))
        assertEquals(1, grading.level(first + TghGrading.LEVEL1_COUNT - 1))
        assertEquals(2, grading.level(first + TghGrading.LEVEL1_COUNT))
        assertEquals(3, grading.level(first + TghGrading.LEVEL1_COUNT + TghGrading.LEVEL2_COUNT))
        assertEquals(3, grading.level(first + TghGrading.ENTRY_COUNT - 1))
        assertEquals(TghGrading.LEVEL_OUT, grading.level(first - 1))
        assertEquals(TghGrading.LEVEL_OUT, grading.level(first + TghGrading.ENTRY_COUNT))
    }

    @Test fun a_foreign_magic_or_version_is_refused() {
        refused(table(magic = "AEGL"))
        refused(table(version = 2))
    }

    @Test fun a_table_holding_any_other_entry_count_is_refused() {
        refused(table(count = TghGrading.ENTRY_COUNT - 1))
        refused(table(count = TghGrading.ENTRY_COUNT + 1))
    }

    @Test fun a_truncated_or_padded_table_is_refused() {
        val whole = table()
        refused(whole.copyOf(whole.size - 1))
        refused(table(padding = 1))
        refused(whole.copyOf(12))
        refused(whole.copyOf(13))
        refused(ByteArray(0))
    }

    @Test fun code_points_that_do_not_ascend_are_refused() {
        refused(table(delta = { if (it == 0) first else if (it == 17) 0 else 1 }))
    }

    @Test fun a_delta_wider_than_the_code_space_or_leaving_it_is_refused() {
        refused(table(delta = { if (it == 0) first else if (it == 1) 1 shl 21 else 1 }))
        refused(table(delta = { if (it == 0) Character.MAX_CODE_POINT else 1 }))
    }

    @Test fun a_level_outside_the_three_bands_or_a_miscounted_band_is_refused() {
        refused(table(level = { if (it == 0) 4 else band(it) }))
        refused(table(level = { if (it == 0) 2 else band(it) }))
        refused(table(level = { if (it == TghGrading.ENTRY_COUNT - 1) 1 else band(it) }))
    }
}
