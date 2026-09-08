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
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.aegis.ime.AegisInputMethodService
import com.aegis.ime.ime.DecodeLane
import com.aegis.ime.ime.EmailDomains
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.ui.BackupJob
import com.aegis.ime.ui.BackupUiState
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.UserDictEdit
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserLexicon
import com.aegis.ime.user.UserLexiconTransfer
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
    private val services = ArrayList<AegisInputMethodService>()

    @Before fun clean() {
        UserDictHot.host = null
        LiveUserData.onRestored = null
        LiveUserData.onLexiconsRestored = null
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
        LiveUserData.onLexiconsRestored = null
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
        assertNotNull(LiveUserData.onLexiconsRestored)
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

    @Test fun english_and_email_json_imports_preserve_unflushed_chinese_words_and_learning() {
        for (mode in BackupManager.Mode.entries) {
            val service = start()
            val editor = attachEditor(service)
            val keyboard = controller(service)
            val lexicon = UserLexicon(prefs)
            lexicon.add(UserLexicon.Kind.ENGLISH, "OpenAegis")
            lexicon.add(UserLexicon.Kind.EMAIL, "example.org")
            val english = UserLexiconTransfer.export(filesDir, prefs, setOf(UserLexiconTransfer.Scope.ENGLISH))
            val email = UserLexiconTransfer.export(filesDir, prefs, setOf(UserLexiconTransfer.Scope.EMAIL))
            assertTrue(lexicon.remove(UserLexicon.Kind.ENGLISH, "OpenAegis"))
            assertTrue(lexicon.resetEmailDefaults())
            shadowOf(Looper.getMainLooper()).idle()

            val liveModel = model(service)
            val liveLearning = learning(service)
            assertTrue(liveModel.addManualWord("weiluopan", "未落盘", System.currentTimeMillis()))
            formLearnedWord(liveLearning)
            val wordsBefore = liveModel.userWordEntries()
            val learnedBefore = liveLearning.formedEntries()
            val dictionaryBefore = userDb.takeIf { it.exists() }?.readText()
            val learningBefore = userLearn.takeIf { it.exists() }?.readText()
            assertTrue(liveModel.dirty)
            assertTrue(liveLearning.dirty)

            fun assertUnchanged() {
                assertEquals(mode.name, wordsBefore, liveModel.userWordEntries())
                assertEquals(mode.name, learnedBefore, liveLearning.formedEntries())
                assertTrue(mode.name, liveModel.dirty)
                assertTrue(mode.name, liveLearning.dirty)
                assertEquals(mode.name, dictionaryBefore, userDb.takeIf { it.exists() }?.readText())
                assertEquals(mode.name, learningBefore, userLearn.takeIf { it.exists() }?.readText())
            }

            keyboard.onKey(Key("", action = KeyAction.TOGGLE_LANG))
            "ope".forEach { keyboard.onKey(Key(it.toString(), output = it.toString())) }
            assertEquals(listOf("ope"), settledCandidates(service))

            english.inputStream().use { UserLexiconTransfer.importData(filesDir, prefs, it, mode) }
            drainRestoredStores()

            assertUnchanged()
            assertEquals(listOf("ope", "OpenAegis"), settledCandidates(service))
            keyboard.reset()
            requireNotNull(editor.editable).clear()
            editor.commitText("name@", 1)
            keyboard.onEditorContextChanged()
            assertFalse("example.org" in settledCandidates(service))

            email.inputStream().use { UserLexiconTransfer.importData(filesDir, prefs, it, mode) }
            drainRestoredStores()

            assertUnchanged()
            assertTrue("example.org" in settledCandidates(service))
            stop(service)
        }
    }

    @Test fun all_lexicons_json_restores_live_chinese_learning_and_english_email_candidates() {
        seed()
        UserLearning().also {
            formLearnedWord(it)
            it.save(userLearn)
        }
        val archive = UserLexiconTransfer.export(filesDir, prefs, UserLexiconTransfer.Scope.entries.toSet())
        val service = start()
        val editor = attachEditor(service)
        val keyboard = controller(service)
        assertTrue(UserDictEdit.remove(userDb, "guidangci", "归档词"))
        assertTrue(model(service).addManualWord("houlaici", "后来词", System.currentTimeMillis()))
        learning(service).clear()
        assertTrue(model(service).dirty)
        assertTrue(learning(service).dirty)
        assertTrue(UserLexicon(prefs).remove(UserLexicon.Kind.ENGLISH, "OpenAegis"))
        assertTrue(UserLexicon(prefs).resetEmailDefaults())
        keyboard.onKey(Key("", action = KeyAction.TOGGLE_LANG))
        "ope".forEach { keyboard.onKey(Key(it.toString(), output = it.toString())) }
        assertEquals(listOf("ope"), settledCandidates(service))

        archive.inputStream().use { UserLexiconTransfer.importData(filesDir, prefs, it, BackupManager.Mode.OVERWRITE) }
        drainRestoredStores()

        assertSeedRestored()
        assertEquals(listOf("你呢嗯"), learning(service).formedEntries().map { it.word })
        assertEquals(listOf("你呢嗯"), UserLearning().apply { load(userLearn) }.formedEntries().map { it.word })
        assertFalse(model(service).dirty)
        assertFalse(learning(service).dirty)
        assertEquals(listOf("ope", "OpenAegis"), settledCandidates(service))
        keyboard.reset()
        requireNotNull(editor.editable).clear()
        editor.commitText("name@", 1)
        keyboard.onEditorContextChanged()
        assertEquals("example.org", settledCandidates(service).first())
        assertFalse("qq.com" in settledCandidates(service))

        stop(service)
        val reopened = start()
        assertSeedRestored()
        assertEquals(listOf("你呢嗯"), learning(reopened).formedEntries().map { it.word })
    }
}
