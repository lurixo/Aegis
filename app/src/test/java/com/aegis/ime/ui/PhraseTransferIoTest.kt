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

import com.aegis.ime.user.ClipboardStore
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
        assertTrue(PhraseTransferIo.exportPhrases(dir) { out })

        val text = String(out.toByteArray(), Charsets.UTF_8)
        assertTrue("export wrote bytes", out.size() > 0)
        assertTrue("export includes category", text.contains("C\t工作\n"))
        assertTrue("export includes phrase", text.contains("P\t已收到\n"))
        assertTrue("export includes note", text.contains("N\t回执\n"))
    }

    @Test fun exportPhrases_treats_null_output_as_failure() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("已收到")); flushPendingWrites() }

        assertFalse(PhraseTransferIo.exportPhrases(dir) { null })
    }

    @Test fun exportPhrases_treats_unwritable_output_as_failure() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("已收到")); flushPendingWrites() }

        assertFalse(PhraseTransferIo.exportPhrases(dir) { FailingOutputStream() })
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
