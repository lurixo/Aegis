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

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aegis.ime.R
import com.aegis.ime.ui.theme.AegisTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiLanguageCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun the_options_show_the_current_choice_and_write_the_chosen_locale() {
        val written = ArrayList<String?>()
        compose.setContent {
            AegisTheme {
                UiLanguageCard(read = { "zh-CN" }, write = { _, tag -> written += tag })
            }
        }

        compose.onNodeWithText(ctx.getString(R.string.settings_language_title)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.language_zh)).assertIsSelected()

        compose.onNodeWithText(ctx.getString(R.string.language_en)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.language_en)).assertIsSelected()
        assertEquals(listOf<String?>("en"), written)

        compose.onNodeWithText(ctx.getString(R.string.language_follow_system)).performClick()
        compose.onNodeWithText(ctx.getString(R.string.language_follow_system)).assertIsSelected()
        assertEquals(listOf("en", null), written)

        compose.onNodeWithText(ctx.getString(R.string.language_zh)).performClick()
        assertEquals(listOf("en", null, "zh-CN"), written)
    }
}
