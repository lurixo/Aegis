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
import com.aegis.ime.user.ClipEntry
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserDictImport
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreRollbackTest {

    private lateinit var filesDir: File
    private lateinit var prefs: SharedPreferences
    private val password = "rollback-pass-01"
    private val hosts = ArrayList<LiveUserDictHost>()
    private val stores = ArrayList<ClipboardStore>()

    @Before fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
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

    @After fun letGo() {
        hosts.forEach { runCatching { it.stopSaving() } }
        stores.forEach { runCatching { it.stopSaving() } }
        UserDictHot.host = null
        LiveUserData.onRestored = null
        LiveUserData.clipboardHost = null
    }

    private fun wipe() {
        listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt").forEach {
            File(filesDir, it).deleteRecursively()
        }
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "emoji").deleteRecursively()
    }

    private fun store(): ClipboardStore = ClipboardStore(filesDir).apply { load() }.also { stores += it }

    private fun seedBackupData() {
        UserModel().apply { addManualWord("beifen", "备份", 2_000L) }.save(File(filesDir, "userdb.txt"))
        UserLearning { 2_000L }.apply { observeCommit("备", "份", "fen", 2_000L) }
            .save(File(filesDir, "userlearn.txt"))
        store().apply {
            addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("备份常用语"))
            importHistory(listOf(ClipEntry.of("备份剪贴")), merge = false)
            flushPendingWrites()
            stopSaving()
        }
        SymbolUsageStore(filesDir).apply { load(); record("§", "备份") }
        SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).apply { load(); record("🎉", "备份") }
        prefs.edit().clear().putString("cn_layout", "备份布局").commit()
    }

    private fun seedLocalData() {
        UserModel().apply { addManualWord("bendi", "本地", 1_000L) }.save(File(filesDir, "userdb.txt"))
        UserLearning { 1_000L }.apply { observeCommit("本", "地", "di", 1_000L) }
            .save(File(filesDir, "userlearn.txt"))
        store().apply {
            addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("本地常用语"))
            importHistory(
                listOf(ClipEntry.of("本地大块" + "b".repeat(ClipboardStore.BIG_THRESHOLD + 1))),
                merge = false,
            )
            flushPendingWrites()
            stopSaving()
        }
        SymbolUsageStore(filesDir).apply { load(); record("★", "本地") }
        SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).apply { load(); record("😀", "本地") }
        prefs.edit().clear().putString("cn_layout", "本机布局").putInt("some_int", 7).commit()
    }

    private fun export(): ByteArray {
        val bos = ByteArrayOutputStream()
        BackupManager.export(filesDir, prefs, password.toCharArray(), bos)
        return bos.toByteArray()
    }

    private fun blockTheLastStore() {
        val f = File(filesDir, "emoji/symbol_usage.txt")
        f.deleteRecursively()
        f.parentFile?.mkdirs()
        assertTrue("precondition: the emoji history path is blocked", f.mkdirs())
        File(f, "blocker").writeText("x")
    }

    private fun snapshot(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (item in BackupItem.entries) {
            val f = File(filesDir, item.relativePath)
            out[item.relativePath] = if (f.isFile) f.readText() else "<not a file>"
        }
        out["clips"] = File(filesDir, "clips").listFiles().orEmpty()
            .sortedBy { it.name }
            .joinToString(",") { it.name + ":" + it.readText().take(24) }
        out["<settings>"] = prefs.all.toSortedMap().toString()
        return out
    }

    private fun restore(backup: ByteArray, mode: BackupManager.Mode = BackupManager.Mode.OVERWRITE) =
        BackupManager.restore(filesDir, prefs, password.toCharArray(), ByteArrayInputStream(backup), mode)

    private fun expectTheRestoreToFail(backup: ByteArray, mode: BackupManager.Mode = BackupManager.Mode.OVERWRITE) {
        try {
            restore(backup, mode)
            fail("expected the blocked store to be reported")
        } catch (e: BackupException) {
            assertEquals(BackupError.IO_ERROR, e.error)
        }
    }

    @Test fun a_restore_that_could_not_finish_leaves_every_store_as_it_was() {
        seedBackupData()
        val backup = export()
        wipe()
        seedLocalData()
        blockTheLastStore()
        val before = snapshot()

        expectTheRestoreToFail(backup)

        assertEquals("a restore is all or nothing", before, snapshot())
        assertFalse("the journal must be spent", File(filesDir, "restore_journal").exists())
        assertFalse("staging must be cleaned up", File(filesDir, "backup_staging").exists())
        assertFalse("the guard must not stay latched", LiveUserData.restoreInProgress)
    }

    @Test fun the_learned_data_is_left_where_it_is_until_the_copy_replacing_it_is_written() {
        seedBackupData()
        val backup = export()
        wipe()
        seedLocalData()
        val userLearn = File(filesDir, "userlearn.txt")
        val local = userLearn.readText()
        val staged = File(filesDir, "backup_staging/userlearn.txt")
        val copy = File(filesDir, "restore_journal/before/userlearn.txt")
        val unreadableOnce = object : SharedPreferences by prefs {
            override fun getAll(): MutableMap<String, *> {
                staged.setReadable(false, false)
                copy.setReadable(false, false)
                return prefs.all
            }
        }

        val restored = runCatching {
            BackupManager.restore(
                filesDir,
                unreadableOnce,
                password.toCharArray(),
                ByteArrayInputStream(backup),
                BackupManager.Mode.OVERWRITE,
            )
        }

        staged.setReadable(true, true)
        copy.setReadable(true, true)
        assertTrue("precondition: the restore could not be carried out", restored.isFailure)
        assertTrue("the learned data was taken off the device", userLearn.isFile)
        assertEquals("the learned data must be exactly as the device had it", local, userLearn.readText())
    }

    @Test fun a_merge_that_could_not_finish_leaves_every_store_as_it_was() {
        seedBackupData()
        val backup = export()
        wipe()
        seedLocalData()
        blockTheLastStore()
        val before = snapshot()

        expectTheRestoreToFail(backup, BackupManager.Mode.MERGE)

        assertEquals("a merge is all or nothing too", before, snapshot())
    }

    @Test fun the_live_dictionary_holds_the_device_words_again_after_a_restore_that_could_not_finish() {
        seedBackupData()
        val backup = export()
        wipe()
        seedLocalData()
        blockTheLastStore()

        val model = UserModel().apply { load(File(filesDir, "userdb.txt")) }
        val host = LiveUserDictHost(model, File(filesDir, "userdb.txt"), UserLearning(), File(filesDir, "userlearn.txt"))
            .also { hosts += it }
        UserDictHot.host = host
        assertEquals(listOf("本地"), host.entries().map { it.word })

        expectTheRestoreToFail(backup)

        assertEquals(
            "the running dictionary must not keep words the restore already took back",
            listOf("本地"),
            host.entries().map { it.word },
        )
        assertEquals(listOf("本地"), UserModel().apply { load(File(filesDir, "userdb.txt")) }.userWordEntries().map { it.word })
    }

    private fun liveDictionary(): LiveUserDictHost {
        val userDb = File(filesDir, "userdb.txt")
        val model = UserModel().apply { if (userDb.isFile) load(userDb, sweepStale = false) }
        return LiveUserDictHost(model, userDb, UserLearning(), File(filesDir, "userlearn.txt"))
            .also { hosts += it; UserDictHot.host = it }
    }

    @Test fun a_device_that_had_no_word_list_is_not_left_holding_the_archive_words() {
        seedBackupData()
        val backup = export()
        wipe()
        seedLocalData()
        assertTrue("precondition: the device carries no word list of its own", File(filesDir, "userdb.txt").delete())
        blockTheLastStore()
        val host = liveDictionary()
        assertEquals(emptyList<String>(), host.entries().map { it.word })

        expectTheRestoreToFail(backup)

        assertEquals(
            "the rollback took the word list back off the device, so the running dictionary must let go of it too",
            emptyList<String>(),
            host.entries().map { it.word },
        )
        assertTrue(host.addWord("xz", "新增", 3_000L))
        assertEquals(
            "or the next word written puts the whole archive back on disk",
            listOf("新增"),
            UserModel().apply { load(File(filesDir, "userdb.txt")) }.userWordEntries().map { it.word },
        )
    }

    @Test fun the_live_clipboard_holds_the_device_phrases_again_after_a_restore_that_could_not_finish() {
        seedBackupData()
        val backup = export()
        wipe()
        seedLocalData()
        blockTheLastStore()

        val live = store()
        LiveUserData.clipboardHost = live
        LiveUserData.onRestored = { live.load() }
        assertEquals(listOf("本地常用语"), live.phrases())

        expectTheRestoreToFail(backup)

        assertEquals(
            "the running clipboard store must not keep phrases the restore already took back",
            listOf("本地常用语"),
            live.phrases(),
        )
    }

    @Test fun a_restore_that_never_finished_is_taken_back_before_the_next_one_starts() {
        seedBackupData()
        val backup = export()
        wipe()
        seedLocalData()
        RestoreJournal.open(filesDir, prefs)
        File(filesDir, "phrases.txt").writeText("C\tdefault\nP\t半途写进来的\n")

        restore(backup, BackupManager.Mode.MERGE)

        assertEquals(
            "what an unfinished restore left behind must be taken back before the next restore builds on it",
            listOf("备份常用语", "本地常用语"),
            store().phrases().sorted(),
        )
        assertFalse("the journal must be spent", File(filesDir, "restore_journal").exists())
    }

    private class KeyboardStartingMidRestore(
        private val filesDir: File,
        private val prefs: SharedPreferences,
    ) : UserDictHot.Host {
        var started = false
            private set

        override fun addWord(reading: String, word: String, now: Long) = false
        override fun removeWord(reading: String, word: String) = false
        override fun importUserDict(importFile: File, merge: Boolean, now: Long): Boolean {
            started = true
            RestoreJournal.finishAnyInterrupted(filesDir, prefs)
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

    @Test fun the_keyboard_starting_part_way_through_a_restore_does_not_undo_it() {
        seedBackupData()
        val backup = export()
        wipe()
        seedLocalData()
        val keyboard = KeyboardStartingMidRestore(filesDir, prefs)
        UserDictHot.host = keyboard

        restore(backup)

        assertTrue("precondition: the keyboard really did start while the restore was running", keyboard.started)
        assertEquals(
            "the journal of a restore still running belongs to that restore, not to whatever else starts up",
            listOf("备份"),
            UserModel().apply { load(File(filesDir, "userdb.txt")) }.userWordEntries().map { it.word },
        )
        assertEquals(listOf("备份常用语"), store().phrases())
        assertEquals("备份布局", prefs.getString("cn_layout", null))
        assertFalse("the journal must be spent", File(filesDir, "restore_journal").exists())
        assertFalse("the guard must not stay latched", LiveUserData.restoreInProgress)
    }

    @Test fun a_restore_that_gets_through_keeps_what_it_wrote() {
        seedBackupData()
        val backup = export()
        wipe()
        seedLocalData()

        restore(backup)

        assertEquals(
            listOf("备份"),
            UserModel().apply { load(File(filesDir, "userdb.txt")) }.userWordEntries().map { it.word },
        )
        assertEquals(listOf("备份常用语"), store().phrases())
        assertEquals("备份布局", prefs.getString("cn_layout", null))
        assertFalse("the journal must be spent", File(filesDir, "restore_journal").exists())
    }

    @Test fun a_restore_that_could_not_be_marked_finished_is_taken_back_off_the_device() {
        seedBackupData()
        val backup = export()
        wipe()
        seedLocalData()
        val before = snapshot()
        var blocked = false
        val blockingTheMark = object : SharedPreferences by prefs {
            override fun getAll(): MutableMap<String, *> {
                if (!blocked) {
                    blocked = File(filesDir, "restore_journal/done.0.tmp").mkdirs() &&
                        File(filesDir, "restore_journal/done.0.tmp/blocker").createNewFile()
                }
                return prefs.all
            }
        }

        try {
            BackupManager.restore(
                filesDir,
                blockingTheMark,
                password.toCharArray(),
                ByteArrayInputStream(backup),
                BackupManager.Mode.OVERWRITE,
            )
            fail("expected a restore that could not be marked finished to be reported")
        } catch (e: BackupException) {
            assertEquals(BackupError.IO_ERROR, e.error)
        }

        assertTrue("precondition: the completion mark really was blocked", blocked)
        assertEquals(
            "a restore that cannot say it finished must be taken back, not left for the next start to undo by halves",
            before,
            snapshot(),
        )
        assertFalse("the journal must be spent", File(filesDir, "restore_journal").exists())
        assertFalse("the guard must not stay latched", LiveUserData.restoreInProgress)
    }
}
