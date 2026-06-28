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

    @Test fun million_char_clip_round_trips_without_truncation_and_externalizes() {
        val dir = newDir()
        val big = "字".repeat(1_000_000)
        val s = ClipboardStore(dir).apply { load(); record(big) }
        assertEquals(1_000_000, s.historyText().first().length)
        s.flushPendingWrites()
        val index = File(dir, "clipboard.txt").readText()
        assertTrue("index is a small B-marker, not the content", index.startsWith("B\t") && index.length < 200)
        val sideFiles = File(dir, "clips").listFiles().orEmpty()
        assertTrue("side file holds the full 1M content", sideFiles.any { it.readText().length == 1_000_000 })
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals("reloaded length", 1_000_000, reloaded.historyText().first().length)
        assertEquals("reloaded content identical", big, reloaded.historyText().first())
    }

    @Test fun small_entries_stay_inline_and_big_ones_externalize() {
        val dir = newDir()
        val small = "x".repeat(ClipboardStore.BIG_THRESHOLD)
        val big = "y".repeat(ClipboardStore.BIG_THRESHOLD + 1)
        val s = ClipboardStore(dir).apply { load(); record(small); record(big); flushPendingWrites() }
        val lines = File(dir, "clipboard.txt").readLines()
        assertEquals(2, lines.size)
        assertTrue("newest (big) is a B-marker", lines[0].startsWith("B\t"))
        assertFalse("small stays inline (bare, not a B-marker)", lines[1].startsWith("B\t"))
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals(listOf(big, small), reloaded.historyText())
    }

    @Test fun legacy_bare_history_and_tab_delimited_clips_survive_upgrade() {
        val dir = newDir()
        File(dir, "clipboard.txt").writeText("B\tcol2\tcol3\nT\tnot a marker\nplain clip")
        val s = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("B\tcol2\tcol3", "T\tnot a marker", "plain clip"), s.historyText())
    }

    @Test fun load_dedupes_duplicate_history_and_keeps_missing_sidecar_marker_literal() {
        val dir = newDir()
        File(dir, "clipboard.txt").writeText("dup\nB\tMissingSidecar42\ndup\n")
        val s = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("dup", "B\tMissingSidecar42"), s.historyText())
    }

    @Test fun deleting_a_big_entry_sweeps_its_side_file() {
        val dir = newDir()
        val big = "z".repeat(ClipboardStore.BIG_THRESHOLD + 100)
        val s = ClipboardStore(dir).apply { load(); record(big); flushPendingWrites() }
        assertTrue("side file written", File(dir, "clips").listFiles().orEmpty().isNotEmpty())
        s.clearHistory(); s.flushPendingWrites()
        assertTrue("orphan side file swept", File(dir, "clips").listFiles().orEmpty().isEmpty())
    }

    private val bigBody = "巨".repeat(ClipboardStore.BIG_THRESHOLD + 1)

    @Test fun loading_a_big_entry_keeps_metadata_only_until_the_body_is_asked_for() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); record(bigBody); flushPendingWrites() }
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals("no body chars are resident after load", 0L, reloaded.residentBodyChars())
        assertEquals(1, reloaded.history().size)
        assertEquals("the body is still reachable on demand", bigBody, reloaded.history().first().body())
        assertEquals("reading a body does not make it resident", 0L, reloaded.residentBodyChars())
        dir.deleteRecursively()
    }

    @Test fun a_big_body_is_read_at_use_time_not_at_load_time() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); record(bigBody); flushPendingWrites() }
        val reloaded = ClipboardStore(dir).apply { load() }
        File(dir, "clips").deleteRecursively()
        assertEquals("the row survives as metadata", 1, reloaded.history().size)
        assertNull("load must not have captured the body", reloaded.history().first().body())
        dir.deleteRecursively()
    }

    @Test fun previews_stay_bounded_while_bodies_grow() {
        val dir = newDir()
        val small = List(200) { "小-$it" }
        ClipboardStore(dir).apply {
            load()
            small.forEach { record(it) }
            repeat(4) { record("巨$it".repeat(ClipboardStore.BIG_THRESHOLD)) }
            flushPendingWrites()
        }
        val reloaded = ClipboardStore(dir).apply { load() }
        val inlineChars = small.sumOf { it.length }.toLong()
        assertEquals("only inline rows are resident", inlineChars, reloaded.residentBodyChars())
        reloaded.history().forEach { it.preview() }
        assertTrue(
            "previews are capped instead of holding whole bodies",
            reloaded.residentBodyChars() <= inlineChars + 4L * ClipEntry.PREVIEW_CHARS,
        )
        assertEquals(
            "the preview is a bounded prefix of the body",
            ClipEntry.PREVIEW_CHARS,
            reloaded.history().first().preview().length,
        )
        dir.deleteRecursively()
    }

    @Test fun a_reference_whose_sidecar_is_gone_is_marked_and_never_written_back_as_content() {
        val dir = newDir()
        val hash = "a".repeat(64)
        File(dir, "clipboard.txt").writeText("B\t$hash\n保留\n")
        val s = ClipboardStore(dir).apply { load() }
        val lost = s.history().first()
        assertFalse("a reference without its sidecar is not available", lost.available)
        assertNull("no substitute body is invented", lost.body())
        assertTrue("the row carries a visible missing mark", lost.preview().startsWith("⚠"))
        s.record("新的一条")
        s.flushPendingWrites()
        assertEquals(
            "the reference is preserved verbatim, never replaced by its own marker text",
            listOf("新的一条", "B\t$hash", "保留"),
            File(dir, "clipboard.txt").readLines(),
        )
        dir.deleteRecursively()
    }

    @Test fun a_reference_heals_when_its_sidecar_comes_back() {
        val dir = newDir()
        val hash = "b".repeat(64)
        File(dir, "clipboard.txt").writeText("B\t$hash\n")
        ClipboardStore(dir).apply { load(); record("触发保存"); flushPendingWrites() }
        File(dir, "clips").mkdirs()
        File(dir, "clips/$hash.txt").writeText(bigBody)
        val healed = ClipboardStore(dir).apply { load() }
        assertEquals(bigBody, healed.historyText().last())
        dir.deleteRecursively()
    }

    @Test fun a_clip_shaped_like_a_sidecar_reference_survives_the_round_trip() {
        val dir = newDir()
        val literal = "B\t" + "c".repeat(64)
        ClipboardStore(dir).apply { load(); record(literal); flushPendingWrites() }
        assertEquals("\\B\t" + "c".repeat(64), File(dir, "clipboard.txt").readLines().first())
        assertEquals(listOf(literal), ClipboardStore(dir).apply { load() }.historyText())
        dir.deleteRecursively()
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
