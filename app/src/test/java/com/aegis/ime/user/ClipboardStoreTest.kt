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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipboardStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newDir(): File = tmp.newFolder()

    @Test fun records_newest_first_and_dedupes() {
        val s = ClipboardStore(newDir()).apply { load() }
        s.record("a"); s.record("b"); s.record("a")
        assertEquals(listOf("a", "b"), s.historyText())
    }

    @Test fun latest_returns_the_newest_aegis_entry_or_null_for_empty_history() {
        val s = ClipboardStore(newDir()).apply { load() }
        assertNull(s.latest())
        s.record("older")
        s.record("latest")
        assertEquals("latest", s.latest())
    }

    @Test fun blank_ignored() {
        val s = ClipboardStore(newDir()).apply { load() }
        s.record("   "); s.record(null); s.record("x")
        assertEquals(listOf("x"), s.historyText())
    }

    @Test fun multiline_clip_survives_persist_roundtrip() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); record("line1\nline2"); flushPendingWrites() }
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals("line1\nline2", reloaded.historyText().first())
    }

    @Test fun crlf_clip_survives_persist_roundtrip() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); record("line1\r\nline2"); flushPendingWrites() }
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals("line1\r\nline2", reloaded.historyText().first())
    }

    @Test fun edge_whitespace_on_a_clip_survives_recording_and_reloading() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); record(" \tline1\nline2 \n"); flushPendingWrites() }
        assertEquals(" \tline1\nline2 \n", ClipboardStore(dir).apply { load() }.historyText().first())
    }

    @Test fun clips_that_differ_only_in_edge_whitespace_stay_distinct_and_dedupe_exactly() {
        val s = ClipboardStore(newDir()).apply { load() }
        s.record(" x "); s.record("x"); s.record(" x ")
        assertEquals(listOf(" x ", "x"), s.historyText())
    }

    @Test fun a_history_whose_bytes_went_bad_reads_as_a_history_nobody_could_read() {
        val dir = newDir()
        val index = File(dir, "clipboard.txt")
        index.writeBytes("好好保存的\n".toByteArray(Charsets.UTF_8) + byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xFF.toByte()))
        val s = ClipboardStore(dir).apply { load() }

        assertFalse("a file the app cannot decode is not a history it could read", s.historyReadable)
        assertTrue("and nothing half decoded may stand in for it", s.historyText().isEmpty())
    }
}
