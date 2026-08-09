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
import android.content.SharedPreferences
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserDictImport
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreOneAtATimeTest {

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var prefs: SharedPreferences
    private val password = "one-at-a-time-01"

    private val heldInCommit = CountDownLatch(1)
    private val letCommitFinish = CountDownLatch(1)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        filesDir = context.filesDir
        prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
        UserDictHot.host = null
        LiveUserData.onRestored = null
        LiveUserData.onBeforeExport = null
        LiveUserData.onBeforeRestore = null
        LiveUserData.clipboardHost = null
        LiveUserData.restoreInProgress = false
        wipe()
        File(filesDir, "backup_staging").deleteRecursively()
        File(filesDir, "restore_journal").deleteRecursively()
        prefs.edit().clear().commit()
    }

    @After fun letEverythingGo() {
        letCommitFinish.countDown()
        UserDictHot.host = null
        LiveUserData.restoreInProgress = false
    }

    private fun wipe() {
        listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt").forEach {
            File(filesDir, it).deleteRecursively()
        }
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "emoji").deleteRecursively()
    }

    private fun seedTheBackupData() {
        UserModel().apply { addManualWord("beifen", "备份", 2_000L) }.save(File(filesDir, "userdb.txt"))
        UserLearning { 2_000L }.apply { observeCommit("备", "份", "fen", 2_000L) }
            .save(File(filesDir, "userlearn.txt"))
        ClipboardStore(filesDir).apply {
            load()
            addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("备份常用语"))
            record("备份剪贴")
            flushPendingWrites()
            stopSaving()
        }
        SymbolUsageStore(filesDir).apply { load(); record("§", "备份") }
        SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).apply { load(); record("🎉", "备份") }
        SymbolUsageStore.flushPendingWrites()
        prefs.edit().putString("cn_layout", "备份布局").commit()
    }

    private fun export(): ByteArray {
        val bos = ByteArrayOutputStream()
        BackupManager.export(filesDir, prefs, password.toCharArray(), bos)
        return bos.toByteArray()
    }

    private fun restore(backup: ByteArray): Result<BackupManager.Mode> = runCatching {
        BackupManager.restore(
            filesDir,
            prefs,
            password.toCharArray(),
            ByteArrayInputStream(backup),
            BackupManager.Mode.OVERWRITE,
        )
    }

    private inner class KeyboardHeldMidCommit : UserDictHot.Host {
        private val calls = AtomicInteger(0)

        override fun addWord(reading: String, word: String, now: Long) = false
        override fun removeWord(reading: String, word: String) = false
        override fun importUserDict(importFile: File, merge: Boolean, now: Long): Boolean {
            if (calls.getAndIncrement() == 0) {
                heldInCommit.countDown()
                letCommitFinish.await(20, TimeUnit.SECONDS)
            }
            return UserDictImport.apply(importFile, File(filesDir, "userdb.txt"), merge, now)
        }
        override fun reloadDictionary() = true
        override fun entries(): List<UserModel.Entry> = emptyList()
        override fun learnedEntries(): List<UserLearning.Formed> = emptyList()
        override fun hasLearnedData() = false
        override fun removeLearned(word: String, reading: String) = false
        override fun clearLearned() = false
        override fun flush() = true
    }

    @Test fun a_second_restore_never_starts_on_top_of_one_that_is_still_running() {
        seedTheBackupData()
        val backup = export()
        wipe()
        prefs.edit().clear().putString("cn_layout", "本机布局").commit()
        UserDictHot.host = KeyboardHeldMidCommit()

        val running = arrayOfNulls<Result<BackupManager.Mode>>(1)
        val first = Thread({ running[0] = restore(backup) }, "aegis-test-restore").apply {
            isDaemon = true
            start()
        }
        assertTrue("precondition: the first restore really is part way through", heldInCommit.await(20, TimeUnit.SECONDS))
        val staging = File(filesDir, "backup_staging")
        val journal = File(filesDir, "restore_journal")
        assertTrue("precondition: it has staged the archive", File(staging, "userdb.txt").isFile)
        assertTrue("precondition: it has written down what to roll back to", File(journal, "before").isDirectory)

        val second = restore(backup)

        assertTrue("a second restore over a running one must be refused", second.isFailure)
        assertEquals(
            BackupError.ALREADY_RESTORING,
            (second.exceptionOrNull() as BackupException).error,
        )
        assertTrue("the running restore's staged archive must still be there", File(staging, "userdb.txt").isFile)
        assertTrue("and so must what it would roll back to", File(journal, "before").isDirectory)

        letCommitFinish.countDown()
        first.join(20_000)

        assertTrue("the restore that was already running must still finish", running[0]?.isSuccess == true)
        assertEquals("备份布局", prefs.getString("cn_layout", null))
        assertEquals(listOf("备份常用语"), ClipboardStore(filesDir).apply { load() }.phrasesIn(ClipboardStore.DEFAULT_CATEGORY_ID))
        assertTrue("the word list came back", File(filesDir, "userdb.txt").readText().contains("备份"))
    }

    @Test fun a_restore_that_finished_does_not_hold_the_next_one_off() {
        seedTheBackupData()
        val backup = export()
        wipe()

        assertTrue(restore(backup).isSuccess)
        assertTrue("nothing may be left latched behind a restore that finished", restore(backup).isSuccess)
    }

    @Test fun a_restore_that_failed_does_not_hold_the_next_one_off() {
        seedTheBackupData()
        val backup = export()
        wipe()

        val refused = restore("not an aegis backup at all".toByteArray())

        assertTrue("precondition: the first attempt was refused", refused.isFailure)
        assertTrue("a refused restore must not latch the next one out", restore(backup).isSuccess)
    }
}
