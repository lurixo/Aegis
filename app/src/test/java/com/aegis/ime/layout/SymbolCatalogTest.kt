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

package com.aegis.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the D symbols catalogue: category set/order, non-empty, no dups, and full pinyin tone coverage. */
class SymbolCatalogTest {

    @Test fun category_titles_match_the_expected_order() {
        // 常用 is dynamic (not in the static catalogue); the rest follow the expected order.
        assertEquals(
            listOf("中文", "英文", "货币", "网络", "数学", "箭头", "角标", "序号", "音标", "拼音"),
            SymbolCatalog.categories.map { it.title },
        )
    }

    @Test fun currency_category_sits_between_english_and_net_with_common_symbols() {
        // U24: 货币 between 英文 and 网络.
        val ids = SymbolCatalog.categories.map { it.id }
        assertEquals(ids.indexOf("en") + 1, ids.indexOf("currency"))
        assertEquals(ids.indexOf("currency") + 1, ids.indexOf("net"))
        val cur = SymbolCatalog.categories.first { it.id == "currency" }.symbols
        for (c in listOf("$", "¥", "€", "£", "₩", "₹", "₽")) assertTrue("missing $c", c in cur)
    }

    @Test fun every_category_is_non_empty_and_has_no_duplicates() {
        for (c in SymbolCatalog.categories) {
            assertTrue("${c.title} must not be empty", c.symbols.isNotEmpty())
            assertEquals("${c.title} has duplicate symbols", c.symbols.size, c.symbols.toSet().size)
        }
    }

    @Test fun pinyin_covers_every_base_vowel_in_all_four_tones() {
        val pinyin = SymbolCatalog.categories.first { it.id == "pinyin" }.symbols.toSet()
        // 拼音 āōēīūǖ 全声调 — each base vowel across tones 1-4.
        val toned = listOf(
            "ā", "á", "ǎ", "à",
            "ō", "ó", "ǒ", "ò",
            "ē", "é", "ě", "è",
            "ī", "í", "ǐ", "ì",
            "ū", "ú", "ǔ", "ù",
            "ǖ", "ǘ", "ǚ", "ǜ",
        )
        for (t in toned) assertTrue("missing toned vowel $t", t in pinyin)
    }

    @Test fun english_includes_escaped_glyphs() {
        val en = SymbolCatalog.categories.first { it.id == "en" }.symbols
        assertTrue("backslash present", "\\" in en)
        assertTrue("double-quote present", "\"" in en)
    }

    @Test fun net_category_is_url_helpers_not_kaomoji() {
        // 网络 = `. / @ - _ http:// https://` (URL helpers), not kaomoji/decoration.
        val net = SymbolCatalog.categories.first { it.id == "net" }.symbols
        for (u in listOf(".", "/", "@", "-", "_", "http://", "https://")) assertTrue("missing $u", u in net)
        assertTrue(
            "no kaomoji/decoration in 网络",
            net.none { s -> s.contains("(") || s.contains("^") || s.contains("♥") || s.contains("★") || s.contains("≧") },
        )
    }
}
