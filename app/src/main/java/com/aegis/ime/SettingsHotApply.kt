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
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId

internal class SettingsHotApply(
    private val onCnLayout: (LayoutId) -> Unit,
    private val onDefaultLang: (Lang) -> Unit,
    private val onAssociations: (Boolean) -> Unit,
    private val onFuzzyRules: (Set<String>) -> Unit,
    private val onEngineAssetsChanged: () -> Unit,
    private val onKeyHaptics: (Boolean) -> Unit = {},
    private val onKeyPreviewNine: (Boolean) -> Unit = {},
    private val onKeyPreviewAlpha: (Boolean) -> Unit = {},
    private val onLetterCase: (com.aegis.ime.ui.LetterCase) -> Unit = {},
) : SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        when {
            key == null -> {}
            key == CN_LAYOUT_PREF -> onCnLayout(cnLayout(prefs))
            key == com.aegis.ime.ui.PREF_DEFAULT_LANG -> onDefaultLang(defaultLang(prefs))
            key == com.aegis.ime.ui.PREF_ASSOCIATIONS_ON -> onAssociations(associationsOn(prefs))
            key == FUZZY_MASTER_PREF || key in FUZZY_RULE_PREF_KEYS -> onFuzzyRules(fuzzyRules(prefs))
            key == com.aegis.ime.ui.PREF_KEY_HAPTICS -> onKeyHaptics(keyHaptics(prefs))
            key == com.aegis.ime.ui.PREF_KEY_PREVIEW_MASTER -> {
                onKeyPreviewNine(keyPreviewNine(prefs))
                onKeyPreviewAlpha(keyPreviewAlpha(prefs))
            }
            key == com.aegis.ime.ui.PREF_KEY_PREVIEW_NINE -> onKeyPreviewNine(keyPreviewNine(prefs))
            key == com.aegis.ime.ui.PREF_KEY_PREVIEW_ALPHA -> onKeyPreviewAlpha(keyPreviewAlpha(prefs))
            key == com.aegis.ime.ui.PREF_LETTER_CASE -> onLetterCase(letterCase(prefs))
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

        fun defaultLang(prefs: SharedPreferences): Lang =
            com.aegis.ime.ui.defaultLangOf(
                prefs.getString(com.aegis.ime.ui.PREF_DEFAULT_LANG, com.aegis.ime.ui.DEFAULT_LANG_DEFAULT),
            )

        fun associationsOn(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(com.aegis.ime.ui.PREF_ASSOCIATIONS_ON, com.aegis.ime.ui.ASSOCIATIONS_DEFAULT_ON)

        fun keyHaptics(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(com.aegis.ime.ui.PREF_KEY_HAPTICS, com.aegis.ime.ui.KEY_HAPTICS_DEFAULT)

        fun keyPreviewMaster(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(com.aegis.ime.ui.PREF_KEY_PREVIEW_MASTER, com.aegis.ime.ui.KEY_PREVIEW_MASTER_DEFAULT)

        fun keyPreviewNine(prefs: SharedPreferences): Boolean =
            keyPreviewMaster(prefs) &&
                prefs.getBoolean(com.aegis.ime.ui.PREF_KEY_PREVIEW_NINE, com.aegis.ime.ui.KEY_PREVIEW_SUB_DEFAULT)

        fun keyPreviewAlpha(prefs: SharedPreferences): Boolean =
            keyPreviewMaster(prefs) &&
                prefs.getBoolean(com.aegis.ime.ui.PREF_KEY_PREVIEW_ALPHA, com.aegis.ime.ui.KEY_PREVIEW_SUB_DEFAULT)

        fun letterCase(prefs: SharedPreferences): com.aegis.ime.ui.LetterCase =
            com.aegis.ime.ui.letterCaseOf(prefs.getString(com.aegis.ime.ui.PREF_LETTER_CASE, com.aegis.ime.ui.LETTER_CASE_DEFAULT))

        fun fuzzyRules(prefs: SharedPreferences): Set<String> =
            Fuzzy.activeRules(prefs.getBoolean(FUZZY_MASTER_PREF, Fuzzy.DEFAULT_ON)) {
                prefs.getBoolean(Fuzzy.prefKey(it), true)
            }
    }
}
