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
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.ui.ASSOCIATIONS_DEFAULT_ON
import com.aegis.ime.ui.AUTO_LEARN_DEFAULT_ON
import com.aegis.ime.ui.KEY_HAPTICS_DEFAULT
import com.aegis.ime.ui.KEY_PREVIEW_MASTER_DEFAULT
import com.aegis.ime.ui.KEY_PREVIEW_SUB_DEFAULT
import com.aegis.ime.ui.LETTER_CASE_DEFAULT
import com.aegis.ime.ui.LetterCase
import com.aegis.ime.ui.PREF_ASSOCIATIONS_ON
import com.aegis.ime.ui.PREF_AUTO_LEARN_ON
import com.aegis.ime.ui.PREF_DEFAULT_LANG
import com.aegis.ime.ui.PREF_KEY_HAPTICS
import com.aegis.ime.ui.PREF_KEY_PREVIEW_ALPHA
import com.aegis.ime.ui.PREF_KEY_PREVIEW_MASTER
import com.aegis.ime.ui.PREF_KEY_PREVIEW_NINE
import com.aegis.ime.ui.PREF_LETTER_CASE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val defaultLangs = mutableListOf<Lang>()
    private val associations = mutableListOf<Boolean>()
    private val autoLearns = mutableListOf<Boolean>()
    private val fuzzySets = mutableListOf<Set<String>>()
    private var engineAssetChanges = 0
    private val keyHaptics = mutableListOf<Boolean>()
    private val keyPreviewsNine = mutableListOf<Boolean>()
    private val keyPreviewsAlpha = mutableListOf<Boolean>()
    private val letterCases = mutableListOf<LetterCase>()

    private val listener = SettingsHotApply(
        onCnLayout = { cnLayouts += it },
        onDefaultLang = { defaultLangs += it },
        onAssociations = { associations += it },
        onAutoLearn = { autoLearns += it },
        onFuzzyRules = { fuzzySets += it },
        onEngineAssetsChanged = { engineAssetChanges++ },
        onKeyHaptics = { keyHaptics += it },
        onKeyPreviewNine = { keyPreviewsNine += it },
        onKeyPreviewAlpha = { keyPreviewsAlpha += it },
        onLetterCase = { letterCases += it },
    )

    @Test fun a_setting_carrying_the_wrong_type_reads_as_its_default_instead_of_throwing() {
        prefs.edit().clear()
            .putInt(SettingsHotApply.CN_LAYOUT_PREF, 7)
            .putInt(PREF_DEFAULT_LANG, 1)
            .putString(PREF_AUTO_LEARN_ON, "yes")
            .putString(PREF_ASSOCIATIONS_ON, "no")
            .putString(PREF_KEY_HAPTICS, "on")
            .putString(PREF_KEY_PREVIEW_MASTER, "on")
            .putInt(PREF_LETTER_CASE, 2)
            .putString(SettingsHotApply.FUZZY_MASTER_PREF, "on")
            .putString(SettingsHotApply.ENGINE_PACK_TOUCH_PREF, "many")
            .commit()
        drain()

        assertEquals(LayoutId.NINE, SettingsHotApply.cnLayout(prefs))
        assertEquals(com.aegis.ime.ui.defaultLangOf(com.aegis.ime.ui.DEFAULT_LANG_DEFAULT), SettingsHotApply.defaultLang(prefs))
        assertEquals(AUTO_LEARN_DEFAULT_ON, SettingsHotApply.autoLearnOn(prefs))
        assertEquals(ASSOCIATIONS_DEFAULT_ON, SettingsHotApply.associationsOn(prefs))
        assertEquals(KEY_HAPTICS_DEFAULT, SettingsHotApply.keyHaptics(prefs))
        assertEquals(KEY_PREVIEW_MASTER_DEFAULT, SettingsHotApply.keyPreviewMaster(prefs))
        assertEquals(com.aegis.ime.ui.letterCaseOf(LETTER_CASE_DEFAULT), SettingsHotApply.letterCase(prefs))
        assertEquals(Fuzzy.activeRules(Fuzzy.DEFAULT_ON) { true }, SettingsHotApply.fuzzyRules(prefs))
        SettingsHotApply.noteEnginePackChanged(prefs)
    }

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
        cnLayouts.size + defaultLangs.size + associations.size + autoLearns.size + fuzzySets.size +
            engineAssetChanges + keyHaptics.size + keyPreviewsNine.size + keyPreviewsAlpha.size + letterCases.size

    private val allRuleKeys = Fuzzy.RULES.mapTo(LinkedHashSet()) { it.key }


    @Test fun cn_layout_change_hot_applies_both_directions_immediately() {
        put { putString("cn_layout", "alpha") }
        assertEquals(listOf(LayoutId.ALPHA), cnLayouts)
        put { putString("cn_layout", "nine") }
        assertEquals(listOf(LayoutId.ALPHA, LayoutId.NINE), cnLayouts)
        assertEquals("no other channel may fire", 2, totalActions())
    }


    @Test fun default_lang_change_hot_applies_both_directions_immediately() {
        put { putString(PREF_DEFAULT_LANG, "en") }
        assertEquals(listOf(Lang.EN), defaultLangs)
        put { putString(PREF_DEFAULT_LANG, "cn") }
        assertEquals(listOf(Lang.EN, Lang.CN), defaultLangs)
        assertEquals("no other channel may fire", 2, totalActions())
    }

    @Test fun default_lang_pref_removal_resolves_to_the_chinese_factory_default() {
        put { putString(PREF_DEFAULT_LANG, "en") }
        put { remove(PREF_DEFAULT_LANG) }
        assertEquals(listOf(Lang.EN, Lang.CN), defaultLangs)
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


    @Test fun auto_learn_toggle_hot_applies_both_directions_immediately() {
        assertTrue("auto learning is ON by default", SettingsHotApply.autoLearnOn(prefs))
        put { putBoolean(PREF_AUTO_LEARN_ON, false) }
        put { putBoolean(PREF_AUTO_LEARN_ON, true) }
        assertEquals(listOf(false, true), autoLearns)
        assertEquals("no other channel may fire", 2, totalActions())
    }

    @Test fun auto_learn_pref_removal_resolves_to_the_production_default() {
        put { putBoolean(PREF_AUTO_LEARN_ON, false) }
        put { remove(PREF_AUTO_LEARN_ON) }
        assertEquals(listOf(false, AUTO_LEARN_DEFAULT_ON), autoLearns)
    }

    @Test fun key_vibration_toggle_hot_applies_both_directions_immediately() {
        put { putBoolean(PREF_KEY_HAPTICS, true) }
        put { putBoolean(PREF_KEY_HAPTICS, false) }
        assertEquals(listOf(true, false), keyHaptics)
        assertEquals("no other channel may fire", 2, totalActions())
    }

    @Test fun the_preview_is_off_by_default_the_master_off_hides_the_default_on_subs() {
        assertFalse("9-key preview is OFF by default", SettingsHotApply.keyPreviewNine(prefs))
        assertFalse("26-key preview is OFF by default", SettingsHotApply.keyPreviewAlpha(prefs))
        assertFalse("the master default is OFF", KEY_PREVIEW_MASTER_DEFAULT)
        assertTrue("the sub default is ON, so flipping the master on previews immediately", KEY_PREVIEW_SUB_DEFAULT)
    }

    @Test fun the_effective_preview_is_master_and_sub_for_all_four_combinations() {
        put { putBoolean(PREF_KEY_PREVIEW_MASTER, false); putBoolean(PREF_KEY_PREVIEW_NINE, true); putBoolean(PREF_KEY_PREVIEW_ALPHA, true) }
        assertFalse("master off → 9-key off despite its sub on", SettingsHotApply.keyPreviewNine(prefs))
        assertFalse("master off → 26-key off despite its sub on", SettingsHotApply.keyPreviewAlpha(prefs))
        put { putBoolean(PREF_KEY_PREVIEW_MASTER, true) }
        assertTrue("master on + 9-sub on → 9-key previews", SettingsHotApply.keyPreviewNine(prefs))
        assertTrue("master on + 26-sub on → 26-key previews", SettingsHotApply.keyPreviewAlpha(prefs))
        put { putBoolean(PREF_KEY_PREVIEW_NINE, false) }
        assertFalse("master on + 9-sub off → only 9-key drops", SettingsHotApply.keyPreviewNine(prefs))
        assertTrue("master on + 26-sub on → 26-key still previews", SettingsHotApply.keyPreviewAlpha(prefs))
        put { putBoolean(PREF_KEY_PREVIEW_ALPHA, false) }
        assertFalse("master on + both subs off → 9-key off", SettingsHotApply.keyPreviewNine(prefs))
        assertFalse("master on + both subs off → 26-key off", SettingsHotApply.keyPreviewAlpha(prefs))
    }

    @Test fun the_preview_master_and_subs_hot_apply_immediately() {
        put { putBoolean(PREF_KEY_PREVIEW_MASTER, true) }
        assertEquals(listOf(true), keyPreviewsNine)
        assertEquals(listOf(true), keyPreviewsAlpha)
        put { putBoolean(PREF_KEY_PREVIEW_MASTER, false) }
        assertEquals(listOf(true, false), keyPreviewsNine)
        assertEquals(listOf(true, false), keyPreviewsAlpha)
        put { putBoolean(PREF_KEY_PREVIEW_MASTER, true) }
        put { putBoolean(PREF_KEY_PREVIEW_NINE, false) }
        assertEquals(listOf(true, false, true, false), keyPreviewsNine)
        assertEquals("the 26-key channel must not fire on a 9-key flip", listOf(true, false, true), keyPreviewsAlpha)
        put { putBoolean(PREF_KEY_PREVIEW_ALPHA, false) }
        assertEquals(listOf(true, false, true, false), keyPreviewsAlpha)
        assertEquals(listOf(true, false, true, false), keyPreviewsNine)
        assertEquals(8, totalActions())
    }

    @Test fun letter_case_hot_applies_all_three_tiers_immediately() {
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
        put { putBoolean(PREF_KEY_PREVIEW_MASTER, true) }
        put { remove(PREF_KEY_PREVIEW_MASTER) }
        assertEquals(listOf(true, false), keyPreviewsNine)
        assertEquals(listOf(true, false), keyPreviewsAlpha)
        put { putBoolean(PREF_KEY_PREVIEW_MASTER, true); putBoolean(PREF_KEY_PREVIEW_NINE, false) }
        put { remove(PREF_KEY_PREVIEW_NINE) }
        assertEquals("removing the 9-key sub restores its default-ON state (master on)", true, keyPreviewsNine.last())
        put { putString(PREF_LETTER_CASE, "upper") }
        put { remove(PREF_LETTER_CASE) }
        assertEquals(listOf(LetterCase.UPPER, LetterCase.AUTO), letterCases)
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
                ModelDownload.GRAM_SHA256_PREF,
                ModelDownload.GRAM_SIZE_PREF,
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
        val enumerated = mutableSetOf(
            "cn_layout", PREF_DEFAULT_LANG, PREF_ASSOCIATIONS_ON, "fuzzy", PREF_KEY_HAPTICS,
            PREF_KEY_PREVIEW_MASTER, PREF_KEY_PREVIEW_NINE, PREF_KEY_PREVIEW_ALPHA, PREF_LETTER_CASE,
        )
        enumerated += Fuzzy.RULES.map { Fuzzy.prefKey(it.key) }
        enumerated += SettingsHotApply.ENGINE_ASSET_PREF_KEYS
        assertEquals(9 + Fuzzy.RULES.size + 6, enumerated.size)
        for (key in enumerated) {
            val before = totalActions()
            put { putString("probe_reset", key) }
            assertEquals(before, totalActions())
            when (key) {
                "cn_layout" -> put { putString(key, "alpha") }
                PREF_DEFAULT_LANG -> put { putString(key, "en") }
                PREF_LETTER_CASE -> put { putString(key, "upper") }
                else -> put { putBoolean(key, true) }
            }
            val expected = if (key == PREF_KEY_PREVIEW_MASTER) 2 else 1
            assertEquals("$key must hot-apply $expected action(s)", before + expected, totalActions())
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
