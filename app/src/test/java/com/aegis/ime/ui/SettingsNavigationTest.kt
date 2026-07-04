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

package com.aegis.ime.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aegis.ime.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * debug.47 grouped-navigation structure, driven as real click-through on the REAL [SetupActivity]
 * (Compose UI tests on Robolectric): the settings home shows exactly the four group entries
 * (input / dictionaries & downloads / user dictionary / about & enable), each opens its own page with
 * the right content on it, and back (header arrow) returns home.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<SetupActivity>()

    private val ctx = RuntimeEnvironment.getApplication()
    private fun s(id: Int) = ctx.getString(id)

    /** group entry title → a marker string that only exists on that group's page. */
    private val groups = linkedMapOf(
        R.string.settings_group_input_title to R.string.layout_card_title,
        R.string.settings_group_dicts_title to R.string.dict_card_title,
        R.string.settings_group_userdict_title to R.string.user_dict_export_button,
        R.string.settings_group_about_title to R.string.setup_steps_title,
    )

    @Test fun home_shows_all_four_group_entries_and_none_of_the_moved_cards() {
        for ((title, _) in groups) compose.onNodeWithText(s(title)).assertExists()
        // The four groups in the graph are exactly the four routes reachable from home.
        assertEquals(4, SettingsRoutes.GROUPS.size)
        // The old single-page content must be OFF the home screen now (it lives in the sub-pages).
        for ((_, marker) in groups) compose.onNodeWithText(s(marker)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.fuzzy_card_title)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.gram_card_title)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.association_title)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.app_version_card_title)).assertDoesNotExist()
    }

    @Test fun every_group_opens_its_page_and_back_returns_home() {
        for ((title, marker) in groups) {
            compose.onNodeWithText(s(title)).performScrollTo().performClick()
            compose.onNodeWithText(s(marker)).assertExists()
            compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
            compose.onNodeWithText(s(R.string.setup_summary)).assertIsDisplayed()
            compose.onNodeWithText(s(marker)).assertDoesNotExist()
        }
    }

    @Test fun input_page_holds_keyboard_mode_fuzzy_and_associations_together() {
        compose.onNodeWithText(s(R.string.settings_group_input_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.layout_card_title)).assertExists()
        compose.onNodeWithText(s(R.string.fuzzy_card_title)).assertExists()
        compose.onNodeWithText(s(R.string.association_title)).assertExists()
    }

    @Test fun dicts_page_holds_the_dict_pack_and_the_model_cards() {
        compose.onNodeWithText(s(R.string.settings_group_dicts_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.dict_card_title)).assertExists()
        compose.onNodeWithText(s(R.string.gram_card_title)).assertExists()
    }

    @Test fun about_page_holds_version_enable_steps_and_the_try_field() {
        compose.onNodeWithText(s(R.string.settings_group_about_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.app_version_card_title)).assertExists()
        compose.onNodeWithText(s(R.string.setup_steps_title)).assertExists()
        compose.onNodeWithText(s(R.string.setup_try_field_label)).assertExists()
    }

    // ---- double-tap races: the outgoing page stays tappable during the nav transition, so a rapid
    // second tap lands too. The clock is frozen so both taps hit the SAME composition — the exact race.

    @Test fun double_tapping_back_never_pops_home_off_the_stack() {
        compose.onNodeWithText(s(R.string.settings_group_input_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.layout_card_title)).assertExists()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick() // second tap of the double-tap
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        // Unguarded, the second pop removes the HOME start destination and the NavHost composes NOTHING.
        compose.onNodeWithText(s(R.string.setup_summary)).assertExists()
    }

    @Test fun double_tapping_a_group_card_does_not_stack_the_page_twice() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(s(R.string.settings_group_input_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.settings_group_input_title)).performClick() // second tap of the double-tap
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.layout_card_title)).assertExists()
        // One back must land on home — not on a duplicate copy of the same page.
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
        compose.onNodeWithText(s(R.string.setup_summary)).assertExists()
        compose.onNodeWithText(s(R.string.layout_card_title)).assertDoesNotExist()
    }

    @Test fun two_finger_tap_on_two_group_cards_opens_only_one_page() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(s(R.string.settings_group_input_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.settings_group_dicts_title)).performClick() // lands while still on home
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        // First tap wins; one back returns straight home.
        compose.onNodeWithText(s(R.string.layout_card_title)).assertExists()
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
        compose.onNodeWithText(s(R.string.setup_summary)).assertExists()
    }
}
