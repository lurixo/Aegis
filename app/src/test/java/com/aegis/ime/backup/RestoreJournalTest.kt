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
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreJournalTest {

    private lateinit var filesDir: File
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        filesDir = context.filesDir
        prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
        UserDictHot.host = null
        LiveUserData.clipboardHost = null
        LiveUserData.restoreInProgress = false
        listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt").forEach {
            File(filesDir, it).deleteRecursively()
        }
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "emoji").deleteRecursively()
        File(filesDir, "restore_journal").deleteRecursively()
        prefs.edit().clear().commit()
    }

    private fun journalDir() = File(filesDir, "restore_journal")

    private fun seedLocalData() {
        UserModel().apply { addManualWord("bendi", "本地", 1_000L) }.save(File(filesDir, "userdb.txt"))
        UserLearning { 1_000L }.apply { observeCommit("本", "地", "di", 1_000L) }
            .save(File(filesDir, "userlearn.txt"))
        ClipboardStore(filesDir).apply {
            load()
            addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("本地常用语"))
            importHistory(listOf(bigClip()).asEntries(), merge = false)
            flushPendingWrites()
            stopSaving()
        }
        SymbolUsageStore(filesDir).apply { load(); record("★", "本地") }
        SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).apply { load(); record("😀", "smileys") }
        prefs.edit().putString("cn_layout", "本机布局").putInt("some_int", 7).commit()
    }

    private fun bigClip(): String = "本地大块" + "b".repeat(ClipboardStore.BIG_THRESHOLD + 1)

    private fun List<String>.asEntries() = map { com.aegis.ime.user.ClipEntry.of(it) }

    private fun snapshot(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (item in BackupItem.entries) {
            val f = File(filesDir, item.relativePath)
            out[item.relativePath] = if (f.isFile) f.readText() else "<absent>"
        }
        out["clips"] = File(filesDir, "clips").listFiles().orEmpty()
            .sortedBy { it.name }
            .joinToString(",") { it.name + ":" + it.readText().take(16) }
        out["<settings>"] = prefs.all.toSortedMap().toString()
        return out
    }

    private fun aRestoreWritesOverEverything() {
        File(filesDir, "userdb.txt").writeText("W\tbeifen\t备份\t1\t2000\n")
        File(filesDir, "userlearn.txt").writeText("")
        File(filesDir, "phrases.txt").writeText("C\t默认\nP\t备份常用语\n")
        File(filesDir, "clipboard.txt").writeText("备份剪贴\n")
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "symbol_usage.txt").writeText("§\t备份")
        File(filesDir, "emoji").mkdirs()
        File(filesDir, "emoji/symbol_usage.txt").writeText("🎉\t备份")
        prefs.edit().clear().putString("cn_layout", "备份布局").commit()
    }

    @Test fun a_restore_that_never_finished_is_taken_back_off_the_device() {
        seedLocalData()
        val before = snapshot()
        RestoreJournal.open(filesDir, prefs)
        aRestoreWritesOverEverything()
        assertFalse("precondition: the device now holds the backup", before == snapshot())

        assertTrue(RestoreJournal.finishAnyInterrupted(filesDir, prefs))

        assertEquals("every store must be back exactly as it was", before, snapshot())
        assertFalse("the journal must be gone once it is spent", journalDir().exists())
    }

    @Test fun a_restore_that_finished_is_left_alone() {
        seedLocalData()
        val journal = RestoreJournal.open(filesDir, prefs)
        aRestoreWritesOverEverything()
        val restored = snapshot()
        journal.markDone()

        assertFalse(
            "a restore that got to the end has nothing to take back",
            RestoreJournal.finishAnyInterrupted(filesDir, prefs),
        )

        assertEquals(restored, snapshot())
        assertFalse(journalDir().exists())
    }

    @Test fun a_journal_that_was_never_finished_being_written_undoes_nothing() {
        seedLocalData()
        val before = snapshot()
        journalDir().deleteRecursively()
        assertTrue(File(journalDir(), "before").mkdirs())
        File(journalDir(), "kept").writeText("userdb.txt")

        assertFalse(
            "no restore had started writing yet, so there is nothing to take back",
            RestoreJournal.finishAnyInterrupted(filesDir, prefs),
        )

        assertEquals(before, snapshot())
        assertFalse(journalDir().exists())
    }

    @Test fun a_journal_is_only_worth_acting_on_once_every_copy_has_been_taken() {
        seedLocalData()
        val before = snapshot()
        var lookedFinishedTooEarly = true
        val failing = object : SharedPreferences by prefs {
            override fun getAll(): MutableMap<String, *> {
                lookedFinishedTooEarly = File(journalDir(), "ready").exists()
                throw IllegalStateException("the settings could not be read")
            }
        }

        val opened = runCatching { RestoreJournal.open(filesDir, failing) }

        assertTrue("precondition: opening the journal failed", opened.isFailure)
        assertFalse(
            "a journal must not look worth acting on before every copy has been taken",
            lookedFinishedTooEarly,
        )
        assertFalse("a journal that could not be opened must leave nothing behind", journalDir().exists())
        assertFalse(RestoreJournal.finishAnyInterrupted(filesDir, prefs))
        assertEquals(before, snapshot())
    }

    @Test fun a_device_with_no_journal_has_nothing_to_finish() {
        seedLocalData()
        val before = snapshot()

        assertFalse(RestoreJournal.finishAnyInterrupted(filesDir, prefs))

        assertEquals(before, snapshot())
    }

    @Test fun a_store_that_was_not_there_before_is_taken_away_again() {
        prefs.edit().putString("cn_layout", "本机布局").commit()
        val before = snapshot()
        RestoreJournal.open(filesDir, prefs)
        aRestoreWritesOverEverything()

        assertTrue(RestoreJournal.finishAnyInterrupted(filesDir, prefs))

        assertEquals("a store the device never had must not be left behind", before, snapshot())
    }

    @Test fun the_sidecar_files_of_a_big_clip_come_back_with_the_index() {
        seedLocalData()
        val before = snapshot()
        assertTrue(
            "precondition: the local history has a sidecar",
            File(filesDir, "clips").listFiles().orEmpty().isNotEmpty(),
        )
        RestoreJournal.open(filesDir, prefs)
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "clipboard.txt").writeText("备份剪贴\n")

        assertTrue(RestoreJournal.finishAnyInterrupted(filesDir, prefs))

        assertEquals(before, snapshot())
    }

    @Test fun a_journal_that_lost_its_list_of_what_was_there_before_takes_nothing_off_the_device() {
        seedLocalData()
        RestoreJournal.open(filesDir, prefs)
        val before = snapshot()
        assertTrue("precondition: the journal looks worth acting on", File(journalDir(), "ready").isFile)
        assertTrue("precondition: the list of what was there before is gone", File(journalDir(), "kept").delete())

        assertFalse(
            "a journal that cannot say what was there before has nothing it can take back",
            RestoreJournal.finishAnyInterrupted(filesDir, prefs),
        )

        assertEquals("not one store may be emptied on the word of a list that is gone", before, snapshot())
        for (item in BackupItem.entries) {
            assertTrue("${item.relativePath} was deleted", File(filesDir, item.relativePath).isFile)
        }
        assertTrue("the big clip sidecars were deleted", File(filesDir, "clips").listFiles().orEmpty().isNotEmpty())
        assertFalse("and the unusable journal must not block the next restore", journalDir().exists())
    }

    @Test fun a_journal_that_lost_the_copies_it_took_takes_nothing_off_the_device() {
        seedLocalData()
        RestoreJournal.open(filesDir, prefs)
        val before = snapshot()
        assertTrue("precondition: the copies taken before the restore are gone", File(journalDir(), "before").deleteRecursively())

        assertFalse(
            "a journal with nothing to put back has nothing it can take back either",
            RestoreJournal.finishAnyInterrupted(filesDir, prefs),
        )

        assertEquals("not one store may be emptied on the word of copies that are gone", before, snapshot())
        assertFalse(journalDir().exists())
    }

    @Test fun a_rollback_that_cannot_be_carried_out_is_not_reported_as_one_that_was() {
        seedLocalData()
        val journal = RestoreJournal.open(filesDir, prefs)
        aRestoreWritesOverEverything()
        val halfRestored = snapshot()
        journalDir().deleteRecursively()

        val rolledBack = runCatching { journal.rollBack(prefs) }

        assertTrue("a rollback that could not happen must not come back as a success", rolledBack.isFailure)
        assertEquals("and it must not have taken anything with it", halfRestored, snapshot())
    }

    @Test fun a_path_the_device_blocked_with_a_directory_is_left_where_it_is() {
        seedLocalData()
        File(filesDir, "phrases.txt").deleteRecursively()
        assertTrue(File(filesDir, "phrases.txt").mkdirs())
        File(filesDir, "phrases.txt/blocker").writeText("x")
        RestoreJournal.open(filesDir, prefs)

        assertTrue(RestoreJournal.finishAnyInterrupted(filesDir, prefs))

        assertTrue("the blocked path must be left exactly as it was", File(filesDir, "phrases.txt").isDirectory)
        assertEquals("x", File(filesDir, "phrases.txt/blocker").readText())
    }
}
