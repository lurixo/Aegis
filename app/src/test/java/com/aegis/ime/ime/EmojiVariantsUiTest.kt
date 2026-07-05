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

package com.aegis.ime.ime

import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.layout.EmojiVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The long-press skin-tone / gender variant selector. The grid cell always shows the default
 * (neutral / yellow) form; a long-press opens a card whose gender row and skin-tone row are built on demand
 * from [EmojiVariants]' combination rules and whose selections COMPOSE (gender × tone). A plain tap is
 * unaffected and still commits the default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojiVariantsUiTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private fun view() = EmojiView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
    private val hand = EmojiCatalog.categories.first { it.id == "hand" }.emoji

    @Test fun long_press_opens_the_selector_only_for_a_variant_capable_cell() {
        val v = view()
        v.openCategoryForTest(2) // 手势
        val plain = hand.indexOf("👀")   // eyes: no skin tone, no gender
        val variant = hand.indexOf("👋") // waving hand: skin tone
        assertTrue("fixtures present", plain >= 0 && variant >= 0)
        assertFalse("a plain cell's long-press opens nothing (falls through to a normal tap)", v.longPressCellForTest(plain))
        assertFalse(v.variantVisibleForTest())
        assertTrue("a variant cell's long-press opens the selector", v.longPressCellForTest(variant))
        assertTrue(v.variantVisibleForTest())
    }

    @Test fun a_plain_tap_still_commits_the_default_form() {
        val v = view()
        var committed = ""
        v.onEmoji = { committed = it }
        v.openCategoryForTest(2)
        v.tapCellForTest(hand.indexOf("🧑‍⚕️")) // neutral health worker cell
        assertEquals("🧑‍⚕️", committed)
    }

    @Test fun skin_only_emoji_offers_default_plus_five_tones_and_commits_the_toned_form() {
        val v = view()
        var committed = ""
        v.onEmoji = { committed = it }
        v.openVariantsForTest("👋")
        assertTrue(v.variantVisibleForTest())
        assertEquals("no gender row for a genderless emoji", emptyList<String>(), v.variantGenderFormsForTest())
        val tones = v.variantSkinFormsForTest()
        assertEquals("default + 5 skin tones", 6, tones.size)
        assertEquals("default (untoned) shown first", "👋", tones[0])
        assertEquals(EmojiVariants.applyTone("👋", EmojiVariants.SKIN_TONES[2]), tones[3])
        v.tapVariantSkinForTest(3)
        assertEquals("tapping a swatch commits that toned form", tones[3], committed)
        assertFalse("the selector dismisses after a commit", v.variantVisibleForTest())
    }

    @Test fun gender_and_skin_axes_compose() {
        val v = view()
        var committed = ""
        v.onEmoji = { committed = it }
        v.openVariantsForTest("🧑‍⚕️")
        assertEquals(listOf("🧑‍⚕️", "👨‍⚕️", "👩‍⚕️"), v.variantGenderFormsForTest())
        assertEquals("skin row starts on the neutral form", "🧑‍⚕️", v.variantSkinFormsForTest()[0])
        // pick 女 → the skin row re-renders to the woman form's tones (composition, not a stored table)
        v.tapVariantGenderForTest(2)
        val womanTones = v.variantSkinFormsForTest()
        assertEquals(6, womanTones.size)
        assertEquals("👩‍⚕️", womanTones[0])
        v.tapVariantSkinForTest(4) // woman + 4th tone
        assertEquals(EmojiVariants.applyTone("👩‍⚕️", EmojiVariants.SKIN_TONES[3]), committed)
        assertFalse(v.variantVisibleForTest())
    }

    @Test fun gender_only_emoji_commits_the_gender_directly() {
        val v = view()
        var committed = ""
        v.onEmoji = { committed = it }
        v.openVariantsForTest("🧞") // genie: man/woman ZWJ but no skin tone
        assertEquals(listOf("🧞", "🧞‍♂️", "🧞‍♀️"), v.variantGenderFormsForTest())
        assertEquals("no skin row for a tone-less role", emptyList<String>(), v.variantSkinFormsForTest())
        v.tapVariantGenderForTest(1)
        assertEquals("🧞‍♂️", committed)
        assertFalse(v.variantVisibleForTest())
    }
}
