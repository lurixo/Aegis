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

package com.aegis.ime.engine

import com.aegis.ime.decoder.T9Pinyin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputAssociationsDataTest {


    @Test fun every_key_is_lowercase_ascii_letters_only() {
        for (key in InputAssociations.entriesForTest().keys) {
            assertTrue("key '$key' must be non-empty a-z", key.isNotEmpty() && key.all { it in 'a'..'z' })
        }
    }

    @Test fun every_key_is_a_valid_toneless_full_pinyin_sequence() {
        for (key in InputAssociations.entriesForTest().keys) {
            assertTrue(
                "key '$key' must segment into whole Mandarin syllables (no jianpin, no prefixes)",
                T9Pinyin.segmentLetters(key) != null,
            )
        }
    }


    @Test fun separated_and_connected_input_forms_are_equivalent_for_every_key() {
        for (key in InputAssociations.entriesForTest().keys) {
            val syllables = T9Pinyin.segmentLetters(key) ?: continue
            val separated = syllables.joinToString("'")
            assertEquals(
                "lookup('$separated') must equal lookup('$key')",
                InputAssociations.lookup(key),
                InputAssociations.lookup(separated),
            )
        }
    }

    @Test fun lookup_is_case_insensitive_and_preserves_all_matches() {
        for ((key, glyphs) in InputAssociations.entriesForTest()) {
            val hit = InputAssociations.lookup(key)
            assertEquals("lookup must retain every matching glyph", glyphs, hit)
            assertEquals("uppercase form must hit the same entry", hit, InputAssociations.lookup(key.uppercase()))
        }
    }


    private val legacyTable: Map<String, List<String>> = mapOf(
        "haode" to listOf("👌"),
        "hao" to listOf("👍"),
        "zan" to listOf("👍"),
        "bang" to listOf("👍"),
        "guzhang" to listOf("👏"),
        "zaijian" to listOf("👋"),
        "baibai" to listOf("👋"),
        "xiexie" to listOf("🙏"),
        "xie" to listOf("🙏"),
        "qiu" to listOf("🙏"),
        "haha" to listOf("😂"),
        "xiao" to listOf("😄", "😂"),
        "kaixin" to listOf("😄"),
        "ku" to listOf("😭"),
        "shangxin" to listOf("😢"),
        "nu" to listOf("😡"),
        "shengqi" to listOf("😡"),
        "ai" to listOf("❤️"),
        "aini" to listOf("❤️"),
        "xin" to listOf("❤️"),
        "shuijiao" to listOf("😴"),
        "huo" to listOf("🔥"),
        "xing" to listOf("⭐"),
        "yueliang" to listOf("🌙"),
        "taiyang" to listOf("☀️"),
        "yu" to listOf("☔"),
        "xue" to listOf("❄️"),
        "hua" to listOf("🌸"),
        "liwu" to listOf("🎁"),
        "dangao" to listOf("🎂"),
        "shengri" to listOf("🎂", "🎉"),
        "qingzhu" to listOf("🎉"),
        "yinyue" to listOf("🎵"),
        "qian" to listOf("💰"),
        "diannao" to listOf("💻"),
        "shouji" to listOf("📱"),
        "jia" to listOf("+"),
        "jian" to listOf("−"),
        "cheng" to listOf("×"),
        "chu" to listOf("÷"),
        "dengyu" to listOf("="),
        "deng" to listOf("="),
        "baifen" to listOf("%"),
        "baifenzhi" to listOf("%"),
        "du" to listOf("°"),
        "renminbi" to listOf("￥"),
        "meiyuan" to listOf("\$"),
        "ouyuan" to listOf("€"),
    )

    @Test fun all_48_legacy_entries_keep_their_glyphs_first_in_order() {
        assertEquals(48, legacyTable.size)
        for ((key, glyphs) in legacyTable) {
            val hit = InputAssociations.lookup(key)
            assertEquals(
                "legacy '$key' must keep its original glyphs first (got $hit)",
                glyphs,
                hit.take(glyphs.size),
            )
        }
    }
}
