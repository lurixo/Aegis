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

    @Test fun url_stays_one_block() {
        assertEquals(listOf("https://github.com/a_b?x=1"), texts("https://github.com/a_b?x=1"))
        assertEquals(listOf(Kind.LINK), kinds("https://github.com/a_b?x=1"))
    }

    @Test fun email_stays_one_block() {
        assertEquals(listOf("联", "系", "bob@x.com", "谢", "谢"), texts("联系bob@x.com谢谢"))
        assertEquals(Kind.EMAIL, ClipSplitter.split("bob@x.com").single().kind)
    }

    @Test fun link_embedded_in_chinese_ends_cleanly_without_trailing_punct() {
        assertEquals(listOf("看", "这", "个", "https://x.com", "。"), texts("看这个https://x.com。"))
        assertEquals(listOf("访", "问", "https://a.b/c?d=1", "，"), texts("访问https://a.b/c?d=1，"))
    }

    @Test fun www_and_bare_domain_are_links() {
        assertEquals(listOf("www.aegis.cn"), texts("www.aegis.cn"))
        assertEquals(listOf("去", "x.com", "看"), texts("去x.com看"))
    }

    @Test fun digits_split_from_chinese() {
        assertEquals(listOf("打", "电", "话", "13800138000"), texts("打电话13800138000"))
        assertEquals(listOf(Kind.HAN, Kind.HAN, Kind.HAN, Kind.DIGIT), kinds("打电话13800138000"))
    }

    @Test fun whitespace_dropped_blocks_kept() {
        assertEquals(listOf("hello", "world"), texts("hello   world"))
        assertEquals(emptyList<String>(), texts("   "))
        assertEquals(emptyList<String>(), texts(""))
    }

    @Test fun plain_han_text_uses_single_character_blocks() {
        assertEquals(listOf("你", "好"), texts("你好"))
    }

    @Test fun non_bmp_han_uses_single_codepoint_blocks() {
        val extB = String(Character.toChars(0x20000))
        assertEquals(listOf(extB, "好"), texts(extB + "好"))
    }

    @Test fun parenthesized_url_is_kept_whole() {
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), texts("https://en.wikipedia.org/wiki/Foo_(bar)"))
        assertEquals(listOf("(", "https://x.com", ")"), texts("(https://x.com)"))
    }

    @Test fun url_trailing_sentence_punct_is_stripped() {
        assertEquals(listOf("看", "https://x.com", "."), texts("看https://x.com."))
    }

    @Test fun wider_tlds_are_recognized_as_links() {
        for (host in listOf("x.ai", "x.ru", "x.uk", "x.tv", "min.io")) {
            assertEquals(host, ClipSplitter.split(host).single().also { assert(it.kind == Kind.LINK) }.text)
        }
    }

    @Test fun ordinary_dotted_word_is_not_a_link() {
        assertEquals(listOf("file", ".", "txt"), texts("file.txt"))
    }
}
