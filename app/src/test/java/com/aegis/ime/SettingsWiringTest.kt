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

/**
 * debug.47 wiring pins (source-level, in the house style of AppSelfUpdateAbsentTest): the IME service is
 * too heavy to instantiate under Robolectric (it parses the ~78 MB bundled dictionaries), so the behaviour
 * is covered by SettingsHotApplyTest / LiveUserDictHostTest, and THIS test pins that the service actually
 * registers those tested components — the registration lines a refactor could silently drop.
 */
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
        // The replaced per-key listeners must not linger.
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
        // Registration must sit AFTER the load gate: a pre-load registration could save a not-yet-loaded
        // (near-empty) model over the user's learning.
        val loadGate = svc.indexOf("userDbLoaded = true")
        val hostReg = svc.indexOf("UserDictHot.host = liveUserDictHost")
        assertTrue("host registration must follow the userDbLoaded gate", loadGate in 1 until hostReg)
    }

    @Test fun engine_reload_rechecks_after_initial_build_and_after_a_successful_hot_reload() {
        val svc = src("src/main/java/com/aegis/ime/AegisInputMethodService.kt")
        // Call sites of maybeReloadEngine() (excluding its definition): the pre-existing onStartInput call,
        // PLUS the post-initial-build re-check and the post-hot-reload re-check — so a pack change racing
        // a build is picked up without waiting for the next onStartInput.
        val calls = Regex("""(?<!fun )maybeReloadEngine\(\)""").findAll(svc).count()
        assertTrue("expected the onStartInput call plus both build-site re-checks, found $calls", calls >= 3)
    }

    @Test fun both_download_cards_bump_the_touch_counter_on_install_and_delete() {
        // The validator/sha prefs can silently not change (null validator, identical re-install value,
        // pre-debug.10 installs with no pref at all) and SharedPreferences only notifies on value changes
        // — so each card must ALSO bump the always-changing counter at both completion sites.
        for (card in listOf(
            "src/main/java/com/aegis/ime/ui/GramDownloadCard.kt",
            "src/main/java/com/aegis/ime/ui/DictDownloadCard.kt",
        )) {
            val bumps = Regex("""SettingsHotApply\.noteEnginePackChanged\(prefs\)""").findAll(src(card)).count()
            assertTrue("$card must bump the counter on install AND delete (found $bumps)", bumps >= 2)
        }
    }

    // Migrated from UserDictCardTest (the card became the user-dict page).
    @Test fun user_dict_page_does_not_own_the_app_version_label() {
        val page = src("src/main/java/com/aegis/ime/ui/UserDictPage.kt")
        assertFalse("user dict page must not read package versionName", page.contains("getPackageInfo"))
        assertFalse("user dict page must not own the app version card", page.contains("AppVersionCard"))
        assertFalse("user dict page must not own the app release label", page.contains("appReleaseLabel"))
    }

    @Test fun every_settings_page_keeps_the_edge_to_edge_inset_contract() {
        val setup = src("src/main/java/com/aegis/ime/ui/SetupActivity.kt")
        // Home and the shared sub-page scaffold both run through the Robolectric-tested inset modifier.
        assertTrue(setup.contains("fun SettingsPageColumn") && setup.contains(".settingsScrollInsets("))
        // The user-dict page is a lazy list, so it applies the same insets-outside-the-scroller contract.
        val page = src("src/main/java/com/aegis/ime/ui/UserDictPage.kt")
        assertTrue(page.contains("windowInsetsPadding(WindowInsets.safeDrawing)"))
    }

    @Test fun no_ui_string_promises_a_delayed_settings_effect_any_more() {
        // The iron rule made every setting immediate; stale "takes effect next time you switch" wording in
        // ANY variant (EN or ZH) would contradict the shipped behaviour.
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
