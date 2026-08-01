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
import android.database.sqlite.SQLiteDatabase
import com.aegis.ime.user.ClipboardDataSnapshot
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.CustomSymbolStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.StoredPhraseCategory
import com.aegis.ime.user.StoredRecentItem
import com.aegis.ime.user.StoredSettingValue
import com.aegis.ime.user.SymbolUsageStore
import com.aegis.ime.user.UserDataDatabase
import com.aegis.ime.user.UserDataMigration
import com.aegis.ime.user.UserDataRestoreStage
import com.aegis.ime.user.UserDataSnapshot
import com.aegis.ime.user.UserDictHot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserLearningSnapshot
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRoundTripTest {

    private lateinit var context: Context
    private lateinit var filesDir: File
    private lateinit var prefs: SharedPreferences
    private val password = "backup-pass-01"

    private val downloadKeys = listOf(
        "engine_pack_touch",
        "gram_validator",
        "gram_sha256",
        "gram_size_bytes",
        "dict_validator",
        "dict_sha256",
        "dict_asset_name",
        "dict_asset_url",
        "dict_release_tag",
        "dict_release_published_at",
    )

    private data class DataSnapshot(
        val user: UserDataSnapshot,
        val learning: UserLearningSnapshot,
        val clipboard: ClipboardDataSnapshot,
        val customSymbols: List<String>,
        val customOperators: List<String>,
        val recentSymbols: List<StoredRecentItem>,
        val recentEmoji: List<StoredRecentItem>,
        val settings: Map<String, StoredSettingValue>,
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        filesDir = context.filesDir
        prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
        UserDictHot.host = null
        LiveUserData.onRestored = null
        LiveUserData.onBeforeExport = null
        LiveUserData.onBeforeRestore = null
        LiveUserData.restoreInProgress = false
        clearCanonicalData()
        prefs.edit().clear().commit()
    }

    private fun clearCanonicalData() {
        filesDir.listFiles().orEmpty().forEach { file ->
            if (
                file.name == UserDataDatabase.DATABASE_NAME ||
                file.name.startsWith(UserDataDatabase.DATABASE_NAME + "-") ||
                file.name.startsWith("user-data-v2.last-good") ||
                file.name in setOf(
                    UserDataDatabase.STATUS_NAME,
                    UserDataMigration.STATUS_NAME,
                    "backup_staging",
                    "backup_export",
                    "backup_verify",
                    "decoded-records",
                    "decoded-database",
                    "userdb.txt",
                    "userlearn.txt",
                    "phrases.txt",
                    "clipboard.txt",
                    "symbol_usage.txt",
                    "clips",
                    "emoji",
                )
            ) {
                file.deleteRecursively()
            }
        }
    }

    private fun <T> withDatabase(block: (UserDataDatabase) -> T): T =
        UserDataMigration.open(filesDir, prefs).use(block)

    private fun seedTypicalData() {
        prefs.edit()
            .putString("cn_layout", "alpha")
            .putBoolean("fuzzy", false)
            .putInt("some_int", 7)
            .putLong("some_long", 9_876_543_210L)
            .putFloat("some_float", -0.0f)
            .putStringSet("some_set", linkedSetOf("乙", "甲", ""))
            .putBoolean("clip_history", false)
            .apply {
                for (key in downloadKeys) putString(key, "device-only-$key")
            }
            .commit()
        withDatabase { database ->
            val model = UserModel(database = database)
            assertTrue(model.addManualWord("nihao", "你好", 1_000L))
            assertTrue(model.addManualWord("shijie", "世界", 1_001L))
            assertTrue(model.record("你好", "世界", 1_002L))

            val learning = UserLearning(database = database)
            repeat(3) {
                learning.observeCommit(null, "张", "zhang", 1_100L + it)
                learning.observeCommit("张", "伟", "wei", 1_200L + it)
                learning.observeBreak()
            }

            val clipboard = ClipboardStore(filesDir, database).apply { load() }
            assertTrue(clipboard.addCategory("工作"))
            assertEquals(2, clipboard.addPhrasesTo("工作", listOf("已收到", "好的")))
            val noteSaved = clipboard.setPhraseNote("工作", "已收到", "回执")
            assertTrue(
                "categories=${clipboard.categories()} phrases=${clipboard.phrasesIn("工作")} failure=${clipboard.lastFailure}",
                noteSaved,
            )
            assertTrue(clipboard.record("剪贴内容一"))
            assertTrue(clipboard.record("剪贴内容二"))

            val customSymbols = CustomSymbolStore(prefs, "custom_symbols", database)
            val customOperators = CustomSymbolStore(prefs, "custom_operators", database)
            assertTrue(customSymbols.add("§"))
            assertTrue(customSymbols.add("¶"))
            assertTrue(customOperators.add("⊕"))

            val symbols = SymbolUsageStore(filesDir, database, "symbols").apply { load() }
            val emoji = SymbolUsageStore(File(filesDir, "emoji"), database, "emoji").apply { load() }
            assertTrue(symbols.record("！", "math"))
            assertTrue(symbols.record("？", "math"))
            assertTrue(emoji.record("😀", "smileys"))
            database.checkpointLastGood()
        }
    }

    private fun snapshot(): DataSnapshot = withDatabase { database ->
        DataSnapshot(
            user = database.readUserData(),
            learning = database.readLearning(),
            clipboard = ClipboardDataSnapshot(database.readClipboardHistory(), database.readPhraseCategories()),
            customSymbols = database.readCustomItems("custom_symbols"),
            customOperators = database.readCustomItems("custom_operators"),
            recentSymbols = database.readRecentItems("symbols"),
            recentEmoji = database.readRecentItems("emoji"),
            settings = database.readSettings(),
        )
    }

    private fun exportVerified(): ByteArray {
        val output = ByteArrayOutputStream()
        BackupManager.export(filesDir, prefs, password.toCharArray(), output)
        val bytes = output.toByteArray()
        BackupManager.verify(filesDir, password.toCharArray(), ByteArrayInputStream(bytes))
        assertFalse(File(filesDir, "backup_export").exists())
        assertFalse(File(filesDir, "backup_verify").exists())
        return bytes
    }

    private fun restore(bytes: ByteArray, mode: BackupManager.Mode, pass: String = password): BackupManager.Mode =
        BackupManager.restore(filesDir, prefs, pass.toCharArray(), ByteArrayInputStream(bytes), mode)

    private fun expectError(expected: BackupError, block: () -> Unit) {
        try {
            block()
            fail("expected BackupException($expected)")
        } catch (failure: BackupException) {
            assertEquals(expected, failure.error)
        }
    }

    @Test
    fun overwriteRoundTripRestoresEveryDatabaseStoreAndSettingWhileKeepingDeviceStateLocal() {
        seedTypicalData()
        val expected = snapshot()
        val backup = exportVerified()

        clearCanonicalData()
        prefs.edit().clear().putString("dict_sha256", "local-download-state").commit()
        restore(backup, BackupManager.Mode.OVERWRITE)

        assertEquals(expected, snapshot())
        withDatabase { database ->
            assertEquals(StoredSettingValue.StringValue("alpha"), database.readSetting("cn_layout"))
            assertEquals(StoredSettingValue.Bool(false), database.readSetting("fuzzy"))
            assertEquals(StoredSettingValue.Integer(7), database.readSetting("some_int"))
            assertEquals(StoredSettingValue.LongValue(9_876_543_210L), database.readSetting("some_long"))
            assertEquals(StoredSettingValue.FloatValue(-0.0f), database.readSetting("some_float"))
            assertEquals(StoredSettingValue.StringSetValue(setOf("乙", "甲", "")), database.readSetting("some_set"))
            assertEquals(StoredSettingValue.Bool(false), database.readSetting("clip_history"))
        }
        assertEquals("local-download-state", prefs.getString("dict_sha256", null))
        assertFalse(prefs.contains("custom_symbols"))
        assertFalse(prefs.contains("custom_operators"))
        assertFalse(LiveUserData.restoreInProgress)
    }

    @Test
    fun mergeKeepsLocalOrderAndSettingsWhileAddingAndCombiningBackupData() {
        seedTypicalData()
        val backup = exportVerified()

        clearCanonicalData()
        prefs.edit().clear().putString("cn_layout", "local").commit()
        withDatabase { database ->
            val model = UserModel(database = database)
            assertTrue(model.addManualWord("beijing", "北京", 2_000L))
            val clipboard = ClipboardStore(filesDir, database).apply { load() }
            assertTrue(clipboard.record("本机剪贴"))
            assertTrue(clipboard.addCategory("本机组"))
            assertEquals(1, clipboard.addPhrasesTo("本机组", listOf("本机短语")))
            assertTrue(CustomSymbolStore(prefs, "custom_symbols", database).add("本机符号"))
            assertTrue(SymbolUsageStore(filesDir, database, "symbols").apply { load() }.record("本", "local"))
        }

        restore(backup, BackupManager.Mode.MERGE)

        withDatabase { database ->
            val readings = database.readUserData().readings
            assertEquals(setOf("北京"), readings["beijing"])
            assertEquals(setOf("你好"), readings["nihao"])
            assertEquals("本机剪贴", database.readClipboardHistory(limit = 1).single())
            assertTrue(database.readClipboardHistory().containsAll(listOf("剪贴内容一", "剪贴内容二")))
            val categories = database.readPhraseCategories().associateBy(StoredPhraseCategory::name)
            assertTrue(categories.containsKey("本机组"))
            assertTrue(categories.containsKey("工作"))
            assertEquals(listOf("本机符号", "§", "¶"), database.readCustomItems("custom_symbols"))
            assertEquals("本", database.readRecentItems("symbols", limit = 1).single().value)
            assertTrue(database.readRecentItems("symbols").map { it.value }.containsAll(listOf("！", "？")))
        }
        withDatabase { database ->
            assertEquals(StoredSettingValue.StringValue("local"), database.readSetting("cn_layout"))
            assertEquals(StoredSettingValue.Integer(7), database.readSetting("some_int"))
        }
    }

    @Test
    fun wrongPasswordTruncationAndCiphertextDamageNeverChangeTheTarget() {
        seedTypicalData()
        val backup = exportVerified()
        withDatabase { database -> assertTrue(ClipboardStore(filesDir, database).apply { load() }.record("target marker")) }
        withDatabase { database -> database.updateSettings(mapOf("target_pref" to StoredSettingValue.StringValue("keep"))) }
        val expected = snapshot()

        expectError(BackupError.WRONG_PASSWORD_OR_CORRUPT) {
            restore(backup, BackupManager.Mode.OVERWRITE, "wrong-password")
        }
        expectError(BackupError.WRONG_PASSWORD_OR_CORRUPT) {
            restore(backup.copyOf(backup.size - 17), BackupManager.Mode.OVERWRITE)
        }
        val damaged = backup.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        expectError(BackupError.WRONG_PASSWORD_OR_CORRUPT) {
            restore(damaged, BackupManager.Mode.OVERWRITE)
        }

        assertEquals(expected, snapshot())
        withDatabase { database ->
            assertEquals(StoredSettingValue.StringValue("keep"), database.readSetting("target_pref"))
        }
        assertFalse(File(filesDir, "backup_staging").exists())
        assertFalse(LiveUserData.restoreInProgress)
    }

    @Test
    fun versionOneIsExplicitlyUnsupportedWithoutChangingData() {
        seedTypicalData()
        val expected = snapshot()
        val backup = exportVerified()
        backup[BackupFormat.MAGIC.size] = 1

        expectError(BackupError.UNSUPPORTED_VERSION) {
            restore(backup, BackupManager.Mode.OVERWRITE)
        }
        assertEquals(expected, snapshot())
    }

    @Test
    fun databaseRecordsBeyondOldEightMiBBoundariesRoundTripExactly() {
        val history = List(9_000) { index ->
            "clip-$index-" + String(CharArray(1_024) { offset ->
                ('a'.code + (index * 131 + offset * 17).mod(26)).toChar()
            })
        }
        withDatabase { database ->
            val clipboard = ClipboardStore(filesDir, database).apply { load() }
            assertTrue(clipboard.importHistory(history, merge = false))
            val customSymbols = CustomSymbolStore(prefs, "custom_symbols", database)
            repeat(450) { assertTrue(customSymbols.add("custom-$it")) }
            val recent = SymbolUsageStore(filesDir, database, "symbols").apply { load() }
            repeat(100) { assertTrue(recent.record("recent-$it", "group-$it")) }
            database.checkpointLastGood()
        }
        assertTrue(File(filesDir, UserDataDatabase.DATABASE_NAME).length() > 8L * 1024L * 1024L)

        val backup = exportVerified()
        clearCanonicalData()
        prefs.edit().clear().commit()
        restore(backup, BackupManager.Mode.OVERWRITE)

        withDatabase { database ->
            var offset = 0
            while (offset < history.size) {
                val expectedPage = history.subList(offset, minOf(offset + 128, history.size))
                assertEquals(expectedPage, database.readClipboardHistory(offset, expectedPage.size))
                offset += expectedPage.size
            }
            assertEquals(450, database.readCustomItems("custom_symbols").size)
            assertEquals(100, database.readRecentItems("symbols").size)
        }
    }

    @Test
    fun verifyReopensAndRejectsAnIncompleteOrTamperedProduct() {
        seedTypicalData()
        val backup = exportVerified()
        expectError(BackupError.WRONG_PASSWORD_OR_CORRUPT) {
            BackupManager.verify(filesDir, password.toCharArray(), ByteArrayInputStream(backup.copyOf(backup.size / 2)))
        }
        val damaged = backup.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        expectError(BackupError.WRONG_PASSWORD_OR_CORRUPT) {
            BackupManager.verify(filesDir, password.toCharArray(), ByteArrayInputStream(damaged))
        }
        assertFalse(File(filesDir, "backup_verify").exists())
    }

    @Test
    fun outputAndInputIoFailuresAreReportedWithoutLeakingTemporaryState() {
        seedTypicalData()
        val expected = snapshot()
        var exportFailed = false
        try {
            BackupManager.export(filesDir, prefs, password.toCharArray(), FailingOutputStream(0))
        } catch (_: IOException) {
            exportFailed = true
        }
        assertTrue(exportFailed)
        assertFalse(File(filesDir, "backup_export").exists())

        val backup = exportVerified()
        expectError(BackupError.IO_ERROR) {
            BackupManager.restore(
                filesDir,
                prefs,
                password.toCharArray(),
                FailingInputStream(backup, backup.size / 2),
                BackupManager.Mode.OVERWRITE,
            )
        }
        assertEquals(expected, snapshot())
        assertFalse(File(filesDir, "backup_staging").exists())
    }

    @Test
    fun interruptionsBeforeCommitKeepTheOldSnapshotAndInterruptionsAfterCommitKeepTheWholeNewSnapshot() {
        seedTypicalData()
        val source = snapshot()
        val backup = exportVerified()
        withDatabase { database ->
            assertTrue(UserModel(database = database).addManualWord("target", "本机目标", 3_000L))
            database.updateSettings(mapOf("target_only" to StoredSettingValue.StringValue("keep-before-commit")))
        }
        val target = snapshot()

        expectError(BackupError.IO_ERROR) {
            BackupManager.restoreForTest(
                filesDir,
                prefs,
                password.toCharArray(),
                ByteArrayInputStream(backup),
                BackupManager.Mode.OVERWRITE,
            ) { stage ->
                if (stage == UserDataRestoreStage.BEFORE_DATABASE_COMMIT) throw IOException("interrupted")
            }
        }
        assertEquals(target, snapshot())

        expectError(BackupError.IO_ERROR) {
            BackupManager.restoreForTest(
                filesDir,
                prefs,
                password.toCharArray(),
                ByteArrayInputStream(backup),
                BackupManager.Mode.OVERWRITE,
            ) { stage ->
                if (stage == UserDataRestoreStage.AFTER_DATABASE_COMMIT) throw IOException("interrupted")
            }
        }
        assertEquals(source, snapshot())

        withDatabase { database ->
            assertTrue(UserModel(database = database).addManualWord("again", "再次改变", 3_001L))
        }
        expectError(BackupError.IO_ERROR) {
            BackupManager.restoreForTest(
                filesDir,
                prefs,
                password.toCharArray(),
                ByteArrayInputStream(backup),
                BackupManager.Mode.OVERWRITE,
            ) { stage ->
                if (stage == UserDataRestoreStage.AFTER_CHECKPOINT) throw IOException("interrupted")
            }
        }
        assertEquals(source, snapshot())
        assertFalse(LiveUserData.restoreInProgress)
    }

    @Test
    fun legacyPreferenceCleanupFailureCannotBlockOrPartiallyApplyDatabaseRestore() {
        seedTypicalData()
        val expected = snapshot()
        val backup = exportVerified()
        withDatabase { database -> assertTrue(ClipboardStore(filesDir, database).apply { load() }.record("local after export")) }
        assertTrue(prefs.edit().remove("cn_layout").putBoolean("fuzzy", true).commit())
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun commit(): Boolean {
                        delegate.commit()
                        return false
                    }
                }
            }
        }

        assertEquals(
            BackupManager.Mode.OVERWRITE,
            BackupManager.restore(
                filesDir,
                failing,
                password.toCharArray(),
                ByteArrayInputStream(backup),
                BackupManager.Mode.OVERWRITE,
            ),
        )
        assertEquals(expected, snapshot())
        assertFalse(LiveUserData.restoreInProgress)
    }

    @Test
    fun databaseOpenFailureLeavesDeviceSpecificStateUntouched() {
        seedTypicalData()
        val backup = exportVerified()
        clearCanonicalData()
        prefs.edit().clear().putString("dict_sha256", "keep").commit()
        val blocker = File(filesDir, UserDataDatabase.DATABASE_NAME).apply {
            mkdirs()
            File(this, "blocker").writeText("x")
        }

        expectError(BackupError.IO_ERROR) {
            restore(backup, BackupManager.Mode.OVERWRITE)
        }
        assertEquals("keep", prefs.getString("dict_sha256", null))
        assertTrue(blocker.isDirectory)
    }

    @Test
    fun restoreRefreshesLiveStoresAndAlwaysReleasesTheCaptureGuard() {
        seedTypicalData()
        val backup = exportVerified()
        val reloads = AtomicInteger(0)
        LiveUserData.onRestored = {
            reloads.incrementAndGet()
            LiveUserData.restoreInProgress = false
        }
        restore(backup, BackupManager.Mode.OVERWRITE)
        assertEquals(1, reloads.get())
        assertFalse(LiveUserData.restoreInProgress)
    }

    @Test
    fun exportAndRestoreInvokeLiveFlushHooksBeforeTakingOrChangingSnapshots() {
        seedTypicalData()
        val exportFlushes = AtomicInteger(0)
        val restoreFlushes = AtomicInteger(0)
        LiveUserData.onBeforeExport = { exportFlushes.incrementAndGet() }
        LiveUserData.onBeforeRestore = { restoreFlushes.incrementAndGet() }
        val backup = exportVerified()
        restore(backup, BackupManager.Mode.OVERWRITE)
        assertEquals(1, exportFlushes.get())
        assertEquals(1, restoreFlushes.get())
    }

    @Test
    fun archiveContainsExactlyOneDatabaseRecordWithSettingsAndNoPreferenceDuplicates() {
        prefs.edit().putString("custom_symbols", "stale-legacy-value").commit()
        seedTypicalData()
        val backup = exportVerified()
        val decoded = decodeRecords(backup)
        assertEquals(setOf("database"), decoded.keys)
        val databaseFile = decoded.getValue("database")
        assertTrue(UserDataDatabase.validateRestoreSource(databaseFile))
        val snapshotDir = File(filesDir, "decoded-database").apply { deleteRecursively(); mkdirs() }
        databaseFile.copyTo(File(snapshotDir, UserDataDatabase.DATABASE_NAME))
        UserDataDatabase.open(snapshotDir).use { database ->
            assertEquals(StoredSettingValue.StringValue("alpha"), database.readSetting("cn_layout"))
            assertEquals(StoredSettingValue.Bool(false), database.readSetting("fuzzy"))
            assertEquals(StoredSettingValue.Integer(7), database.readSetting("some_int"))
        }
    }

    @Test
    fun beta29BackupPreferencesMigrateLosslesslyIntoTheStagedDatabaseBeforeAtomicRestore() {
        seedTypicalData()
        val expectedUserData = snapshot().user
        val legacyValues = linkedMapOf<String, Any>(
            "clip_history" to false,
            "legacy_bool" to true,
            "legacy_int" to 41,
            "legacy_long" to 9_876_543_210L,
            "legacy_float" to 1.25f,
            "legacy_string" to "原始设置值",
            "legacy_set" to linkedSetOf("乙", "甲"),
        )
        val backup = legacyBeta29Backup(legacyValues)

        clearCanonicalData()
        prefs.edit().clear().putString("dict_sha256", "device-local").commit()
        restore(backup, BackupManager.Mode.OVERWRITE)

        withDatabase { database ->
            assertEquals(expectedUserData, database.readUserData())
            assertEquals(StoredSettingValue.Bool(false), database.readSetting("clip_history"))
            assertEquals(StoredSettingValue.Bool(true), database.readSetting("legacy_bool"))
            assertEquals(StoredSettingValue.Integer(41), database.readSetting("legacy_int"))
            assertEquals(StoredSettingValue.LongValue(9_876_543_210L), database.readSetting("legacy_long"))
            assertEquals(StoredSettingValue.FloatValue(1.25f), database.readSetting("legacy_float"))
            assertEquals(StoredSettingValue.StringValue("原始设置值"), database.readSetting("legacy_string"))
            assertEquals(
                StoredSettingValue.StringSetValue(setOf("乙", "甲")),
                database.readSetting("legacy_set"),
            )
        }
        assertEquals("device-local", prefs.getString("dict_sha256", null))
        assertTrue(legacyValues.keys.none(prefs::contains))
    }

    @Test
    fun eachExportUsesFreshAuthenticatedEncryption() {
        seedTypicalData()
        val first = exportVerified()
        val second = exportVerified()
        assertNotEquals(first.toList(), second.toList())
        val haystack = String(first, Charsets.ISO_8859_1)
        assertFalse(haystack.contains("剪贴内容一"))
        assertFalse(haystack.contains(password))
    }

    private fun decodeRecords(backup: ByteArray): LinkedHashMap<String, File> {
        val directory = File(filesDir, "decoded-records").apply {
            deleteRecursively()
            mkdirs()
        }
        val files = LinkedHashMap<String, File>()
        BackupCrypto.readDecrypted(ByteArrayInputStream(backup), password.toCharArray()) { plain ->
            GZIPInputStream(plain).use { gzip ->
                BackupArchive.read(
                    DataInputStream(gzip),
                    object : BackupArchive.Visitor {
                        override fun openRecord(name: String, kind: Int): OutputStream {
                            val file = File(directory, name.replace('/', '_'))
                            files[name] = file
                            return file.outputStream()
                        }
                    },
                )
            }
        }
        return files
    }

    private fun legacyBeta29Backup(settings: LinkedHashMap<String, Any>): ByteArray {
        val directory = File(filesDir, "legacy-beta29-backup").apply { deleteRecursively(); mkdirs() }
        val databaseFile = File(directory, UserDataDatabase.DATABASE_NAME)
        withDatabase { database -> database.exportSnapshot(databaseFile) }
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL("DROP TABLE user_setting_set_values")
            database.execSQL("DROP TABLE user_settings")
            database.execSQL(
                "DELETE FROM metadata WHERE key LIKE 'beta29_settings_%' OR key='settings_checkpoint_pending'",
            )
            database.execSQL("PRAGMA user_version=3")
        }
        val output = ByteArrayOutputStream()
        BackupCrypto.writeEncrypted(output, password.toCharArray()) { cipher ->
            GZIPOutputStream(cipher).use { gzip ->
                val writer = BackupArchive.Writer(java.io.DataOutputStream(gzip))
                writer.writeRecord("database", BackupArchive.KIND_DATABASE) { record ->
                    databaseFile.inputStream().use { input -> input.copyTo(record, 64 * 1024) }
                }
                settings.entries.forEachIndexed { index, (key, value) ->
                    writer.writeRecord(
                        "preference/${index.toString().padStart(8, '0')}",
                        BackupArchive.KIND_PREFERENCE,
                    ) { record ->
                        val data = java.io.DataOutputStream(record)
                        PrefsCodec.writeEntry(data, key, value)
                        data.flush()
                    }
                }
                writer.finish()
                gzip.finish()
            }
        }
        return output.toByteArray()
    }

    private class FailingOutputStream(private val failAfter: Int) : OutputStream() {
        private var written = 0

        override fun write(value: Int) {
            if (written >= failAfter) throw IOException("simulated output failure")
            written++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (written + length > failAfter) throw IOException("simulated output failure")
            written += length
        }
    }

    private class FailingInputStream(
        private val bytes: ByteArray,
        private val failAfter: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= failAfter) throw IOException("simulated input failure")
            return if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= failAfter) throw IOException("simulated input failure")
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position, failAfter - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }
}
