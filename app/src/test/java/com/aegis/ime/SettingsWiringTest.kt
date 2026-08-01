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

    @Test fun service_serves_the_live_user_dict_host_before_building_the_initial_engine() {
        val svc = src("src/main/java/com/aegis/ime/AegisInputMethodService.kt")
        assertTrue(
            "the live host must be registered on the dictionary-load worker",
            svc.contains("UserDictHot.host = liveUserDictHost"),
        )
        assertTrue(
            "onDestroy must withdraw only its own host",
            svc.contains("if (UserDictHot.host === liveUserDictHost) UserDictHot.host = null"),
        )
        val loadGate = svc.indexOf("runCatching { com.aegis.ime.engine.InputAssociations.lookup(\"nihao\") }")
        val hostReg = svc.indexOf("UserDictHot.host = liveUserDictHost")
        val engineBuild = svc.indexOf("val engine = buildEngine()")
        assertTrue("host registration must follow worker warmup", loadGate in 1 until hostReg)
        assertTrue("host registration must precede initial engine construction", hostReg in 1 until engineBuild)
    }

    @Test fun service_routes_database_backed_user_learning_and_reloads_after_restore() {
        val svc = src("src/main/java/com/aegis/ime/AegisInputMethodService.kt")
        assertTrue(svc.contains("private val userDatabase by userDatabaseDelegate"))
        assertTrue(svc.contains("private val userModel by lazy { UserModel(database = userDatabase) }"))
        assertTrue(svc.contains("private val userLearning by lazy { UserLearning(database = userDatabase) }"))
        assertTrue(svc.contains("private val userLearnFile by lazy { File(filesDir, \"userlearn.txt\") }"))
        assertTrue(svc.contains("controller.userLearning = userLearning"))
        assertTrue(svc.contains("octagram, userLearning)"))
        val restored = svc.substringAfter("LiveUserData.onRestored = {").substringBefore("LiveUserData.registerClipboardPersistenceHooks")
        assertTrue(restored.contains("userModel.reloadFromStorage()"))
        assertTrue(restored.contains("userLearning.reloadFromStorage()"))
    }

    @Test fun service_teardown_drains_clipboard_persistence_without_clearing_the_restore_guard() {
        val svc = src("src/main/java/com/aegis/ime/AegisInputMethodService.kt")
        val onDestroy = svc.substringAfter("override fun onDestroy()").substringBefore("// --- ImeHost ---")
        assertTrue(
            "onDestroy must drain owned clipboard persistence hooks before withdrawing them",
            onDestroy.contains("LiveUserData.unregisterClipboardPersistenceHooks(clipboardPendingWriteFlush)"),
        )
        assertFalse(
            "onDestroy must leave restore guard ownership to restore/reload code",
            onDestroy.contains("LiveUserData.restoreInProgress = false"),
        )
    }

    @Test fun engine_reload_rechecks_after_initial_build_and_after_a_successful_hot_reload() {
        val svc = src("src/main/java/com/aegis/ime/AegisInputMethodService.kt")
        val calls = Regex("""(?<!fun )maybeReloadEngine\(\)""").findAll(svc).count()
        assertTrue("expected the onStartInput call plus both build-site re-checks, found $calls", calls >= 3)
    }

    @Test fun opening_settings_hides_the_keyboard_before_launching_the_activity() {
        val svc = src("src/main/java/com/aegis/ime/AegisInputMethodService.kt")
        val body = svc.substringAfter("private fun openSettings()").substringBefore("private fun launchPhraseTransfer")
        val hide = body.indexOf("requestHideSelf(0)")
        val launch = body.indexOf("startActivity(")
        assertTrue("openSettings must request the IME hide", hide >= 0)
        assertTrue("the hide request must precede the activity launch", launch > hide)
    }

    @Test fun download_work_is_screen_independent_and_observed_by_cards() {
        val runtime = src("src/main/java/com/aegis/ime/ui/DownloadCardWork.kt")
        assertTrue("download runtime must use the application context, not a screen context", runtime.contains("context.applicationContext"))
        assertTrue("download runtime must expose observer snapshots for recreated cards", runtime.contains("fun observe(context: Context"))

        val gram = src("src/main/java/com/aegis/ime/ui/GramDownloadCard.kt")
        val dict = src("src/main/java/com/aegis/ime/ui/DictDownloadCard.kt")
        assertTrue("model card must observe the process-level download runtime", gram.contains("GramDownloadWork.observe(context)"))
        assertTrue("dictionary card must observe the process-level download runtime", dict.contains("DictDownloadWork.observe(context)"))
        assertFalse("model card must not own the long-running download thread", gram.contains("ModelDownload.download("))
        assertFalse("dictionary card must not own the long-running download thread", dict.contains("ModelDownload.download("))
    }

    @Test fun both_download_cards_bump_the_touch_counter_on_install_and_delete() {
        val runtime = src("src/main/java/com/aegis/ime/ui/DownloadCardWork.kt")
        val installBumps = Regex("""SettingsHotApply\.noteEnginePackChanged\(prefs\)""").findAll(runtime).count()
        assertTrue("download runtime must bump the counter for model and dictionary installs", installBumps >= 2)
        for (card in listOf(
            "src/main/java/com/aegis/ime/ui/GramDownloadCard.kt",
            "src/main/java/com/aegis/ime/ui/DictDownloadCard.kt",
        )) {
            val deleteBumps = Regex("""SettingsHotApply\.noteEnginePackChanged\(prefs\)""").findAll(src(card)).count()
            assertTrue("$card must bump the counter on delete (found $deleteBumps)", deleteBumps >= 1)
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

    @Test fun data_backup_is_a_home_group_not_an_about_entry() {
        val setup = src("src/main/java/com/aegis/ime/ui/SetupActivity.kt")
        assertTrue("home route order must place backup between user dictionary and about", setup.contains("listOf(INPUT, DICTS, USER_DICT, BACKUP, ABOUT)"))
        assertTrue("backup route must open BackupActivity directly", setup.contains("SettingsRoutes.BACKUP -> BackupActivity::class.java"))
        val aboutPage = setup.substringAfter("fun AboutPage").substringBefore("fun SetupStepActions")
        assertFalse("About page must not keep a duplicate data-backup entry", aboutPage.contains("settings_backup_title"))
        val aboutActivity = src("src/main/java/com/aegis/ime/ui/AboutActivity.kt")
        assertFalse("AboutActivity must not launch BackupActivity", aboutActivity.contains("BackupActivity"))
    }

    @Test fun backup_password_dialogs_keep_visibility_and_default_autofill_affordances() {
        val backup = src("src/main/java/com/aegis/ime/ui/BackupActivity.kt")
        assertTrue("password fields must route through the shared show/hide field", backup.contains("fun PasswordTextField"))
        assertTrue("password fields must offer a show control", backup.contains("backup_password_show"))
        assertTrue("password fields must offer a hide control", backup.contains("backup_password_hide"))
        assertTrue("export/import dialogs must expose the saved default action", backup.contains("backup_default_password_use_button"))
        assertTrue("default password fill must populate export password and confirmation", backup.contains("confirm = fill"))
        assertTrue("default password fill must populate import password", backup.contains("password = fill"))
        val store = src("src/main/java/com/aegis/ime/ui/BackupDefaultPasswordStore.kt")
        assertTrue("default password must use Android Keystore AES-GCM", store.contains("AndroidKeyStore") && store.contains("AES/GCM/NoPadding"))
        assertFalse("default password store must not use the backup settings prefs", store.contains("getSharedPreferences(\"aegis\""))
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
