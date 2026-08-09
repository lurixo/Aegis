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
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupExportOmitsUnreadableStoresTest {

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var prefs: SharedPreferences
    private val password = "backup-pass-01"
    private val closedOff = ArrayList<File>()
    private val stores = ArrayList<ClipboardStore>()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        filesDir = context.filesDir
        prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
        UserDictHot.host = null
        LiveUserData.clipboardHost = null
        LiveUserData.onBeforeExport = null
        LiveUserData.restoreInProgress = false
        listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt").forEach {
            File(filesDir, it).deleteRecursively()
        }
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "emoji").deleteRecursively()
        prefs.edit().clear().commit()
        seedEveryStore()
    }

    @After fun tearDown() {
        closedOff.forEach { it.setReadable(true, true) }
        stores.forEach { it.stopSaving() }
        UserDictHot.host = null
        LiveUserData.clipboardHost = null
    }

    private fun clip() = ClipboardStore(filesDir).apply { load() }.also { stores += it }

    private fun seedEveryStore() {
        UserModel().apply { addManualWord("nihao", "你好", 1_000L) }.save(File(filesDir, "userdb.txt"))
        UserLearning { 1_000L }.apply { observeCommit("备", "份", "fen", 1_000L) }.save(File(filesDir, "userlearn.txt"))
        clip().apply {
            addCategory("工作")
            addPhrasesTo("工作", listOf("已收到"))
            record("剪贴内容一")
            flushPendingWrites()
        }
        SymbolUsageStore(filesDir).apply { load(); record("！", "math") }
        SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).apply { load(); record("😀", "smileys") }
        prefs.edit().putString("cn_layout", "alpha").commit()
    }

    private fun closeOff(relativePath: String) {
        val f = File(filesDir, relativePath)
        assertTrue("precondition: $relativePath could be closed off", f.setReadable(false, false))
        closedOff += f
    }

    private fun export(): Pair<BackupManager.ExportReport, ByteArray> {
        val bos = ByteArrayOutputStream()
        val report = BackupManager.export(filesDir, prefs, password.toCharArray(), bos)
        return report to bos.toByteArray()
    }

    private fun namesIn(backup: ByteArray): Set<String> {
        val names = LinkedHashSet<String>()
        BackupCrypto.readDecrypted(ByteArrayInputStream(backup), password.toCharArray()) { plain ->
            java.util.zip.GZIPInputStream(plain).use { gz ->
                BackupArchive.read(java.io.DataInputStream(gz), object : BackupArchive.Visitor {
                    override fun onPrefs(blob: ByteArray) {}
                    override fun openFile(relativePath: String): ByteArrayOutputStream {
                        names.add(relativePath)
                        return ByteArrayOutputStream()
                    }
                })
            }
        }
        return names
    }

    private fun bigClip(): String = "大剪贴".repeat(ClipboardStore.BIG_THRESHOLD)

    @Test fun a_healthy_export_leaves_nothing_out() {
        val (report, backup) = export()
        assertEquals(emptySet<BackupItem>(), report.omitted)
        assertEquals(
            setOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt", "emoji/symbol_usage.txt"),
            namesIn(backup),
        )
    }

    @Test fun an_unreadable_dictionary_is_left_out_of_the_archive() {
        File(filesDir, "userdb.txt").writeText("this is not an aegis user dictionary\nW\t词\t1\t1\n")
        val (report, backup) = export()
        assertEquals(setOf(BackupItem.DICTIONARY), report.omitted)
        assertFalse("userdb.txt" in namesIn(backup))
        assertTrue("everything else must still go in", "phrases.txt" in namesIn(backup))
    }

    @Test fun an_unreadable_learning_store_is_left_out_of_the_archive() {
        File(filesDir, "userlearn.txt").writeText("not a learning file at all\n")
        val (report, backup) = export()
        assertEquals(setOf(BackupItem.LEARNING), report.omitted)
        assertFalse("userlearn.txt" in namesIn(backup))
        assertTrue("userdb.txt" in namesIn(backup))
    }

    @Test fun an_unreadable_phrase_file_is_left_out_of_the_archive() {
        closeOff("phrases.txt")
        val (report, backup) = export()
        assertEquals(setOf(BackupItem.PHRASES), report.omitted)
        assertFalse("phrases.txt" in namesIn(backup))
        assertTrue("clipboard.txt" in namesIn(backup))
    }

    @Test fun an_unreadable_clipboard_index_is_left_out_of_the_archive() {
        closeOff("clipboard.txt")
        val (report, backup) = export()
        assertEquals(setOf(BackupItem.CLIPBOARD), report.omitted)
        assertFalse("clipboard.txt" in namesIn(backup))
        assertTrue("phrases.txt" in namesIn(backup))
    }

    @Test fun an_unreadable_symbol_history_is_left_out_of_the_archive() {
        closeOff("symbol_usage.txt")
        val (report, backup) = export()
        assertEquals(setOf(BackupItem.SYMBOL_USAGE), report.omitted)
        assertFalse("symbol_usage.txt" in namesIn(backup))
        assertTrue("emoji/symbol_usage.txt" in namesIn(backup))
    }

    @Test fun an_unreadable_emoji_history_is_left_out_of_the_archive() {
        closeOff("emoji/symbol_usage.txt")
        val (report, backup) = export()
        assertEquals(setOf(BackupItem.EMOJI_USAGE), report.omitted)
        assertFalse("emoji/symbol_usage.txt" in namesIn(backup))
        assertTrue("symbol_usage.txt" in namesIn(backup))
    }

    @Test fun an_unreadable_clipboard_index_takes_its_sidecars_out_with_it() {
        val live = clip().apply { record(bigClip()); flushPendingWrites() }
        assertTrue(
            "precondition: the big clip left a side file behind",
            File(filesDir, "clips").listFiles().orEmpty().any { it.isFile },
        )
        closeOff("clipboard.txt")
        clip().also { LiveUserData.clipboardHost = it }
        assertFalse("precondition: the live store could not read the index", LiveUserData.clipboardHost!!.historyReadable)
        File(filesDir, "clipboard.txt").setReadable(true, true)
        live.stopSaving()

        val (report, backup) = export()

        assertEquals(setOf(BackupItem.CLIPBOARD), report.omitted)
        assertFalse("clipboard.txt" in namesIn(backup))
        assertTrue(
            "a side file with no index to name it is dead weight in the archive",
            namesIn(backup).none { it.startsWith("clips/") },
        )
    }

    @Test fun the_export_report_names_every_store_it_left_out() {
        File(filesDir, "userdb.txt").writeText("this is not an aegis user dictionary\nW\t词\t1\t1\n")
        File(filesDir, "userlearn.txt").writeText("not a learning file at all\n")
        closeOff("phrases.txt")
        closeOff("symbol_usage.txt")

        val (report, backup) = export()

        assertEquals(
            setOf(BackupItem.DICTIONARY, BackupItem.LEARNING, BackupItem.PHRASES, BackupItem.SYMBOL_USAGE),
            report.omitted,
        )
        assertEquals(setOf("clipboard.txt", "emoji/symbol_usage.txt"), namesIn(backup))
    }

    @Test fun a_store_that_was_left_out_is_left_exactly_as_it_was() {
        File(filesDir, "userdb.txt").writeText("this is not an aegis user dictionary\nW\t词\t1\t1\n")
        val db = File(filesDir, "userdb.txt")
        val before = db.readBytes().toList()
        val stamp = db.lastModified()

        export()

        assertEquals(before, db.readBytes().toList())
        assertEquals(stamp, db.lastModified())
    }

    @Test fun the_running_dictionary_answers_for_the_file_it_holds() {
        val db = File(filesDir, "userdb.txt").apply { writeText("this is not an aegis user dictionary\nW\t词\t1\t1\n") }
        val model = UserModel().apply { runCatching { load(db) }; record(null, "打过字", 1L) }
        val host = LiveUserDictHost(model, db, UserLearning(), File(filesDir, "userlearn.txt"))
        UserDictHot.host = host
        try {
            val (report, backup) = export()
            assertEquals(setOf(BackupItem.DICTIONARY), report.omitted)
            assertFalse(
                "the store that gave up on the file is the one that knows it must not be copied",
                "userdb.txt" in namesIn(backup),
            )
        } finally {
            UserDictHot.host = null
            host.stopSaving()
        }
    }
}
