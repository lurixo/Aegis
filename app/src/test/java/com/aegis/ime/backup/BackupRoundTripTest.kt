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
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRoundTripTest {

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var prefs: SharedPreferences
    private val password = "backup-pass-01"

    private companion object {
        val DOWNLOAD_KEYS = listOf(
            "engine_pack_touch", "gram_validator", "dict_validator", "dict_sha256",
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
        listOf("userdb.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt").forEach { File(filesDir, it).delete() }
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "emoji").deleteRecursively()
        File(filesDir, "backup_staging").deleteRecursively()
        prefs.edit().clear().commit()
    }


    private fun userdbFile() = File(filesDir, "userdb.txt")
    private fun freshClip() = ClipboardStore(filesDir).apply { load() }

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
        listOf("userdb.txt", "phrases.txt", "clipboard.txt", "symbol_usage.txt").forEach { File(filesDir, it).delete() }
        File(filesDir, "clips").deleteRecursively()
        File(filesDir, "emoji").deleteRecursively()
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

        assertTrue(clip.history().containsAll(listOf("剪贴内容一", "剪贴内容二")))

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

        assertTrue(freshClip().history().containsAll(listOf("本机剪贴", "剪贴内容一", "剪贴内容二")))

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

        assertTrue("absent category must be left intact", freshClip().history().contains("请勿删除"))
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
        freshClip().apply { importHistory(listOf(bigClip, "普通"), merge = false) }

        val backup = export()
        wipeUserData()
        restore(backup, BackupManager.Mode.OVERWRITE)

        assertEquals(count, UserModel().apply { load(userdbFile()) }.userWordEntries().size)
        val history = freshClip().history()
        assertTrue(history.contains("普通"))
        assertTrue("the multi-MB entry round-trips", history.contains(bigClip))
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
        UserDictHot.host = LiveUserDictHost(live, userDb)
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

            assertTrue("queued big clipboard entry must restore from the backup", freshClip().history().contains(marker))
        } finally {
            releaseIo.countDown()
            exportWorker.shutdownNow()
            LiveUserData.onBeforeExport = null
        }
    }

    @Test fun restore_flushes_queued_clipboard_write_before_committing_restored_history() {
        val restoredMarker = "restored-clipboard-marker-" + "r".repeat(ClipboardStore.BIG_THRESHOLD + 1)
        freshClip().apply { importHistory(listOf(restoredMarker), merge = false) }
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

            assertEquals(listOf(restoredMarker), freshClip().history())
        } finally {
            releaseIo.countDown()
            restoreWorker.shutdownNow()
            LiveUserData.onBeforeRestore = null
        }
    }

    @Test fun service_teardown_drains_queued_clipboard_write_before_restore_can_commit() {
        val restoredMarker = "restored-after-service-teardown-" + "t".repeat(ClipboardStore.BIG_THRESHOLD + 1)
        freshClip().apply { importHistory(listOf(restoredMarker), merge = false) }
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
            assertEquals(listOf(restoredMarker), freshClip().history())
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
}
