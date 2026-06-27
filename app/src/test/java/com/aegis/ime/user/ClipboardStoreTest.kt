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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

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

    @Test fun multi_delete_and_clear_persist() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load() }
        s.record("a"); s.record("b"); s.record("c")
        s.deleteAll(listOf("a", "c"))
        assertEquals(listOf("b"), s.historyText())
        s.flushPendingWrites()
        assertEquals(listOf("b"), ClipboardStore(dir).apply { load() }.historyText())
        s.clearHistory()
        assertTrue(s.historyText().isEmpty())
        s.flushPendingWrites()
        assertTrue(ClipboardStore(dir).apply { load() }.historyText().isEmpty())
    }


    @Test fun a_history_nobody_could_read_is_never_written_over() {
        val dir = newDir()
        val index = File(dir, "clipboard.txt").apply { writeText("读不出来的一条\n") }
        assertTrue("precondition: the index cannot be read back", index.setReadable(false, false))
        val s = ClipboardStore(dir).apply { load() }
        assertFalse("precondition: the store knows it could not read the history", s.historyReadable)
        s.record("读不出来之后复制的")
        s.flushPendingWrites()

        assertFalse("a clear over a history nobody could read must not be reported as done", s.clearHistory())

        assertTrue(index.setReadable(true, false))
        assertEquals("what could not be read must not be thrown away either", "读不出来的一条\n", index.readText())
    }

    @Test fun a_history_whose_bytes_went_bad_reads_as_a_history_nobody_could_read() {
        val dir = newDir()
        val index = File(dir, "clipboard.txt")
        index.writeBytes("好好保存的\n".toByteArray(Charsets.UTF_8) + byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xFF.toByte()))
        val s = ClipboardStore(dir).apply { load() }

        assertFalse("a file the app cannot decode is not a history it could read", s.historyReadable)
        assertTrue("and nothing half decoded may stand in for it", s.historyText().isEmpty())
    }

    @Test fun a_clip_copied_over_a_history_nobody_could_read_never_stands_in_for_it() {
        val dir = newDir()
        val index = File(dir, "clipboard.txt").apply { writeText("读不出来的一条\n") }
        assertTrue("precondition: the index cannot be read back", index.setReadable(false, false))
        val s = ClipboardStore(dir).apply { load() }
        assertFalse("precondition: the store knows it could not read the history", s.historyReadable)

        s.record("读不出来之后复制的")
        s.flushPendingWrites()

        assertTrue(
            "a clip copied afterwards must not show up as the history nobody could read",
            s.historyText().isEmpty(),
        )
        assertFalse("and the store must still say it could not read the history", s.historyReadable)
        assertFalse(
            "a delete over a history nobody could read must not be reported as done",
            s.deleteAll(listOf("读不出来的一条")),
        )
        assertTrue(index.setReadable(true, false))
    }

    @Test fun a_delete_that_could_not_be_written_says_it_was_not_written() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load(); record("要删的"); record("留下的"); flushPendingWrites() }
        val blocker = s.tempFileFor(File(dir, "clipboard.txt"))
        assertTrue("precondition: the history write is blocked", blocker.mkdirs())
        assertTrue(File(blocker, "occupied").createNewFile())

        val reported = CopyOnWriteArrayList<Boolean>()
        s.reportClipWritesTo({ it.run() }) { reported.add(it) }

        assertTrue(
            "the store takes the delete before the file has had its say",
            s.deleteAll(listOf("要删的")),
        )
        s.flushPendingWrites()

        assertEquals("a delete owes the panel exactly one answer", 1, reported.size)
        assertFalse(
            "a delete that never reached the file must not come back as one that did",
            reported.single(),
        )
        assertEquals(
            "the clip is still on the disk, which is what the user has to be told",
            listOf("留下的", "要删的"),
            ClipboardStore(dir).apply { load() }.historyText(),
        )
    }

    @Test fun a_clear_that_could_not_be_written_says_it_was_not_written() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load(); record("要清的"); flushPendingWrites() }
        val blocker = s.tempFileFor(File(dir, "clipboard.txt"))
        assertTrue("precondition: the history write is blocked", blocker.mkdirs())
        assertTrue(File(blocker, "occupied").createNewFile())

        val reported = CopyOnWriteArrayList<Boolean>()
        s.reportClipWritesTo({ it.run() }) { reported.add(it) }

        assertTrue("the store takes the clear before the file has had its say", s.clearHistory())
        s.flushPendingWrites()

        assertFalse("a clear that never reached the file must not come back as one that did", reported.single())
        assertEquals(listOf("要清的"), ClipboardStore(dir).apply { load() }.historyText())
    }

    @Test fun a_delete_that_was_written_says_so() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load(); record("要删的"); record("留下的"); flushPendingWrites() }

        val reported = CopyOnWriteArrayList<Boolean>()
        s.reportClipWritesTo({ it.run() }) { reported.add(it) }

        assertTrue("the store takes the delete before the file has had its say", s.deleteAll(listOf("要删的")))
        s.flushPendingWrites()

        assertEquals("a delete owes the panel exactly one answer", 1, reported.size)
        assertTrue("a delete the file took must come back as one that did", reported.single())
        assertEquals(listOf("留下的"), ClipboardStore(dir).apply { load() }.historyText())
    }

    @Test fun a_delete_after_the_writer_was_handed_back_says_it_was_not_written() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load(); record("a"); record("b"); flushPendingWrites() }
        s.stopSaving()

        val reported = CopyOnWriteArrayList<Boolean>()
        s.reportClipWritesTo({ it.run() }) { reported.add(it) }

        assertTrue(s.deleteAll(s.historyKeys().take(1)))
        assertTrue(s.clearHistory())

        assertEquals(
            "a store with no writer left cannot promise either change lands",
            listOf(false, false),
            reported.toList(),
        )
    }

    @Test fun two_stores_over_one_directory_never_share_a_temp_file() {
        val dir = newDir()
        val a = ClipboardStore(dir).apply { load() }
        val b = ClipboardStore(dir).apply { load() }
        try {
            for (name in listOf("clipboard.txt", "phrases.txt")) {
                val dest = File(dir, name)
                assertNotEquals(
                    "a swap that loses the race deletes the target, so two stores must never stage through one path",
                    a.tempFileFor(dest),
                    b.tempFileFor(dest),
                )
            }
        } finally {
            a.stopSaving()
            b.stopSaving()
        }
    }
}
