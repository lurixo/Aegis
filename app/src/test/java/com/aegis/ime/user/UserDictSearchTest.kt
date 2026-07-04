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

package com.aegis.ime.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDictSearchTest {

    private val entries = listOf(
        UserModel.Entry("nihao", "你好", 5),
        UserModel.Entry("nihaoya", "你好呀", 3),
        UserModel.Entry("ceshi", "测试", 2),
        UserModel.Entry("haode", "好的", 1),
        UserModel.Entry("okgo", "OKGo", 1),
    )

    private fun words(query: String) = UserDictSearch.filter(entries, query).map { it.word }


    @Test fun blank_query_returns_everything_in_order() {
        assertEquals(entries, UserDictSearch.filter(entries, ""))
        assertEquals(entries, UserDictSearch.filter(entries, "   "))
    }


    @Test fun unmatched_query_returns_empty() {
        assertEquals(emptyList<String>(), words("zzzz"))
        assertEquals(emptyList<String>(), words("不存在"))
    }


    @Test fun pinyin_prefix_matches_all_readings_it_prefixes() {
        assertEquals(listOf("你好", "你好呀"), words("nih"))
        assertEquals(listOf("你好", "你好呀"), words("nihao"))
        assertEquals(listOf("你好呀"), words("nihaoy"))
    }

    @Test fun pinyin_infix_and_separator_tolerance() {
        assertEquals("infix letters also match", listOf("你好", "你好呀", "好的"), words("hao"))
        assertEquals("apostrophes/spaces are syllable separators", listOf("你好", "你好呀"), words("ni'hao"))
        assertEquals(listOf("你好", "你好呀"), words("ni hao"))
        assertEquals("case-insensitive pinyin", listOf("你好", "你好呀"), words("NiHao"))
    }


    @Test fun word_substring_matches() {
        assertEquals(listOf("你好", "你好呀", "好的"), words("好"))
        assertEquals(listOf("你好呀"), words("好呀"))
        assertEquals(listOf("测试"), words("测试"))
    }

    @Test fun latin_words_match_case_insensitively_by_word_too() {
        assertTrue(words("okg").contains("OKGo"))
        assertTrue(words("OKGO").contains("OKGo"))
    }

    @Test fun cjk_query_never_matches_readings() {
        assertEquals(emptyList<String>(), words("好a好"))
    }


    @Test fun ten_thousand_entries_filter_correctly() {
        val big = ArrayList<UserModel.Entry>(10_000)
        for (i in 0 until 10_000) {
            val reading = buildString {
                var n = i
                repeat(4) { append('a' + n % 26); n /= 26 }
            }
            big += UserModel.Entry(reading, "词$i", 0)
        }
        val hits = UserDictSearch.filter(big, "aa")
        assertEquals(big.count { it.reading.contains("aa") }, hits.size)
        assertEquals("blank returns all 10k", 10_000, UserDictSearch.filter(big, "").size)
        assertEquals("word search hits exactly one", listOf("词9999"), UserDictSearch.filter(big, "词9999").map { it.word })
    }
}
