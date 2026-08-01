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

package com.aegis.ime.ui

import android.database.sqlite.SQLiteFullException
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.UserDataMigration
import com.aegis.ime.user.UserDataTransferStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhraseTransferIoTest {

    private fun newDir(): File = Files.createTempDirectory("phrase-transfer").toFile()

    @Test fun exportPhrases_reloads_persisted_store_and_writes_category_phrase_data() {
        val dir = newDir()
        val staleStore = ClipboardStore(dir).apply { load() }
        assertFalse(staleStore.categories().contains("工作"))

        ClipboardStore(dir).apply {
            load()
            addCategory("工作")
            addPhrasesTo("工作", listOf("已收到"))
            setPhraseNote("工作", "已收到", "回执")
        }

        val out = ByteArrayOutputStream()
        assertTrue(PhraseTransferIo.exportPhrases(dir) { out })

        val text = String(out.toByteArray(), Charsets.UTF_8)
        assertTrue("export wrote bytes", out.size() > 0)
        assertTrue("export includes category", text.contains("C\t工作\n"))
        assertTrue("export includes phrase", text.contains("P\t已收到\n"))
        assertTrue("export includes note", text.contains("N\t回执\n"))
    }

    @Test fun exportPhrases_treats_null_output_as_failure() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("已收到")) }

        assertFalse(PhraseTransferIo.exportPhrases(dir) { null })
    }

    @Test fun exportPhrases_treats_unwritable_output_as_failure() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("已收到")) }

        assertFalse(PhraseTransferIo.exportPhrases(dir) { FailingOutputStream() })
    }

    @Test fun streamingRoundTripPreservesEscapesNotesAndAcceptsCrlf() {
        val source = newDir()
        UserDataMigration.open(source).use { database ->
            val store = ClipboardStore(source, database).apply { load() }
            assertTrue(store.addCategory("Work"))
            assertEquals(2, store.addPhrasesTo("Work", listOf("line1\nline2", "slash\\value")))
            assertTrue(store.setPhraseNote("Work", "line1\nline2", "note\\next"))
        }
        val output = ByteArrayOutputStream()
        assertTrue(PhraseTransferIo.exportPhrases(source) { output })
        val crlf = output.toString(Charsets.UTF_8.name()).replace("\n", "\r\n").toByteArray()

        val destination = newDir()
        assertTrue(PhraseTransferIo.importPhrases(destination, null, ByteArrayInputStream(crlf), merge = false))
        UserDataMigration.open(destination).use { database ->
            assertEquals(
                listOf("line1\nline2", "slash\\value"),
                database.readPhrases("Work", 0, 10).map { it.text },
            )
            assertEquals("note\\next", database.phraseNote("Work", "line1\nline2"))
        }
    }

    @Test fun streamingMergeDeduplicatesAndOverwriteCanonicalizesLegacyDefault() {
        val dir = newDir()
        UserDataMigration.open(dir).use { database ->
            val store = ClipboardStore(dir, database).apply { load() }
            assertTrue(store.addCategory("工作"))
            assertEquals(2, store.addPhrasesTo("工作", listOf("本机", "共同")))
            assertTrue(store.setPhraseNote("工作", "共同", "本机注"))
        }
        val merge = "C\t工作\nP\t共同\nN\t备份注\nP\t新增\nC\t工作\nP\t再次新增\n".byteInputStream()
        assertTrue(PhraseTransferIo.importPhrases(dir, null, merge, merge = true))
        UserDataMigration.open(dir).use { database ->
            assertEquals(
                listOf("本机", "共同", "新增", "再次新增"),
                database.readPhrases("工作", 0, 10).map { it.text },
            )
            assertEquals("本机注", database.phraseNote("工作", "共同"))
        }

        val overwrite = "C\t默认\nP\t备份一\nN\t一注\nC\t默认\nP\t备份二\nP\t备份一\nN\t不覆盖\n".byteInputStream()
        assertTrue(PhraseTransferIo.importPhrases(dir, null, overwrite, merge = false))
        UserDataMigration.open(dir).use { database ->
            assertFalse(database.phraseCategoryExists("默认"))
            assertEquals(
                listOf("备份一", "备份二"),
                database.readPhrases(ClipboardStore.DEFAULT_CATEGORY_ID, 0, 10).map { it.text },
            )
            assertEquals("一注", database.phraseNote(ClipboardStore.DEFAULT_CATEGORY_ID, "备份一"))
        }
    }

    @Test fun emptyGarbageAndBlankCategoryImportsNeverClearExistingPhrases() {
        val dir = newDir()
        UserDataMigration.open(dir).use { database ->
            val store = ClipboardStore(dir, database).apply { load() }
            assertTrue(store.addCategory("原数据"))
            assertEquals(1, store.addPhrasesTo("原数据", listOf("保留")))
        }
        for (invalid in listOf("", "\n  \n", "garbage\n", "C\t\nP\tbad\n")) {
            assertFalse(PhraseTransferIo.importPhrases(dir, null, invalid.byteInputStream(), merge = false))
        }
        UserDataMigration.open(dir).use { database ->
            assertEquals(listOf("保留"), database.readPhrases("原数据", 0, 10).map { it.text })
        }
    }

    @Test fun corruptAndIoInterruptedImportsPreserveTheOriginalDatabase() {
        val dir = newDir()
        UserDataMigration.open(dir).use { database ->
            val store = ClipboardStore(dir, database).apply { load() }
            assertTrue(store.addCategory("原数据"))
            assertEquals(1, store.addPhrasesTo("原数据", listOf("保留")))
        }
        val corrupt = "C\t新组\nP\t新短语\ninvalid\n".toByteArray()
        assertFalse(PhraseTransferIo.importPhrases(dir, null, ByteArrayInputStream(corrupt), merge = false))
        assertFalse(
            PhraseTransferIo.importPhrases(
                dir,
                null,
                FailingInputStream("C\t新组\nP\t新短语\n".toByteArray(), 9),
                merge = false,
            ),
        )
        UserDataMigration.open(dir).use { database ->
            assertEquals(listOf("保留"), database.readPhrases("原数据", 0, 10).map { it.text })
            assertFalse(database.phraseCategoryExists("新组"))
        }
    }

    @Test fun largePhraseLibraryImportsAndExportsWithoutWholeFileAggregation() {
        val dir = newDir()
        val source = File(dir, "large-phrases.txt")
        source.bufferedWriter().use { writer ->
            writer.write("C\t大集合\n")
            repeat(12_000) { index -> writer.write("P\t短语-$index\n") }
        }
        source.inputStream().use { input ->
            assertTrue(PhraseTransferIo.importPhrases(dir, null, input, merge = false))
        }
        UserDataMigration.open(dir).use { database -> assertEquals(12_000L, database.phraseCount("大集合")) }
        val output = CountingOutputStream()
        assertTrue(PhraseTransferIo.exportPhrases(dir) { output })
        assertTrue(output.count > 100_000L)
    }

    @Test fun stagedPhraseImportRollsBackOnEnospcAndEveryReportedInterruption() {
        val dir = newDir()
        val incoming = "C\t新组\nP\t新短语\n".toByteArray()
        UserDataMigration.open(dir).use { database ->
            val store = ClipboardStore(dir, database).apply { load() }
            assertTrue(store.addCategory("原数据"))
            assertEquals(1, store.addPhrasesTo("原数据", listOf("保留")))
            for (failureStage in listOf(
                UserDataTransferStage.AFTER_VALIDATION,
                UserDataTransferStage.BEFORE_DATABASE_COMMIT,
            )) {
                try {
                    database.importPhrases(ByteArrayInputStream(incoming), merge = false) { stage ->
                        if (stage == failureStage) {
                            if (stage == UserDataTransferStage.AFTER_VALIDATION) {
                                throw SQLiteFullException("database or disk is full")
                            }
                            throw IOException("interrupted")
                        }
                    }
                    fail("expected staged import failure at $failureStage")
                } catch (_: Exception) {
                    assertEquals(listOf("保留"), database.readPhrases("原数据", 0, 10).map { it.text })
                    assertFalse(database.phraseCategoryExists("新组"))
                }
            }
            try {
                database.importPhrases(ByteArrayInputStream(incoming), merge = false) { stage ->
                    if (stage == UserDataTransferStage.AFTER_DATABASE_COMMIT) throw IOException("interrupted")
                }
                fail("expected post-commit interruption")
            } catch (_: IOException) {
                assertEquals(listOf("保留"), database.readPhrases("原数据", 0, 10).map { it.text })
                assertFalse(database.phraseCategoryExists("新组"))
            }
            assertTrue(database.importPhrases(ByteArrayInputStream(incoming), merge = false))
            assertFalse(database.phraseCategoryExists("原数据"))
            assertEquals(listOf("新短语"), database.readPhrases("新组", 0, 10).map { it.text })
        }
    }

    private class FailingOutputStream : OutputStream() {
        override fun write(b: Int) {
            throw IOException("write failed")
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            throw IOException("write failed")
        }
    }

    private class FailingInputStream(
        private val bytes: ByteArray,
        private val failAfter: Int,
    ) : java.io.InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= failAfter) throw IOException("read failed")
            return if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= failAfter) throw IOException("read failed")
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
