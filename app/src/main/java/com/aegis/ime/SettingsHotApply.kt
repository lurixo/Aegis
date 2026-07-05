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

/**
 * The one place that maps a settings-screen preference change onto its live hot-apply action, so EVERY
 * setting takes effect the moment it is changed — never "next time an input field is focused". The
 * settings Activity and the IME service share the process and the "aegis" prefs file, so an in-process
 * [SharedPreferences.OnSharedPreferenceChangeListener] is the whole transport:
 *
 *  - "cn_layout"            → [onCnLayout] with the resolved [LayoutId] (controller switches the live layout)
 *  - "pref_associations_on" → [onAssociations] (controller shows/hides predictions immediately)
 *  - "fuzzy" / "fuzzy_<rule>" → [onFuzzyRules] with the full re-resolved active-rule set
 *    (controller pushes it into the live engine; the decoder applies it on the next keystroke)
 *  - download-state prefs the dict/model cards commit on install/delete completion
 *    ([ENGINE_ASSET_PREF_KEYS]) → [onEngineAssetsChanged] (the service re-checks the downloaded-asset
 *    signature and hot-rebuilds the engine off the main thread)
 *
 * Unrelated keys are ignored. The mapping is pure (callbacks injected) so a test can drive a real prefs
 * instance and assert the exact action per setting, without the IME service or its dictionaries.
 *
 * The user dictionary is file-backed rather than pref-backed, so its immediate path is [com.aegis.ime.user.UserDictHot],
 * not this listener.
 */
internal class SettingsHotApply(
    private val onCnLayout: (LayoutId) -> Unit,
    private val onAssociations: (Boolean) -> Unit,
    private val onFuzzyRules: (Set<String>) -> Unit,
    private val onEngineAssetsChanged: () -> Unit,
    // ⑤ touch-feedback toggles (0048): key vibration + the magnified press preview, applied live.
    private val onKeyHaptics: (Boolean) -> Unit = {},
    private val onKeyPreview: (Boolean) -> Unit = {},
) : SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        when {
            key == null -> {} // clear(): the service re-reads everything on the next onStartInputView anyway
            key == CN_LAYOUT_PREF -> onCnLayout(cnLayout(prefs))
            key == com.aegis.ime.ui.PREF_ASSOCIATIONS_ON -> onAssociations(associationsOn(prefs))
            key == FUZZY_MASTER_PREF || key in FUZZY_RULE_PREF_KEYS -> onFuzzyRules(fuzzyRules(prefs))
            key == com.aegis.ime.ui.PREF_KEY_HAPTICS -> onKeyHaptics(keyHaptics(prefs))
            key == com.aegis.ime.ui.PREF_KEY_PREVIEW -> onKeyPreview(keyPreview(prefs))
            key in ENGINE_ASSET_PREF_KEYS -> onEngineAssetsChanged()
        }
    }

    companion object {
        const val CN_LAYOUT_PREF = "cn_layout"
        const val FUZZY_MASTER_PREF = "fuzzy"

        /** Per-rule fuzzy toggles, single-sourced from [Fuzzy.RULES]. */
        val FUZZY_RULE_PREF_KEYS: Set<String> = Fuzzy.RULES.mapTo(LinkedHashSet()) { Fuzzy.prefKey(it.key) }

        /**
         * The guaranteed "downloaded packs changed" signal: a monotonic counter the download cards bump
         * via [noteEnginePackChanged] on every install/delete completion. The validator/sha prefs alone
         * can miss a change — SharedPreferences only notifies on a VALUE change, and a download response
         * without ETag/Last-Modified stores null (a no-op remove), a re-download can store an identical
         * validator, and a pre-debug.10 install never wrote the pref at all (its delete then removes an
         * absent key). The counter always changes, so the listener always fires.
         */
        const val ENGINE_PACK_TOUCH_PREF = "engine_pack_touch"

        /**
         * Prefs whose change means "the downloaded packs changed": the cards' state prefs plus the
         * always-changing [ENGINE_PACK_TOUCH_PREF] counter. The dict card commits its pref batch (and
         * bumps the counter) only after installDictPack succeeded (zip verified, extracted, removed), so
         * a reload triggered here never reads a half-installed pack; maybeReloadEngine's
         * installInProgress guard backstops that.
         */
        val ENGINE_ASSET_PREF_KEYS: Set<String> = setOf(
            ModelDownload.VALIDATOR_PREF,
            ModelDownload.DICT_VALIDATOR_PREF,
            ModelDownload.DICT_SHA256_PREF,
            ENGINE_PACK_TOUCH_PREF,
        )

        /** Bump the [ENGINE_PACK_TOUCH_PREF] counter — always a value change, so the listener always fires. */
        fun noteEnginePackChanged(prefs: SharedPreferences) {
            prefs.edit().putLong(ENGINE_PACK_TOUCH_PREF, prefs.getLong(ENGINE_PACK_TOUCH_PREF, 0L) + 1L).apply()
        }

        /** The CN default keyboard selected by the prefs (nine-key unless the user picked 26-key). */
        fun cnLayout(prefs: SharedPreferences): LayoutId =
            if (prefs.getString(CN_LAYOUT_PREF, "nine") == "alpha") LayoutId.ALPHA else LayoutId.NINE

        /** The prediction toggle as the prefs resolve it (single-sourced default). */
        fun associationsOn(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(com.aegis.ime.ui.PREF_ASSOCIATIONS_ON, com.aegis.ime.ui.ASSOCIATIONS_DEFAULT_ON)

        /** ⑤ The key-vibration toggle as the prefs resolve it (single-sourced default in KeyFeedbackCards). */
        fun keyHaptics(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(com.aegis.ime.ui.PREF_KEY_HAPTICS, com.aegis.ime.ui.KEY_HAPTICS_DEFAULT)

        /** ⑤ The key-press-preview toggle as the prefs resolve it (single-sourced default in KeyFeedbackCards). */
        fun keyPreview(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(com.aegis.ime.ui.PREF_KEY_PREVIEW, com.aegis.ime.ui.KEY_PREVIEW_DEFAULT)

        /** The active fuzzy rule-key set as the prefs resolve it (master + per-rule; pure [Fuzzy.activeRules]). */
        fun fuzzyRules(prefs: SharedPreferences): Set<String> =
            Fuzzy.activeRules(prefs.getBoolean(FUZZY_MASTER_PREF, Fuzzy.DEFAULT_ON)) {
                prefs.getBoolean(Fuzzy.prefKey(it), true)
            }
    }
}
