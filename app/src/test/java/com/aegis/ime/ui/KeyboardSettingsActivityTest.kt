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

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aegis.ime.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class KeyboardSettingsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<KeyboardSettingsActivity>()

    @Test fun holds_only_keyboard_settings_and_back_finishes() {
        for (title in listOf(R.string.layout_card_title, R.string.letter_case_title,
            R.string.key_sound_title, R.string.key_vibration_title, R.string.key_preview_title,
            R.string.settings_language_title)) {
            compose.onNodeWithText(compose.activity.getString(title)).performScrollTo().assertExists()
        }
        for (title in listOf(R.string.default_lang_title, R.string.fuzzy_master_title,
            R.string.association_master_title, R.string.association_cn_title, R.string.association_en_title,
            R.string.association_email_title, R.string.auto_learn_title)) {
            compose.onNodeWithText(compose.activity.getString(title)).assertDoesNotExist()
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.settings_back)).performClick()
        compose.waitForIdle()
        assertTrue("back arrow finishes the Activity", compose.activity.isFinishing)
    }
}
