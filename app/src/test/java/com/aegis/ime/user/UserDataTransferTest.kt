// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
// A PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.user

import android.database.sqlite.SQLiteFullException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserDataTransferTest {

    @Before
    fun resetHotHost() {
        UserDictHot.host = null
    }

    private fun newDir(): File = Files.createTempDirectory("user-transfer").toFile()

    @Test
    fun freshInstallExportsTheLatestSQLiteDictionaryInCompatibleFormat() {
        val root = newDir()
        UserDataMigration.open(root).use { database ->
            val model = UserModel(database = database)
            assertTrue(model.addManualWord("nihao", "你好", 1_000L))
            assertTrue(model.addManualWord("shijie", "世界", 1_001L))
            assertTrue(model.record("你好", "世界", 1_002L))
        }
        val legacy = File(root, "userdb.txt")
        assertFalse(legacy.exists())

        val output = ByteArrayOutputStream()
        assertTrue(UserDictEdit.export(legacy, output))
        val rows = parse(output.toByteArray())

        assertTrue(rows.contains(UserDataTransfer.UserDictionaryRow.Word("你好", 1, 1_000L)))
        assertTrue(rows.contains(UserDataTransfer.UserDictionaryRow.Word("世界", 2, 1_002L)))
        assertTrue(rows.contains(UserDataTransfer.UserDictionaryRow.Reading("nihao", "你好")))
        assertTrue(rows.contains(UserDataTransfer.UserDictionaryRow.Reading("shijie", "世界")))
        assertTrue(rows.contains(UserDataTransfer.UserDictionaryRow.Bigram("你好", "世界", 1)))
    }

    @Test
    fun migratedInstallExportNeverCopiesAStaleLegacyUserdb() {
        val root = newDir()
        val legacy = File(root, "userdb.txt").apply {
            writeText("aegis-userdb 1\nW\t旧词\t9\t10\nR\told\t旧词\n")
        }
        UserDataMigration.open(root).use { database ->
            database.removeWord("旧词")
            assertTrue(UserModel(database = database).addManualWord("new", "新词", 2_000L))
        }
        legacy.writeText("aegis-userdb 1\nW\t旧词\t9\t10\nR\told\t旧词\n")
        assertTrue(legacy.readText().contains("旧词"))

        val output = ByteArrayOutputStream()
        assertTrue(UserDictEdit.export(legacy, output))
        val rows = parse(output.toByteArray())

        assertTrue(rows.contains(UserDataTransfer.UserDictionaryRow.Word("新词", 1, 2_000L)))
        assertTrue(rows.none { row ->
            row is UserDataTransfer.UserDictionaryRow.Word && row.word == "旧词"
        })
    }

    @Test
    fun corruptAndIoFailedDictionaryImportsLeaveTheOriginalUntouched() {
        val root = newDir()
        UserDataMigration.open(root).use { database ->
            assertTrue(UserModel(database = database).addManualWord("keep", "保留", 1L))
            val original = database.readUserData()
            val corrupt = "aegis-userdb 1\nW\t新词\t1\t2\nR\tnew\t不存在\n".toByteArray()
            runCatching { database.importUserDictionary(ByteArrayInputStream(corrupt), merge = false) }
                .onSuccess { fail("expected corrupt import") }
            assertEquals(original, database.readUserData())

            val valid = "aegis-userdb 1\nW\t新词\t1\t2\nR\tnew\t新词\n".toByteArray()
            runCatching {
                database.importUserDictionary(FailingInputStream(valid, valid.size - 3), merge = false)
            }.onSuccess { fail("expected input failure") }
            assertEquals(original, database.readUserData())
        }
    }

    @Test
    fun streamingMergeAccumulatesWithSaturationAndKeepsExistingWords() {
        val root = newDir()
        val initial = "aegis-userdb 1\nW\t旧词\t1\t1\nR\told\t旧词\nW\t词\t999999999\t1\n".toByteArray()
        val incoming = "aegis-userdb 1\nW\t新词\t1\t2\nR\tnew\t新词\nW\t词\t10\t2\n".toByteArray()
        UserDataMigration.open(root).use { database ->
            assertTrue(database.importUserDictionary(ByteArrayInputStream(initial), merge = false))
            assertTrue(database.importUserDictionary(ByteArrayInputStream(incoming), merge = true))
            assertEquals(setOf("旧词", "新词", "词"), database.readUserData().words.keys)
            assertEquals(StoredWord(1_000_000_000, 2L), database.readStoredWord("词"))
        }
    }

    @Test
    fun stagedDictionaryImportRollsBackOnEnospcAndEveryReportedInterruption() {
        val root = newDir()
        val incoming = "aegis-userdb 1\nW\t新词\t1\t2\nR\tnew\t新词\n".toByteArray()
        UserDataMigration.open(root).use { database ->
            assertTrue(UserModel(database = database).addManualWord("keep", "保留", 1L))
            val original = database.readUserData()
            for (failureStage in UserDataTransferStage.entries) {
                try {
                    database.importUserDictionary(ByteArrayInputStream(incoming), merge = false) { stage ->
                        if (stage == failureStage) {
                            if (stage == UserDataTransferStage.AFTER_VALIDATION) {
                                throw SQLiteFullException("database or disk is full")
                            }
                            throw IOException("interrupted")
                        }
                    }
                    fail("expected import failure at $failureStage")
                } catch (_: Exception) {
                    assertEquals(original, database.readUserData())
                }
            }
            assertTrue(database.importUserDictionary(ByteArrayInputStream(incoming), merge = false))
            assertEquals(setOf("新词"), database.readUserData().words.keys)
        }
    }

    @Test
    fun veryLargeDictionaryUsesStreamingInputAndCursorOutput() {
        val root = newDir()
        val source = File(root, "large-userdb.txt")
        source.bufferedWriter().use { writer ->
            writer.write("aegis-userdb 1\n")
            repeat(20_000) { index -> writer.write("W\t词$index\t1\t$index\n") }
            repeat(20_000) { index -> writer.write("R\tbulk\t词$index\n") }
        }
        UserDataMigration.open(root).use { database ->
            source.inputStream().use { input -> assertTrue(database.importUserDictionary(input, merge = false)) }
            assertEquals(20_000L, database.userWordEntryCount())
            val output = CountingOutputStream()
            database.writeUserDictionary(output)
            assertTrue(output.count > 500_000L)
        }
    }

    @Test
    fun streamingValidationCrossesEveryOldTwoHundredFiftyThousandRowMilestone() {
        val milestones = listOf(249_999L, 250_000L, 250_001L, 500_000L)
        val observed = ArrayList<Long>()
        var accepted = 0L
        val total = UserDataTransfer.readUserDictionary(GeneratedRowsInputStream(500_000)) {
            accepted++
            if (accepted in milestones) observed.add(accepted)
        }
        assertEquals(milestones, observed)
        assertEquals(500_000L, total)
    }

    @Test
    fun validRowsCrossEveryOldFourThousandNinetySixCharacterMilestone() {
        for (lineLength in listOf(4_095, 4_096, 4_097, 16_384)) {
            val word = "界".repeat(lineLength - 6)
            val input = "${UserDataTransfer.USER_DICTIONARY_HEADER}\nW\t$word\t1\t1\n".byteInputStream()
            var accepted: UserDataTransfer.UserDictionaryRow? = null
            assertEquals(1L, UserDataTransfer.readUserDictionary(input) { accepted = it })
            assertEquals(UserDataTransfer.UserDictionaryRow.Word(word, 1, 1L), accepted)
            assertEquals(lineLength, "W\t$word\t1\t1".length)
        }
    }

    @Test
    fun overlongTransferRowIsRejectedWithoutChangingData() {
        val root = newDir()
        UserDataMigration.open(root).use { database ->
            assertTrue(UserModel(database = database).addManualWord("keep", "保留", 1L))
            val original = database.readUserData()
            val line = "W\t" + "x".repeat(UserDataTransfer.MAX_LINE_CHARS + 1) + "\t1\t1\n"
            val input = ("aegis-userdb 1\n" + line).byteInputStream()
            runCatching { database.importUserDictionary(input, merge = false) }
                .onSuccess { fail("expected bounded-line rejection") }
            assertEquals(original, database.readUserData())
        }
    }

    @Test
    fun concurrentReadersSeeTheOldOrNewSnapshotAndWritersSerializeAfterImport() {
        val root = newDir()
        UserDataMigration.open(root).use { database ->
            assertTrue(UserModel(database = database).addManualWord("old", "旧词", 1L))
        }
        val importer = UserDataDatabase.open(root)
        val reader = UserDataDatabase.open(root)
        val writer = UserDataDatabase.open(root)
        val validated = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val incoming = "aegis-userdb 1\nW\t新词\t1\t2\nR\tnew\t新词\n".toByteArray()
            val importFuture = executor.submit<Boolean> {
                importer.importUserDictionary(ByteArrayInputStream(incoming), merge = false) { stage ->
                    if (stage == UserDataTransferStage.AFTER_VALIDATION) {
                        validated.countDown()
                        assertTrue(release.await(10, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(validated.await(10, TimeUnit.SECONDS))
            assertEquals(setOf("旧词"), reader.readUserData().words.keys)
            val writerFuture = executor.submit {
                writerStarted.countDown()
                writer.recordWord("并发词", "concurrent", null, 3L, true)
            }
            assertTrue(writerStarted.await(10, TimeUnit.SECONDS))
            assertFalse(writerFuture.isDone)
            release.countDown()
            assertTrue(importFuture.get(10, TimeUnit.SECONDS))
            writerFuture.get(10, TimeUnit.SECONDS)
            assertEquals(setOf("新词", "并发词"), reader.readUserData().words.keys)
        } finally {
            release.countDown()
            executor.shutdownNow()
            importer.close()
            reader.close()
            writer.close()
        }
    }

    private fun parse(bytes: ByteArray): List<UserDataTransfer.UserDictionaryRow> {
        val rows = ArrayList<UserDataTransfer.UserDictionaryRow>()
        UserDataTransfer.readUserDictionary(ByteArrayInputStream(bytes), rows::add)
        return rows
    }

    private class GeneratedRowsInputStream(private val rowCount: Int) : InputStream() {
        private var nextRow = 0
        private var chunk = (UserDataTransfer.USER_DICTIONARY_HEADER + "\n").toByteArray()
        private var chunkOffset = 0
        private val oneByte = ByteArray(1)

        private fun advance(): Boolean {
            if (nextRow >= rowCount) return false
            chunk = "W\t词$nextRow\t1\t$nextRow\n".toByteArray()
            chunkOffset = 0
            nextRow++
            return true
        }

        override fun read(): Int {
            val count = read(oneByte, 0, 1)
            return if (count < 0) -1 else oneByte[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            var written = 0
            while (written < length) {
                if (chunkOffset >= chunk.size && !advance()) break
                val count = minOf(length - written, chunk.size - chunkOffset)
                chunk.copyInto(buffer, offset + written, chunkOffset, chunkOffset + count)
                chunkOffset += count
                written += count
            }
            return if (written == 0) -1 else written
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

    private class CountingOutputStream : OutputStream() {
        var count = 0L
            private set

        override fun write(value: Int) {
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            count += length
        }
    }
}
