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

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xxhdpi")
class AppNarrowLargeFontTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private lateinit var originalConfiguration: Configuration
    private var scenario: ActivityScenario<UserDictActivity>? = null

    @Before fun launchWithLargeText() {
        originalConfiguration = Configuration(app.resources.configuration)
        app.resources.updateConfiguration(
            Configuration(originalConfiguration).apply { fontScale = 1.8f },
            app.resources.displayMetrics,
        )
        scenario = ActivityScenario.launch(UserDictActivity::class.java)
    }

    @After fun closeAndRestoreConfiguration() {
        scenario?.close()
        app.resources.updateConfiguration(originalConfiguration, app.resources.displayMetrics)
    }

    @Test fun user_dictionary_core_actions_and_add_form_remain_reachable() {
        compose.onNodeWithTag("app_back_button").assertIsDisplayed()
        compose.onNodeWithTag("user_dict_search").assertIsDisplayed()
        compose.onNodeWithTag("user_dict_open_add").assertIsDisplayed().performClick()
        compose.onNodeWithTag("user_dict_new_word").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("user_dict_new_reading").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("user_dict_add").performScrollTo().assertIsDisplayed()
    }
}
