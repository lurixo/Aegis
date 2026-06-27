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

package com.aegis.ime.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import com.aegis.ime.R
import com.aegis.ime.ime.EmailDomains
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLexicon
import com.aegis.ime.user.UserLexiconTransfer
import com.aegis.ime.user.UserLexiconTransfer.Scope
import com.aegis.ime.user.UserStoreEdits
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UserLexiconTransferUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    private val sourcePrefs = context.getSharedPreferences("lexicon-transfer-ui-source", Context.MODE_PRIVATE)
    private val lexicon = UserLexicon(prefs)
    private val db = File(context.filesDir, "userdb.txt")
    private val learn = File(context.filesDir, "userlearn.txt")
    private val input = File(context.cacheDir, "lexicon-transfer-ui-input.json")
    private val output = File(context.cacheDir, "lexicon-transfer-ui-output.json")
    private var scenario: ActivityScenario<UserDictActivity>? = null

    @Before fun reset() {
        drainEdits()
        resetLiveState()
        clearFiles()
        prefs.edit().clear().commit()
        sourcePrefs.edit().clear().commit()
        AegisToast.reset()
    }

    @After fun cleanup() {
        drainEdits()
        scenario?.close()
        shadowOf(Looper.getMainLooper()).idle()
        drainEdits()
        resetLiveState()
        clearFiles()
        prefs.edit().clear().commit()
        sourcePrefs.edit().clear().commit()
        AegisToast.reset()
    }

    private fun resetLiveState() {
        UserDictHot.host = null
        LiveUserData.onRestored = null
        LiveUserData.onLexiconsRestored = null
        LiveUserData.onBeforeExport = null
        LiveUserData.onBeforeRestore = null
        LiveUserData.clipboardHost = null
        LiveUserData.restoreInProgress = false
        LiveUserData.restoreTrouble = null
    }

    private fun clearFiles() {
        listOf(db, learn, input, output).forEach { it.delete() }
        File(context.filesDir, "backup_staging").deleteRecursively()
        File(context.filesDir, "restore_journal").deleteRecursively()
        (stages("lexicon-import-") + stages("lexicon-export-")).forEach { it.delete() }
    }

    private fun stages(prefix: String): List<File> =
        context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith(prefix) && it.extension == "tmp" }

    private fun drainEdits() {
        val lane = UserStoreEdits::class.java.getDeclaredField("lane").run {
            isAccessible = true
            get(UserStoreEdits) as ExecutorService
        }
        lane.submit { }.get(10, TimeUnit.SECONDS)
    }

    private fun settleEdits() {
        repeat(2) {
            drainEdits()
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
        }
    }

    private fun seedStores() {
        db.writeText("aegis-userdb 1\nR\tceshi\t测试\n")
        learn.writeText("aegis-userlearn 1\n")
        lexicon.add(UserLexicon.Kind.ENGLISH, "OriginalWord")
        lexicon.add(UserLexicon.Kind.EMAIL, "private.example")
        lexicon.remove(UserLexicon.Kind.EMAIL, "gmail.com")
        EmailDomains(prefs).record("private.example")
        prefs.edit().putBoolean("keep_unrelated_setting", true).commit()
    }

    private fun open(tab: String) {
        scenario = ActivityScenario.launch(UserDictActivity::class.java)
        compose.onNodeWithTag("user_lexicon_tab_$tab").performClick()
        settleEdits()
    }

    private fun openTools() {
        compose.onNodeWithTag("user_lexicon_open_more").performClick()
        compose.onNodeWithTag("user_dict_more_sheet").assertExists()
    }

    private fun openExport() {
        openTools()
        compose.onNodeWithTag("user_dict_export").performScrollTo().performClick()
        compose.onNodeWithTag("lexicon_export_confirm").assertExists()
    }

    private fun selectOnly(scope: Scope) {
        Scope.entries.filter { it != scope }.forEach {
            compose.onNodeWithTag("lexicon_export_scope_${it.name.lowercase()}").performClick()
        }
    }

    private data class Picker(val requestCode: Int, val intent: Intent)

    private fun takePicker(action: String): Picker {
        settleEdits()
        var picker: Picker? = null
        scenario!!.onActivity { activity ->
            val launched = shadowOf(activity).nextStartedActivityForResult
            assertNotNull("the SAF picker must be launched", launched)
            assertEquals(action, launched.intent.action)
            picker = Picker(launched.requestCode, launched.intent)
        }
        return requireNotNull(picker)
    }

    private fun deliver(picker: Picker, file: File) {
        scenario!!.onActivity { activity ->
            assertTrue(activity.activityResultRegistry.dispatchResult(picker.requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
        }
        settleEdits()
    }

    private fun openImport(file: File) {
        openTools()
        compose.onNodeWithTag("user_dict_import").performScrollTo().performClick()
        val picker = takePicker(Intent.ACTION_OPEN_DOCUMENT)
        deliver(picker, file)
        compose.onNodeWithTag("lexicon_import_scopes").assertExists()
    }

    private fun assertImportScope(label: Int) {
        compose.onNodeWithTag("lexicon_import_scopes")
            .assertTextEquals(context.getString(R.string.user_lexicon_import_help, context.getString(label)))
    }

    private fun readExport(scope: Scope): JSONObject {
        assertEquals(setOf(scope), output.inputStream().use(UserLexiconTransfer::inspect))
        val json = JSONObject(output.readText())
        assertEquals(setOf("format", "version", scope.name.lowercase()), json.keys().asSequence().toSet())
        assertEquals("aegis-lexicons", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        return json.getJSONObject(scope.name.lowercase())
    }

    @Test fun english_and_email_tools_default_to_all_export_scopes_and_disable_export_when_none_selected() {
        seedStores()
        open("english")
        val before = prefs.all.toMap()
        for (tab in listOf("english", "email")) {
            compose.onNodeWithTag("user_lexicon_tab_$tab").performClick()
            openExport()
            Scope.entries.forEach {
                compose.onNodeWithTag("lexicon_export_scope_${it.name.lowercase()}").assertIsOn().performClick()
            }
            compose.onNodeWithTag("lexicon_export_confirm").assertIsNotEnabled()
            compose.onNodeWithTag("lexicon_export_scope_english").performClick()
            compose.onNodeWithTag("lexicon_export_confirm").assertIsEnabled()
            compose.onNodeWithTag("lexicon_export_cancel").performClick()
            compose.onNodeWithTag("lexicon_export_confirm").assertDoesNotExist()
        }
        assertEquals(before, prefs.all)
        assertTrue(stages("lexicon-export-").isEmpty())
    }

    @Test fun english_only_export_uses_the_pending_snapshot_after_activity_recreation_during_the_picker() {
        seedStores()
        open("english")
        openExport()
        selectOnly(Scope.ENGLISH)
        compose.onNodeWithTag("lexicon_export_confirm").performClick()
        val picker = takePicker(Intent.ACTION_CREATE_DOCUMENT)
        assertEquals("application/json", picker.intent.type)
        assertEquals("aegis-lexicons.json", picker.intent.getStringExtra(Intent.EXTRA_TITLE))
        val staged = stages("lexicon-export-").single()
        val snapshot = staged.readBytes()
        assertEquals(setOf(Scope.ENGLISH), snapshot.inputStream().use(UserLexiconTransfer::inspect))

        scenario!!.recreate()
        settleEdits()
        compose.onNodeWithTag("user_lexicon_tab_english").assertIsSelected()
        assertTrue("the pending snapshot must survive activity recreation", staged.isFile)
        prefs.edit().putStringSet(UserLexicon.PREF_ENGLISH_WORDS, setOf("ChangedAfterPicker")).commit()
        output.writeBytes(ByteArray(20_000))
        deliver(picker, output)

        assertTrue(snapshot.contentEquals(output.readBytes()))
        assertEquals("OriginalWord", readExport(Scope.ENGLISH).getJSONArray("words").getString(0))
        assertEquals(listOf("ChangedAfterPicker"), lexicon.entries(UserLexicon.Kind.ENGLISH))
        assertEquals(context.getString(R.string.user_dict_toast_export_done), AegisToast.textForTest())
        assertFalse(staged.exists())
        assertTrue(stages("lexicon-export-").isEmpty())
    }

    @Test fun email_only_export_contains_custom_suffixes_disabled_defaults_and_frequency_counts() {
        seedStores()
        open("email")
        openExport()
        selectOnly(Scope.EMAIL)
        compose.onNodeWithTag("lexicon_export_confirm").performClick()
        deliver(takePicker(Intent.ACTION_CREATE_DOCUMENT), output)

        val email = readExport(Scope.EMAIL)
        assertEquals(listOf("private.example"), email.getJSONArray("domains").let { values -> List(values.length()) { values.getString(it) } })
        assertEquals(listOf("gmail.com"), email.getJSONArray("disabledDefaults").let { values -> List(values.length()) { values.getString(it) } })
        assertEquals(1L, email.getJSONObject("counts").getLong("private.example"))
        assertEquals(listOf("OriginalWord"), lexicon.entries(UserLexicon.Kind.ENGLISH))
        assertTrue(stages("lexicon-export-").isEmpty())
    }

    @Test fun english_import_inspects_scope_cancels_without_writes_then_overwrites_only_english_and_refreshes_the_list() {
        seedStores()
        UserLexicon(sourcePrefs).add(UserLexicon.Kind.ENGLISH, "ImportedWord")
        input.writeBytes(UserLexiconTransfer.export(context.cacheDir, sourcePrefs, setOf(Scope.ENGLISH)))
        open("english")
        compose.onNodeWithText("OriginalWord").assertIsDisplayed()
        val before = prefs.all.toMap()
        val beforeDb = db.readText()
        val beforeLearn = learn.readText()

        openImport(input)
        assertImportScope(R.string.user_lexicon_english)
        assertEquals(before, prefs.all)
        compose.onNodeWithTag("user_dict_import_cancel").performClick()
        settleEdits()
        assertEquals(before, prefs.all)
        assertEquals(beforeDb, db.readText())
        assertEquals(beforeLearn, learn.readText())
        assertTrue(stages("lexicon-import-").isEmpty())
        compose.onNodeWithText("OriginalWord").assertIsDisplayed()

        openImport(input)
        assertImportScope(R.string.user_lexicon_english)
        compose.onNodeWithTag("user_dict_import_overwrite").performClick()
        settleEdits()

        assertEquals(listOf("ImportedWord"), lexicon.entries(UserLexicon.Kind.ENGLISH))
        assertEquals(before.filterKeys { it != UserLexicon.PREF_ENGLISH_WORDS }, prefs.all.filterKeys { it != UserLexicon.PREF_ENGLISH_WORDS })
        assertEquals(beforeDb, db.readText())
        assertEquals(beforeLearn, learn.readText())
        compose.onNodeWithText("OriginalWord").assertDoesNotExist()
        compose.onNodeWithText("ImportedWord").assertIsDisplayed()
        assertEquals(context.getString(R.string.user_dict_toast_import_overwritten), AegisToast.textForTest())
        assertFalse(LiveUserData.restoreInProgress)
        assertTrue(stages("lexicon-import-").isEmpty())
    }

    @Test fun email_import_overwrites_empty_custom_data_and_disabled_defaults_without_touching_other_dictionaries() {
        seedStores()
        val source = UserLexicon(sourcePrefs)
        source.remove(UserLexicon.Kind.EMAIL, "qq.com")
        input.writeBytes(UserLexiconTransfer.export(context.cacheDir, sourcePrefs, setOf(Scope.EMAIL)))
        open("email")
        compose.onNodeWithTag("user_lexicon_list").performScrollToNode(hasText("private.example"))
        compose.onNodeWithText("private.example").assertIsDisplayed()
        val beforeDb = db.readText()
        val beforeLearn = learn.readText()

        openImport(input)
        assertImportScope(R.string.user_lexicon_email)
        compose.onNodeWithTag("user_dict_import_overwrite").performClick()
        settleEdits()

        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS - "qq.com", lexicon.entries(UserLexicon.Kind.EMAIL))
        assertEquals(setOf("qq.com"), prefs.getStringSet(UserLexicon.PREF_DISABLED_EMAIL_DOMAINS, emptySet()))
        assertEquals(emptySet<String>(), prefs.getStringSet(UserLexicon.PREF_EMAIL_DOMAINS, emptySet()))
        assertFalse(prefs.all.keys.any { it.startsWith(UserLexicon.EMAIL_COUNT_PREFIX) })
        assertEquals(listOf("OriginalWord"), lexicon.entries(UserLexicon.Kind.ENGLISH))
        assertEquals(beforeDb, db.readText())
        assertEquals(beforeLearn, learn.readText())
        assertTrue(prefs.getBoolean("keep_unrelated_setting", false))
        compose.onNodeWithText("private.example").assertDoesNotExist()
        compose.onNodeWithTag("user_lexicon_list").performScrollToNode(hasText("gmail.com"))
        compose.onNodeWithText("gmail.com").assertIsDisplayed()
        compose.onNodeWithTag("user_lexicon_tab_english").performClick()
        compose.onNodeWithText("OriginalWord").assertIsDisplayed()
        assertFalse(LiveUserData.restoreInProgress)
        assertTrue(stages("lexicon-import-").isEmpty())
    }
}
