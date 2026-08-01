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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserDataDatabaseTest {

    private fun root() = Files.createTempDirectory("aegis-user-data").toFile().also { it.deleteOnExit() }

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
}
