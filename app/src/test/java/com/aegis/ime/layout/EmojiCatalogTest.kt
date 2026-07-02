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

/** Chinese IME behavior note. */
class EmojiCatalogTest {

    @Test fun has_the_expected_categories_in_order() {
        // Chinese IME behavior note.
        assertEquals(
            listOf("黄脸", "手势", "旗帜", "动物", "植物", "食物", "旅行", "活动", "物品", "符号"),
            EmojiCatalog.categories.map { it.title },
        )
    }

    @Test fun flags_sit_between_gestures_and_animals() {
        val titles = EmojiCatalog.categories.map { it.title }
        assertTrue("旗帜 after 手势", titles.indexOf("旗帜") > titles.indexOf("手势"))
        assertTrue("旗帜 before 动物", titles.indexOf("旗帜") < titles.indexOf("动物"))
    }

    @Test fun is_a_near_complete_keyboard() {
        // E2: ~1500+ across the categories (a near-complete standard emoji keyboard), not the old ~200.
        assertTrue("only ${EmojiCatalog.categories.sumOf { it.emoji.size }} emoji", EmojiCatalog.categories.sumOf { it.emoji.size } >= 1000)
    }

    @Test fun every_category_non_empty_and_no_duplicates() {
        for (c in EmojiCatalog.categories) {
            assertTrue("${c.title} empty", c.emoji.isNotEmpty())
            assertEquals("${c.title} has duplicates", c.emoji.size, c.emoji.toSet().size)
        }
    }

    @Test fun flags_are_regional_indicator_emoji() {
        val flags = EmojiCatalog.categories.first { it.id == "flag" }.emoji
        assertTrue("🇨🇳 present", "🇨🇳" in flags)
        assertTrue("🇺🇸 present", "🇺🇸" in flags)
        assertTrue("several flags", flags.size >= 10)
    }

    @Test fun gestures_include_thumbs_up() {
        assertTrue("👍" in EmojiCatalog.categories.first { it.id == "hand" }.emoji)
    }
}
