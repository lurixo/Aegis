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

package com.aegis.ime.user

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteFullException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserDataDatabaseTest {

    private class SimulatedProcessDeath : Error()

    private fun root() = Files.createTempDirectory("aegis-user-data").toFile().also { it.deleteOnExit() }

    private fun rewriteSchema(file: File, statements: List<String>) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            for (statement in statements) database.execSQL(statement)
        }
        File(file.parentFile, file.name + "-wal").delete()
        File(file.parentFile, file.name + "-shm").delete()
    }

    private fun rewriteSnapshotDigest(root: File, databaseName: String, digestName: String) {
        val file = File(root, databaseName)
        File(root, digestName).writeText(UserDataDatabase.fileIdentity(file).substringAfter(':') + "\n")
    }

    @Test
    fun everyConcurrentConnectionStartsInWalMode() {
        val root = root()
        UserDataDatabase.open(root).use { first ->
            assertEquals("wal", first.journalModeForTest().lowercase())
            assertTrue(first.foreignKeysEnabledForTest())
            UserDataDatabase.open(root).use { second ->
                assertEquals("wal", second.journalModeForTest().lowercase())
                assertTrue(second.foreignKeysEnabledForTest())
                first.putMetadata("first", "visible")
                assertEquals("visible", second.metadata("first"))
            }
        }
    }

    @Test
    fun updatingAUserWordNeverCascadesAwayItsReadings() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            assertTrue(database.foreignKeysEnabledForTest())
            val model = UserModel(database = database)
            assertTrue(model.addManualWord("chang", "长", 1L))
            assertTrue(model.addManualWord("zhang", "长", 2L))
            assertTrue(model.record("前", "长", 3L))
            assertEquals(setOf("chang", "zhang"), database.readUserData().readings.filterValues { "长" in it }.keys)
        }
    }

    @Test
    fun storesLongWordsAndReadingsWithoutLegacyQuotas() {
        val root = root()
        val word = "长".repeat(300)
        val reading = "chang".repeat(80)
        UserDataDatabase.open(root).use { database ->
            val model = UserModel(database = database)
            assertTrue(model.addManualWord(reading, word, 7))
            database.checkpointLastGood()
        }

        UserDataDatabase.open(root).use { database ->
            val model = UserModel(database = database)
            assertEquals(listOf(word), model.readingSnapshot()[reading])
            assertTrue(model.wordBoost(word) > 0.0)
            assertTrue(database.integrityOk())
        }
    }

    @Test
    fun wordsAndReadingsCrossEveryOldTwoHundredFiftySixUnitMilestoneAtomically() {
        val lengths = listOf(255, 256, 257, 1_024)
        val expected = LinkedHashMap<String, String>()
        val root = root()
        UserDataDatabase.open(root).use { database ->
            val model = UserModel(database = database)
            for ((index, length) in lengths.withIndex()) {
                val word = String(Character.toChars(0x4E00 + index)).repeat(length)
                val reading = ('a' + index).toString().repeat(length)
                assertTrue("word/reading length $length", model.addManualWord(reading, word, index.toLong()))
                expected[reading] = word
            }
            database.checkpointLastGood()
        }

        UserDataDatabase.open(root).use { database ->
            val snapshot = database.readUserData()
            for ((reading, word) in expected) {
                assertTrue(word in snapshot.words)
                assertEquals(setOf(word), snapshot.readings[reading])
            }
            assertTrue(database.integrityOk())
        }
    }

    @Test
    fun failedDatabaseWriteKeepsTheLastValidModelState() {
        val root = root()
        val database = UserDataDatabase.open(root)
        val model = UserModel(database = database)
        assertTrue(model.addManualWord("ceshi", "测试", 1))
        database.close()

        assertFalse(model.addManualWord("shibai", "失败", 2))
        assertEquals(listOf("测试"), model.readingSnapshot()["ceshi"])
        assertFalse(model.readingSnapshot().containsKey("shibai"))
        assertNotNull(model.lastFailure)
    }

    @Test
    fun corruptDatabaseRestoresTheVerifiedLastGoodSnapshot() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            val model = UserModel(database = database)
            assertTrue(model.addManualWord("beijing", "北京", 10))
            database.checkpointLastGood()
        }
        root.resolve(UserDataDatabase.DATABASE_NAME).writeText("corrupt")

        UserDataDatabase.open(root).use { recovered ->
            assertEquals(UserDataRecoveryKind.LAST_GOOD, recovered.recoveryReport.kind)
            assertEquals(listOf("北京"), UserModel(database = recovered).readingSnapshot()["beijing"])
            assertTrue(recovered.integrityOk())
        }
    }

    @Test
    fun integrityOkDatabaseWithMissingTableRestoresTheVerifiedLastGoodSnapshot() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            assertTrue(UserModel(database = database).addManualWord("beijing", "北京", 10))
            database.checkpointLastGood()
        }
        rewriteSchema(
            root.resolve(UserDataDatabase.DATABASE_NAME),
            listOf("DROP TABLE clipboard_history"),
        )

        UserDataDatabase.open(root).use { recovered ->
            assertEquals(UserDataRecoveryKind.LAST_GOOD, recovered.recoveryReport.kind)
            assertEquals(listOf("北京"), UserModel(database = recovered).readingSnapshot()["beijing"])
        }
    }

    @Test
    fun integrityOkDatabaseWithWrongColumnAndConstraintsRestoresLastGood() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            assertTrue(UserModel(database = database).addManualWord("beijing", "北京", 10))
            database.checkpointLastGood()
        }
        rewriteSchema(
            root.resolve(UserDataDatabase.DATABASE_NAME),
            listOf(
                "DROP TABLE user_words",
                "CREATE TABLE user_words (word TEXT PRIMARY KEY, count TEXT NOT NULL, last_used INTEGER NOT NULL)",
            ),
        )

        UserDataDatabase.open(root).use { recovered ->
            assertEquals(UserDataRecoveryKind.LAST_GOOD, recovered.recoveryReport.kind)
            assertEquals(listOf("北京"), UserModel(database = recovered).readingSnapshot()["beijing"])
        }
    }

    @Test
    fun corruptionWithoutSnapshotCreatesAndReportsAValidEmptyDatabase() {
        val root = root()
        root.resolve(UserDataDatabase.DATABASE_NAME).writeText("corrupt")

        UserDataDatabase.open(root).use { recovered ->
            assertEquals(UserDataRecoveryKind.EMPTY, recovered.recoveryReport.kind)
            assertTrue(recovered.isEmpty())
            assertTrue(recovered.integrityOk())
            assertTrue(root.resolve(UserDataDatabase.STATUS_NAME).readText().contains("kind=empty"))
        }
    }

    @Test
    fun digestMismatchRejectsAnOtherwiseValidLastGoodDatabase() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            assertTrue(UserModel(database = database).addManualWord("keep", "保留", 1))
            database.checkpointLastGood()
        }
        val unrelatedRoot = root()
        UserDataDatabase.open(unrelatedRoot).use { database ->
            assertTrue(UserModel(database = database).addManualWord("other", "其他", 1))
            database.checkpointLastGood()
        }
        File(unrelatedRoot, UserDataDatabase.LAST_GOOD_NAME)
            .copyTo(File(root, UserDataDatabase.LAST_GOOD_NAME), overwrite = true)
        File(root, "user-data-v2.last-good.previous.sha256").writeText("0".repeat(64))
        File(root, UserDataDatabase.DATABASE_NAME).writeText("corrupt")

        UserDataDatabase.open(root).use { recovered ->
            assertEquals(UserDataRecoveryKind.EMPTY, recovered.recoveryReport.kind)
            assertTrue(recovered.isEmpty())
            assertTrue(recovered.integrityOk())
        }
    }

    @Test
    fun invalidCurrentSnapshotFallsBackToTheVerifiedPreviousGeneration() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            val model = UserModel(database = database)
            assertTrue(model.addManualWord("first", "第一", 1))
            assertTrue(model.addManualWord("second", "第二", 1))
        }
        File(root, "user-data-v2.last-good.sha256").writeText("0".repeat(64))
        File(root, UserDataDatabase.DATABASE_NAME).writeText("corrupt")

        UserDataDatabase.open(root).use { recovered ->
            assertEquals(UserDataRecoveryKind.LAST_GOOD, recovered.recoveryReport.kind)
            val readings = UserModel(database = recovered).readingSnapshot()
            assertEquals(listOf("第一"), readings["first"])
            assertFalse(readings.containsKey("second"))
            assertTrue(recovered.recoveryReport.detail.contains("previous"))
        }
    }

    @Test
    fun schemaInvalidLatestSnapshotFallsBackToTheVerifiedPreviousGeneration() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            val model = UserModel(database = database)
            assertTrue(model.addManualWord("first", "第一", 1))
            assertTrue(model.addManualWord("second", "第二", 2))
        }
        rewriteSchema(File(root, UserDataDatabase.LAST_GOOD_NAME), listOf("DROP TABLE recent_items"))
        rewriteSnapshotDigest(
            root,
            UserDataDatabase.LAST_GOOD_NAME,
            "user-data-v2.last-good.sha256",
        )
        File(root, UserDataDatabase.DATABASE_NAME).writeText("corrupt")

        UserDataDatabase.open(root).use { recovered ->
            assertEquals(UserDataRecoveryKind.LAST_GOOD, recovered.recoveryReport.kind)
            assertTrue(recovered.recoveryReport.detail.contains("previous"))
            assertEquals(listOf("第一"), UserModel(database = recovered).readingSnapshot()["first"])
            assertFalse(UserModel(database = recovered).readingSnapshot().containsKey("second"))
        }
    }

    @Test
    fun schemaInvalidMainAndBothSnapshotsCreateAndReportAValidEmptyDatabase() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            val model = UserModel(database = database)
            assertTrue(model.addManualWord("first", "第一", 1))
            assertTrue(model.addManualWord("second", "第二", 2))
        }
        rewriteSchema(File(root, UserDataDatabase.LAST_GOOD_NAME), listOf("DROP TABLE recent_items"))
        rewriteSnapshotDigest(root, UserDataDatabase.LAST_GOOD_NAME, "user-data-v2.last-good.sha256")
        rewriteSchema(File(root, "user-data-v2.last-good.previous.db"), listOf("DROP TABLE custom_items"))
        rewriteSnapshotDigest(
            root,
            "user-data-v2.last-good.previous.db",
            "user-data-v2.last-good.previous.sha256",
        )
        rewriteSchema(File(root, UserDataDatabase.DATABASE_NAME), listOf("DROP TABLE clipboard_history"))

        UserDataDatabase.open(root).use { recovered ->
            assertEquals(UserDataRecoveryKind.EMPTY, recovered.recoveryReport.kind)
            assertTrue(recovered.isEmpty())
            assertTrue(recovered.integrityOk())
        }
    }

    @Test
    fun everyOrdinaryUserDataWriteIsPresentInTheImmediatelyRecoverableSnapshot() {
        val cases = listOf(
            Pair<(UserDataDatabase) -> Unit, (UserDataDatabase) -> Boolean>(
                { database -> database.recordClipboard("latest clip") },
                { database -> database.containsClipboard("latest clip") },
            ),
            Pair<(UserDataDatabase) -> Unit, (UserDataDatabase) -> Boolean>(
                { database -> database.addPhrases("latest category", listOf("latest phrase")) },
                { database -> database.phraseNote("latest category", "latest phrase") != null },
            ),
            Pair<(UserDataDatabase) -> Unit, (UserDataDatabase) -> Boolean>(
                { database -> database.addCustomItem("symbol", "latest symbol") },
                { database -> database.containsCustomItem("symbol", "latest symbol") },
            ),
            Pair<(UserDataDatabase) -> Unit, (UserDataDatabase) -> Boolean>(
                { database -> database.recordRecentItem("emoji", "latest", StoredRecentItem("latest emoji", null)) },
                { database -> database.recentItemOrigin("emoji", "latest") == null && database.recentItemCount("emoji") == 1L },
            ),
            Pair<(UserDataDatabase) -> Unit, (UserDataDatabase) -> Boolean>(
                { database -> UserModel(database = database).addManualWord("latest", "最新用户词", 1L) },
                { database -> database.hasUserReading("latest", "最新用户词") },
            ),
        )
        for ((write, verify) in cases) {
            val root = root()
            UserDataDatabase.open(root).use(write)
            File(root, UserDataDatabase.DATABASE_NAME).writeText("corrupt")
            UserDataDatabase.open(root).use { recovered ->
                assertEquals(UserDataRecoveryKind.LAST_GOOD, recovered.recoveryReport.kind)
                assertTrue(verify(recovered))
            }
        }
    }

    @Test
    fun checkpointIoAndEnospcFailuresRollBackTheCommittedDatabaseBeforeReturningFailure() {
        for (failureStage in listOf(UserDataCommitStage.BEFORE_CHECKPOINT, UserDataCommitStage.AFTER_CHECKPOINT)) {
            for (failure in listOf(IOException("I/O"), SQLiteFullException("ENOSPC"))) {
                val root = root()
                UserDataDatabase.open(root).use { database ->
                    database.recordClipboard("keep")
                    database.setCommitStageHookForTest { stage ->
                        if (stage == failureStage) throw failure
                    }
                    try {
                        database.recordClipboard("reject")
                        throw AssertionError("expected durable write failure")
                    } catch (actual: Exception) {
                        assertTrue(actual === failure)
                    }
                    database.setCommitStageHookForTest(null)
                    assertEquals(listOf("keep"), database.readClipboardHistory())
                }
                UserDataDatabase.open(root).use { reopened ->
                    assertEquals(listOf("keep"), reopened.readClipboardHistory())
                }
            }
        }
    }

    @Test
    fun processDeathAfterDatabaseCommitRestoresTheUnacknowledgedPreviousState() {
        val root = root()
        try {
            UserDataDatabase.open(root).use { database ->
                database.recordClipboard("keep")
                database.setCommitStageHookForTest { stage ->
                    if (stage == UserDataCommitStage.AFTER_DATABASE_COMMIT) throw SimulatedProcessDeath()
                }
                database.recordClipboard("unacknowledged")
            }
            throw AssertionError("expected simulated process death")
        } catch (_: SimulatedProcessDeath) {
        }
        assertTrue(File(root, UserDataDatabase.COMMIT_JOURNAL_NAME).isFile)
        UserDataDatabase.open(root).use { reopened ->
            assertEquals(UserDataRecoveryKind.LAST_GOOD, reopened.recoveryReport.kind)
            assertEquals(listOf("keep"), reopened.readClipboardHistory())
        }
    }

    @Test
    fun privacyRemainsOffAfterCheckpointAndImmediateRollbackBothFail() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            database.updateSettings(
                mapOf(UserSettingsSchema.CLIPBOARD_HISTORY to StoredSettingValue.Bool(false)),
            )
            database.setCommitStageHookForTest { stage ->
                when (stage) {
                    UserDataCommitStage.BEFORE_CHECKPOINT -> throw IOException("checkpoint ENOSPC")
                    UserDataCommitStage.BEFORE_ROLLBACK -> throw IOException("rollback ENOSPC")
                    else -> Unit
                }
            }
            try {
                database.updateSettings(
                    mapOf(UserSettingsSchema.CLIPBOARD_HISTORY to StoredSettingValue.Bool(true)),
                )
                throw AssertionError("expected durable settings failure")
            } catch (_: IOException) {
            }
        }
        assertTrue(File(root, UserDataDatabase.COMMIT_JOURNAL_NAME).isFile)
        UserDataDatabase.open(root).use { reopened ->
            assertEquals(
                StoredSettingValue.Bool(false),
                reopened.readSetting(UserSettingsSchema.CLIPBOARD_HISTORY),
            )
        }
    }

    @Test
    fun userReadingAndLearningRemovalRollBackTogetherWhenDurabilityFails() {
        val root = root()
        UserDataDatabase.open(root).use { database ->
            assertTrue(UserModel(database = database).addManualWord("ceshi", "测试", 1L))
            database.upsertFormedUsage("测试", "ceshi", StoredUsage(3.0, 2L))
            database.upsertPendingUsage("ceshi", "测试", StoredUsage(2.0, 2L))
            database.upsertFollowUsage("前", "测试", StoredUsage(4.0, 2L))
            val userBefore = database.readUserData()
            val learningBefore = database.readLearning()
            database.setCommitStageHookForTest { stage ->
                if (stage == UserDataCommitStage.BEFORE_CHECKPOINT) throw IOException("ENOSPC")
            }

            try {
                database.removeUserReadingAndLearning("ceshi", "测试")
                throw AssertionError("expected durable removal failure")
            } catch (_: IOException) {
            }
            database.setCommitStageHookForTest(null)
            assertEquals(userBefore, database.readUserData())
            assertEquals(learningBefore, database.readLearning())
        }
    }

    @Test
    fun legacyMigrationIsVerifiedAndIdempotent() {
        val root = root()
        val oldUser = UserModel().apply { addManualWord("ceshi", "测试", 4) }
        val oldLearning = UserLearning()
        repeat(3) {
            oldLearning.observeCommit(null, "张", "zhang", 5)
            oldLearning.observeCommit("张", "伟", "wei", 5)
            oldLearning.observeBreak()
        }
        UserDataDatabase.open(root).use { database ->
            database.migrateLegacy(
                oldUser.storageSnapshot(),
                oldLearning.storageSnapshot(),
                mapOf("userdb" to "fixture-a", "userlearn" to "fixture-b"),
            )
            assertEquals(oldUser.storageSnapshot(), database.readUserData())
            assertEquals(oldLearning.storageSnapshot(), database.readLearning())
            database.migrateLegacy(UserDataSnapshot(emptyMap(), emptyMap(), emptyMap()), null, emptyMap())
            assertEquals(oldUser.storageSnapshot(), database.readUserData())
            assertEquals("complete", database.metadata("beta29_migration"))
        }
    }

    @Test
    fun invalidLegacyDataBlocksTheSwitchAndCanBeRetriedAfterRepair() {
        val root = root()
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("invalid-legacy-${root.name}", 0)
        val legacy = File(root, "userdb.txt")
        legacy.writeText("invalid")

        var failed = false
        try {
            UserDataMigration.open(root, preferences).close()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(legacy.isFile)
        assertTrue(File(root, UserDataMigration.STATUS_NAME).readText().contains("status=failed"))
        UserDataDatabase.open(root).use { database ->
            assertEquals(null, database.metadata("beta29_migration"))
            assertTrue(database.isEmpty())
        }

        UserModel().apply { assertTrue(addManualWord("retry", "重试", 4)); save(legacy) }
        UserDataMigration.open(root, preferences).use { database ->
            assertEquals(listOf("重试"), UserModel(database = database).readingSnapshot()["retry"])
            assertEquals("complete", database.metadata("beta29_migration"))
            assertTrue(File(root, UserDataMigration.STATUS_NAME).readText().contains("status=complete"))
        }
        assertFalse(legacy.exists())
    }

    @Test
    fun everyLegacyMigrationBoundaryRetriesWithoutLossOrReplay() {
        for (interruptedAt in LegacyDataMigrationStage.entries) {
            val root = root()
            val preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("legacy-stage-${root.name}", 0)
            val legacy = File(root, "userdb.txt")
            legacy.writeText("aegis-userdb 1\nW\t重试\t4\t9\nR\tretry\t重试\n")
            var failed = false
            try {
                UserDataMigration.openWithLegacyStage(root, preferences) { stage ->
                    if (stage == interruptedAt) throw IOException("simulated $stage interruption")
                }.close()
            } catch (_: IOException) {
                failed = true
            }
            assertTrue("stage was not reached: $interruptedAt", failed)

            UserDataDatabase.open(root).use { database ->
                val committed = interruptedAt >= LegacyDataMigrationStage.AFTER_DATABASE_COMMIT
                assertEquals(
                    "unexpected marker state after $interruptedAt",
                    if (committed) "complete" else null,
                    database.metadata("beta29_migration"),
                )
                assertEquals(committed, database.hasUserReading("retry", "重试"))
            }
            assertEquals(
                "legacy source lifecycle mismatch after $interruptedAt",
                interruptedAt != LegacyDataMigrationStage.AFTER_LEGACY_CLEANUP,
                legacy.isFile,
            )

            UserDataMigration.open(root, preferences).use { database ->
                assertEquals("complete", database.metadata("beta29_migration"))
                assertTrue(database.hasUserReading("retry", "重试"))
                assertEquals(1L, database.userWordEntryCount())
                assertTrue(database.integrityOk())
                assertTrue(database.foreignKeysOk())
            }
            assertFalse(legacy.exists())
            assertTrue(File(root, UserDataMigration.STATUS_NAME).readText().contains("status=complete"))
        }
    }

    @Test
    fun committedLegacyMigrationNeverReplaysAStaleSourceDuringCleanupRetry() {
        val root = root()
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("legacy-cleanup-gap-${root.name}", 0)
        val legacy = File(root, "userdb.txt")
        legacy.writeText("aegis-userdb 1\nW\t原始\t2\t3\nR\tyuanshi\t原始\n")
        try {
            UserDataMigration.openWithLegacyStage(root, preferences) { stage ->
                if (stage == LegacyDataMigrationStage.BEFORE_LEGACY_CLEANUP) {
                    throw IOException("simulated cleanup interruption")
                }
            }.close()
            throw AssertionError("expected cleanup interruption")
        } catch (_: IOException) {
        }

        UserDataDatabase.open(root).use { database ->
            assertTrue(UserModel(database = database).addManualWord("new", "新值", 10L))
        }
        legacy.writeText("aegis-userdb 1\nW\t陈旧\t9\t9\nR\tchenjiu\t陈旧\n")

        UserDataMigration.open(root, preferences).use { database ->
            assertTrue(database.hasUserReading("yuanshi", "原始"))
            assertTrue(database.hasUserReading("new", "新值"))
            assertFalse(database.hasUserReading("chenjiu", "陈旧"))
            assertEquals(2L, database.userWordEntryCount())
        }
        assertFalse(legacy.exists())
    }

    @Test
    fun beta29CollectionsMigrateWithoutOldCountLimitsAndRemainIdempotent() {
        val root = root()
        val history = (0..200_000).map { "clip-$it" }
        File(root, "clipboard.txt").writeText(history.joinToString("\n"))
        File(root, "phrases.txt").writeText("C\tdefault\nP\tlegacy phrase\nC\twork\nP\tlegacy work\n")
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("collection-migration-${root.name}", 0)
        val customSymbols = (0 until 401).map { "symbol-$it" }
        val customOperators = (0 until 407).map { "operator-$it" }
        assertTrue(
            preferences.edit()
                .putString("custom_symbols", customSymbols.joinToString("\n"))
                .putString("custom_operators", customOperators.joinToString("\n"))
                .commit(),
        )
        File(root, "symbol_usage.txt").writeText((0 until 91).joinToString("\n") { "recent-$it\tgroup-$it" })
        val emojiRoot = File(root, "emoji").apply { mkdirs() }
        File(emojiRoot, "symbol_usage.txt").writeText((0 until 93).joinToString("\n") { "emoji-$it" })

        UserDataMigration.open(root, preferences).use { database ->
            assertEquals(200_001L, database.clipboardHistoryCount())
            assertEquals(history.take(3), database.readClipboardHistory(limit = 3))
            assertEquals(history.subList(99_998, 100_002), database.readClipboardHistory(99_998, 4))
            assertEquals(history.takeLast(3), database.readClipboardHistory(199_998, 3))
            assertEquals(customSymbols, database.readCustomItems("custom_symbols"))
            assertEquals(customSymbols.subList(198, 203), database.readCustomItems("custom_symbols", 198, 5))
            assertEquals(customOperators, database.readCustomItems("custom_operators"))
            assertEquals(customOperators.subList(198, 203), database.readCustomItems("custom_operators", 198, 5))
            assertEquals((0 until 91).map { "recent-$it" }, database.readRecentItems("symbols").map { it.value })
            assertEquals((28 until 33).map { "recent-$it" }, database.readRecentItems("symbols", 28, 5).map { it.value })
            assertEquals((0 until 93).map { "emoji-$it" }, database.readRecentItems("emoji").map { it.value })
            assertEquals((28 until 33).map { "emoji-$it" }, database.readRecentItems("emoji", 28, 5).map { it.value })
            assertEquals(listOf("default", "work"), database.readPhraseCategories().map { it.name })
            assertEquals("complete", database.metadata("beta29_clipboard_migration"))
            assertTrue(database.integrityOk())
        }

        assertFalse(File(root, "clipboard.txt").exists())
        assertFalse(File(root, "phrases.txt").exists())
        File(root, "clipboard.txt").writeText("late-legacy-change\n")
        preferences.edit().putString("custom_symbols", "late-legacy-change").commit()

        UserDataMigration.open(root, preferences).use { database ->
            assertEquals(200_001L, database.clipboardHistoryCount())
            assertFalse(database.containsClipboard("late-legacy-change"))
            assertEquals(customSymbols, database.readCustomItems("custom_symbols"))
        }
        assertFalse(File(root, "clipboard.txt").exists())
        assertFalse(preferences.contains("custom_symbols"))
    }

    @Test
    fun learningTablesCrossEveryFormerFixedCountMilestone() {
        val formedCap = 500
        val pendingCap = 2_000
        val followPreviousCap = 1_500
        val followPerPreviousCap = 8
        val formed = LinkedHashMap<String, Map<String, StoredUsage>>()
        repeat(formedCap * 4) { index ->
            formed["成熟$index"] = mapOf("formed$index" to StoredUsage(3.0, index.toLong()))
        }
        val pending = LinkedHashMap<Pair<String, String>, StoredUsage>()
        repeat(pendingCap * 4) { index ->
            pending["pending$index" to "待成熟$index"] = StoredUsage(1.0, index.toLong())
        }
        val follows = LinkedHashMap<String, Map<String, StoredUsage>>()
        repeat(followPreviousCap * 4) { index ->
            val successors = if (index == 0) {
                (0 until followPerPreviousCap * 4).associate { successor ->
                    "后续$successor" to StoredUsage(2.0, successor.toLong())
                }
            } else {
                mapOf("后续$index" to StoredUsage(2.0, index.toLong()))
            }
            follows["前项$index"] = successors
        }

        UserDataDatabase.open(root()).use { database ->
            database.replaceLearning(UserLearningSnapshot(formed, pending, follows))
            val stored = database.readLearning()
            assertEquals(formedCap * 4, stored.formed.size)
            assertEquals(pendingCap * 4, stored.pending.size)
            assertEquals(followPreviousCap * 4, stored.follows.size)
            assertEquals(followPerPreviousCap * 4, stored.follows.getValue("前项0").size)
            for (size in listOf(formedCap - 1, formedCap, formedCap + 1, formedCap * 4)) {
                assertTrue(stored.formed.containsKey("成熟${size - 1}"))
            }
            for (size in listOf(pendingCap - 1, pendingCap, pendingCap + 1, pendingCap * 4)) {
                assertTrue(stored.pending.containsKey("pending${size - 1}" to "待成熟${size - 1}"))
            }
            for (size in listOf(followPreviousCap - 1, followPreviousCap, followPreviousCap + 1, followPreviousCap * 4)) {
                assertTrue(stored.follows.containsKey("前项${size - 1}"))
            }
            for (size in listOf(
                followPerPreviousCap - 1,
                followPerPreviousCap,
                followPerPreviousCap + 1,
                followPerPreviousCap * 4,
            )) {
                assertTrue(stored.follows.getValue("前项0").containsKey("后续${size - 1}"))
            }
        }
    }

    @Test
    fun databaseBackedCollectionsSupportManagementAndPagedReadsBeyondOldLimits() {
        val root = root()
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("collection-management-${root.name}", 0)
        UserDataDatabase.open(root).use { database ->
            val clipboard = ClipboardStore(root, database).apply { load() }
            val history = (0 until 320).map { "clip-$it" }
            assertTrue(clipboard.importHistory(history, merge = false))
            assertTrue(clipboard.record("clip-200"))
            assertEquals(listOf("clip-200", "clip-0", "clip-1"), clipboard.historyPage(0, 3))
            assertEquals(history.subList(49, 59), clipboard.historyPage(50, 10))
            assertTrue(clipboard.delete("clip-250"))
            assertFalse(clipboard.history().contains("clip-250"))

            assertTrue(clipboard.addCategory("large"))
            assertEquals(240, clipboard.addPhrasesTo("large", (0 until 240).map { "phrase-$it" }))
            assertEquals((100 until 110).map { "phrase-$it" }, clipboard.phrasesPage("large", 100, 10))

            val custom = CustomSymbolStore(preferences, "custom_symbols", database)
            val expectedCustom = (0 until 240).map { "custom-$it" }
            expectedCustom.forEach { assertTrue(custom.add(it)) }
            val allCustom = ArrayList<String>()
            var customVersion: Long? = null
            while (allCustom.size < expectedCustom.size) {
                val page = custom.pageSnapshot(allCustom.size, 53, customVersion)
                assertFalse(page.restartRequired)
                if (customVersion == null) customVersion = page.version else assertEquals(customVersion, page.version)
                assertTrue(page.items.isNotEmpty())
                allCustom.addAll(page.items)
            }
            assertEquals(expectedCustom, allCustom)
            assertEquals(expectedCustom.size, allCustom.toSet().size)
            val runtimeCustom = custom.pagedList()
            assertEquals(expectedCustom.size, runtimeCustom.size)
            assertEquals(expectedCustom, runtimeCustom.indices.map(runtimeCustom::get))
            assertEquals(240L, custom.count())
            assertEquals((200 until 210).map { "custom-$it" }, custom.page(200, 10))
            assertTrue(custom.remove("custom-205"))

            val recent = SymbolUsageStore(root, database, "symbols").apply { load() }
            repeat(80) { assertTrue(recent.record("recent-$it", "group-$it")) }
            assertEquals(80, recent.recent().size)
            assertEquals((59 downTo 50).map { "recent-$it" }, recent.recentPage(20, 10).map { it.symbol })
            assertTrue(recent.record("recent-20", "new-origin"))
            assertEquals("recent-20", recent.recent().first())
            assertEquals("new-origin", recent.originOf("recent-20"))
            database.checkpointLastGood()
        }

        UserDataDatabase.open(root).use { database ->
            assertEquals(319L, database.clipboardHistoryCount())
            assertEquals("clip-200", database.readClipboardHistory(limit = 1).single())
            assertEquals(239, database.readCustomItems("custom_symbols").size)
            assertEquals(80, database.readRecentItems("symbols").size)
            assertEquals("recent-20", database.readRecentItems("symbols", limit = 1).single().value)
            assertTrue(database.integrityOk())
        }
    }

    @Test
    fun failedCollectionWritesKeepTheLastValidState() {
        val root = root()
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("collection-failure-${root.name}", 0)
        val database = UserDataDatabase.open(root)
        val clipboard = ClipboardStore(root, database).apply { load() }
        val custom = CustomSymbolStore(preferences, "custom_symbols", database)
        val recent = SymbolUsageStore(root, database, "symbols").apply { load() }
        assertTrue(clipboard.record("kept clip"))
        assertEquals(1, clipboard.addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("kept phrase")))
        assertTrue(custom.add("kept symbol"))
        assertTrue(recent.record("kept recent", "kept origin"))
        database.close()

        assertFalse(clipboard.record("lost clip"))
        assertEquals(0, clipboard.addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("lost phrase")))
        assertFalse(custom.add("lost symbol"))
        assertFalse(recent.record("lost recent"))
        assertEquals(listOf("kept clip"), clipboard.history())
        assertEquals(listOf("kept phrase"), clipboard.phrasesIn(ClipboardStore.DEFAULT_CATEGORY_ID))
        assertEquals(listOf("kept symbol"), custom.list())
        assertEquals(listOf("kept recent"), recent.recent())
        assertNotNull(clipboard.lastFailure)
        assertNotNull(custom.lastFailure)
        assertNotNull(recent.lastFailure)

        UserDataDatabase.open(root).use { reopened ->
            assertEquals(listOf("kept clip"), reopened.readClipboardHistory())
            assertEquals(listOf("kept phrase"), reopened.readPhrases(ClipboardStore.DEFAULT_CATEGORY_ID).map { it.text })
            assertEquals(listOf("kept symbol"), reopened.readCustomItems("custom_symbols"))
            assertEquals(listOf("kept recent"), reopened.readRecentItems("symbols").map { it.value })
        }
    }

    @Test
    fun restoreCheckpointFailureRollsBackTheCommittedDatabaseState() {
        val sourceRoot = root()
        val stagingRoot = root()
        val sourceSnapshot = File(stagingRoot, "source.db")
        UserDataDatabase.open(sourceRoot).use { database ->
            assertTrue(UserModel(database = database).addManualWord("backup", "备份", 1))
            database.exportSnapshot(sourceSnapshot)
        }

        val targetRoot = root()
        UserDataDatabase.open(targetRoot).use { database ->
            assertTrue(UserModel(database = database).addManualWord("local", "本机", 2))
            database.checkpointLastGood()
            val expected = database.readUserData()
            File(targetRoot, UserDataDatabase.LAST_GOOD_NAME).apply {
                delete()
                assertTrue(mkdir())
                File(this, "blocker").writeText("x")
            }

            var failed = false
            try {
                database.restoreFrom(sourceSnapshot, merge = false)
            } catch (_: IOException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(expected, database.readUserData())
            assertTrue(database.integrityOk())
            assertTrue(database.foreignKeysOk())
        }
    }
}
