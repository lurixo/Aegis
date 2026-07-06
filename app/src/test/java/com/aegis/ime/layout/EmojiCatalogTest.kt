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

class EmojiCatalogTest {

    @Test fun has_the_expected_categories_in_order() {
        assertEquals(
            listOf("face", "hand", "flag", "animal", "plant", "food", "travel", "activity", "object", "symbol"),
            EmojiCatalog.categories.map { it.id },
        )
    }

    @Test fun flags_sit_between_gestures_and_animals() {
        val ids = EmojiCatalog.categories.map { it.id }
        assertTrue("flag after hand", ids.indexOf("flag") > ids.indexOf("hand"))
        assertTrue("flag before animal", ids.indexOf("flag") < ids.indexOf("animal"))
    }

    @Test fun is_a_full_coverage_keyboard_with_pinned_per_category_counts() {
        val counts = EmojiCatalog.categories.associate { it.id to it.emoji.size }
        assertEquals(
            mapOf(
                "face" to 130, "hand" to 230, "flag" to 270, "animal" to 130, "plant" to 76,
                "food" to 131, "travel" to 140, "activity" to 85, "object" to 295, "symbol" to 263,
            ),
            counts,
        )
        assertEquals("total default cells", 1750, EmojiCatalog.categories.sumOf { it.emoji.size })
    }

    @Test fun every_category_non_empty_and_no_duplicates_within_or_across() {
        val seen = HashMap<String, String>()
        for (c in EmojiCatalog.categories) {
            assertTrue("${c.id} empty", c.emoji.isNotEmpty())
            assertEquals("${c.id} has duplicates", c.emoji.size, c.emoji.toSet().size)
            for (e in c.emoji) {
                val prev = seen.put(e, c.id)
                assertTrue("'$e' appears in both ${prev} and ${c.id}", prev == null)
            }
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
