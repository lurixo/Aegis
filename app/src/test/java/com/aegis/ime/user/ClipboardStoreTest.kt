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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClipboardStoreTest {

    private fun newDir(): File = Files.createTempDirectory("clipstore").toFile()

    @Test fun records_newest_first_and_dedupes() {
        val s = ClipboardStore(newDir()).apply { load() }
        s.record("a"); s.record("b"); s.record("a")
        assertEquals(listOf("a", "b"), s.history())
    }

    @Test fun blank_ignored() {
        val s = ClipboardStore(newDir()).apply { load() }
        s.record("   "); s.record(null); s.record("x")
        assertEquals(listOf("x"), s.history())
    }

    @Test fun no_default_phrases_seeded_on_first_run() {
        // debug.14 item1: a fresh store ships with NO preset phrases.
        val s = ClipboardStore(newDir()).apply { load() }
        assertTrue(s.phrases().isEmpty())
    }

    @Test fun multiline_clip_survives_persist_roundtrip() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); record("line1\nline2"); awaitWritesForTest() } // E5: persistence is async
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals("line1\nline2", reloaded.history().first())
    }

    @Test fun image_entries_ride_history_and_stay_distinguishable() {
        // U22: an image entry is a marker+path on the SAME history list; text entries are untouched.
        val s = ClipboardStore(newDir()).apply { load() }
        s.record("hello")
        s.recordImage("/data/x/clipboard_images/1.png")
        val h = s.history()
        assertEquals(2, h.size)
        assertTrue("newest is the image", ClipboardStore.isImageEntry(h[0]))
        assertEquals("/data/x/clipboard_images/1.png", ClipboardStore.imagePath(h[0]))
        assertFalse("text entry is not an image", ClipboardStore.isImageEntry(h[1]))
        assertEquals("hello", h[1])
        assertEquals("imagePath on plain text is identity", "hello", ClipboardStore.imagePath("hello"))
    }

    @Test fun image_markers_are_never_saved_as_phrases() {
        // M-2: a batch "添加常用语" that includes an image entry must NOT write its marker into phrases.
        val s = ClipboardStore(newDir()).apply { load() }
        val img = ClipboardStore.IMG_PREFIX + "/data/x/clipboard_images/1.png"
        val added = s.addPhrasesTo("默认", listOf("正常短语", img))
        assertEquals("only the text phrase is added", 1, added)
        assertTrue("正常短语" in s.phrases())
        assertFalse("no image marker leaks into phrases", s.phrases().any { ClipboardStore.isImageEntry(it) })
    }

    @Test fun crlf_clip_survives_persist_roundtrip() {
        // readLines() also splits on \r / \r\n, so CRLF text (desktop/web clips) must be escaped too.
        val dir = newDir()
        ClipboardStore(dir).apply { load(); record("line1\r\nline2"); awaitWritesForTest() } // E5: persistence is async
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals("line1\r\nline2", reloaded.history().first())
    }

    @Test fun multi_delete_and_clear_persist() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load() }
        s.record("a"); s.record("b"); s.record("c") // history = [c, b, a]
        s.deleteAll(listOf("a", "c"))
        assertEquals(listOf("b"), s.history())
        s.awaitWritesForTest() // E5: persistence is async
        // persisted: a fresh store sees the same survivor
        assertEquals(listOf("b"), ClipboardStore(dir).apply { load() }.history())
        s.clearHistory()
        assertTrue(s.history().isEmpty())
        s.awaitWritesForTest()
        assertTrue(ClipboardStore(dir).apply { load() }.history().isEmpty())
    }

    // ---- E5: million-char clip must round-trip losslessly without main-thread heavy IO ----

    @Test fun million_char_clip_round_trips_without_truncation_and_externalizes() {
        val dir = newDir()
        val big = "字".repeat(1_000_000) // 1,000,000 chars
        val s = ClipboardStore(dir).apply { load(); record(big) }
        // record() returns immediately (async persist); the in-memory entry is the FULL text, untruncated.
        assertEquals(1_000_000, s.history().first().length)
        s.awaitWritesForTest()
        // clipboard.txt stays SMALL — the big entry is a one-line B-marker, NOT a million chars inline.
        val index = File(dir, "clipboard.txt").readText()
        assertTrue("index is a small B-marker, not the content", index.startsWith("B\t") && index.length < 200)
        // the full content lives in a content-addressed side file, untruncated.
        val sideFiles = File(dir, "clips").listFiles().orEmpty()
        assertTrue("side file holds the full 1M content", sideFiles.any { it.readText().length == 1_000_000 })
        // reload from disk → identical, no truncation.
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals("reloaded length", 1_000_000, reloaded.history().first().length)
        assertEquals("reloaded content identical", big, reloaded.history().first())
    }

    @Test fun small_entries_stay_inline_and_big_ones_externalize() {
        val dir = newDir()
        val small = "x".repeat(ClipboardStore.BIG_THRESHOLD)           // == threshold → inline
        val big = "y".repeat(ClipboardStore.BIG_THRESHOLD + 1)         // just over → externalized
        val s = ClipboardStore(dir).apply { load(); record(small); record(big); awaitWritesForTest() }
        val lines = File(dir, "clipboard.txt").readLines()
        assertEquals(2, lines.size)
        assertTrue("newest (big) is a B-marker", lines[0].startsWith("B\t"))
        assertFalse("small stays inline (bare, not a B-marker)", lines[1].startsWith("B\t"))
        // both survive a reload intact (dedup/order preserved)
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals(listOf(big, small), reloaded.history())
    }

    @Test fun legacy_bare_history_and_tab_delimited_clips_survive_upgrade() {
        // Pre-E5 clipboard.txt = bare encoded lines, no markers, no clips/ dir. Tab-delimited clips that merely
        // start with the new "B\t" marker prefix must be preserved verbatim (no drop, no prefix-stripping).
        val dir = newDir()
        File(dir, "clipboard.txt").writeText("B\tcol2\tcol3\nT\tnot a marker\nplain clip")
        val s = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("B\tcol2\tcol3", "T\tnot a marker", "plain clip"), s.history())
    }

    @Test fun deleting_a_big_entry_sweeps_its_side_file() {
        val dir = newDir()
        val big = "z".repeat(ClipboardStore.BIG_THRESHOLD + 100)
        val s = ClipboardStore(dir).apply { load(); record(big); awaitWritesForTest() }
        assertTrue("side file written", File(dir, "clips").listFiles().orEmpty().isNotEmpty())
        s.clearHistory(); s.awaitWritesForTest()
        assertTrue("orphan side file swept", File(dir, "clips").listFiles().orEmpty().isEmpty())
    }

    @Test fun batch_add_phrases_dedupes_trims_and_persists() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load() }
        val before = s.phrases().size
        val added = s.addPhrases(listOf("自定义短语", "  自定义短语  ", "", "另一条"))
        assertEquals("blank + duplicate dropped", 2, added)
        assertTrue("自定义短语" in s.phrases())
        assertTrue("另一条" in s.phrases())
        // a reloaded store keeps the user-added phrases (persisted)
        val reloaded = ClipboardStore(dir).apply { load() }
        assertTrue("自定义短语" in reloaded.phrases())
        assertEquals(before + 2, reloaded.phrases().size)
    }

    // ---- C5 categories ----

    @Test fun first_run_has_an_empty_default_category() {
        // debug.14 item1: NO preset phrases — the "默认" category exists (so the UI has an add target) but is empty.
        val s = ClipboardStore(newDir()).apply { load() }
        assertEquals(listOf("默认"), s.categories())
        assertTrue("no default phrases are seeded", s.phrasesIn("默认").isEmpty())
        assertTrue("no phrases at all on first run", s.phrases().isEmpty())
    }

    @Test fun existing_user_phrases_survive_the_no_seed_change() {
        // item1 must only affect the FIRST-RUN seed — an existing phrases.txt is loaded verbatim, untouched.
        val dir = newDir()
        ClipboardStore(dir).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("已收到")) }
        val reloaded = ClipboardStore(dir).apply { load() }
        assertTrue("工作" in reloaded.categories())
        assertEquals(listOf("已收到"), reloaded.phrasesIn("工作"))
    }

    @Test fun add_target_and_delete_categories_persist() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load() }
        assertTrue(s.addCategory("工作"))
        assertFalse("blank rejected", s.addCategory("   "))
        assertFalse("duplicate rejected", s.addCategory("工作"))
        assertEquals(3, s.addPhrasesTo("工作", listOf("已收到，马上处理", "请稍等", "已收到，马上处理", "会后回复")))
        assertEquals(listOf("已收到，马上处理", "请稍等", "会后回复"), s.phrasesIn("工作"))
        // persisted with categories intact
        val reloaded = ClipboardStore(dir).apply { load() }
        assertTrue("工作" in reloaded.categories())
        assertEquals(listOf("已收到，马上处理", "请稍等", "会后回复"), reloaded.phrasesIn("工作"))
        // delete category
        reloaded.deleteCategory("工作")
        assertFalse("工作" in reloaded.categories())
        assertTrue("工作" !in ClipboardStore(dir).apply { load() }.categories())
    }

    @Test fun rename_category_rejects_collision_and_persists() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load(); addCategory("A"); addCategory("B") }
        assertFalse("collision rejected", s.renameCategory("A", "B"))
        assertTrue(s.renameCategory("A", "甲"))
        assertTrue("甲" in ClipboardStore(dir).apply { load() }.categories())
    }

    @Test fun legacy_flat_phrase_file_migrates_into_default_category() {
        val dir = newDir()
        File(dir, "phrases.txt").writeText("你好\n谢谢\n多行\\n短语") // old flat format, last line multi-line encoded
        val s = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("默认"), s.categories())
        assertEquals(listOf("你好", "谢谢", "多行\n短语"), s.phrasesIn("默认"))
    }
}
