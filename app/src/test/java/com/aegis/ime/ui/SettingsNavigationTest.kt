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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<SetupActivity>()

    private val ctx = RuntimeEnvironment.getApplication()
    private fun s(id: Int) = ctx.getString(id)

    private val groups = linkedMapOf(
        R.string.settings_group_input_title to R.string.layout_card_title,
        R.string.settings_group_dicts_title to R.string.dict_card_title,
        R.string.settings_group_userdict_title to R.string.user_dict_export_button,
        R.string.settings_group_about_title to R.string.setup_steps_title,
    )

    @Test fun home_shows_all_four_group_entries_and_none_of_the_moved_cards() {
        for ((title, _) in groups) compose.onNodeWithText(s(title)).assertExists()
        assertEquals(4, SettingsRoutes.GROUPS.size)
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


    @Test fun double_tapping_back_never_pops_home_off_the_stack() {
        compose.onNodeWithText(s(R.string.settings_group_input_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.layout_card_title)).assertExists()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.setup_summary)).assertExists()
    }

    @Test fun double_tapping_a_group_card_does_not_stack_the_page_twice() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(s(R.string.settings_group_input_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.settings_group_input_title)).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.layout_card_title)).assertExists()
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
        compose.onNodeWithText(s(R.string.setup_summary)).assertExists()
        compose.onNodeWithText(s(R.string.layout_card_title)).assertDoesNotExist()
    }

    @Test fun two_finger_tap_on_two_group_cards_opens_only_one_page() {
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(s(R.string.settings_group_input_title)).performScrollTo().performClick()
        compose.onNodeWithText(s(R.string.settings_group_dicts_title)).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(s(R.string.layout_card_title)).assertExists()
        compose.onNodeWithContentDescription(s(R.string.settings_back)).performClick()
        compose.onNodeWithText(s(R.string.setup_summary)).assertExists()
    }
}
