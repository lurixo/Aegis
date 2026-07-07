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

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aegis.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun str(id: Int) = RuntimeEnvironment.getApplication().getString(id)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupAboutEntryTest {
    @get:Rule val compose = createAndroidComposeRule<AboutActivity>()

    @Test fun about_page_opens_the_backup_activity() {
        compose.onNodeWithText(str(R.string.settings_backup_title)).performScrollTo().performClick()
        compose.waitForIdle()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(BackupActivity::class.java.name, started?.component?.className)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupActivityUiTest {
    @get:Rule val compose = createAndroidComposeRule<BackupActivity>()

    @Test fun shows_export_and_import_actions() {
        compose.onNodeWithText(str(R.string.backup_export_button)).assertExists()
        compose.onNodeWithText(str(R.string.backup_import_button)).assertExists()
    }

    @Test fun back_arrow_finishes_the_activity() {
        compose.onNodeWithContentDescription(str(R.string.settings_back)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.isFinishing)
    }
}
