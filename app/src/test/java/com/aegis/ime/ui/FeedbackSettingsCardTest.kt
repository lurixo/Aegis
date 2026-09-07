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

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aegis.ime.R
import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.ui.theme.AegisTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FeedbackSettingsCardTest {
    @get:Rule val compose = createComposeRule()
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    private fun node(id: Int) = compose.onNodeWithText(context.getString(id))

    @Test fun fuzzy_rules_hide_when_off_and_keep_their_choices() {
        prefs.edit().putBoolean("fuzzy", false).commit()
        compose.setContent {
            AegisTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { FuzzySettingsCard() }
            }
        }
        node(R.string.fuzzy_rule_zh_title).assertDoesNotExist()
        node(R.string.fuzzy_master_title).performClick()
        node(R.string.fuzzy_rule_zh_title).performScrollTo().performClick()
        node(R.string.fuzzy_master_title).performScrollTo().performClick()
        node(R.string.fuzzy_rule_zh_title).assertDoesNotExist()
        node(R.string.fuzzy_master_title).performClick()
        node(R.string.fuzzy_rule_zh_title).assertExists()
        assertFalse(prefs.getBoolean(Fuzzy.prefKey("zh"), true))
    }
}
