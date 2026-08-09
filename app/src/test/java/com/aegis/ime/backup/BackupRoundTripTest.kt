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

import com.aegis.ime.user.historyText
import com.aegis.ime.user.asClipEntries
import android.content.Context
import android.content.SharedPreferences
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.CustomSymbolStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.LiveUserDictHost
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRoundTripTest {

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var prefs: SharedPreferences
    private val password = "backup-pass-01"

    private companion object {
        val DOWNLOAD_KEYS = listOf(
            "engine_pack_touch", "gram_validator", "gram_sha256", "gram_size_bytes", "dict_validator", "dict_sha256",
            "dict_asset_name", "dict_asset_url", "dict_release_tag", "dict_release_published_at",
        )
    }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        filesDir = context.filesDir
        prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
        UserDictHot.host = null
        LiveUserData.onRestored = null
        LiveUserData.onBeforeExport = null
        LiveUserData.onBeforeRestore = null
        LiveUserData.restoreInProgress = false
        listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt").forEach {
            File(filesDir, it).deleteRecursively()
        }
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "emoji").deleteRecursively()
        File(filesDir, "backup_staging").deleteRecursively()
        prefs.edit().clear().commit()
    }

    private val hosts = ArrayList<LiveUserDictHost>()

    @After fun stopHosts() {
        hosts.forEach { runCatching { it.stopSaving() } }
    }

    private fun liveHost(
        model: UserModel,
        userDb: File,
        userLearning: UserLearning? = null,
        userLearnFile: File? = null,
        onSaved: (Long?, Long?) -> Unit = { _, _ -> },
    ): LiveUserDictHost =
        LiveUserDictHost(model, userDb, userLearning, userLearnFile, onSaved).also { hosts += it }

    private fun userdbFile() = File(filesDir, "userdb.txt")
    private fun freshClip() = ClipboardStore(filesDir).apply { load() }
    private fun clipSideFileNames(): Set<String> =
        File(filesDir, "clips").listFiles().orEmpty().filter { it.isFile }.mapTo(LinkedHashSet()) { it.name }

    private fun clipboardIo(store: ClipboardStore): ExecutorService {
        val field = ClipboardStore::class.java.getDeclaredField("io")
        field.isAccessible = true
        return field.get(store) as ExecutorService
    }

    private fun seedTypicalData() {
        UserModel().apply {
            addManualWord("nihao", "你好", 1000)
            addManualWord("shijie", "世界", 1001)
            record("你好", "世界", 1002)
        }.save(userdbFile())

        freshClip().apply {
            addCategory("工作")
            addPhrasesTo("工作", listOf("已收到", "好的"))
            setPhraseNote("工作", "已收到", "回执")
            record("剪贴内容一")
            record("剪贴内容二")
            flushPendingWrites()
        }

        SymbolUsageStore(filesDir).apply { load(); record("！", "math"); record("？", "math") }
        SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).apply { load(); record("😀", "smileys") }

        val e = prefs.edit()
            .putString("cn_layout", "alpha")
            .putBoolean("fuzzy", false)
            .putString("custom_symbols", "§\n¶")
            .putInt("some_int", 7)
        for (k in DOWNLOAD_KEYS) e.putString(k, "device-only-$k")
        e.commit()
    }

    private fun export(): ByteArray {
        val bos = ByteArrayOutputStream()
        BackupManager.export(filesDir, prefs, password.toCharArray(), bos)
        return bos.toByteArray()
    }

    private fun restore(bytes: ByteArray, mode: BackupManager.Mode, pass: String = password) =
        BackupManager.restore(filesDir, prefs, pass.toCharArray(), ByteArrayInputStream(bytes), mode)

    private fun wipeUserData() {
        listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt").forEach {
            File(filesDir, it).deleteRecursively()
        }
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "emoji").deleteRecursively()
    }

    private fun archiveEntries(backup: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArrayOutputStream>()
        BackupCrypto.readDecrypted(ByteArrayInputStream(backup), password.toCharArray()) { plain ->
            GZIPInputStream(plain).use { gzip ->
                BackupArchive.read(
                    DataInputStream(gzip),
                    object : BackupArchive.Visitor {
                        override fun onPrefs(blob: ByteArray) = Unit

                        override fun openFile(relativePath: String): OutputStream =
                            entries.getOrPut(relativePath) { ByteArrayOutputStream() }
                    },
                )
            }
        }
        return entries.mapValues { it.value.toByteArray() }
    }

    private fun aWordListThatOwesADeletion(): File {
        val db = userdbFile()
        UserModel().apply {
            addManualWord("ci", "词", 1_000L)
            assertTrue(addTombstone("你呢嗯", ""))
        }.save(db)
        assertTrue("precondition: the device file says it owes a deletion", db.readText().startsWith("aegis-userdb 4\n"))
        return db
    }

    @Test fun an_archive_carries_a_word_list_without_the_deletions_the_device_owes() {
        aWordListThatOwesADeletion()

        val carried = archiveEntries(export())["userdb.txt"]

        assertNotNull("the archive must carry the word list", carried)
        val text = carried!!.toString(Charsets.UTF_8)
        assertFalse("an archive must not carry a deletion the device owes", text.contains("\nD\t"))
        assertFalse("nor head the word list as one that carries them", text.startsWith("aegis-userdb 4"))
        assertTrue("and it must still carry the words themselves", text.contains("R\tci\t词\n"))
    }

    @Test fun a_restore_leaves_the_deletions_the_device_owes_where_they_are() {
        UserModel().apply { addManualWord("bei", "备", 2_000L) }.save(userdbFile())
        val backup = export()
        val db = aWordListThatOwesADeletion()

        restore(backup, BackupManager.Mode.OVERWRITE)

        val after = UserModel().apply { load(db, sweepStale = false) }
        assertEquals(listOf("你呢嗯" to ""), after.tombstones())
        assertEquals(listOf("备"), after.userWordEntries().map { it.word })
    }

    private fun blockFilePathWithDirectory(relativePath: String) {
        val dir = File(filesDir, relativePath)
        dir.deleteRecursively()
        dir.parentFile?.mkdirs()
        assertTrue("precondition: blocker directory was created", dir.mkdirs())
        File(dir, "blocker").writeText("x")
    }

    private fun expectRestoreIoFailure(backup: ByteArray, mode: BackupManager.Mode) {
        try {
            restore(backup, mode)
            fail("expected restore to report a persistence failure")
        } catch (e: BackupException) {
            assertEquals(BackupError.IO_ERROR, e.error)
        }
        assertFalse("staging must be cleaned up", File(filesDir, "backup_staging").exists())
        assertFalse("guard must not stay latched after a failed restore", LiveUserData.restoreInProgress)
    }

    private class CommitFailingSharedPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        val editedKeys = LinkedHashSet<String>()
        var commitCalled = false
            private set

        override fun edit(): SharedPreferences.Editor =
            CommitFailingEditor(delegate.edit(), editedKeys) { commitCalled = true }

        private class CommitFailingEditor(
            private val delegate: SharedPreferences.Editor,
            private val editedKeys: MutableSet<String>,
            private val onCommit: () -> Unit,
        ) : SharedPreferences.Editor by delegate {
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                editedKeys.add(key)
                delegate.putBoolean(key, value)
                return this
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                editedKeys.add(key)
                delegate.putFloat(key, value)
                return this
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                editedKeys.add(key)
                delegate.putInt(key, value)
                return this
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                editedKeys.add(key)
                delegate.putLong(key, value)
                return this
            }

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                editedKeys.add(key)
                delegate.putString(key, value)
                return this
            }

            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
                editedKeys.add(key)
                delegate.putStringSet(key, values)
                return this
            }

            override fun commit(): Boolean {
                onCommit()
                return false
            }
        }
    }

    private fun decodeUserdbFromBackup(backup: ByteArray): UserModel {
        val captured = ByteArrayOutputStream()
        BackupCrypto.readDecrypted(ByteArrayInputStream(backup), password.toCharArray()) { plain ->
            java.util.zip.GZIPInputStream(plain).use { gz ->
                BackupArchive.read(java.io.DataInputStream(gz), object : BackupArchive.Visitor {
                    override fun onPrefs(blob: ByteArray) {}
                    override fun openFile(relativePath: String) =
                        if (relativePath == "userdb.txt") captured else ByteArrayOutputStream()
                })
            }
        }
        val tmp = File(filesDir, "decoded_userdb_probe.txt")
        tmp.writeBytes(captured.toByteArray())
        return UserModel().apply { load(tmp) }.also { tmp.delete() }
    }

    private fun decodeFileNamesFromBackup(backup: ByteArray): Set<String> {
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

    @Test fun user_learning_file_obeys_overwrite_and_minimal_merge_semantics() {
        val userLearn = File(filesDir, "userlearn.txt")
        UserLearning { 1_000L }.apply {
            observeCommit("备份", "内容", "", 1_000L)
            save(userLearn)
        }
        val backedUp = userLearn.readBytes()
        val backup = export()
        assertTrue("user learning must be present in the archive", "userlearn.txt" in decodeFileNamesFromBackup(backup))

        userLearn.delete()
        restore(backup, BackupManager.Mode.OVERWRITE)
        assertArrayEquals(backedUp, userLearn.readBytes())

        UserLearning { 2_000L }.apply {
            observeCommit("本机", "保留", "", 2_000L)
            save(userLearn)
        }
        val local = userLearn.readBytes()
        restore(backup, BackupManager.Mode.MERGE)
        assertArrayEquals(local, userLearn.readBytes())
    }

    @Test fun export_flushes_live_user_learning_before_archiving() {
        val userLearn = File(filesDir, "userlearn.txt")
        val learning = UserLearning { 1_000L }.apply {
            observeCommit("实时", "学习", "", 1_000L)
        }
        UserDictHot.host = liveHost(UserModel(), userdbFile(), learning, userLearn)
        try {
            val backup = export()
            assertFalse("export must flush the secondary store", learning.dirty)
            assertTrue("the flushed file must enter the archive", "userlearn.txt" in decodeFileNamesFromBackup(backup))
            UserDictHot.host = null
            userLearn.delete()
            restore(backup, BackupManager.Mode.OVERWRITE)
            val restored = UserLearning { 1_000L }.apply { load(userLearn) }
            assertEquals(listOf("学习"), restored.follows("实时").map { it.first })
        } finally {
            UserDictHot.host = null
        }
    }

    @Test fun merge_restore_flushes_live_user_learning_before_preserving_the_local_file() {
        val userLearn = File(filesDir, "userlearn.txt")
        UserLearning { 1_000L }.apply {
            observeCommit("备份", "内容", "", 1_000L)
            save(userLearn)
        }
        val backup = export()
        userLearn.delete()

        val learning = UserLearning { 2_000L }.apply {
            observeCommit("本机", "保留", "", 2_000L)
        }
        UserDictHot.host = liveHost(UserModel(), userdbFile(), learning, userLearn)
        try {
            restore(backup, BackupManager.Mode.MERGE)
            val reloaded = UserLearning { 2_000L }.apply { load(userLearn) }
            assertEquals(listOf("保留"), reloaded.follows("本机").map { it.first })
            assertTrue(reloaded.follows("备份").isEmpty())
        } finally {
            UserDictHot.host = null
        }
    }


    @Test fun overwrite_restore_recovers_every_store_item_by_item() {
        seedTypicalData()
        val backup = export()

        wipeUserData()
        prefs.edit().clear().commit()

        restore(backup, BackupManager.Mode.OVERWRITE)

        val model = UserModel().apply { load(userdbFile()) }
        val entries = model.userWordEntries().associate { (it.reading to it.word) to it.count }
        assertEquals(setOf("nihao" to "你好", "shijie" to "世界"), entries.keys)
        assertEquals(1, entries["nihao" to "你好"])
        assertEquals(2, entries["shijie" to "世界"])
        assertTrue("word boost survived", model.wordBoost("你好") > 0.0)
        assertEquals(listOf("世界"), model.successors("你好", 8))

        val clip = freshClip()
        assertTrue(clip.phrasesIn("工作").containsAll(listOf("已收到", "好的")))
        assertEquals("回执", clip.noteFor("工作", "已收到"))

        assertTrue(clip.historyText().containsAll(listOf("剪贴内容一", "剪贴内容二")))

        assertTrue(SymbolUsageStore(filesDir).apply { load() }.recent().containsAll(listOf("！", "？")))
        assertTrue(SymbolUsageStore(File(filesDir, "emoji")).apply { load() }.recent().contains("😀"))

        assertEquals("alpha", prefs.getString("cn_layout", null))
        assertFalse(prefs.getBoolean("fuzzy", true))
        assertEquals("§\n¶", prefs.getString("custom_symbols", null))
        assertEquals(7, prefs.getInt("some_int", -1))
        for (k in DOWNLOAD_KEYS) assertFalse("download-state key $k must not be restored", prefs.contains(k))
    }

    @Test fun the_archive_never_carries_download_state_keys() {
        seedTypicalData()
        val backup = export()

        var prefsBlob: ByteArray? = null
        BackupCrypto.readDecrypted(ByteArrayInputStream(backup), password.toCharArray()) { plain ->
            java.util.zip.GZIPInputStream(plain).use { gz ->
                BackupArchive.read(java.io.DataInputStream(gz), object : BackupArchive.Visitor {
                    override fun onPrefs(blob: ByteArray) { prefsBlob = blob }
                    override fun openFile(relativePath: String) = java.io.ByteArrayOutputStream()
                })
            }
        }
        val decoded = PrefsCodec.decode(prefsBlob!!)
        assertTrue("real settings are present", decoded.containsKey("cn_layout"))
        for (k in DOWNLOAD_KEYS) assertFalse("archive must omit $k", decoded.containsKey(k))
    }

    @Test fun overwrite_preserves_download_state_keys_already_on_the_device() {
        seedTypicalData()
        val backup = export()

        wipeUserData()
        prefs.edit().clear().putString("dict_sha256", "STILL_HERE").commit()

        restore(backup, BackupManager.Mode.OVERWRITE)

        assertEquals("alpha", prefs.getString("cn_layout", null))
        assertEquals("STILL_HERE", prefs.getString("dict_sha256", null))
    }

    @Test fun merge_unions_data_and_keeps_local_settings() {
        seedTypicalData()
        val backup = export()

        wipeUserData()
        UserModel().apply { addManualWord("beijing", "北京", 2000) }.save(userdbFile())
        freshClip().apply { record("本机剪贴"); flushPendingWrites() }
        prefs.edit().putString("cn_layout", "nine").commit()

        restore(backup, BackupManager.Mode.MERGE)

        val model = UserModel().apply { load(userdbFile()) }
        val entries = model.userWordEntries().map { it.reading to it.word }.toSet()
        assertTrue("kept local word", entries.contains("beijing" to "北京"))
        assertTrue("added backup words", entries.containsAll(listOf("nihao" to "你好", "shijie" to "世界")))

        assertTrue(freshClip().historyText().containsAll(listOf("本机剪贴", "剪贴内容一", "剪贴内容二")))

        assertEquals("nine", prefs.getString("cn_layout", null))
        assertEquals("§\n¶", prefs.getString("custom_symbols", null))
    }

    @Test fun wrong_password_is_rejected_and_leaves_data_untouched() {
        seedTypicalData()
        val backup = export()
        val userdbBefore = userdbFile().readText()

        try {
            restore(backup, BackupManager.Mode.OVERWRITE, pass = "not-the-password")
            fail("expected a wrong-password failure")
        } catch (e: BackupException) {
            assertEquals(BackupError.WRONG_PASSWORD_OR_CORRUPT, e.error)
        }

        assertEquals("existing data must be unchanged", userdbBefore, userdbFile().readText())
        assertFalse("staging must be cleaned up", File(filesDir, "backup_staging").exists())
    }

    @Test fun a_corrupt_file_is_rejected_and_leaves_data_untouched() {
        seedTypicalData()
        val backup = export()
        backup[backup.size / 2] = (backup[backup.size / 2].toInt() xor 0x7F).toByte()
        val userdbBefore = userdbFile().readText()

        try {
            restore(backup, BackupManager.Mode.OVERWRITE)
            fail("expected a corrupt-file failure")
        } catch (e: BackupException) {
            assertEquals(BackupError.WRONG_PASSWORD_OR_CORRUPT, e.error)
        }
        assertEquals(userdbBefore, userdbFile().readText())
        assertFalse(File(filesDir, "backup_staging").exists())
    }

    @Test fun overwrite_does_not_wipe_a_category_absent_from_the_backup() {
        UserModel().apply { addManualWord("nihao", "你好", 1000) }.save(userdbFile())
        val backup = export()

        freshClip().apply { record("请勿删除"); flushPendingWrites() }

        restore(backup, BackupManager.Mode.OVERWRITE)

        assertTrue("absent category must be left intact", freshClip().historyText().contains("请勿删除"))
        assertTrue(UserModel().apply { load(userdbFile()) }.userWordEntries().any { it.word == "你好" })
    }

    @Test fun restore_refreshes_live_stores_and_clears_the_capture_guard() {
        seedTypicalData()
        val backup = export()
        wipeUserData()
        prefs.edit().clear().commit()

        var reloaded = false
        LiveUserData.onRestored = { reloaded = true; LiveUserData.restoreInProgress = false }

        restore(backup, BackupManager.Mode.OVERWRITE)

        assertTrue("the running service's stores are refreshed after a restore", reloaded)
        assertFalse("the capture guard is released once the reload lands", LiveUserData.restoreInProgress)
    }

    @Test fun restore_reports_failure_when_live_user_dictionary_import_fails() {
        UserModel().apply { addManualWord("nihao", "你好", 1000) }.save(userdbFile())
        val backup = export()
        wipeUserData()

        UserDictHot.host = object : UserDictHot.Host {
            override fun addWord(reading: String, word: String, now: Long) = true
            override fun removeWord(reading: String, word: String) = true
            override fun importUserDict(importFile: File, merge: Boolean, now: Long) = false
            override fun entries(): List<UserModel.Entry> = emptyList()
            override fun learnedEntries(): List<UserLearning.Formed> = emptyList()
            override fun hasLearnedData(): Boolean = false
            override fun removeLearned(word: String, reading: String) = true
            override fun clearLearned() = true
            override fun flush() = true
        }
        try {
            expectRestoreIoFailure(backup, BackupManager.Mode.OVERWRITE)
        } finally {
            UserDictHot.host = null
        }
    }

    @Test fun restore_reports_failure_when_the_live_stores_cannot_be_flushed_first() {
        seedTypicalData()
        val backup = export()
        wipeUserData()

        UserDictHot.host = FlushRefusingHost()
        try {
            expectRestoreIoFailure(backup, BackupManager.Mode.OVERWRITE)
        } finally {
            UserDictHot.host = null
        }
    }

    @Test fun export_reports_failure_when_the_live_stores_cannot_be_flushed_first() {
        seedTypicalData()

        UserDictHot.host = FlushRefusingHost()
        try {
            export()
            fail("expected export to report a persistence failure")
        } catch (e: BackupException) {
            assertEquals(BackupError.IO_ERROR, e.error)
        } finally {
            UserDictHot.host = null
        }
    }

    @Test fun a_restore_holds_the_guard_up_the_whole_time_it_is_replacing_the_stores() {
        seedTypicalData()
        val backup = export()
        wipeUserData()

        var heldBeforeAnythingMoved = false
        var heldWhileReplacingTheDictionary = false
        var heldWhileReloading = false
        val real = liveHost(UserModel(), userdbFile(), UserLearning(), File(filesDir, "userlearn.txt"))
        LiveUserData.onBeforeRestore = { heldBeforeAnythingMoved = LiveUserData.restoreInProgress }
        LiveUserData.onRestored = { heldWhileReloading = LiveUserData.restoreInProgress }
        UserDictHot.host = object : UserDictHot.Host by real {
            override fun importUserDict(importFile: File, merge: Boolean, now: Long): Boolean {
                heldWhileReplacingTheDictionary = LiveUserData.restoreInProgress
                return real.importUserDict(importFile, merge, now)
            }
        }
        try {
            restore(backup, BackupManager.Mode.OVERWRITE)
        } finally {
            LiveUserData.onBeforeRestore = null
            LiveUserData.onRestored = null
            UserDictHot.host = null
            real.stopSaving()
        }

        assertTrue("the guard must be up before the first byte moves", heldBeforeAnythingMoved)
        assertTrue(
            "the writes that stand down for a restore read this flag while the stores are being replaced, not after",
            heldWhileReplacingTheDictionary,
        )
        assertTrue("and it must still be up when the reload is handed the result", heldWhileReloading)
    }

    @Test fun a_reload_hook_that_throws_does_not_leave_the_guard_standing_forever() {
        seedTypicalData()
        val backup = export()
        wipeUserData()

        LiveUserData.onRestored = { throw IllegalStateException("the reload hook blew up") }
        try {
            runCatching { restore(backup, BackupManager.Mode.OVERWRITE) }
        } finally {
            LiveUserData.onRestored = null
        }

        assertFalse(
            "a guard nobody lowers again stops the keyboard from ever seeing the stores change",
            LiveUserData.restoreInProgress,
        )
    }

    @Test fun an_export_is_not_blocked_by_a_dictionary_that_could_not_be_read() {
        seedTypicalData()
        val db = userdbFile().apply { writeText("this is not an aegis user dictionary\nW\t词\t1\t1\n") }
        val model = UserModel().apply {
            runCatching { load(db) }
            record(null, "打过字", 1L)
        }
        UserDictHot.host = liveHost(model, db, UserLearning(), File(filesDir, "userlearn.txt"))
        try {
            val backup = export()
            assertTrue(
                "one store going bad must not take away the user's ability to back up everything else",
                backup.isNotEmpty(),
            )
            assertFalse(
                "a dictionary nobody could read has nothing worth carrying into the archive",
                "userdb.txt" in decodeFileNamesFromBackup(backup),
            )
            assertTrue("phrases.txt" in decodeFileNamesFromBackup(backup))
        } finally {
            UserDictHot.host = null
        }
    }

    @Test fun an_export_is_not_blocked_by_a_learning_store_that_could_not_be_read() {
        seedTypicalData()
        val learn = File(filesDir, "userlearn.txt").apply { writeText("not a learning file at all\n") }
        val learning = UserLearning().apply {
            load(learn)
            observeCommit(null, "你", "ni", 1L)
            observeCommit("你", "呢", "ne", 1L)
        }
        UserDictHot.host = liveHost(UserModel(), userdbFile(), learning, learn)
        try {
            val backup = export()
            assertTrue(
                "one store going bad must not take away the user's ability to back up everything else",
                backup.isNotEmpty(),
            )
            assertFalse(
                "a learning store that could not be read is left out instead of copied in as it stands",
                "userlearn.txt" in decodeFileNamesFromBackup(backup),
            )
            assertTrue("userdb.txt" in decodeFileNamesFromBackup(backup))
        } finally {
            UserDictHot.host = null
        }
    }

    @Test fun a_restore_is_not_blocked_by_a_dictionary_that_could_not_be_read() {
        seedTypicalData()
        val backup = export()
        wipeUserData()

        val db = userdbFile().apply { writeText("this is not an aegis user dictionary\nW\t词\t1\t1\n") }
        val model = UserModel().apply {
            runCatching { load(db) }
            record(null, "打过字", 1L)
        }
        UserDictHot.host = liveHost(model, db, UserLearning(), File(filesDir, "userlearn.txt"))
        try {
            restore(backup, BackupManager.Mode.OVERWRITE)
            assertTrue(
                "the worse the dictionary is, the more the user needs the restore to go through",
                UserModel().apply { load(db) }.readingSnapshot().isNotEmpty(),
            )
        } finally {
            UserDictHot.host = null
        }
    }

    @Test fun a_restore_is_not_blocked_by_a_learning_store_that_could_not_be_read() {
        seedTypicalData()
        val recently = System.currentTimeMillis()
        UserLearning().apply {
            repeat(8) {
                var prev: String? = null
                for ((word, reading) in listOf("你" to "ni", "呢" to "ne", "嗯" to "n")) {
                    observeCommit(prev, word, reading, recently)
                    prev = word
                }
                observeBreak()
            }
        }.save(File(filesDir, "userlearn.txt"))
        val backup = export()
        wipeUserData()

        val learn = File(filesDir, "userlearn.txt").apply { writeText("not a learning file at all\n") }
        val learning = UserLearning().apply {
            load(learn)
            observeCommit(null, "你", "ni", 1L)
            observeCommit("你", "呢", "ne", 1L)
        }
        UserDictHot.host = liveHost(UserModel(), userdbFile(), learning, learn)
        try {
            restore(backup, BackupManager.Mode.OVERWRITE)
            assertTrue(
                "the restored learning file must replace the one that could not be read",
                learn.readText().startsWith("aegis-userlearn"),
            )
        } finally {
            UserDictHot.host = null
        }
    }

    private class FlushRefusingHost : UserDictHot.Host {
        override fun addWord(reading: String, word: String, now: Long) = true
        override fun removeWord(reading: String, word: String) = true
        override fun importUserDict(importFile: File, merge: Boolean, now: Long) = true
        override fun entries(): List<UserModel.Entry> = emptyList()
        override fun learnedEntries(): List<UserLearning.Formed> = emptyList()
        override fun hasLearnedData() = false
        override fun removeLearned(word: String, reading: String) = true
        override fun clearLearned() = true
        override fun flush() = false
    }

    @Test fun restore_reports_failure_when_preference_commit_fails_after_decoding_prefs() {
        seedTypicalData()
        val backup = export()
        wipeUserData()
        prefs.edit().clear().commit()
        val failingPrefs = CommitFailingSharedPreferences(prefs)

        try {
            BackupManager.restore(
                filesDir,
                failingPrefs,
                password.toCharArray(),
                ByteArrayInputStream(backup),
                BackupManager.Mode.OVERWRITE,
            )
            fail("expected restore to report a preference persistence failure")
        } catch (e: BackupException) {
            assertEquals(BackupError.IO_ERROR, e.error)
        }

        assertTrue(
            "restored preferences must be decoded and applied before commit failure is reported",
            "cn_layout" in failingPrefs.editedKeys,
        )
        assertTrue("preference editor commit must be reached", failingPrefs.commitCalled)
        assertFalse("staging must be cleaned up", File(filesDir, "backup_staging").exists())
        assertFalse("guard must not stay latched after a failed restore", LiveUserData.restoreInProgress)
    }

    @Test fun restore_reports_failure_when_phrase_persistence_fails() {
        freshClip().apply { addPhrasesTo("default", listOf("短语写入失败探针")) }
        val backup = export()
        wipeUserData()
        blockFilePathWithDirectory("phrases.txt")

        expectRestoreIoFailure(backup, BackupManager.Mode.OVERWRITE)
    }

    @Test fun merge_restore_reports_failure_when_clipboard_persistence_fails() {
        freshClip().apply { record("剪贴写入失败探针"); flushPendingWrites() }
        val backup = export()
        wipeUserData()
        blockFilePathWithDirectory("clipboard.txt")

        expectRestoreIoFailure(backup, BackupManager.Mode.MERGE)
    }

    @Test fun restore_reports_failure_when_symbol_persistence_fails() {
        SymbolUsageStore(filesDir).apply { load(); record("！", "zh") }
        val backup = export()
        wipeUserData()
        blockFilePathWithDirectory("symbol_usage.txt")

        expectRestoreIoFailure(backup, BackupManager.Mode.OVERWRITE)
    }

    @Test fun restore_reports_failure_when_emoji_persistence_fails() {
        SymbolUsageStore(File(filesDir, "emoji").apply { mkdirs() }).apply { load(); record("😀", "smileys") }
        val backup = export()
        wipeUserData()
        blockFilePathWithDirectory("emoji/symbol_usage.txt")

        expectRestoreIoFailure(backup, BackupManager.Mode.OVERWRITE)
    }

    @Test fun a_failed_restore_never_latches_the_capture_guard() {
        seedTypicalData()
        val backup = export()
        try {
            restore(backup, BackupManager.Mode.OVERWRITE, pass = "wrong")
            fail("expected failure")
        } catch (e: BackupException) {
            assertEquals(BackupError.WRONG_PASSWORD_OR_CORRUPT, e.error)
        }
        assertFalse("guard must not stay latched after a failed restore", LiveUserData.restoreInProgress)
    }

    @Test fun the_backup_file_reveals_no_plaintext_or_password() {
        freshClip().apply { record("UNIQUE-LEAK-MARKER-42"); flushPendingWrites() }
        val backup = export()
        val haystack = String(backup, Charsets.ISO_8859_1)
        assertFalse("user content must be encrypted", haystack.contains("UNIQUE-LEAK-MARKER-42"))
        assertFalse("password must never appear", haystack.contains(password))
    }

    @Test fun large_dictionary_and_a_multi_megabyte_clip_round_trip_without_loading_it_all() {
        val model = UserModel()
        val count = 60_000
        for (i in 0 until count) model.addManualWord("reading$i", "词$i", 1000L + i)
        model.save(userdbFile())

        val bigClip = "巨大剪贴".repeat(400_000)
        freshClip().apply { importHistory(listOf(bigClip, "普通").asClipEntries(), merge = false) }

        val backup = export()
        wipeUserData()
        restore(backup, BackupManager.Mode.OVERWRITE)

        assertEquals(count, UserModel().apply { load(userdbFile()) }.userWordEntries().size)
        val history = freshClip().historyText()
        assertTrue(history.contains("普通"))
        assertTrue("the multi-MB entry round-trips", history.contains(bigClip))
    }

    @Test fun overwrite_restore_replaces_clip_sidecar_set() {
        val restoredBig = "restored-sidecar-" + "r".repeat(ClipboardStore.BIG_THRESHOLD + 1)
        freshClip().apply { importHistory(listOf(restoredBig).asClipEntries(), merge = false) }
        val backup = export()
        val restoredSidecars = clipSideFileNames()
        assertTrue("precondition: restored history has a sidecar", restoredSidecars.isNotEmpty())

        wipeUserData()
        val staleBig = "stale-sidecar-" + "s".repeat(ClipboardStore.BIG_THRESHOLD + 1)
        freshClip().apply { importHistory(listOf(staleBig).asClipEntries(), merge = false) }
        val staleSidecars = clipSideFileNames()
        assertTrue("precondition: target history has a stale sidecar", staleSidecars.isNotEmpty())
        assertTrue("precondition: sidecar hashes differ", restoredSidecars.intersect(staleSidecars).isEmpty())

        restore(backup, BackupManager.Mode.OVERWRITE)

        assertEquals(listOf(restoredBig), freshClip().historyText())
        assertEquals(restoredSidecars, clipSideFileNames())
    }

    @Test fun overwrite_restore_normalizes_duplicate_clips_and_missing_sidecars() {
        File(filesDir, "clipboard.txt").writeText("重复剪贴\nB\tMissingSidecar42\n重复剪贴\n")
        val backup = export()

        wipeUserData()
        restore(backup, BackupManager.Mode.OVERWRITE)

        assertEquals(listOf("重复剪贴", "B\tMissingSidecar42"), freshClip().historyText())
        assertEquals(listOf("重复剪贴", "B\tMissingSidecar42"), File(filesDir, "clipboard.txt").readLines())
        assertTrue(clipSideFileNames().isEmpty())
    }

    @Test fun export_after_overwrite_omits_unreferenced_clip_sidecars() {
        val restoredBig = "export-restored-sidecar-" + "r".repeat(ClipboardStore.BIG_THRESHOLD + 1)
        freshClip().apply { importHistory(listOf(restoredBig).asClipEntries(), merge = false) }
        val backup = export()
        val restoredSidecars = clipSideFileNames()
        assertTrue("precondition: restored history has a sidecar", restoredSidecars.isNotEmpty())

        wipeUserData()
        val staleBig = "export-stale-sidecar-" + "s".repeat(ClipboardStore.BIG_THRESHOLD + 1)
        freshClip().apply { importHistory(listOf(staleBig).asClipEntries(), merge = false) }
        val staleSidecars = clipSideFileNames()
        assertTrue("precondition: target history has a stale sidecar", staleSidecars.isNotEmpty())
        assertTrue("precondition: sidecar hashes differ", restoredSidecars.intersect(staleSidecars).isEmpty())

        restore(backup, BackupManager.Mode.OVERWRITE)
        val clipsDir = File(filesDir, "clips").apply { mkdirs() }
        for (name in staleSidecars) File(clipsDir, name).writeText(staleBig)

        val archiveNames = decodeFileNamesFromBackup(export())

        for (name in restoredSidecars) assertTrue("restored sidecar must be archived", "clips/$name" in archiveNames)
        for (name in staleSidecars) assertFalse("stale sidecar must not be archived", "clips/$name" in archiveNames)
    }

    @Test fun export_flushes_live_learning_not_yet_written_to_disk() {
        val userDb = userdbFile()
        UserModel().apply { addManualWord("nihao", "你好", 1000) }.save(userDb)

        val live = UserModel().apply {
            load(userDb)
            addManualWord("shijie", "世界", 2000)
            record("你好", "世界", 2001)
        }
        assertTrue("precondition: the live model has unsaved learning", live.dirty)
        UserDictHot.host = liveHost(live, userDb)
        try {
            val decoded = decodeUserdbFromBackup(export())
            val entries = decoded.userWordEntries().associate { (it.reading to it.word) to it.count }
            assertTrue("live-only learned word must be in the backup", entries.containsKey("shijie" to "世界"))
            assertEquals("its exact frequency (one add + one commit) must survive", 2, entries["shijie" to "世界"])
            assertEquals("the freshly learned bigram must be flushed too", listOf("世界"), decoded.successors("你好", 8))
        } finally {
            UserDictHot.host = null
        }
    }

    @Test fun export_flushes_queued_big_clipboard_write_before_copying_index_and_side_file() {
        val clip = freshClip()
        val ioBlocked = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        clipboardIo(clip).execute {
            ioBlocked.countDown()
            releaseIo.await(5, TimeUnit.SECONDS)
        }
        assertTrue("precondition: clipboard IO thread is blocked", ioBlocked.await(1, TimeUnit.SECONDS))

        val marker = "queued-big-clipboard-marker-" + "x".repeat(ClipboardStore.BIG_THRESHOLD + 1)
        clip.record(marker)

        val flushEntered = CountDownLatch(1)
        val exportWorker = Executors.newSingleThreadExecutor()
        try {
            LiveUserData.onBeforeExport = {
                flushEntered.countDown()
                clip.flushPendingWrites()
            }

            val export = exportWorker.submit<ByteArray> { export() }
            assertTrue("export reached the live clipboard flush hook", flushEntered.await(1, TimeUnit.SECONDS))
            assertFalse("export must wait for the queued clipboard write", export.isDone)

            releaseIo.countDown()
            val backup = export.get(5, TimeUnit.SECONDS)

            wipeUserData()
            restore(backup, BackupManager.Mode.OVERWRITE)

            assertTrue("queued big clipboard entry must restore from the backup", freshClip().historyText().contains(marker))
        } finally {
            releaseIo.countDown()
            exportWorker.shutdownNow()
            LiveUserData.onBeforeExport = null
        }
    }

    @Test fun restore_flushes_queued_clipboard_write_before_committing_restored_history() {
        val restoredMarker = "restored-clipboard-marker-" + "r".repeat(ClipboardStore.BIG_THRESHOLD + 1)
        freshClip().apply { importHistory(listOf(restoredMarker).asClipEntries(), merge = false) }
        val backup = export()
        wipeUserData()

        val liveClip = freshClip()
        val ioBlocked = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        clipboardIo(liveClip).execute {
            ioBlocked.countDown()
            releaseIo.await(5, TimeUnit.SECONDS)
        }
        assertTrue("precondition: clipboard IO thread is blocked", ioBlocked.await(1, TimeUnit.SECONDS))

        liveClip.record("queued-stale-before-restore")

        val flushEntered = CountDownLatch(1)
        val restoreWorker = Executors.newSingleThreadExecutor()
        try {
            LiveUserData.onBeforeRestore = {
                flushEntered.countDown()
                liveClip.flushPendingWrites()
            }

            val restoreFuture = restoreWorker.submit<BackupManager.Mode> {
                restore(backup, BackupManager.Mode.OVERWRITE)
            }
            assertTrue("restore reached the live clipboard flush hook", flushEntered.await(1, TimeUnit.SECONDS))
            assertFalse("restore must wait for the queued clipboard write before committing", restoreFuture.isDone)

            releaseIo.countDown()
            assertEquals(BackupManager.Mode.OVERWRITE, restoreFuture.get(5, TimeUnit.SECONDS))

            assertEquals(listOf(restoredMarker), freshClip().historyText())
        } finally {
            releaseIo.countDown()
            restoreWorker.shutdownNow()
            LiveUserData.onBeforeRestore = null
        }
    }

    @Test fun service_teardown_drains_queued_clipboard_write_before_restore_can_commit() {
        val restoredMarker = "restored-after-service-teardown-" + "t".repeat(ClipboardStore.BIG_THRESHOLD + 1)
        freshClip().apply { importHistory(listOf(restoredMarker).asClipEntries(), merge = false) }
        val backup = export()
        wipeUserData()

        val liveClip = freshClip()
        val ioBlocked = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        clipboardIo(liveClip).execute {
            ioBlocked.countDown()
            releaseIo.await(5, TimeUnit.SECONDS)
        }
        assertTrue("precondition: clipboard IO thread is blocked", ioBlocked.await(1, TimeUnit.SECONDS))

        liveClip.record("queued-stale-during-service-teardown")

        val flushCalls = AtomicInteger(0)
        val teardownFlushEntered = CountDownLatch(1)
        val restoreFlushEntered = CountDownLatch(1)
        val flush = {
            when (flushCalls.incrementAndGet()) {
                1 -> teardownFlushEntered.countDown()
                2 -> restoreFlushEntered.countDown()
            }
            liveClip.flushPendingWrites()
        }
        val teardownWorker = Executors.newSingleThreadExecutor()
        val restoreWorker = Executors.newSingleThreadExecutor()
        try {
            LiveUserData.onBeforeExport = flush
            LiveUserData.onBeforeRestore = flush

            val teardownFuture = teardownWorker.submit {
                LiveUserData.unregisterClipboardPersistenceHooks(flush)
            }
            assertTrue("service teardown reached the live clipboard flush hook", teardownFlushEntered.await(1, TimeUnit.SECONDS))
            assertFalse("service teardown must wait for the queued clipboard write", teardownFuture.isDone)

            val restoreFuture = restoreWorker.submit<BackupManager.Mode> {
                restore(backup, BackupManager.Mode.OVERWRITE)
            }
            assertTrue("restore must still see the flush hook while teardown is draining it", restoreFlushEntered.await(1, TimeUnit.SECONDS))
            assertFalse("restore must wait for the queued clipboard write before committing", restoreFuture.isDone)

            releaseIo.countDown()
            teardownFuture.get(5, TimeUnit.SECONDS)
            assertEquals(BackupManager.Mode.OVERWRITE, restoreFuture.get(5, TimeUnit.SECONDS))

            assertNull(LiveUserData.onBeforeExport)
            assertNull(LiveUserData.onBeforeRestore)
            assertEquals(listOf(restoredMarker), freshClip().historyText())
        } finally {
            releaseIo.countDown()
            teardownWorker.shutdownNow()
            restoreWorker.shutdownNow()
            LiveUserData.onBeforeExport = null
            LiveUserData.onBeforeRestore = null
        }
    }

    @Test fun a_legacy_flat_phrases_file_restores_instead_of_being_silently_dropped() {
        File(filesDir, "phrases.txt").writeText("张三\n李四\n王五\n")
        val backup = export()

        wipeUserData()
        prefs.edit().clear().commit()

        restore(backup, BackupManager.Mode.OVERWRITE)

        assertTrue(
            "legacy flat phrases must survive the round-trip",
            freshClip().phrases().containsAll(listOf("张三", "李四", "王五")),
        )
    }

    @Test fun overwrite_restore_merges_duplicate_phrase_categories_from_backup() {
        File(filesDir, "phrases.txt").writeText(
            "C\t工作\n" +
                "P\t备份一\n" +
                "N\t一注\n" +
                "C\t工作\n" +
                "P\t备份二\n" +
                "P\t备份一\n" +
                "N\t不应覆盖\n",
        )
        val backup = export()

        wipeUserData()
        freshClip().apply {
            addCategory("工作")
            addPhrasesTo("工作", listOf("本机旧"))
            addCategory("本机组")
            addPhrasesTo("本机组", listOf("不应保留"))
        }

        restore(backup, BackupManager.Mode.OVERWRITE)

        val clip = freshClip()
        assertEquals(1, clip.categories().count { it == "工作" })
        assertEquals(listOf("备份一", "备份二"), clip.phrasesIn("工作"))
        assertEquals("一注", clip.noteFor("工作", "备份一"))
        assertFalse("本机组" in clip.categories())
        assertFalse("本机旧" in clip.phrasesIn("工作"))
    }

    @Test fun merge_restore_collapses_local_duplicate_phrase_categories() {
        File(filesDir, "phrases.txt").writeText(
            "C\t工作\n" +
                "P\t备份一\n" +
                "P\t共同\n" +
                "N\t备份注\n" +
                "C\t新组\n" +
                "P\t新短语\n",
        )
        val backup = export()

        wipeUserData()
        File(filesDir, "phrases.txt").writeText(
            "C\t工作\n" +
                "P\t本机一\n" +
                "C\t工作\n" +
                "P\t本机二\n" +
                "P\t共同\n" +
                "N\t本机注\n" +
                "C\t本机组\n" +
                "P\t保留\n",
        )

        restore(backup, BackupManager.Mode.MERGE)

        val clip = freshClip()
        assertEquals(1, clip.categories().count { it == "工作" })
        assertEquals(listOf("本机一", "本机二", "共同", "备份一"), clip.phrasesIn("工作"))
        assertEquals("本机注", clip.noteFor("工作", "共同"))
        assertEquals(listOf("新短语"), clip.phrasesIn("新组"))
        assertEquals(listOf("保留"), clip.phrasesIn("本机组"))
    }

    @Test fun malformed_blank_phrase_category_backup_never_wipes_existing_phrases() {
        File(filesDir, "phrases.txt").writeText("C\t\nP\t无分类短语\n")
        val backup = export()

        wipeUserData()
        freshClip().apply { addPhrasesTo("default", listOf("请勿删除的短语")) }

        restore(backup, BackupManager.Mode.OVERWRITE)

        val clip = freshClip()
        assertTrue(clip.phrases().contains("请勿删除的短语"))
        assertFalse("" in clip.categories())
    }

    @Test fun an_empty_phrases_backup_never_wipes_existing_phrases_on_overwrite() {
        File(filesDir, "phrases.txt").writeText("")
        val backup = export()

        freshClip().addPhrasesTo("default", listOf("请勿删除的短语"))

        restore(backup, BackupManager.Mode.OVERWRITE)

        assertTrue(
            "an empty-phrases backup must not wipe the device's phrases on overwrite",
            freshClip().phrases().contains("请勿删除的短语"),
        )
    }


    private fun containerVersion(backup: ByteArray): Int = backup[BackupFormat.MAGIC.size].toInt() and 0xFF

    private fun firstEntryTag(backup: ByteArray): Int {
        var tag = -1
        BackupCrypto.readDecrypted(ByteArrayInputStream(backup), password.toCharArray()) { plain ->
            java.util.zip.GZIPInputStream(plain).use { gz -> tag = gz.read() }
        }
        return tag
    }

    private fun legacyFormatBackup(): ByteArray {
        val bos = ByteArrayOutputStream()
        BackupCrypto.writeEncrypted(bos, password.toCharArray(), BackupFormat.HEADER_VERSION) { cipherOut ->
            val gzip = java.util.zip.GZIPOutputStream(cipherOut)
            val out = java.io.DataOutputStream(gzip)
            BackupArchive.writePrefs(out, PrefsCodec.encode(prefs.all.filterKeys { it !in DOWNLOAD_KEYS }))
            for (rel in listOf("userdb.txt", "userlearn.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt", "emoji/symbol_usage.txt")) {
                val f = File(filesDir, rel)
                if (f.isFile) BackupArchive.writeFile(out, rel, f)
            }
            BackupArchive.writeEnd(out)
            out.flush()
            gzip.finish()
        }
        return bos.toByteArray()
    }

    private fun hugeSymbolList(): String {
        val sb = StringBuilder(12 * 1024 * 1024)
        var i = 0
        while (sb.length < 12 * 1024 * 1024) {
            sb.append("sym").append(i++).append('\n')
        }
        sb.append("符号").append('\n').append("¶")
        return sb.toString()
    }

    @Test fun an_ordinary_backup_keeps_the_legacy_container_version_and_prefs_entry() {
        seedTypicalData()
        val backup = export()

        assertEquals(BackupFormat.HEADER_VERSION, containerVersion(backup))
        assertEquals('P'.code, firstEntryTag(backup))
    }

    @Test fun a_backup_whose_settings_section_is_damaged_is_reported_as_damaged_not_as_an_io_fault() {
        seedTypicalData()
        val damaged = ByteArrayOutputStream()
        java.io.DataOutputStream(damaged).use { blob ->
            blob.writeInt(1)
            val key = "cn_layout".toByteArray(Charsets.UTF_8)
            blob.writeInt(key.size)
            blob.write(key)
            blob.writeByte('X'.code)
        }
        val bos = ByteArrayOutputStream()
        BackupCrypto.writeEncrypted(bos, password.toCharArray(), BackupFormat.HEADER_VERSION) { cipherOut ->
            val gzip = java.util.zip.GZIPOutputStream(cipherOut)
            val out = java.io.DataOutputStream(gzip)
            BackupArchive.writePrefs(out, damaged.toByteArray())
            BackupArchive.writeEnd(out)
            out.flush()
            gzip.finish()
        }

        try {
            restore(bos.toByteArray(), BackupManager.Mode.OVERWRITE)
            fail("expected the damaged settings section to be refused")
        } catch (e: BackupException) {
            assertEquals(
                "a file the app cannot parse is a damaged file, not a disk that would not read",
                BackupError.WRONG_PASSWORD_OR_CORRUPT,
                e.error,
            )
        }
        assertFalse("staging must be cleaned up", File(filesDir, "backup_staging").exists())
        assertFalse("guard must not stay latched after a failed restore", LiveUserData.restoreInProgress)
    }

    @Test fun a_legacy_container_backup_still_restores() {
        seedTypicalData()
        val backup = legacyFormatBackup()
        assertEquals(BackupFormat.HEADER_VERSION, containerVersion(backup))
        assertEquals('P'.code, firstEntryTag(backup))

        wipeUserData()
        prefs.edit().clear().commit()

        restore(backup, BackupManager.Mode.OVERWRITE)

        assertEquals("alpha", prefs.getString("cn_layout", null))
        assertEquals("§\n¶", prefs.getString("custom_symbols", null))
        assertEquals(7, prefs.getInt("some_int", -1))
        val entries = UserModel().apply { load(userdbFile()) }.userWordEntries().map { it.reading to it.word }
        assertEquals(setOf("nihao" to "你好", "shijie" to "世界"), entries.toSet())
        val clip = freshClip()
        assertTrue(clip.historyText().containsAll(listOf("剪贴内容一", "剪贴内容二")))
        assertTrue(clip.phrasesIn("工作").containsAll(listOf("已收到", "好的")))
        assertTrue(SymbolUsageStore(filesDir).apply { load() }.recent().containsAll(listOf("！", "？")))
    }

    @Test fun prefs_far_beyond_the_legacy_cap_survive_a_full_export_and_restore() {
        seedTypicalData()
        val symbols = hugeSymbolList()
        assertTrue("precondition: the value alone exceeds the old 8 MB gate", symbols.toByteArray().size > 8 * 1024 * 1024)
        prefs.edit().putString("custom_symbols", symbols).commit()

        val backup = export()
        assertEquals(BackupFormat.HEADER_VERSION_CHUNKED_PREFS, containerVersion(backup))
        assertEquals('p'.code, firstEntryTag(backup))

        wipeUserData()
        prefs.edit().clear().commit()

        restore(backup, BackupManager.Mode.OVERWRITE)

        assertEquals(symbols, prefs.getString("custom_symbols", null))
        assertEquals("alpha", prefs.getString("cn_layout", null))
        assertFalse(prefs.getBoolean("fuzzy", true))
        assertEquals(7, prefs.getInt("some_int", -1))
        for (k in DOWNLOAD_KEYS) assertFalse("download-state key $k must not be restored", prefs.contains(k))

        val entries = UserModel().apply { load(userdbFile()) }.userWordEntries().map { it.reading to it.word }
        assertEquals(setOf("nihao" to "你好", "shijie" to "世界"), entries.toSet())
        assertTrue(freshClip().historyText().containsAll(listOf("剪贴内容一", "剪贴内容二")))
        assertTrue(SymbolUsageStore(File(filesDir, "emoji")).apply { load() }.recent().contains("😀"))
    }

    @Test fun a_custom_symbol_store_beyond_the_legacy_cap_survives_a_restore() {
        val store = CustomSymbolStore(prefs)
        prefs.edit().putString("custom_symbols", hugeSymbolList()).commit()
        val before = store.list()
        assertTrue(before.size > 100_000)

        val backup = export()
        prefs.edit().clear().commit()
        restore(backup, BackupManager.Mode.OVERWRITE)

        assertEquals(before, CustomSymbolStore(prefs).list())
    }
}
