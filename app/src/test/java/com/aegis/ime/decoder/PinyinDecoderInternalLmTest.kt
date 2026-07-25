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
import org.junit.Test

class PinyinDecoderInternalLmTest {

    private val rows = listOf(
        EngineFixture.Row("ceshi", "甲乙", 100),
        EngineFixture.Row("ceshi", "丙丁", 100),
    )
    private val t9Rows = rows.map { it.copy(key = T9Pinyin.toT9(it.key)) }
    private val lm = EngineFixture.buildLm(
        mapOf('甲'.code to 100L, '乙'.code to 100L, '丙'.code to 100L, '丁'.code to 100L),
        mapOf(('丙'.code to '丁'.code) to 100L),
    )

    @Test fun internal_character_bigrams_rank_whole_words_on_both_keyboards() {
        val letter = PinyinDecoder(EngineFixture.build(rows), lm)
        val t9 = PinyinDecoder(EngineFixture.build(t9Rows), lm)

        assertEquals("丙丁", letter.decode("ceshi", 2).first())
        assertEquals("丙丁", letter.decodeCovered("ceshi", 2).first().word)
        assertEquals("丙丁", t9.decode(T9Pinyin.toT9("ceshi"), 2).first())
        assertEquals("丙丁", t9.decodeCovered(T9Pinyin.toT9("ceshi"), 2).first().word)
    }
}
