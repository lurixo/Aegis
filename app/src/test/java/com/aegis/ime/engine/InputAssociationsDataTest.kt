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

    @Test fun reported_full_half_duplicates_now_surface_only_the_full_width_form() {
        val renminbi = InputAssociations.lookup("renminbi")
        assertTrue("renminbi offers the full-width ￥ (got $renminbi)", "￥" in renminbi)
        assertTrue("half-width ¥ (U+00A5) must NOT be a renminbi candidate (got $renminbi)", "¥" !in renminbi)
        val wenhao = InputAssociations.lookup("wenhao")
        assertTrue("wenhao offers the full-width ？ (got $wenhao)", "？" in wenhao)
        assertTrue("half-width ? (U+003F) must NOT be a wenhao candidate (got $wenhao)", "?" !in wenhao)
        assertTrue("¥ stays reachable via riyuan (日元)", "¥" in InputAssociations.lookup("riyuan"))
    }

    @Test fun every_symbol_row_glyph_surfaces_for_every_name() {
        for (row in SymbolAssociations.rows()) {
            for (key in row.keyList) {
                val hit = InputAssociations.lookup(key)
                for (g in row.glyphList) {
                    assertTrue("'$g' (${row.name.ifEmpty { row.keys }}) must appear for '$key', got $hit", g in hit)
                }
            }
        }
    }

    @Test fun glyphs_carry_no_han_characters_beyond_the_allowlist() {
        val allowed = setOf("円", "元", "圆", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖", "拾", "佰", "仟", "万", "亿", "貳", "參", "陸", "萬", "億")
        for ((key, glyphs) in InputAssociations.entriesForTest()) {
            for (g in glyphs) {
                if (g in allowed) continue
                val hasHan = g.codePoints().anyMatch { Character.isIdeographic(it) }
                assertTrue("key '$key' carries a Han-character glyph '$g' — allowlist it deliberately or drop it", !hasHan)
            }
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
