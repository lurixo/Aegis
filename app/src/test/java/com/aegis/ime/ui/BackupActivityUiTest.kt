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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.aegis.ime.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun str(id: Int) = RuntimeEnvironment.getApplication().getString(id)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupActivityUiTest {
    @get:Rule val compose = createAndroidComposeRule<BackupActivity>()

    @Test fun shows_export_and_import_actions() {
        compose.onNodeWithText(str(R.string.backup_default_password_title)).assertExists()
        compose.onNodeWithText(str(R.string.backup_export_button)).assertExists()
        compose.onNodeWithText(str(R.string.backup_import_button)).assertExists()
    }

    @Test fun removing_default_password_requires_second_step_confirmation() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("aegis_backup_default_password", Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putInt("version", 1)
            .putString("iv", "AA==")
            .putString("ciphertext", "AA==")
            .commit()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.backup_default_password_remove_button)).performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue("first remove click must not clear the saved password", prefs.contains("ciphertext"))
        compose.onNodeWithText(str(R.string.backup_default_password_remove_title)).assertExists()

        compose.onNodeWithText(str(R.string.backup_default_password_remove_confirm_button)).performClick()
        compose.waitForIdle()

        assertFalse("confirmation must clear the saved password", prefs.contains("ciphertext"))
        compose.onNodeWithText(str(R.string.backup_default_password_remove_title)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.backup_default_password_removed)).assertExists()
    }

    @Test fun back_arrow_finishes_the_activity() {
        compose.onNodeWithContentDescription(str(R.string.settings_back)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(compose.activity.isFinishing)
    }
}
