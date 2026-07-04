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
import com.aegis.ime.ui.PREF_ASSOCIATIONS_ON
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsHotApplyTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val prefs: SharedPreferences = ctx.getSharedPreferences("aegis", Context.MODE_PRIVATE)

    private val cnLayouts = mutableListOf<LayoutId>()
    private val associations = mutableListOf<Boolean>()
    private val fuzzySets = mutableListOf<Set<String>>()
    private var engineAssetChanges = 0

    private val listener = SettingsHotApply(
        onCnLayout = { cnLayouts += it },
        onAssociations = { associations += it },
        onFuzzyRules = { fuzzySets += it },
        onEngineAssetsChanged = { engineAssetChanges++ },
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

    private fun totalActions() = cnLayouts.size + associations.size + fuzzySets.size + engineAssetChanges

    private val allRuleKeys = Fuzzy.RULES.mapTo(LinkedHashSet()) { it.key }


    @Test fun cn_layout_change_hot_applies_both_directions_immediately() {
        put { putString("cn_layout", "alpha") }
        assertEquals(listOf(LayoutId.ALPHA), cnLayouts)
        put { putString("cn_layout", "nine") }
        assertEquals(listOf(LayoutId.ALPHA, LayoutId.NINE), cnLayouts)
        assertEquals("no other channel may fire", 2, totalActions())
    }


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
        assertEquals(1 + 2 * Fuzzy.RULES.size, fuzzySets.size)
        assertEquals(fuzzySets.size, totalActions())
    }

    @Test fun fuzzy_rule_toggles_with_master_off_still_push_the_empty_set() {
        put { putBoolean("fuzzy", false) }
        for (rule in Fuzzy.RULES) {
            put { putBoolean(Fuzzy.prefKey(rule.key), false) }
            assertEquals(emptySet<String>(), fuzzySets.last())
        }
        assertEquals(1 + Fuzzy.RULES.size, fuzzySets.size)
    }


    @Test fun every_pack_state_pref_triggers_an_engine_reload_check_on_install_and_delete() {
        for (key in SettingsHotApply.ENGINE_ASSET_PREF_KEYS) {
            val before = engineAssetChanges
            put { putString(key, "state-1") }
            assertEquals("$key set must trigger a reload check", before + 1, engineAssetChanges)
            put { remove(key) }
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
        put { remove(ModelDownload.VALIDATOR_PREF) }
        assertEquals(0, engineAssetChanges)
        SettingsHotApply.noteEnginePackChanged(prefs); drain()
        assertEquals(1, engineAssetChanges)
        SettingsHotApply.noteEnginePackChanged(prefs); drain()
        assertEquals("every bump fires, even back to back", 2, engineAssetChanges)
        assertEquals(engineAssetChanges, totalActions())
    }


    @Test fun the_pref_backed_settings_surface_is_fully_enumerated() {
        val enumerated = mutableSetOf("cn_layout", PREF_ASSOCIATIONS_ON, "fuzzy")
        enumerated += Fuzzy.RULES.map { Fuzzy.prefKey(it.key) }
        enumerated += SettingsHotApply.ENGINE_ASSET_PREF_KEYS
        assertEquals(3 + Fuzzy.RULES.size + 4, enumerated.size)
        for (key in enumerated) {
            val before = totalActions()
            put { putString("probe_reset", key) }
            assertEquals(before, totalActions())
            when (key) {
                "cn_layout" -> put { putString(key, "alpha") }
                else -> put { putBoolean(key, true) }
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
