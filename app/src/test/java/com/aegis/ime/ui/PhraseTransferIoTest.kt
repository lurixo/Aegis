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

import com.aegis.ime.R
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.UnreadablePhrasesException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

class PhraseTransferIoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newDir(): File = tmp.newFolder()

    @After fun releaseTheLiveStore() {
        LiveUserData.clipboardHost = null
    }

    @Test fun exportPhrases_reloads_persisted_store_and_writes_category_phrase_data() {
        val dir = newDir()
        val staleStore = ClipboardStore(dir).apply { load() }
        assertFalse(staleStore.exportPhrasesText().contains("工作"))

        ClipboardStore(dir).apply {
            load()
            addCategory("工作")
            addPhrasesTo("工作", listOf("已收到"))
            setPhraseNote("工作", "已收到", "回执")
            flushPendingWrites()
        }

        val out = ByteArrayOutputStream()
        assertEquals(
            R.string.phrase_transfer_toast_export_ok,
            phraseExportMessage(PhraseTransferIo.exportPhrases(dir) { out }),
        )

        val text = String(out.toByteArray(), Charsets.UTF_8)
        assertTrue("export wrote bytes", out.size() > 0)
        assertTrue("export includes category", text.contains("C\t工作\n"))
        assertTrue("export includes phrase", text.contains("P\t已收到\n"))
        assertTrue("export includes note", text.contains("N\t回执\n"))
    }

    @Test fun exportPhrases_treats_null_output_as_failure() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("已收到")); flushPendingWrites() }

        assertEquals(
            "a file that could not be opened is a file that could not be written",
            R.string.phrase_transfer_toast_export_write_failed,
            phraseExportMessage(PhraseTransferIo.exportPhrases(dir) { null }),
        )
    }

    @Test fun exportPhrases_treats_unwritable_output_as_failure() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("已收到")); flushPendingWrites() }

        assertEquals(
            R.string.phrase_transfer_toast_export_write_failed,
            phraseExportMessage(PhraseTransferIo.exportPhrases(dir) { FailingOutputStream() }),
        )
    }

    @Test fun exportPhrases_treats_a_phrase_list_nobody_could_read_as_failure() {
        val dir = newDir()
        val file = File(dir, "phrases.txt").apply { writeText("C\t工作\nP\t读不出来的常用语\n") }
        assertTrue("precondition: the phrase file cannot be read back", file.setReadable(false, false))
        try {
            val out = ByteArrayOutputStream()
            assertEquals(
                "an export that never read the phrases must not be reported as an unwritable file",
                R.string.phrase_transfer_toast_export_store_unreadable,
                phraseExportMessage(PhraseTransferIo.exportPhrases(dir) { out }),
            )
            assertEquals("nothing may be handed to the picked document", 0, out.size())
        } finally {
            file.setReadable(true, false)
        }
    }

    @Test fun an_export_of_a_phrase_list_nobody_could_read_says_which_failure_it_was() {
        assertEquals(
            R.string.phrase_transfer_toast_export_store_unreadable,
            phraseExportMessage(Result.failure(UnreadablePhrasesException())),
        )
        assertEquals(
            R.string.phrase_transfer_toast_export_write_failed,
            phraseExportMessage(Result.failure(IOException("the picked document rejected the write"))),
        )
        assertEquals(
            R.string.phrase_transfer_toast_export_empty,
            phraseExportMessage(Result.success(false)),
        )
        assertEquals(
            R.string.phrase_transfer_toast_export_ok,
            phraseExportMessage(Result.success(true)),
        )
    }

    @Test fun an_import_over_a_phrase_list_nobody_could_read_says_which_failure_it_was() {
        assertEquals(
            R.string.phrase_transfer_toast_import_store_unreadable,
            phraseImportMessage(Result.failure(UnreadablePhrasesException()), merge = true),
        )
        assertEquals(
            R.string.phrase_transfer_toast_import_write_failed,
            phraseImportMessage(Result.failure(IOException("phrases.txt could not be replaced")), merge = true),
        )
        assertEquals(
            R.string.phrase_transfer_toast_import_invalid,
            phraseImportMessage(Result.success(false), merge = true),
        )
        assertEquals(
            R.string.phrase_transfer_toast_import_merged,
            phraseImportMessage(Result.success(true), merge = true),
        )
        assertEquals(
            R.string.phrase_transfer_toast_import_overwritten,
            phraseImportMessage(Result.success(true), merge = false),
        )
    }

    @Test fun an_import_that_could_not_be_saved_is_not_reported_as_one_that_could_not_be_read() {
        val dir = newDir()
        val store = ClipboardStore(dir).apply { load() }
        try {
            assertTrue("precondition: the saved phrases can be read", store.phrasesReadable)
            assertTrue(
                "precondition: the write cannot reach the disk",
                store.tempFileFor(File(dir, "phrases.txt")).mkdir(),
            )

            val outcome = runCatching { store.importPhrasesText("C\t工作\nP\t已收到\n", merge = false) }

            assertTrue("precondition: the import really did fail", outcome.isFailure)
            assertEquals(
                R.string.phrase_transfer_toast_import_write_failed,
                phraseImportMessage(outcome, merge = false),
            )
        } finally {
            store.stopSaving()
        }
    }

    @Test fun an_import_over_phrases_that_could_not_be_read_still_names_the_read() {
        val dir = newDir()
        val file = File(dir, "phrases.txt").apply { writeText("C\t工作\nP\t读不出来的常用语\n") }
        assertTrue("precondition: the phrase file cannot be read back", file.setReadable(false, false))
        val store = ClipboardStore(dir).apply { load() }
        try {
            assertFalse("precondition: the store knows it could not read the phrases", store.phrasesReadable)

            val outcome = runCatching { store.importPhrasesText("C\t工作\nP\t新的\n", merge = true) }

            assertTrue("precondition: the import really did fail", outcome.isFailure)
            assertEquals(
                R.string.phrase_transfer_toast_import_store_unreadable,
                phraseImportMessage(outcome, merge = true),
            )
        } finally {
            store.stopSaving()
            file.setReadable(true, false)
        }
    }

    @Test fun a_phrase_export_reads_through_the_store_that_owns_the_file() {
        val dir = newDir()
        val live = ClipboardStore(dir).apply {
            load()
            addCategory("工作")
            addPhrasesTo("工作", listOf("内存里的"))
            flushPendingWrites()
        }
        File(dir, "phrases.txt").writeText("C\t工作\nP\t磁盘上的\n")
        LiveUserData.clipboardHost = live
        try {
            val out = ByteArrayOutputStream()
            assertTrue(PhraseTransferIo.exportPhrases(dir) { out }.getOrDefault(false))
            val text = String(out.toByteArray(), Charsets.UTF_8)
            assertTrue("the running store is the one that knows what the phrases are", text.contains("P\t内存里的\n"))
            assertFalse("a second store over the same file reads whatever happens to be there", text.contains("磁盘上的"))
        } finally {
            live.stopSaving()
        }
    }

    @Test fun a_phrase_import_writes_through_the_store_that_owns_the_file() {
        val src = File("src/main/java/com/aegis/ime/ui/PhraseTransferActivity.kt").readText()
        assertTrue(src.contains("LiveUserData.withClipboardStore(filesDir)"))
        assertEquals(
            "the import must not build its own store over the same file",
            src.windowed("withClipboardStore(filesDir)".length) { it == "withClipboardStore(filesDir)" }.count { it },
            src.windowed("ClipboardStore(filesDir)".length) { it == "ClipboardStore(filesDir)" }.count { it },
        )
    }

    private class FailingOutputStream : OutputStream() {
        override fun write(b: Int) {
            throw IOException("write failed")
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            throw IOException("write failed")
        }
    }
}
