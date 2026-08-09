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
import com.aegis.ime.user.historyText
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
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestorePreflightTest {

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var prefs: SharedPreferences
    private val password = "preflight-pass-01"

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
        listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt").forEach {
            File(filesDir, it).deleteRecursively()
        }
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "emoji").deleteRecursively()
        File(filesDir, "backup_staging").deleteRecursively()
        prefs.edit().clear().commit()
    }

    private fun seedLocalData() {
        UserModel().apply { addManualWord("bendi", "本地", 1_000L) }.save(File(filesDir, "userdb.txt"))
        UserLearning { 1_000L }.apply { observeCommit("本", "地", "di", 1_000L) }
            .save(File(filesDir, "userlearn.txt"))
        ClipboardStore(filesDir).apply {
            load()
            addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("本地常用语"))
            record("本地剪贴")
            record("本地大块" + "b".repeat(ClipboardStore.BIG_THRESHOLD + 1))
            flushPendingWrites()
            stopSaving()
        }
        SymbolUsageStore(filesDir).apply { load(); record("★", "本地") }
        SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).apply { load(); record("😀", "smileys") }
        prefs.edit().putString("cn_layout", "本机布局").putInt("some_int", 7).commit()
    }

    private fun archiveOf(entries: Map<String, ByteArray>): ByteArray {
        val carrier = File(filesDir, "preflight_carrier").apply { deleteRecursively(); mkdirs() }
        val out = ByteArrayOutputStream()
        BackupCrypto.writeEncrypted(out, password.toCharArray(), BackupFormat.HEADER_VERSION) { cipherOut ->
            val gzip = GZIPOutputStream(cipherOut)
            val data = DataOutputStream(gzip)
            BackupArchive.writePrefs(data, PrefsCodec.encode(mapOf("cn_layout" to "备份布局")))
            for ((rel, bytes) in entries) {
                val staged = File(carrier, rel)
                staged.parentFile?.mkdirs()
                staged.writeBytes(bytes)
                BackupArchive.writeFile(data, rel, staged)
            }
            BackupArchive.writeEnd(data)
            data.flush()
            gzip.finish()
        }
        carrier.deleteRecursively()
        return out.toByteArray()
    }

    private fun healthyEntries(): LinkedHashMap<String, ByteArray> {
        val carrier = File(filesDir, "preflight_source").apply { deleteRecursively(); mkdirs() }
        UserModel().apply { addManualWord("beifen", "备份", 2_000L) }.save(File(carrier, "userdb.txt"))
        UserLearning { 2_000L }.apply { observeCommit("备", "份", "fen", 2_000L) }
            .save(File(carrier, "userlearn.txt"))
        ClipboardStore(carrier).apply {
            load()
            addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("备份常用语"))
            record("备份剪贴")
            flushPendingWrites()
            stopSaving()
        }
        SymbolUsageStore(carrier).apply { load(); record("§", "备份") }
        SymbolUsageStore(File(carrier, "emoji").apply { mkdirs() }).apply { load(); record("🎉", "备份") }
        val out = LinkedHashMap<String, ByteArray>()
        for (item in BackupItem.entries) {
            val f = File(carrier, item.relativePath)
            if (f.isFile) out[item.relativePath] = f.readBytes()
        }
        carrier.deleteRecursively()
        return out
    }

    private fun restore(bytes: ByteArray, mode: BackupManager.Mode = BackupManager.Mode.OVERWRITE) =
        BackupManager.restore(filesDir, prefs, password.toCharArray(), ByteArrayInputStream(bytes), mode)

    private fun localSnapshot(): List<String> = listOf(
        UserModel().apply { load(File(filesDir, "userdb.txt")) }.userWordEntries().map { it.word }.toString(),
        File(filesDir, "userlearn.txt").let { if (it.isFile) it.readText() else "<absent>" },
        ClipboardStore(filesDir).apply { load() }.let { it.phrases().toString() + it.historyText().toString() },
        File(filesDir, "clips").listFiles().orEmpty().sortedBy { it.name }
            .joinToString(",") { it.name + ":" + it.readText().take(24) },
        SymbolUsageStore(filesDir).apply { load() }.recent().toString(),
        SymbolUsageStore(File(filesDir, "emoji")).apply { load() }.recent().toString(),
        prefs.all.toSortedMap().toString(),
    )

    private fun expectDamagedReport(backup: ByteArray, expected: Set<BackupItem>) {
        val before = localSnapshot()
        try {
            restore(backup)
            fail("expected the restore to refuse a backup it could not read")
        } catch (e: BackupException) {
            assertEquals(BackupError.DAMAGED_CONTENT, e.error)
            assertEquals("the report must name the part that could not be read", expected, e.items)
        }
        assertEquals("not one byte of the local data may change", before, localSnapshot())
        assertFalse("staging must be cleaned up", File(filesDir, "backup_staging").exists())
        assertFalse("the guard must not stay latched", LiveUserData.restoreInProgress)
    }

    @Test fun a_backup_whose_dictionary_cannot_be_parsed_is_refused_whole() {
        seedLocalData()
        val entries = healthyEntries()
        entries["userdb.txt"] = "this is not an aegis user dictionary\nW\t词\t1\t1\n".toByteArray()

        expectDamagedReport(archiveOf(entries), setOf(BackupItem.DICTIONARY))
    }

    @Test fun a_backup_whose_learned_data_cannot_be_parsed_is_refused_whole() {
        seedLocalData()
        val entries = healthyEntries()
        entries["userlearn.txt"] = "not a learning file at all\n".toByteArray()

        expectDamagedReport(archiveOf(entries), setOf(BackupItem.LEARNING))
    }

    @Test fun a_backup_with_more_than_one_damaged_part_names_every_one_of_them() {
        seedLocalData()
        val entries = healthyEntries()
        entries["userdb.txt"] = "rubbish\nW\t词\t1\t1\n".toByteArray()
        entries["userlearn.txt"] = "rubbish too\n".toByteArray()

        expectDamagedReport(
            archiveOf(entries),
            setOf(BackupItem.DICTIONARY, BackupItem.LEARNING),
        )
    }

    @Test fun a_merge_of_a_damaged_backup_is_refused_just_as_whole() {
        seedLocalData()
        val entries = healthyEntries()
        entries["userdb.txt"] = "rubbish\nW\t词\t1\t1\n".toByteArray()
        val backup = archiveOf(entries)
        val before = localSnapshot()

        try {
            restore(backup, BackupManager.Mode.MERGE)
            fail("expected the merge to refuse a backup it could not read")
        } catch (e: BackupException) {
            assertEquals(BackupError.DAMAGED_CONTENT, e.error)
            assertEquals(setOf(BackupItem.DICTIONARY), e.items)
        }

        assertEquals(before, localSnapshot())
    }

    @Test fun a_backup_that_reads_back_whole_is_restored() {
        seedLocalData()
        val backup = archiveOf(healthyEntries())

        restore(backup)

        assertEquals(
            listOf("备份"),
            UserModel().apply { load(File(filesDir, "userdb.txt")) }.userWordEntries().map { it.word },
        )
        assertTrue(ClipboardStore(filesDir).apply { load() }.phrases().contains("备份常用语"))
        assertEquals("备份布局", prefs.getString("cn_layout", null))
    }
}
