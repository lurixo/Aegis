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

package com.aegis.ime

import android.content.Context
import com.aegis.ime.dict.Fuzzy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FuzzyPreferencesTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("aegis", Context.MODE_PRIVATE)
    private val expectedDefaults = setOf("zh", "ch", "sh", "ang", "eng", "ing")

    @Test fun fresh_settings_keep_six_default_rules_after_master_is_enabled() {
        prefs.edit().clear().commit()
        assertEquals(expectedDefaults, Fuzzy.activeRules(true) { FuzzyPreferences.ruleEnabled(prefs, it) })
        assertEquals(emptySet<String>(), SettingsHotApply.fuzzyRules(prefs))
        prefs.edit().putBoolean("fuzzy", true).commit()
        assertEquals(expectedDefaults, SettingsHotApply.fuzzyRules(prefs))
        prefs.edit().putBoolean("fuzzy", false).commit()
        assertEquals(emptySet<String>(), SettingsHotApply.fuzzyRules(prefs))
        prefs.edit().putBoolean("fuzzy", true).commit()
        assertEquals(expectedDefaults, SettingsHotApply.fuzzyRules(prefs))
    }

    @Test fun existing_master_and_old_defaults_marker_use_the_same_six_defaults() {
        for (version in listOf(null, 1, 2)) {
            prefs.edit().clear().putBoolean("fuzzy", true).apply {
                if (version != null) putInt("fuzzy_defaults_version", version)
            }.commit()
            assertEquals("defaults marker $version", expectedDefaults, SettingsHotApply.fuzzyRules(prefs))
        }
    }

    @Test fun explicit_rule_choices_override_defaults_for_existing_installations() {
        for (version in listOf(null, 1, 2)) {
            prefs.edit().clear().putBoolean("fuzzy", true)
                .putBoolean("fuzzy_zh", false)
                .putBoolean("fuzzy_n_l", true)
                .putBoolean("fuzzy_hui_fei", true).apply {
                    if (version != null) putInt("fuzzy_defaults_version", version)
                }.commit()
            assertEquals(expectedDefaults - "zh" + setOf("n_l", "hui_fei"), SettingsHotApply.fuzzyRules(prefs))
            prefs.edit().remove("fuzzy_n_l").remove("fuzzy_zh").commit()
            assertEquals(expectedDefaults + "hui_fei", SettingsHotApply.fuzzyRules(prefs))
        }
    }
}
