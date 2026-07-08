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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsWiringTest {

    private fun src(path: String) = File(path).readText()

    @Test fun service_registers_the_hot_apply_listener_for_its_whole_lifetime() {
        val svc = src("src/main/java/com/aegis/ime/AegisInputMethodService.kt")
        assertTrue(
            "onCreate must register the settings hot-apply listener",
            svc.contains("registerOnSharedPreferenceChangeListener(settingsHotApply)"),
        )
        assertTrue(
            "onDestroy must unregister it",
            svc.contains("unregisterOnSharedPreferenceChangeListener(settingsHotApply)"),
        )
        assertFalse(svc.contains("layoutPrefListener"))
        assertFalse(svc.contains("associationPrefListener"))
    }

    @Test fun service_serves_the_live_user_dict_host_after_the_initial_load() {
        val svc = src("src/main/java/com/aegis/ime/AegisInputMethodService.kt")
        assertTrue(
            "the live host must be registered once the initial userdb load finished",
            svc.contains("UserDictHot.host = liveUserDictHost"),
        )
        assertTrue(
            "onDestroy must withdraw only its own host",
            svc.contains("if (UserDictHot.host === liveUserDictHost) UserDictHot.host = null"),
        )
        val loadGate = svc.indexOf("userDbLoaded = true")
        val hostReg = svc.indexOf("UserDictHot.host = liveUserDictHost")
        assertTrue("host registration must follow the userDbLoaded gate", loadGate in 1 until hostReg)
    }

    @Test fun engine_reload_rechecks_after_initial_build_and_after_a_successful_hot_reload() {
        val svc = src("src/main/java/com/aegis/ime/AegisInputMethodService.kt")
        val calls = Regex("""(?<!fun )maybeReloadEngine\(\)""").findAll(svc).count()
        assertTrue("expected the onStartInput call plus both build-site re-checks, found $calls", calls >= 3)
    }

    @Test fun both_download_cards_bump_the_touch_counter_on_install_and_delete() {
        for (card in listOf(
            "src/main/java/com/aegis/ime/ui/GramDownloadCard.kt",
            "src/main/java/com/aegis/ime/ui/DictDownloadCard.kt",
        )) {
            val bumps = Regex("""SettingsHotApply\.noteEnginePackChanged\(prefs\)""").findAll(src(card)).count()
            assertTrue("$card must bump the counter on install AND delete (found $bumps)", bumps >= 2)
        }
    }

    @Test fun user_dict_page_does_not_own_the_app_version_label() {
        val page = src("src/main/java/com/aegis/ime/ui/UserDictPage.kt")
        assertFalse("user dict page must not read package versionName", page.contains("getPackageInfo"))
        assertFalse("user dict page must not own the app version card", page.contains("AppVersionCard"))
        assertFalse("user dict page must not own the app release label", page.contains("appReleaseLabel"))
    }

    @Test fun every_settings_page_keeps_the_edge_to_edge_inset_contract() {
        val setup = src("src/main/java/com/aegis/ime/ui/SetupActivity.kt")
        assertTrue(setup.contains("fun SettingsPageColumn") && setup.contains(".settingsScrollInsets("))
        val page = src("src/main/java/com/aegis/ime/ui/UserDictPage.kt")
        assertTrue(page.contains(".userDictPageInsets("))
        assertTrue(page.contains("bottomInsets = WindowInsets.safeDrawing"))
        assertTrue(page.contains("topInsets = settingsTopInset()"))
        assertTrue(page.contains("bottomInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)"))
        assertTrue(page.contains("topInsets.only(WindowInsetsSides.Top)"))
        assertFalse("user dict must not source top padding directly from safeDrawing", page.contains(".windowInsetsPadding(WindowInsets.safeDrawing)"))
    }

    @Test fun the_model_card_sits_above_the_dictionary_card_on_the_dicts_page() {
        val setup = src("src/main/java/com/aegis/ime/ui/SetupActivity.kt")
        val dictsPage = setup.substringAfter("fun DictSettingsPage").substringBefore("fun AboutPage")
        val gram = dictsPage.indexOf("GramDownloadCard()")
        val dict = dictsPage.indexOf("DictDownloadCard()")
        assertTrue("both download cards must be on the dicts page", gram >= 0 && dict >= 0)
        assertTrue("GramDownloadCard must render before DictDownloadCard", gram < dict)
    }

    @Test fun the_input_page_wires_the_case_card_and_the_merged_preview_card() {
        val setup = src("src/main/java/com/aegis/ime/ui/SetupActivity.kt")
        val inputPage = setup.substringAfter("fun InputSettingsPage").substringBefore("fun DictSettingsPage")
        for (card in listOf("LetterCaseCard()", "KeyPreviewCard()")) {
            assertTrue("input page must render $card", inputPage.contains(card))
        }
        assertFalse("the old split 9-key preview card must be gone", setup.contains("KeyPreviewNineToggleCard("))
        assertFalse("the old split 26-key preview card must be gone", setup.contains("KeyPreviewAlphaToggleCard("))
    }

    @Test fun the_gram_card_drops_the_unsourced_internal_evaluation_score() {
        val en = src("src/main/res/values/strings.xml")
        val zh = src("src/main/res/values-zh/strings.xml")
        assertFalse("EN gram card must not cite an internal evaluation score", en.contains("internal evaluation top-1"))
        assertFalse("EN gram card must not cite an internal evaluation score", en.contains("about 9 points"))
        assertFalse("ZH gram card must not cite an internal evaluation score", zh.contains("内部评测"))
        assertFalse("ZH gram card must not cite an internal evaluation score", zh.contains("约 9 分"))
        assertTrue("EN gram card keeps the optional/offline wording", en.contains("input remains fully offline"))
        assertTrue("ZH gram card keeps the optional/offline wording", zh.contains("全程离线"))
    }

    @Test fun no_ui_string_promises_a_delayed_settings_effect_any_more() {
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-zh/strings.xml")) {
            val text = src(path)
            assertFalse("$path still promises next-switch effect", text.contains("next time you switch"))
            assertFalse("$path still promises next-switch effect", text.contains("下次切换"))
            assertFalse("$path still promises a restart", text.contains("重启输入法"))
            assertFalse("$path still promises switch/restart loading", text.contains("切换/重启"))
        }
        val fuzzyCard = src("src/main/java/com/aegis/ime/ui/FuzzySettingsCard.kt")
        assertFalse(
            "FuzzySettingsCard's stale delayed-effect comment must stay gone",
            fuzzyCard.contains("Takes effect next time"),
        )
    }
}
