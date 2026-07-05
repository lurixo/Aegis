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
import android.content.SharedPreferences
import android.os.Looper
import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.dict.ModelDownload
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.ui.ASSOCIATIONS_DEFAULT_ON
import com.aegis.ime.ui.KEY_HAPTICS_DEFAULT
import com.aegis.ime.ui.KEY_PREVIEW_DEFAULT
import com.aegis.ime.ui.LETTER_CASE_DEFAULT
import com.aegis.ime.ui.LetterCase
import com.aegis.ime.ui.PREF_ASSOCIATIONS_ON
import com.aegis.ime.ui.PREF_KEY_HAPTICS
import com.aegis.ime.ui.PREF_KEY_PREVIEW_ALPHA
import com.aegis.ime.ui.PREF_KEY_PREVIEW_NINE
import com.aegis.ime.ui.PREF_LETTER_CASE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The settings iron rule (debug.47): EVERY pref-backed setting hot-applies the moment it changes.
 * This drives the production [SettingsHotApply] listener through a REAL SharedPreferences instance
 * (registered exactly like the IME service registers it) and asserts, PER SETTING with no sampling,
 * that the change fires the correct live action with the correct value:
 *
 *  - cn_layout (keyboard mode)      → onCnLayout, immediately
 *  - pref_associations_on (联想)     → onAssociations, immediately
 *  - fuzzy master + EVERY rule      → onFuzzyRules with the recomputed active set, immediately
 *  - dict-pack / model pack state   → onEngineAssetsChanged (engine hot-reload check), immediately,
 *    on both install (pref set) and delete (pref removed)
 *
 * The user dictionary is file-backed, not pref-backed; its immediate path is covered by
 * LiveUserDictHostTest / UserDictEditDispatchTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsHotApplyTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val prefs: SharedPreferences = ctx.getSharedPreferences("aegis", Context.MODE_PRIVATE)

    private val cnLayouts = mutableListOf<LayoutId>()
    private val associations = mutableListOf<Boolean>()
    private val fuzzySets = mutableListOf<Set<String>>()
    private var engineAssetChanges = 0
    private val keyHaptics = mutableListOf<Boolean>()
    private val keyPreviewsNine = mutableListOf<Boolean>()
    private val keyPreviewsAlpha = mutableListOf<Boolean>()
    private val letterCases = mutableListOf<LetterCase>()

    private val listener = SettingsHotApply(
        onCnLayout = { cnLayouts += it },
        onAssociations = { associations += it },
        onFuzzyRules = { fuzzySets += it },
        onEngineAssetsChanged = { engineAssetChanges++ },
        onKeyHaptics = { keyHaptics += it },
        onKeyPreviewNine = { keyPreviewsNine += it },
        onKeyPreviewAlpha = { keyPreviewsAlpha += it },
        onLetterCase = { letterCases += it },
    )

    @Before fun register() {
        prefs.edit().clear().commit()
        drain()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    @After fun unregister() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).commit()
        drain()
    }

    private fun totalActions() =
        cnLayouts.size + associations.size + fuzzySets.size + engineAssetChanges + keyHaptics.size +
            keyPreviewsNine.size + keyPreviewsAlpha.size + letterCases.size

    private val allRuleKeys = Fuzzy.RULES.mapTo(LinkedHashSet()) { it.key }

    // ---- keyboard mode ----

    @Test fun cn_layout_change_hot_applies_both_directions_immediately() {
        put { putString("cn_layout", "alpha") }
        assertEquals(listOf(LayoutId.ALPHA), cnLayouts)
        put { putString("cn_layout", "nine") }
        assertEquals(listOf(LayoutId.ALPHA, LayoutId.NINE), cnLayouts)
        assertEquals("no other channel may fire", 2, totalActions())
    }

    // ---- 联想 ----

    @Test fun association_toggle_hot_applies_both_directions_immediately() {
        put { putBoolean(PREF_ASSOCIATIONS_ON, true) }
        put { putBoolean(PREF_ASSOCIATIONS_ON, false) }
        assertEquals(listOf(true, false), associations)
        assertEquals(2, totalActions())
    }

    @Test fun association_pref_removal_resolves_to_the_production_default() {
        put { putBoolean(PREF_ASSOCIATIONS_ON, true) }
        put { remove(PREF_ASSOCIATIONS_ON) }
        assertEquals(listOf(true, ASSOCIATIONS_DEFAULT_ON), associations)
    }

    // ---- ⑤ touch feedback: key vibration + press preview ----

    @Test fun key_vibration_toggle_hot_applies_both_directions_immediately() {
        put { putBoolean(PREF_KEY_HAPTICS, true) }
        put { putBoolean(PREF_KEY_HAPTICS, false) }
        assertEquals(listOf(true, false), keyHaptics)
        assertEquals("no other channel may fire", 2, totalActions())
    }

    @Test fun the_two_preview_toggles_hot_apply_independently_and_immediately() {
        // ① the 9-key and 26-key previews are separate prefs → separate channels, no cross-talk.
        put { putBoolean(PREF_KEY_PREVIEW_NINE, true) }
        assertEquals(listOf(true), keyPreviewsNine)
        assertEquals("the 26-key channel must not fire", emptyList<Boolean>(), keyPreviewsAlpha)
        put { putBoolean(PREF_KEY_PREVIEW_ALPHA, true) }
        assertEquals(listOf(true), keyPreviewsAlpha)
        put { putBoolean(PREF_KEY_PREVIEW_NINE, false) }
        put { putBoolean(PREF_KEY_PREVIEW_ALPHA, false) }
        assertEquals(listOf(true, false), keyPreviewsNine)
        assertEquals(listOf(true, false), keyPreviewsAlpha)
        assertEquals(4, totalActions())
    }

    @Test fun letter_case_hot_applies_all_three_tiers_immediately() {
        // ② auto / upper / lower each push the resolved enum, in order, immediately.
        put { putString(PREF_LETTER_CASE, "upper") }
        put { putString(PREF_LETTER_CASE, "lower") }
        put { putString(PREF_LETTER_CASE, "auto") }
        assertEquals(listOf(LetterCase.UPPER, LetterCase.LOWER, LetterCase.AUTO), letterCases)
        assertEquals("no other channel may fire", 3, totalActions())
    }

    @Test fun touch_feedback_pref_removal_resolves_to_production_defaults() {
        put { putBoolean(PREF_KEY_HAPTICS, !KEY_HAPTICS_DEFAULT) }
        put { remove(PREF_KEY_HAPTICS) }
        assertEquals(listOf(!KEY_HAPTICS_DEFAULT, KEY_HAPTICS_DEFAULT), keyHaptics)
        put { putBoolean(PREF_KEY_PREVIEW_NINE, !KEY_PREVIEW_DEFAULT) }
        put { remove(PREF_KEY_PREVIEW_NINE) }
        assertEquals(listOf(!KEY_PREVIEW_DEFAULT, KEY_PREVIEW_DEFAULT), keyPreviewsNine)
        put { putBoolean(PREF_KEY_PREVIEW_ALPHA, !KEY_PREVIEW_DEFAULT) }
        put { remove(PREF_KEY_PREVIEW_ALPHA) }
        assertEquals(listOf(!KEY_PREVIEW_DEFAULT, KEY_PREVIEW_DEFAULT), keyPreviewsAlpha)
        // ② case pref removal resolves to the production default (auto).
        put { putString(PREF_LETTER_CASE, "upper") }
        put { remove(PREF_LETTER_CASE) }
        assertEquals(listOf(LetterCase.UPPER, LetterCase.AUTO), letterCases)
    }

    // ---- fuzzy: master + EVERY rule, no sampling ----

    @Test fun fuzzy_master_hot_pushes_full_set_on_and_empty_set_off() {
        put { putBoolean("fuzzy", true) }
        assertEquals(allRuleKeys, fuzzySets.last())
        put { putBoolean("fuzzy", false) }
        assertEquals(emptySet<String>(), fuzzySets.last())
        assertEquals(2, fuzzySets.size)
        assertEquals(2, totalActions())
    }

    @Test fun every_fuzzy_rule_toggle_hot_pushes_the_recomputed_set() {
        put { putBoolean("fuzzy", true) }
        for (rule in Fuzzy.RULES) {
            val before = fuzzySets.size
            put { putBoolean(Fuzzy.prefKey(rule.key), false) }
            assertEquals("rule ${rule.key}: off must push exactly one recomputed set", before + 1, fuzzySets.size)
            assertEquals("rule ${rule.key}: off must drop exactly that rule", allRuleKeys - rule.key, fuzzySets.last())
            put { putBoolean(Fuzzy.prefKey(rule.key), true) }
            assertEquals("rule ${rule.key}: back on must restore the full set", allRuleKeys, fuzzySets.last())
        }
        // 1 master + 2 pushes per rule, and nothing leaked into other channels
        assertEquals(1 + 2 * Fuzzy.RULES.size, fuzzySets.size)
        assertEquals(fuzzySets.size, totalActions())
    }

    @Test fun fuzzy_rule_toggles_with_master_off_still_push_the_empty_set() {
        // Master off wins: flipping a rule must re-push (deterministically empty), never a stale set.
        put { putBoolean("fuzzy", false) }
        for (rule in Fuzzy.RULES) {
            put { putBoolean(Fuzzy.prefKey(rule.key), false) }
            assertEquals(emptySet<String>(), fuzzySets.last())
        }
        assertEquals(1 + Fuzzy.RULES.size, fuzzySets.size)
    }

    // ---- dictionary pack + enhancement model: install AND delete, per pref, no sampling ----

    @Test fun every_pack_state_pref_triggers_an_engine_reload_check_on_install_and_delete() {
        for (key in SettingsHotApply.ENGINE_ASSET_PREF_KEYS) {
            val before = engineAssetChanges
            put { putString(key, "state-1") } // download/install completion commits this pref
            assertEquals("$key set must trigger a reload check", before + 1, engineAssetChanges)
            put { remove(key) } // delete removes it
            assertEquals("$key removal must trigger a reload check", before + 2, engineAssetChanges)
        }
        assertEquals(engineAssetChanges, totalActions())
    }

    @Test fun pack_state_keys_cover_the_gram_model_both_dict_pack_prefs_and_the_touch_counter() {
        assertEquals(
            setOf(
                ModelDownload.VALIDATOR_PREF,
                ModelDownload.DICT_VALIDATOR_PREF,
                ModelDownload.DICT_SHA256_PREF,
                SettingsHotApply.ENGINE_PACK_TOUCH_PREF,
            ),
            SettingsHotApply.ENGINE_ASSET_PREF_KEYS,
        )
    }

    @Test fun the_touch_counter_fires_even_when_the_validator_prefs_do_not_change() {
        // The three state prefs can silently not change: a download response without ETag/Last-Modified
        // stores null (a no-op remove), a re-install stores an identical value, and deleting a
        // pre-debug.10 install removes an absent key. SharedPreferences fires listeners only on VALUE
        // changes, so those cases alone would strand the pack change until the next field focus.
        put { remove(ModelDownload.VALIDATOR_PREF) } // absent key: no listener, documented Android behavior
        assertEquals(0, engineAssetChanges)
        // noteEnginePackChanged is the cards' completion signal — a monotonic counter, always a change.
        SettingsHotApply.noteEnginePackChanged(prefs); drain()
        assertEquals(1, engineAssetChanges)
        SettingsHotApply.noteEnginePackChanged(prefs); drain()
        assertEquals("every bump fires, even back to back", 2, engineAssetChanges)
        assertEquals(engineAssetChanges, totalActions())
    }

    // ---- completeness + isolation ----

    @Test fun the_pref_backed_settings_surface_is_fully_enumerated() {
        // Every hot-applied pref key, spelled out. A new settings pref must be added here AND get a
        // mapping + its own assertion above — this test is the no-sampling census.
        val enumerated = mutableSetOf(
            "cn_layout", PREF_ASSOCIATIONS_ON, "fuzzy", PREF_KEY_HAPTICS,
            PREF_KEY_PREVIEW_NINE, PREF_KEY_PREVIEW_ALPHA, PREF_LETTER_CASE,
        )
        enumerated += Fuzzy.RULES.map { Fuzzy.prefKey(it.key) }
        enumerated += SettingsHotApply.ENGINE_ASSET_PREF_KEYS
        assertEquals(7 + Fuzzy.RULES.size + 4, enumerated.size)
        for (key in enumerated) {
            val before = totalActions()
            put { putString("probe_reset", key) } // unrelated write: no action
            assertEquals(before, totalActions())
            when (key) {
                "cn_layout" -> put { putString(key, "alpha") }
                PREF_LETTER_CASE -> put { putString(key, "upper") } // string pref: write a non-default value
                else -> put { putBoolean(key, true) } // the two preview toggles default false → true forces a change
            }
            assertTrue("$key must hot-apply exactly one action", totalActions() == before + 1)
        }
    }

    @Test fun unrelated_prefs_hot_apply_nothing() {
        put {
            putBoolean("dl_hint_dismissed", true)
            putBoolean("clip_history", false)
            putString("custom_symbols", "★")
            putString("gram_probe_unrelated", "x")
        }
        assertEquals(0, totalActions())
    }
}
