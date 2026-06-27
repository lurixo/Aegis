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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipSplitterTest {

    private fun texts(s: String) = ClipSplitter.blocks(s)
    private fun kinds(s: String) = ClipSplitter.split(s).map { it.kind }

    @Test fun chinese_sentence_is_word_segmented_not_per_character() {
        assertEquals(
            listOf("明天", "下午", "三", "点", "在", "公司", "门口", "见"),
            texts("明天下午三点在公司门口见"),
        )
        assertTrue(kinds("明天下午三点在公司门口见").all { it == Kind.HAN })
    }

    @Test fun common_two_character_words_group() {
        assertEquals(listOf("你好"), texts("你好"))
        assertEquals(listOf("打电话", "13800138000"), texts("打电话13800138000"))
        assertEquals(listOf(Kind.HAN, Kind.DIGIT), kinds("打电话13800138000"))
    }

    @Test fun mixed_chinese_latin_symbol() {
        assertEquals(listOf("你好", "hello", ",", "world", "!"), texts("你好hello,world!"))
        assertEquals(listOf(Kind.HAN, Kind.LATIN, Kind.SYMBOL, Kind.LATIN, Kind.SYMBOL), kinds("你好hello,world!"))
    }

    @Test fun email_stays_one_block() {
        assertEquals(listOf("联系", "bob@x.com", "谢谢"), texts("联系bob@x.com谢谢"))
        assertEquals(Kind.EMAIL, ClipSplitter.split("bob@x.com").single().kind)
    }

    @Test fun order_number_string_segments_every_class() {
        val value = "订单号:AB-12345，金额￥89.9"
        assertEquals(
            listOf("订单", "号", ":", "AB-12345", "，", "金额", "￥", "89.9"),
            texts(value),
        )
        assertEquals(
            listOf(Kind.HAN, Kind.HAN, Kind.SYMBOL, Kind.LATIN, Kind.SYMBOL, Kind.HAN, Kind.SYMBOL, Kind.DIGIT),
            kinds(value),
        )
    }

    @Test fun japanese_mixed_script_segments_and_never_glues_kana_to_punctuation() {
        assertEquals(listOf("東京", "タワー", "へ", "行ってきます"), texts("東京タワーへ行ってきます"))
        assertEquals(listOf(Kind.HAN, Kind.KANA, Kind.KANA, Kind.HAN), kinds("東京タワーへ行ってきます"))
    }

    @Test fun japanese_kana_runs_are_first_class_and_okurigana_attaches() {
        assertEquals(listOf("こんにちは", "世界"), texts("こんにちは世界"))
        assertEquals(listOf(Kind.KANA, Kind.HAN), kinds("こんにちは世界"))
        assertEquals(listOf("お", "茶を", "飲む"), texts("お茶を飲む"))
    }

    @Test fun english_mixed_alphanumeric_tokens_stay_whole() {
        assertEquals(listOf("Meet", "me", "at", "5pm"), texts("Meet me at 5pm"))
        assertEquals(listOf("AB-12345"), texts("AB-12345"))
        assertEquals(listOf("89.9"), texts("89.9"))
        assertEquals(listOf("2020-01-01"), texts("2020-01-01"))
        assertEquals(Kind.DIGIT, ClipSplitter.split("89.9").single().kind)
        assertEquals(Kind.LATIN, ClipSplitter.split("5pm").single().kind)
    }

    @Test fun accented_latin_words_stay_whole() {
        assertEquals(listOf("café", "résumé"), texts("café résumé"))
        assertTrue(kinds("café résumé").all { it == Kind.LATIN })
    }

    @Test fun urls_are_single_link_blocks() {
        for (url in listOf(
            "https://blog.youtube/news-and-events/watch-fifa-world-cup-youtube/",
            "http://x.io/a-b/c2?x=10&y=z",
            "www.aegis.cn/path",
            "https://en.wikipedia.org/wiki/Foo_(bar)",
        )) {
            assertEquals(listOf(url), texts(url))
            assertEquals(Kind.LINK, ClipSplitter.split(url).single().kind)
        }
    }

    @Test fun urls_keep_surrounding_text_and_punctuation_separate() {
        assertEquals(listOf("看", "这个", "https://x.com", "。"), texts("看这个https://x.com。"))
        assertEquals(listOf("(", "https://x.com", ")"), texts("(https://x.com)"))
        assertEquals(listOf("https://x.com", "."), texts("https://x.com."))
        assertEquals(
            listOf("看", "这个", "https://blog.youtube/a-b/", "好"),
            texts("看这个https://blog.youtube/a-b/好"),
        )
    }

    @Test fun bare_dotted_words_are_not_links() {
        assertEquals(listOf("file", ".", "txt"), texts("file.txt"))
        assertEquals(listOf("x", ".", "ai"), texts("x.ai"))
    }

    @Test fun currency_signs_stand_alone() {
        assertEquals(listOf("￥", "89.9"), texts("￥89.9"))
        assertEquals(listOf("$", "5"), texts("$5"))
        assertEquals(Kind.SYMBOL, ClipSplitter.split("￥").single().kind)
    }

    @Test fun grapheme_clusters_are_never_split() {
        val flag = "🇨🇳"
        val zwjFamily = "👨‍👩‍👧‍👦"
        val skin = "👍🏽"
        val keycap = "1️⃣"
        for (g in listOf(flag, zwjFamily, skin, keycap)) {
            assertEquals(listOf(g), texts(g))
        }
        assertEquals(
            listOf("旗", flag, "家", zwjFamily, "好", skin, "了"),
            texts("旗${flag}家${zwjFamily}好${skin}了"),
        )
    }

    @Test fun combining_marks_do_not_split_a_word() {
        val decomposed = "café"
        assertEquals(listOf(decomposed), texts(decomposed))
        assertEquals(listOf("é"), texts("é"))
    }

    @Test fun whitespace_delimits_and_is_dropped() {
        val value = "看 \t https://x.io/a-b2\n好!"
        assertEquals(listOf("看", "https://x.io/a-b2", "好", "!"), texts(value))
        assertEquals(value.filterNot { it.isWhitespace() }, texts(value).joinToString(""))
    }

    @Test fun empty_and_minimal_inputs() {
        assertEquals(emptyList<String>(), texts(""))
        assertEquals(emptyList<String>(), texts(" \t\n"))
        assertEquals(listOf("a"), texts("a"))
        assertEquals(listOf("1"), texts("1"))
        assertEquals(listOf("."), texts("."))
        assertEquals(listOf("你"), texts("你"))
    }

    @Test fun reconstruction_drops_only_whitespace() {
        for (value in listOf(
            "明天下午三点在公司门口见",
            "订单号:AB-12345，金额￥89.9",
            "東京タワーへ行ってきます",
            "Meet me at 5pm",
            "看这个https://x.com。",
            "(https://x.com)",
            "https://en.wikipedia.org/wiki/Foo_(bar)",
            "联系bob@x.com谢谢",
            "café résumé",
            "min.io/a-b2?x=1",
            "旗🇨🇳家👨‍👩‍👧‍👦好👍🏽了",
        )) {
            assertEquals("reconstruct '$value'", value.filterNot { it.isWhitespace() }, texts(value).joinToString(""))
        }
    }
}
