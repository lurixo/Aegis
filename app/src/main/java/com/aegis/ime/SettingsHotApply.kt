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

import android.content.SharedPreferences
import com.aegis.ime.dict.Fuzzy

internal class SettingsHotApply {
    companion object {
        const val FUZZY_MASTER_PREF = "fuzzy"

        const val ENGINE_PACK_TOUCH_PREF = "engine_pack_touch"

        private fun SharedPreferences.flag(key: String, def: Boolean): Boolean =
            runCatching { getBoolean(key, def) }.getOrDefault(def)

        private fun SharedPreferences.count(key: String, def: Long): Long =
            runCatching { getLong(key, def) }.getOrDefault(def)

        fun noteEnginePackChanged(prefs: SharedPreferences) {
            prefs.edit().putLong(ENGINE_PACK_TOUCH_PREF, prefs.count(ENGINE_PACK_TOUCH_PREF, 0L) + 1L).apply()
        }

        fun fuzzyRules(prefs: SharedPreferences): Set<String> =
            Fuzzy.activeRules(prefs.flag(FUZZY_MASTER_PREF, Fuzzy.DEFAULT_ON)) {
                FuzzyPreferences.ruleEnabled(prefs, it)
            }
    }
}
