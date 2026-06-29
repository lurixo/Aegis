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

    @Test fun legacy_image_entries_are_dropped_on_load() {
        // U22 removed: an old clipboard.txt may still hold "img:<path>" image markers pointing into the
        // (now-deleted) clipboard_images dir — load() must silently drop them; text history is untouched.
        val dir = newDir()
        File(dir, "clipboard.txt").writeText(
            "img:/data/user/0/com.aegis.ime/files/clipboard_images/123.png\n" +
            "hello world\n"
        )
        val h = ClipboardStore(dir).apply { load() }.history()
        assertEquals("only the text entry survives", listOf("hello world"), h)
    }

    @Test fun legacy_image_dir_is_reclaimed_on_load() {
        // U22 removed: a pre-removal build left filesDir/clipboard_images with saved image bytes — load() must
        // reclaim that orphaned dir so it can't linger forever; phrases/history are unaffected.
        val dir = newDir()
        File(dir, "clipboard_images").apply { mkdirs() }.also { File(it, "1.png").writeText("x") }
        ClipboardStore(dir).apply { load() }
        assertFalse("orphaned image dir reclaimed", File(dir, "clipboard_images").exists())
    }

    @Test fun text_starting_with_img_prefix_is_preserved() {
        // A normal text clip that merely starts with "img:" (no clipboard_images path) is NOT a legacy image
        // marker and must be kept verbatim — the drop is precise, never eats real text.
        val dir = newDir()
        File(dir, "clipboard.txt").writeText("img:hello\nreal text\n")
        val h = ClipboardStore(dir).apply { load() }.history()
        assertEquals(listOf("img:hello", "real text"), h)
        assertFalse("the text-only img: entry is not classified as a legacy image",
            ClipboardStore.isLegacyImageEntry("img:hello"))
        assertTrue("a real clipboard_images marker is classified as a legacy image",
            ClipboardStore.isLegacyImageEntry("img:/x/clipboard_images/1.png"))
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

    // --- debug.16: edit / move a saved phrase ---

    @Test fun edit_phrase_replaces_in_place_preserving_order_and_persists() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("一", "二", "三")) }
        assertTrue(s.editPhrase("工作", "二", "  贰  ")) // surrounding space sanitized
        assertEquals(listOf("一", "贰", "三"), s.phrasesIn("工作")) // in place, order kept, sanitized
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("一", "贰", "三"), reloaded.phrasesIn("工作")) // persisted
    }

    @Test fun edit_phrase_strips_iso_control_chars() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("一")) }
        assertTrue(s.editPhrase("工作", "一", "a\tb\nc")) // tab/newline are ISO controls → stripped
        assertEquals(listOf("abc"), s.phrasesIn("工作"))
    }

    @Test fun edit_phrase_rejects_empty_or_control_only_and_leaves_store_unchanged() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("一", "二")) }
        assertFalse("blank after trim rejected", s.editPhrase("工作", "二", "   "))
        assertFalse("control-only rejected", s.editPhrase("工作", "二", " \t\n"))
        assertEquals(listOf("一", "二"), s.phrasesIn("工作")) // untouched
    }

    @Test fun edit_phrase_rejects_duplicate_of_another_item_but_allows_self() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("一", "二")) }
        assertFalse("would collide with existing 一", s.editPhrase("工作", "二", "一"))
        assertEquals(listOf("一", "二"), s.phrasesIn("工作"))
        assertTrue("replacing with its own value is allowed", s.editPhrase("工作", "二", "二"))
        assertEquals(listOf("一", "二"), s.phrasesIn("工作"))
    }

    @Test fun edit_phrase_rejects_unknown_category_or_phrase() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("一")) }
        assertFalse(s.editPhrase("不存在", "一", "二"))
        assertFalse(s.editPhrase("工作", "缺失", "二"))
    }

    @Test fun move_phrase_across_categories_dedupes_and_persists() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply {
            load(); addCategory("甲"); addCategory("乙")
            addPhrasesTo("甲", listOf("x", "y")); addPhrasesTo("乙", listOf("z"))
        }
        assertTrue(s.movePhrase("甲", "x", "乙"))
        assertEquals(listOf("y"), s.phrasesIn("甲"))        // removed from source
        assertEquals(listOf("z", "x"), s.phrasesIn("乙"))   // appended to target
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("y"), reloaded.phrasesIn("甲"))
        assertEquals(listOf("z", "x"), reloaded.phrasesIn("乙"))
    }

    @Test fun move_phrase_target_must_exist() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("x")) }
        assertFalse("never auto-create on move", s.movePhrase("甲", "x", "丙"))
        assertEquals(listOf("x"), s.phrasesIn("甲")) // unchanged
        assertFalse("丙 not created", "丙" in s.categories())
    }

    @Test fun move_phrase_dedupes_when_already_in_target() {
        val s = ClipboardStore(newDir()).apply {
            load(); addCategory("甲"); addCategory("乙")
            addPhrasesTo("甲", listOf("x")); addPhrasesTo("乙", listOf("x"))
        }
        assertTrue(s.movePhrase("甲", "x", "乙"))
        assertTrue("removed from source", s.phrasesIn("甲").isEmpty())
        assertEquals(listOf("x"), s.phrasesIn("乙")) // not duplicated
    }

    @Test fun move_phrase_absent_in_source_is_rejected_no_phantom_at_target() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addCategory("乙"); addPhrasesTo("乙", listOf("z")) }
        assertFalse("nothing to move → reject", s.movePhrase("甲", "ghost", "乙"))
        assertEquals(listOf("z"), s.phrasesIn("乙")) // target unchanged (no phantom "ghost")
    }

    @Test fun move_phrase_to_same_category_is_a_noop() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("x", "y")) }
        assertTrue(s.movePhrase("甲", "x", "甲"))
        assertEquals(listOf("x", "y"), s.phrasesIn("甲")) // order preserved, no reorder
    }

    @Test fun move_phrases_to_batch_moves_present_items_dedupes_and_counts() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply {
            load(); addCategory("甲"); addCategory("乙")
            addPhrasesTo("甲", listOf("a", "b", "c")); addPhrasesTo("乙", listOf("b")) // "b" already in target
        }
        // move a, b, ghost: a moves, b is removed from source but not duplicated in target, ghost absent → skipped
        assertEquals(2, s.movePhrasesTo("甲", listOf("a", "b", "ghost"), "乙"))
        assertEquals(listOf("c"), s.phrasesIn("甲"))
        assertEquals(listOf("b", "a"), s.phrasesIn("乙"))
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("c"), reloaded.phrasesIn("甲"))
        assertEquals(listOf("b", "a"), reloaded.phrasesIn("乙"))
    }

    @Test fun move_phrases_to_rejects_unknown_or_same_target() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("a")) }
        assertEquals(0, s.movePhrasesTo("甲", listOf("a"), "丙")) // target missing
        assertEquals(0, s.movePhrasesTo("甲", listOf("a"), "甲")) // same category
        assertEquals(listOf("a"), s.phrasesIn("甲")) // unchanged
    }

    @Test fun reorder_phrase_moves_item_and_persists() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("a", "b", "c", "d")) }
        assertTrue(s.reorderPhrase("甲", 0, 2)) // a → index 2
        assertEquals(listOf("b", "c", "a", "d"), s.phrasesIn("甲"))
        assertTrue(s.reorderPhrase("甲", 3, 0)) // d → front
        assertEquals(listOf("d", "b", "c", "a"), s.phrasesIn("甲"))
        assertEquals(listOf("d", "b", "c", "a"), ClipboardStore(dir).apply { load() }.phrasesIn("甲")) // persisted
    }

    @Test fun new_category_with_pending_clip_lands_the_clip_in_it() {
        // Mirrors confirmInlineInput's ADD_CATEGORY-with-pending step (剪贴板 添加常用语→新建分类→确认).
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load(); addCategory("默认") }
        val name = "工作".trim()
        s.addCategory(name); s.addPhrasesTo(name, listOf("hello")) // create then land the carried clip
        assertEquals(listOf("hello"), s.phrasesIn("工作"))
        // cancel-equivalent: with NO confirm calls the category is never created and nothing is added.
        assertFalse("未确认不应创建分类", "私人" in ClipboardStore(dir).apply { load() }.categories())
    }

    @Test fun new_category_with_pending_move_lands_item_in_it() {
        // Mirrors confirmInlineInput's ADD_CATEGORY-with-pending-move step (移动到分类→新建分类→确认).
        val s = ClipboardStore(newDir()).apply { load(); addCategory("默认"); addPhrasesTo("默认", listOf("你好", "在吗")) }
        val name = "工作".trim()
        s.addCategory(name); s.movePhrasesTo("默认", listOf("你好"), name) // create then land the carried move
        assertEquals(listOf("在吗"), s.phrasesIn("默认")) // removed from source
        assertEquals(listOf("你好"), s.phrasesIn("工作")) // moved into the new category
    }

    @Test fun reorder_phrase_rejects_bad_indices_and_noops() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("a", "b")) }
        assertFalse(s.reorderPhrase("甲", 0, 0))   // no-op
        assertFalse(s.reorderPhrase("甲", -1, 1))  // out of range
        assertFalse(s.reorderPhrase("甲", 0, 5))   // out of range
        assertFalse(s.reorderPhrase("无", 0, 1))   // unknown category
        assertEquals(listOf("a", "b"), s.phrasesIn("甲"))
    }

    // ---------- debug.17 F2: phrase notes (display alias; 上屏 uses the original text) ----------

    @Test fun note_persists_and_phrasesIn_still_returns_original_text() {
        val dir = newDir()
        ClipboardStore(dir).apply {
            load(); addCategory("甲"); addPhrasesTo("甲", listOf("你好世界"))
            assertTrue(setPhraseNote("甲", "你好世界", "招呼"))
        }
        val r = ClipboardStore(dir).apply { load() }
        assertEquals("note persists across reload", "招呼", r.noteFor("甲", "你好世界"))
        assertEquals("phrasesIn returns the ORIGINAL text, not the note", listOf("你好世界"), r.phrasesIn("甲"))
    }

    @Test fun setPhraseNote_blank_clears_and_edit_keeps_note() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("orig")) }
        s.setPhraseNote("甲", "orig", "别名")
        assertTrue("editPhrase keeps the note attached", s.editPhrase("甲", "orig", "orig2"))
        assertEquals("别名", s.noteFor("甲", "orig2"))
        assertTrue(s.setPhraseNote("甲", "orig2", "  ")) // blank clears
        assertEquals("", s.noteFor("甲", "orig2"))
        assertFalse("unknown phrase → false", s.setPhraseNote("甲", "missing", "x"))
    }

    @Test fun movePhrase_carries_the_note() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addCategory("乙"); addPhrasesTo("甲", listOf("p")) }
        s.setPhraseNote("甲", "p", "标签")
        assertTrue(s.movePhrase("甲", "p", "乙"))
        assertEquals("标签", s.noteFor("乙", "p"))
    }

    @Test fun move_into_category_with_same_text_carries_note_no_silent_loss() {
        // fix: on a text collision at the target, the moved phrase's note must NOT be silently dropped.
        val s = ClipboardStore(newDir()).apply {
            load(); addCategory("工作"); addCategory("私人")
            addPhrasesTo("工作", listOf("谢谢")); addPhrasesTo("私人", listOf("谢谢")) // same text in both
            setPhraseNote("工作", "谢谢", "thx") // source has a note; target's is note-less
        }
        assertTrue(s.movePhrase("工作", "谢谢", "私人"))
        assertTrue("removed from source", s.phrasesIn("工作").isEmpty())
        assertEquals("deduped at target", listOf("谢谢"), s.phrasesIn("私人"))
        assertEquals("note carried onto the kept target item (not lost)", "thx", s.noteFor("私人", "谢谢"))
    }

    @Test fun batch_move_collision_carries_note() {
        val s = ClipboardStore(newDir()).apply {
            load(); addCategory("甲"); addCategory("乙")
            addPhrasesTo("甲", listOf("x")); addPhrasesTo("乙", listOf("x"))
            setPhraseNote("甲", "x", "n")
        }
        assertEquals(1, s.movePhrasesTo("甲", listOf("x"), "乙"))
        assertEquals("n", s.noteFor("乙", "x"))
    }

    // ---------- debug.17 E2: clear a category's phrases (category itself stays) ----------

    @Test fun clearPhrasesIn_empties_but_keeps_category() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("a", "b", "c")) }
        assertEquals(3, s.clearPhrasesIn("甲"))
        assertTrue(s.phrasesIn("甲").isEmpty())
        assertTrue("category still exists", "甲" in s.categories())
        assertEquals("already empty → 0", 0, s.clearPhrasesIn("甲"))
        assertEquals("unknown → 0", 0, s.clearPhrasesIn("无"))
    }

    // ---------- debug.17 E1: import / export (round-trip + merge/overwrite + never-clear) ----------

    @Test fun export_import_roundtrip_preserves_categories_phrases_notes() {
        val src = ClipboardStore(newDir()).apply {
            load(); addCategory("工作"); addPhrasesTo("工作", listOf("已收到", "稍等")); setPhraseNote("工作", "已收到", "回执")
            addCategory("私人"); addPhrasesTo("私人", listOf("晚安"))
        }
        val text = src.exportPhrasesText()
        val dst = ClipboardStore(newDir()).apply { load() }
        assertTrue(dst.importPhrasesText(text, merge = false))
        assertTrue(dst.categories().containsAll(listOf("工作", "私人")))
        assertEquals(listOf("已收到", "稍等"), dst.phrasesIn("工作"))
        assertEquals("回执", dst.noteFor("工作", "已收到"))
        assertEquals(listOf("晚安"), dst.phrasesIn("私人"))
    }

    @Test fun import_merge_accumulates_and_dedupes() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("已收到")) }
        val incoming = "C\t工作\nP\t已收到\nP\t稍等\nC\t新组\nP\t你好\n" // 已收到 dup, 稍等 new, 新组 new
        assertTrue(s.importPhrasesText(incoming, merge = true))
        assertEquals("dedup 已收到, add 稍等", listOf("已收到", "稍等"), s.phrasesIn("工作"))
        assertEquals(listOf("你好"), s.phrasesIn("新组"))
    }

    @Test fun import_overwrite_replaces_whole_library() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("旧组"); addPhrasesTo("旧组", listOf("旧")) }
        assertTrue(s.importPhrasesText("C\t新组\nP\t新\n", merge = false))
        assertFalse("旧组 replaced away", "旧组" in s.categories())
        assertEquals(listOf("新"), s.phrasesIn("新组"))
    }

    @Test fun import_empty_or_unparseable_never_clears() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("keep")) }
        assertFalse("empty → no change", s.importPhrasesText("", merge = false))
        assertFalse("blank lines → no change", s.importPhrasesText("\n  \n", merge = false))
        assertFalse("garbage with no markers → no change", s.importPhrasesText("just some text\nmore", merge = false))
        assertEquals("library intact after failed overwrite", listOf("keep"), s.phrasesIn("甲"))
    }

    // ---------- debug.17: capture policy (secure fields no longer block) ----------

    @Test fun shouldCapture_only_gated_by_history_switch() {
        assertTrue("history on → capture (even secure fields)", ClipboardStore.shouldCapture(true))
        assertFalse("history off → never capture", ClipboardStore.shouldCapture(false))
    }
}
