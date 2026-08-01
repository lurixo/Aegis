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

package com.aegis.ime.user

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UserSettingsWiringTest {

    private fun source(path: String) = File(path).readText()

    @Test fun every_ordinary_settings_surface_reads_sqlite_and_commits_before_visible_state() {
        for (path in listOf(
            "src/main/java/com/aegis/ime/ui/AssociationToggleCard.kt",
            "src/main/java/com/aegis/ime/ui/DefaultLangCard.kt",
            "src/main/java/com/aegis/ime/ui/FuzzySettingsCard.kt",
            "src/main/java/com/aegis/ime/ui/KeyFeedbackCards.kt",
            "src/main/java/com/aegis/ime/ui/LayoutChoiceCard.kt",
            "src/main/java/com/aegis/ime/ui/LetterCaseCard.kt",
            "src/main/java/com/aegis/ime/ui/SetupActivity.kt",
        )) {
            val text = source(path)
            assertTrue("$path must use the SQLite settings facade", text.contains("userSettings(context)"))
            assertTrue("$path must gate UI updates on synchronous persistence", text.contains("persistUserSetting("))
            assertFalse("$path must not read ordinary settings from SharedPreferences", text.contains("getSharedPreferences(\"aegis\""))
        }
    }

    @Test fun service_uses_sqlite_for_runtime_settings_and_fail_closed_clipboard_privacy() {
        val service = source("src/main/java/com/aegis/ime/AegisInputMethodService.kt")
        assertTrue(service.contains("private val settingsPreferences by lazy { userSettings(this) }"))
        assertTrue(service.contains("settingsPreferences.registerOnSharedPreferenceChangeListener(settingsHotApply)"))
        assertTrue(service.contains("settingsPreferences.getBoolean(\"clip_history\", false)"))
        assertTrue(service.contains("settingsPreferences.edit().putBoolean(\"clip_history\", on).commit()"))
        assertFalse(service.contains("getSharedPreferences(\"aegis\", MODE_PRIVATE).getBoolean(\"clip_history\""))
    }

    @Test fun raw_sqlite_is_used_without_room_datastore_or_dependency_changes() {
        val database = source("src/main/java/com/aegis/ime/user/UserDataDatabase.kt")
        val settings = source("src/main/java/com/aegis/ime/user/UserSettingsPreferences.kt")
        val dependencies = source("../build.gradle.kts") + source("build.gradle.kts")
        assertTrue(database.contains("SQLiteDatabase"))
        assertTrue(settings.contains("UserDataDatabase"))
        assertFalse(dependencies.contains("androidx.room"))
        assertFalse(dependencies.contains("datastore"))
    }

    @Test fun settings_reads_use_a_bounded_snapshot_and_restore_invalidates_it() {
        val settings = source("src/main/java/com/aegis/ime/user/UserSettingsPreferences.kt")
        val backup = source("src/main/java/com/aegis/ime/backup/BackupManager.kt")
        assertTrue(settings.contains("MAX_SETTINGS_PER_ROOT = 256"))
        assertTrue(settings.contains("MAX_ROOTS = 32"))
        assertTrue(settings.contains("UserSettingsSnapshotCache.read(root)"))
        assertTrue(backup.contains("UserSettingsPreferences.invalidateCache(filesDir)"))
    }
}
