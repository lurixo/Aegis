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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeadingSinglesTailTest {

    private fun frequencyOf(i: Int): Int = 101 + (i * 7919) % 899

    private val singles = List(3_000) { String(Character.toChars(0x4E00 + it)) }

    private val rows = singles.mapIndexed { i, w -> EngineFixture.Row("shi", w, frequencyOf(i)) } +
        listOf(EngineFixture.Row("shixian", "实现", 900), EngineFixture.Row("shi", "实施", 700))

    @Test
    fun large_homophone_tail_is_complete_unique_and_frequency_ordered() {
        val decoder = PinyinDecoder(EngineFixture.build(rows))
        for (input in listOf("shixa", "shix")) {
            val words = decoder.decodeCovered(input, 40).map { it.word }
            assertEquals(input, words.toSet().size, words.size)
            val tail = words.filter { it.codePointCount(0, it.length) == 1 }
            assertEquals(input, singles.toSet(), tail.toSet())
            val frequency = singles.withIndex().associate { (i, w) -> w to frequencyOf(i) }
            for (i in 1 until tail.size) {
                assertTrue("$input $i", frequency.getValue(tail[i - 1]) >= frequency.getValue(tail[i]))
            }
        }
    }

    @Test
    fun tail_keeps_the_same_entries_when_words_are_already_listed() {
        val decoder = PinyinDecoder(EngineFixture.build(rows))
        val words = decoder.decodeCovered("shixian", 40).map { it.word }
        assertEquals(words.toSet().size, words.size)
        assertTrue(words.contains("实现"))
        assertEquals(singles.toSet(), words.filter { it.codePointCount(0, it.length) == 1 }.toSet())
    }
}
