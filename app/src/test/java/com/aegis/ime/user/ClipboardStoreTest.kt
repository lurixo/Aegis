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
        assertEquals(listOf("x"), s.history())
    }

    @Test fun no_default_phrases_seeded_on_first_run() {
        val s = ClipboardStore(newDir()).apply { load() }
        assertTrue(s.phrases().isEmpty())
    }

    @Test fun multiline_clip_survives_persist_roundtrip() {
        val dir = newDir()
        ClipboardStore(dir).apply { load(); record("line1\nline2"); flushPendingWrites() }
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals("line1\nline2", reloaded.history().first())
    }

    @Test fun legacy_image_entries_are_dropped_on_load() {
        val dir = newDir()
        File(dir, "clipboard.txt").writeText(
            "img:/data/user/0/com.aegis.ime/files/clipboard_images/123.png\n" +
            "hello world\n"
        )
        val h = ClipboardStore(dir).apply { load() }.history()
        assertEquals("only the text entry survives", listOf("hello world"), h)
    }

    @Test fun legacy_image_dir_is_reclaimed_on_load() {
        val dir = newDir()
        File(dir, "clipboard_images").apply { mkdirs() }.also { File(it, "1.png").writeText("x") }
        ClipboardStore(dir).apply { load() }
        assertFalse("orphaned image dir reclaimed", File(dir, "clipboard_images").exists())
    }

    @Test fun text_starting_with_img_prefix_is_preserved() {
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
        val dir = newDir()
        ClipboardStore(dir).apply { load(); record("line1\r\nline2"); flushPendingWrites() }
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals("line1\r\nline2", reloaded.history().first())
    }

    @Test fun multi_delete_and_clear_persist() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load() }
        s.record("a"); s.record("b"); s.record("c")
        s.deleteAll(listOf("a", "c"))
        assertEquals(listOf("b"), s.history())
        s.flushPendingWrites()
        assertEquals(listOf("b"), ClipboardStore(dir).apply { load() }.history())
        s.clearHistory()
        assertTrue(s.history().isEmpty())
        s.flushPendingWrites()
        assertTrue(ClipboardStore(dir).apply { load() }.history().isEmpty())
    }


    @Test fun million_char_clip_round_trips_without_truncation_and_externalizes() {
        val dir = newDir()
        val big = "字".repeat(1_000_000)
        val s = ClipboardStore(dir).apply { load(); record(big) }
        assertEquals(1_000_000, s.history().first().length)
        s.flushPendingWrites()
        val index = File(dir, "clipboard.txt").readText()
        assertTrue("index is a small B-marker, not the content", index.startsWith("B\t") && index.length < 200)
        val sideFiles = File(dir, "clips").listFiles().orEmpty()
        assertTrue("side file holds the full 1M content", sideFiles.any { it.readText().length == 1_000_000 })
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals("reloaded length", 1_000_000, reloaded.history().first().length)
        assertEquals("reloaded content identical", big, reloaded.history().first())
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
        assertEquals(listOf(big, small), reloaded.history())
    }

    @Test fun legacy_bare_history_and_tab_delimited_clips_survive_upgrade() {
        val dir = newDir()
        File(dir, "clipboard.txt").writeText("B\tcol2\tcol3\nT\tnot a marker\nplain clip")
        val s = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("B\tcol2\tcol3", "T\tnot a marker", "plain clip"), s.history())
    }

    @Test fun load_dedupes_duplicate_history_and_keeps_missing_sidecar_marker_literal() {
        val dir = newDir()
        File(dir, "clipboard.txt").writeText("dup\nB\tMissingSidecar42\ndup\n")
        val s = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("dup", "B\tMissingSidecar42"), s.history())
    }

    @Test fun deleting_a_big_entry_sweeps_its_side_file() {
        val dir = newDir()
        val big = "z".repeat(ClipboardStore.BIG_THRESHOLD + 100)
        val s = ClipboardStore(dir).apply { load(); record(big); flushPendingWrites() }
        assertTrue("side file written", File(dir, "clips").listFiles().orEmpty().isNotEmpty())
        s.clearHistory(); s.flushPendingWrites()
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
        val reloaded = ClipboardStore(dir).apply { load() }
        assertTrue("自定义短语" in reloaded.phrases())
        assertEquals(before + 2, reloaded.phrases().size)
    }

    @Test fun added_phrases_land_at_front_preserving_batch_order() {
        val s = ClipboardStore(newDir()).apply { load(); addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("old")) }
        assertEquals(2, s.addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("new1", "new2", "old")))
        assertEquals(listOf("new1", "new2", "old"), s.phrasesIn(ClipboardStore.DEFAULT_CATEGORY_ID))
    }

    @Test fun phrase_dedup_is_scoped_to_the_target_category() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("工作"); addCategory("私人") }
        assertEquals(1, s.addPhrasesTo("工作", listOf("谢谢", "谢谢")))
        assertEquals(1, s.addPhrasesTo("私人", listOf("谢谢")))
        assertEquals(listOf("谢谢"), s.phrasesIn("工作"))
        assertEquals(listOf("谢谢"), s.phrasesIn("私人"))
    }


    @Test fun first_run_has_an_empty_default_category() {
        val s = ClipboardStore(newDir()).apply { load() }
        assertEquals(listOf(ClipboardStore.DEFAULT_CATEGORY_ID), s.categories())
        assertTrue("no default phrases are seeded", s.phrasesIn(ClipboardStore.DEFAULT_CATEGORY_ID).isEmpty())
        assertTrue("no phrases at all on first run", s.phrases().isEmpty())
    }

    @Test fun existing_user_phrases_survive_the_no_seed_change() {
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
        val reloaded = ClipboardStore(dir).apply { load() }
        assertTrue("工作" in reloaded.categories())
        assertEquals(listOf("已收到，马上处理", "请稍等", "会后回复"), reloaded.phrasesIn("工作"))
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
        File(dir, "phrases.txt").writeText("你好\n谢谢\n多行\\n短语")
        val s = ClipboardStore(dir).apply { load() }
        assertEquals(listOf(ClipboardStore.DEFAULT_CATEGORY_ID), s.categories())
        assertEquals(listOf("你好", "谢谢", "多行\n短语"), s.phrasesIn(ClipboardStore.DEFAULT_CATEGORY_ID))
    }

    @Test fun legacy_default_category_name_migrates_to_the_stable_id() {
        val dir = newDir()
        File(dir, "phrases.txt").writeText("C\t默认\nP\t你好\nC\t工作\nP\t已收到")
        val s = ClipboardStore(dir).apply { load() }
        assertEquals(listOf(ClipboardStore.DEFAULT_CATEGORY_ID, "工作"), s.categories())
        assertEquals(listOf("你好"), s.phrasesIn(ClipboardStore.DEFAULT_CATEGORY_ID))
        assertEquals(listOf("已收到"), s.phrasesIn("工作"))
    }


    @Test fun edit_phrase_replaces_in_place_preserving_order_and_persists() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("一", "二", "三")) }
        assertTrue(s.editPhrase("工作", "二", "  贰  "))
        assertEquals(listOf("一", "贰", "三"), s.phrasesIn("工作"))
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("一", "贰", "三"), reloaded.phrasesIn("工作"))
    }

    @Test fun edit_phrase_strips_iso_control_chars() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("一")) }
        assertTrue(s.editPhrase("工作", "一", "a\tb\nc"))
        assertEquals(listOf("abc"), s.phrasesIn("工作"))
    }

    @Test fun edit_phrase_rejects_empty_or_control_only_and_leaves_store_unchanged() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("工作"); addPhrasesTo("工作", listOf("一", "二")) }
        assertFalse("blank after trim rejected", s.editPhrase("工作", "二", "   "))
        assertFalse("control-only rejected", s.editPhrase("工作", "二", " \t\n"))
        assertEquals(listOf("一", "二"), s.phrasesIn("工作"))
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
        assertEquals(listOf("y"), s.phrasesIn("甲"))
        assertEquals(listOf("z", "x"), s.phrasesIn("乙"))
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("y"), reloaded.phrasesIn("甲"))
        assertEquals(listOf("z", "x"), reloaded.phrasesIn("乙"))
    }

    @Test fun move_phrase_target_must_exist() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("x")) }
        assertFalse("never auto-create on move", s.movePhrase("甲", "x", "丙"))
        assertEquals(listOf("x"), s.phrasesIn("甲"))
        assertFalse("丙 not created", "丙" in s.categories())
    }

    @Test fun move_phrase_dedupes_when_already_in_target() {
        val s = ClipboardStore(newDir()).apply {
            load(); addCategory("甲"); addCategory("乙")
            addPhrasesTo("甲", listOf("x")); addPhrasesTo("乙", listOf("x"))
        }
        assertTrue(s.movePhrase("甲", "x", "乙"))
        assertTrue("removed from source", s.phrasesIn("甲").isEmpty())
        assertEquals(listOf("x"), s.phrasesIn("乙"))
    }

    @Test fun move_phrase_absent_in_source_is_rejected_no_phantom_at_target() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addCategory("乙"); addPhrasesTo("乙", listOf("z")) }
        assertFalse("nothing to move → reject", s.movePhrase("甲", "ghost", "乙"))
        assertEquals(listOf("z"), s.phrasesIn("乙"))
    }

    @Test fun move_phrase_to_same_category_is_a_noop() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("x", "y")) }
        assertTrue(s.movePhrase("甲", "x", "甲"))
        assertEquals(listOf("x", "y"), s.phrasesIn("甲"))
    }

    @Test fun move_phrases_to_batch_moves_present_items_dedupes_and_counts() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply {
            load(); addCategory("甲"); addCategory("乙")
            addPhrasesTo("甲", listOf("a", "b", "c")); addPhrasesTo("乙", listOf("b"))
        }
        assertEquals(2, s.movePhrasesTo("甲", listOf("a", "b", "ghost"), "乙"))
        assertEquals(listOf("c"), s.phrasesIn("甲"))
        assertEquals(listOf("b", "a"), s.phrasesIn("乙"))
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("c"), reloaded.phrasesIn("甲"))
        assertEquals(listOf("b", "a"), reloaded.phrasesIn("乙"))
    }

    @Test fun move_phrases_to_rejects_unknown_or_same_target() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("a")) }
        assertEquals(0, s.movePhrasesTo("甲", listOf("a"), "丙"))
        assertEquals(0, s.movePhrasesTo("甲", listOf("a"), "甲"))
        assertEquals(listOf("a"), s.phrasesIn("甲"))
    }

    @Test fun reorder_phrase_moves_item_and_persists() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("a", "b", "c", "d")) }
        assertTrue(s.reorderPhrase("甲", 0, 2))
        assertEquals(listOf("b", "c", "a", "d"), s.phrasesIn("甲"))
        assertTrue(s.reorderPhrase("甲", 3, 0))
        assertEquals(listOf("d", "b", "c", "a"), s.phrasesIn("甲"))
        assertEquals(listOf("d", "b", "c", "a"), ClipboardStore(dir).apply { load() }.phrasesIn("甲"))
    }

    @Test fun new_category_with_pending_clip_lands_the_clip_in_it() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load(); addCategory("默认") }
        val name = "工作".trim()
        s.addCategory(name); s.addPhrasesTo(name, listOf("hello"))
        assertEquals(listOf("hello"), s.phrasesIn("工作"))
        assertFalse("未确认不应创建分类", "私人" in ClipboardStore(dir).apply { load() }.categories())
    }

    @Test fun new_category_with_pending_move_lands_item_in_it() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("默认"); addPhrasesTo("默认", listOf("你好", "在吗")) }
        val name = "工作".trim()
        s.addCategory(name); s.movePhrasesTo("默认", listOf("你好"), name)
        assertEquals(listOf("在吗"), s.phrasesIn("默认"))
        assertEquals(listOf("你好"), s.phrasesIn("工作"))
    }

    @Test fun reorder_phrase_rejects_bad_indices_and_noops() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("a", "b")) }
        assertFalse(s.reorderPhrase("甲", 0, 0))
        assertFalse(s.reorderPhrase("甲", -1, 1))
        assertFalse(s.reorderPhrase("甲", 0, 5))
        assertFalse(s.reorderPhrase("无", 0, 1))
        assertEquals(listOf("a", "b"), s.phrasesIn("甲"))
    }

    @Test fun reorder_category_moves_category_and_persists() {
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load(); addCategory("甲"); addCategory("乙"); addCategory("丙") }
        assertTrue(s.reorderCategory(3, 1))
        assertEquals(listOf(ClipboardStore.DEFAULT_CATEGORY_ID, "丙", "甲", "乙"), s.categories())
        assertTrue(s.reorderCategory(0, 3))
        assertEquals(listOf("丙", "甲", "乙", ClipboardStore.DEFAULT_CATEGORY_ID), s.categories())
        assertEquals(listOf("丙", "甲", "乙", ClipboardStore.DEFAULT_CATEGORY_ID), ClipboardStore(dir).apply { load() }.categories())
    }

    @Test fun reorder_category_rejects_bad_indices_and_noops() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addCategory("乙") }
        assertFalse(s.reorderCategory(0, 0))
        assertFalse(s.reorderCategory(-1, 1))
        assertFalse(s.reorderCategory(0, 3))
        assertEquals(listOf(ClipboardStore.DEFAULT_CATEGORY_ID, "甲", "乙"), s.categories())
    }


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
        assertTrue(s.setPhraseNote("甲", "orig2", "  "))
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
        val s = ClipboardStore(newDir()).apply {
            load(); addCategory("工作"); addCategory("私人")
            addPhrasesTo("工作", listOf("谢谢")); addPhrasesTo("私人", listOf("谢谢"))
            setPhraseNote("工作", "谢谢", "thx")
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


    @Test fun clearPhrasesIn_empties_but_keeps_category() {
        val s = ClipboardStore(newDir()).apply { load(); addCategory("甲"); addPhrasesTo("甲", listOf("a", "b", "c")) }
        assertEquals(3, s.clearPhrasesIn("甲"))
        assertTrue(s.phrasesIn("甲").isEmpty())
        assertTrue("category still exists", "甲" in s.categories())
        assertEquals("already empty → 0", 0, s.clearPhrasesIn("甲"))
        assertEquals("unknown → 0", 0, s.clearPhrasesIn("无"))
    }

    @Test fun clearPhrasesIn_only_empties_the_named_category() {
        val s = ClipboardStore(newDir()).apply {
            load()
            addCategory("甲")
            addCategory("乙")
            addPhrasesTo("甲", listOf("a"))
            addPhrasesTo("乙", listOf("b"))
        }
        assertEquals(1, s.clearPhrasesIn("甲"))
        assertTrue(s.phrasesIn("甲").isEmpty())
        assertEquals(listOf("b"), s.phrasesIn("乙"))
    }


    @Test fun shouldCapture_only_gated_by_history_switch() {
        assertTrue("history on → capture (even secure fields)", ClipboardStore.shouldCapture(true))
        assertFalse("history off → never capture", ClipboardStore.shouldCapture(false))
    }
}
