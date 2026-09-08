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

package com.aegis.ime.backup

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.aegis.ime.AegisInputMethodService
import com.aegis.ime.R
import com.aegis.ime.ime.DecodeLane
import com.aegis.ime.ime.EmailDomains
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.ui.BackupActivity
import com.aegis.ime.ui.BackupJob
import com.aegis.ime.ui.BackupUiState
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserLexicon
import com.aegis.ime.user.UserModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserLexiconBackupWiringTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val filesDir: File get() = context.filesDir
    private val userDb: File get() = File(filesDir, "userdb.txt")
    private val userLearn: File get() = File(filesDir, "userlearn.txt")
    private val prefs get() = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
    private val password = "lexicon-backup-pass"
    private val services = ArrayList<AegisInputMethodService>()
    private val page by lazy { Robolectric.buildActivity(BackupActivity::class.java).get() }

    @Before fun clean() {
        UserDictHot.host = null
        LiveUserData.onRestored = null
        LiveUserData.onBeforeExport = null
        LiveUserData.onBeforeRestore = null
        LiveUserData.clipboardHost = null
        LiveUserData.restoreInProgress = false
        LiveUserData.restoreTrouble = null
        assertFalse(BackupJob.inProgress)
        prefs.edit().clear().commit()
        filesDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    @After fun close() {
        services.toList().forEach(::stop)
        UserDictHot.host = null
        LiveUserData.onRestored = null
        LiveUserData.onBeforeExport = null
        LiveUserData.onBeforeRestore = null
        LiveUserData.clipboardHost = null
        LiveUserData.restoreInProgress = false
        val discard: (BackupUiState.Result) -> Unit = {}
        BackupJob.reportTo(discard)
        BackupJob.stopReportingTo(discard)
    }

    private fun start(): AegisInputMethodService {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).create().get()
        services += service
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && loading()) Thread.yield()
        assertFalse("the service must finish opening its actual stores", loading())
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(UserDictHot.host)
        assertNotNull(LiveUserData.onRestored)
        return service
    }

    private fun loading() = Thread.getAllStackTraces().keys.any { it.name == "aegis-dict-load" && it.isAlive }

    private fun stop(service: AegisInputMethodService) {
        service.onDestroy()
        services.remove(service)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun controller(service: AegisInputMethodService): KeyboardController =
        service.javaClass.getDeclaredField("controller").run {
            isAccessible = true
            get(service) as KeyboardController
        }

    private fun settledCandidates(service: AegisInputMethodService): List<String> {
        val worker = service.javaClass.getDeclaredField("decodeWorker").run {
            isAccessible = true
            get(service) as ExecutorService
        }
        val lane = service.javaClass.getDeclaredField("decodeLane").run {
            isAccessible = true
            get(service) as DecodeLane
        }
        shadowOf(Looper.getMainLooper()).idle()
        worker.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("the real decode worker and its main-thread result must finish", lane.pending)
        return controller(service).candidateWords()
    }

    private fun model(service: AegisInputMethodService): UserModel =
        service.javaClass.getDeclaredField("userModel").run {
            isAccessible = true
            get(service) as UserModel
        }

    private fun learning(service: AegisInputMethodService): UserLearning =
        service.javaClass.getDeclaredField("userLearning").run {
            isAccessible = true
            get(service) as UserLearning
        }

    private fun formLearnedWord(learning: UserLearning) {
        val now = System.currentTimeMillis()
        learning.enabled = true
        repeat(8) {
            var previous: String? = null
            for ((word, reading) in listOf("你" to "ni", "呢" to "ne", "嗯" to "n")) {
                learning.observeCommit(previous, word, reading, now)
                previous = word
            }
            learning.observeBreak()
        }
        assertEquals(listOf("你呢嗯"), learning.formedEntries().map { it.word })
    }

    private fun attachEditor(service: AegisInputMethodService): BaseInputConnection {
        service.onStartInput(EditorInfo().apply {
            packageName = "com.example.editor"
            fieldId = 101
            inputType = InputType.TYPE_CLASS_TEXT
        }, false)
        val editor = BaseInputConnection(FrameLayout(service), true)
        for (name in listOf("mInputConnection", "mStartedInputConnection")) {
            service.javaClass.superclass!!.getDeclaredField(name).apply {
                isAccessible = true
                set(service, editor)
            }
        }
        controller(service).setEnAssociationsEnabled(true)
        controller(service).setEmailAssociationsEnabled(true)
        return editor
    }

    private fun drainRestoredStores() {
        shadowOf(Looper.getMainLooper()).idle()
        (UserDictHot.host as? LiveUserDictHost)?.let { host ->
            val io = host.javaClass.getDeclaredField("io").run {
                isAccessible = true
                get(host) as ExecutorService
            }
            io.submit {}.get(10, TimeUnit.SECONDS)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("the actual service restore callback must release the capture guard", LiveUserData.restoreInProgress)
    }

    private fun runActivityJob(action: () -> Unit): BackupUiState.Result {
        var result: BackupUiState.Result? = null
        val listener: (BackupUiState.Result) -> Unit = { result = it }
        BackupJob.reportTo(listener)
        try {
            action()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (result == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertNotNull("the activity backup job must return its result", result)
            return requireNotNull(result)
        } finally {
            BackupJob.stopReportingTo(listener)
        }
    }

    private fun export(): ByteArray {
        val uri = Uri.parse("content://com.aegis.ime.test/user-lexicon-backup")
        val out = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, out)
        page.javaClass.getDeclaredField("pendingExportPassword").apply {
            isAccessible = true
            set(page, password.toCharArray())
        }
        val result = runActivityJob {
            page.javaClass.getDeclaredMethod("onExportTarget", Uri::class.java).apply {
                isAccessible = true
                invoke(page, uri)
            }
        }
        assertEquals(R.string.backup_export_ok, result.messageRes)
        return out.toByteArray().also { assertTrue(it.isNotEmpty()) }
    }

    private fun restore(bytes: ByteArray, mode: BackupManager.Mode = BackupManager.Mode.OVERWRITE) {
        val uri = Uri.parse("content://com.aegis.ime.test/user-lexicon-restore")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        page.javaClass.getDeclaredField("pendingImportUri").apply {
            isAccessible = true
            set(page, uri)
        }
        val result = runActivityJob {
            page.javaClass.getDeclaredMethod("beginImport", String::class.java, BackupManager.Mode::class.java).apply {
                isAccessible = true
                invoke(page, password, mode)
            }
        }
        assertEquals(
            if (mode == BackupManager.Mode.MERGE) R.string.backup_import_ok_merge else R.string.backup_import_ok_overwrite,
            result.messageRes,
        )
        drainRestoredStores()
    }

    private fun seed() {
        assertTrue(UserDictEdit.add(userDb, "归档词", "guidangci", System.currentTimeMillis()))
        val lexicon = UserLexicon(prefs)
        assertEquals(UserLexicon.AddResult.ADDED, lexicon.add(UserLexicon.Kind.ENGLISH, "OpenAegis"))
        assertEquals(UserLexicon.AddResult.ADDED, lexicon.add(UserLexicon.Kind.EMAIL, "example.org"))
        assertTrue(lexicon.remove(UserLexicon.Kind.EMAIL, "qq.com"))
        EmailDomains(prefs).record("example.org")
    }

    private fun assertSeedRestored() {
        assertEquals(listOf("归档词"), UserDictEdit.summary(userDb).entries.map { it.word })
        assertEquals(listOf("归档词"), UserModel().apply { load(userDb) }.userWordEntries().map { it.word })
        assertEquals(listOf("OpenAegis"), UserLexicon(prefs).entries(UserLexicon.Kind.ENGLISH))
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS - "qq.com" + "example.org", UserLexicon(prefs).entries(UserLexicon.Kind.EMAIL))
        assertEquals("example.org", EmailDomains(prefs).suggestions().first())
        assertEquals(1L, prefs.getLong(UserLexicon.EMAIL_COUNT_PREFIX + "example.org", 0L))
    }

    private fun assertEmptyRestored() {
        assertTrue(UserDictEdit.summary(userDb).entries.isEmpty())
        assertTrue(UserLexicon(prefs).entries(UserLexicon.Kind.ENGLISH).isEmpty())
        assertEquals(UserLexicon.COMMON_EMAIL_DOMAINS, UserLexicon(prefs).entries(UserLexicon.Kind.EMAIL))
        assertFalse(prefs.all.keys.any { it.startsWith(UserLexicon.EMAIL_COUNT_PREFIX) })
    }

    @Test fun encrypted_activity_backup_restores_all_lexicons_into_the_running_service_and_after_reopening() {
        seed()
        val service = start()
        val archive = export()
        assertTrue(UserDictEdit.remove(userDb, "guidangci", "归档词"))
        assertTrue(UserDictEdit.add(userDb, "后来词", "houlaici", System.currentTimeMillis()))
        assertTrue(UserLexicon(prefs).remove(UserLexicon.Kind.ENGLISH, "OpenAegis"))
        assertTrue(UserLexicon(prefs).resetEmailDefaults())
        val editor = attachEditor(service)
        val keyboard = controller(service)
        keyboard.onKey(Key("", action = KeyAction.TOGGLE_LANG))
        "ope".forEach { keyboard.onKey(Key(it.toString(), output = it.toString())) }
        assertEquals(listOf("ope"), settledCandidates(service))

        restore(archive)

        assertSeedRestored()
        assertEquals(listOf("ope", "OpenAegis"), settledCandidates(service))
        keyboard.onPickCandidate(1)
        assertEquals("OpenAegis", editor.editable.toString())
        stop(service)
        val reopened = start()
        assertSeedRestored()
        attachEditor(reopened)
        controller(reopened).onKey(Key("", action = KeyAction.TOGGLE_LANG))
        "ope".forEach { controller(reopened).onKey(Key(it.toString(), output = it.toString())) }
        assertTrue("OpenAegis" in settledCandidates(reopened))
    }

    @Test fun restore_refreshes_live_email_candidates_in_both_chinese_layouts_and_english() {
        seed()
        val archive = export()
        for (layout in listOf("alpha", "nine", "english")) {
            assertTrue(UserLexicon(prefs).resetEmailDefaults())
            val service = start()
            val editor = attachEditor(service)
            val keyboard = controller(service)
            keyboard.switchTextLayoutForTest(layout == "nine")
            if (layout == "english") keyboard.onKey(Key("", action = KeyAction.TOGGLE_LANG))
            editor.commitText("name@", 1)
            keyboard.onEditorContextChanged()
            assertFalse(layout, "example.org" in settledCandidates(service))
            assertTrue(layout, "qq.com" in settledCandidates(service))

            restore(archive)

            assertEquals(layout, "example.org", settledCandidates(service).first())
            assertFalse(layout, "qq.com" in settledCandidates(service))
            keyboard.onPickCandidate(0)
            assertEquals(layout, "name@example.org", editor.editable.toString())
            stop(service)
        }
    }

    @Test fun a_pristine_backup_explicitly_restores_empty_lexicons_without_a_running_service() {
        assertFalse(userDb.exists())
        assertFalse(prefs.contains(UserLexicon.PREF_ENGLISH_WORDS))
        assertFalse(prefs.contains(UserLexicon.PREF_EMAIL_DOMAINS))
        assertFalse(prefs.contains(UserLexicon.PREF_DISABLED_EMAIL_DOMAINS))
        val archive = export()
        seed()

        restore(archive)

        assertEmptyRestored()
    }

    @Test fun a_pristine_backup_can_clear_a_running_dictionary_and_survives_reopening() {
        val archive = export()
        val service = start()
        seed()

        restore(archive)

        assertEmptyRestored()
        stop(service)
        start()
        assertEmptyRestored()
    }

    @Test fun a_saved_empty_dictionary_merges_without_removal_and_overwrites_with_its_empty_state() {
        UserModel().save(userDb)
        val archive = export()
        start()
        seed()

        restore(archive, BackupManager.Mode.MERGE)

        assertSeedRestored()
        restore(archive)
        assertEmptyRestored()
    }

}
