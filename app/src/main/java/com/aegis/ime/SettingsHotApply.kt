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
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.layout.LayoutId

internal class SettingsHotApply(
    private val onCnLayout: (LayoutId) -> Unit,
    private val onAssociations: (Boolean) -> Unit,
    private val onFuzzyRules: (Set<String>) -> Unit,
    private val onEngineAssetsChanged: () -> Unit,
) : SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        when {
            key == null -> {}
            key == CN_LAYOUT_PREF -> onCnLayout(cnLayout(prefs))
            key == com.aegis.ime.ui.PREF_ASSOCIATIONS_ON -> onAssociations(associationsOn(prefs))
            key == FUZZY_MASTER_PREF || key in FUZZY_RULE_PREF_KEYS -> onFuzzyRules(fuzzyRules(prefs))
            key in ENGINE_ASSET_PREF_KEYS -> onEngineAssetsChanged()
        }
    }

    companion object {
        const val CN_LAYOUT_PREF = "cn_layout"
        const val FUZZY_MASTER_PREF = "fuzzy"

        val FUZZY_RULE_PREF_KEYS: Set<String> = Fuzzy.RULES.mapTo(LinkedHashSet()) { Fuzzy.prefKey(it.key) }

        const val ENGINE_PACK_TOUCH_PREF = "engine_pack_touch"

        val ENGINE_ASSET_PREF_KEYS: Set<String> = setOf(
            ModelDownload.VALIDATOR_PREF,
            ModelDownload.DICT_VALIDATOR_PREF,
            ModelDownload.DICT_SHA256_PREF,
            ENGINE_PACK_TOUCH_PREF,
        )

        fun noteEnginePackChanged(prefs: SharedPreferences) {
            prefs.edit().putLong(ENGINE_PACK_TOUCH_PREF, prefs.getLong(ENGINE_PACK_TOUCH_PREF, 0L) + 1L).apply()
        }

        fun cnLayout(prefs: SharedPreferences): LayoutId =
            if (prefs.getString(CN_LAYOUT_PREF, "nine") == "alpha") LayoutId.ALPHA else LayoutId.NINE

        fun associationsOn(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(com.aegis.ime.ui.PREF_ASSOCIATIONS_ON, com.aegis.ime.ui.ASSOCIATIONS_DEFAULT_ON)

        fun fuzzyRules(prefs: SharedPreferences): Set<String> =
            Fuzzy.activeRules(prefs.getBoolean(FUZZY_MASTER_PREF, Fuzzy.DEFAULT_ON)) {
                prefs.getBoolean(Fuzzy.prefKey(it), true)
            }
    }
}
