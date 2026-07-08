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
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSystemBarThemeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun system_bar_icons_follow_theme_changes_without_recreating_the_activity() {
        val activity = compose.activity
        val configuration = mutableStateOf(Configuration(activity.resources.configuration))
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                SettingsActivityChrome { Box(Modifier.fillMaxSize()) }
            }
        }
        for (dark in listOf(false, true, false)) {
            compose.runOnIdle {
                configuration.value = Configuration(configuration.value).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
            }
            compose.runOnIdle {
                val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                assertEquals(!dark, controller.isAppearanceLightStatusBars)
                assertEquals(!dark, controller.isAppearanceLightNavigationBars)
                assertEquals(activity, compose.activity)
            }
        }
    }
}
