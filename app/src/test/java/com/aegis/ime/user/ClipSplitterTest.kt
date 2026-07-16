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

import com.aegis.ime.user.ClipSplitter.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipSplitterTest {

    private fun texts(s: String) = ClipSplitter.blocks(s)
    private fun kinds(s: String) = ClipSplitter.split(s).map { it.kind }

    @Test fun mixed_cn_en_symbol_splits_by_class() {
        assertEquals(listOf("你", "好", "hello", ",", "world", "!"), texts("你好hello,world!"))
        assertEquals(listOf(Kind.HAN, Kind.HAN, Kind.LATIN, Kind.SYMBOL, Kind.LATIN, Kind.SYMBOL), kinds("你好hello,world!"))
    }

    @Test fun reported_url_splits_at_character_class_boundaries() {
        val url = "https://blog.youtube/news-and-events/watch-fifa-world-cup-youtube/"
        assertEquals(
            listOf(
                "https", "://", "blog", ".", "youtube", "/", "news", "-", "and", "-", "events", "/",
                "watch", "-", "fifa", "-", "world", "-", "cup", "-", "youtube", "/",
            ),
            texts(url),
        )
        assertEquals(url, texts(url).joinToString(""))
    }

    @Test fun email_stays_one_block() {
        assertEquals(listOf("联", "系", "bob@x.com", "谢", "谢"), texts("联系bob@x.com谢谢"))
        assertEquals(Kind.EMAIL, ClipSplitter.split("bob@x.com").single().kind)
    }

    @Test fun common_url_forms_use_the_same_boundaries() {
        assertEquals(
            listOf(
                "http", "://", "x", ".", "io", "/", "a", "-", "b", "/", "c", "2", "?", "x", "=", "10",
                "&", "y", "=", "z",
            ),
            texts("http://x.io/a-b/c2?x=10&y=z"),
        )
        assertEquals(listOf("www", ".", "aegis", ".", "cn", "/", "path"), texts("www.aegis.cn/path"))
        assertEquals(listOf("x", ".", "ai"), texts("x.ai"))
    }

    @Test fun url_boundaries_preserve_chinese_and_sentence_punctuation() {
        assertEquals(
            listOf("看", "这", "个", "https", "://", "x", ".", "com", "。"),
            texts("看这个https://x.com。"),
        )
        assertEquals(
            listOf("访", "问", "https", "://", "a", ".", "b", "/", "c", "?", "d", "=", "1", "，"),
            texts("访问https://a.b/c?d=1，"),
        )
    }

    @Test fun digits_split_from_chinese() {
        assertEquals(listOf("打", "电", "话", "13800138000"), texts("打电话13800138000"))
        assertEquals(listOf(Kind.HAN, Kind.HAN, Kind.HAN, Kind.DIGIT), kinds("打电话13800138000"))
    }

    @Test fun whitespace_delimits_and_is_dropped() {
        val value = "看 \t https://x.io/a-b2\n好!"
        assertEquals(listOf("看", "https", "://", "x", ".", "io", "/", "a", "-", "b", "2", "好", "!"), texts(value))
        assertEquals(value.filterNot { it.isWhitespace() }, texts(value).joinToString(""))
    }

    @Test fun empty_and_minimal_inputs_preserve_existing_classes() {
        assertEquals(emptyList<String>(), texts(""))
        assertEquals(emptyList<String>(), texts(" \t\n"))
        assertEquals(listOf("a"), texts("a"))
        assertEquals(listOf("1"), texts("1"))
        assertEquals(listOf("."), texts("."))
        assertEquals(listOf("你"), texts("你"))
    }

    @Test fun plain_latin_numeric_and_punctuation_runs_stay_grouped() {
        assertEquals(listOf("hello"), texts("hello"))
        assertEquals(listOf("13800138000"), texts("13800138000"))
        assertEquals(listOf("?!/."), texts("?!/."))
        assertEquals(listOf(Kind.LATIN, Kind.DIGIT, Kind.SYMBOL, Kind.HAN), kinds("a1.你"))
    }

    @Test fun plain_han_text_uses_single_character_blocks() {
        assertEquals(listOf("你", "好"), texts("你好"))
    }

    @Test fun non_bmp_han_uses_single_codepoint_blocks() {
        val extB = String(Character.toChars(0x20000))
        assertEquals(listOf(extB, "好"), texts(extB + "好"))
    }

    @Test fun url_punctuation_and_delimiters_reconstruct_in_order() {
        for (value in listOf(
            "(https://x.com)",
            "https://en.wikipedia.org/wiki/Foo_(bar)",
            "https://x.com.",
            "www.aegis.cn/path",
            "min.io/a-b2?x=1",
        )) {
            assertEquals(value, texts(value).joinToString(""))
        }
    }

    @Test fun ordinary_dotted_word_is_not_a_link() {
        assertEquals(listOf("file", ".", "txt"), texts("file.txt"))
    }
}
