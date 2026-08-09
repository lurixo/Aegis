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

import com.aegis.ime.layout.SymbolCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class SymbolUsageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var release: CountDownLatch? = null

    @After fun letGo() {
        release?.countDown()
        SymbolUsageStore.flushPendingWrites()
    }

    private fun newDir(): File = tmp.newFolder()

    private fun occupyTheWriteLane() {
        val field = SymbolUsageStore::class.java.getDeclaredField("io")
        field.isAccessible = true
        val io = field.get(null) as ExecutorService
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        release = gate
        io.execute {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
        assertTrue("precondition: the symbol writer is occupied", entered.await(2, TimeUnit.SECONDS))
    }

    @Test fun records_newest_first_and_dedupes() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        s.record("★"); s.record("→"); s.record("★")
        assertEquals(listOf("★", "→"), s.recent())
    }

    @Test fun blank_is_ignored() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        s.record(""); s.record("π")
        assertEquals(listOf("π"), s.recent())
    }

    @Test fun persists_across_reload() {
        val dir = newDir()
        SymbolUsageStore(dir).apply { load(); record("÷"); record("≈") }
        SymbolUsageStore.flushPendingWrites()
        assertEquals(listOf("≈", "÷"), SymbolUsageStore(dir).apply { load() }.recent())
    }

    @Test fun clear_removes_memory_and_persisted_recents() {
        val dir = newDir()
        val store = SymbolUsageStore(dir).apply {
            load()
            record("÷", "math")
            record("≈", "math")
        }
        store.clear()
        assertTrue(store.recent().isEmpty())
        assertTrue(store.recentEntries().isEmpty())
        assertTrue(SymbolUsageStore(dir).apply { load() }.recent().isEmpty())
        assertEquals("", File(dir, "symbol_usage.txt").readText())
    }

    @Test fun load_dedupes_a_file_with_duplicate_lines() {
        val dir = newDir()
        File(dir, "symbol_usage.txt").writeText("★\n→\n★\n→\n☆")
        val s = SymbolUsageStore(dir).apply { load() }
        assertEquals(listOf("★", "→", "☆"), s.recent())
    }

    @Test fun caps_history_size() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        for (i in 0 until 50) s.record("s$i")
        assertTrue("recent must be capped", s.recent(100).size <= 30)
        assertEquals("most recent stays at the front", "s49", s.recent().first())
    }


    @Test fun records_carry_and_expose_their_origin_category() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        s.record("\$", "货币")
        s.record("℃", "角标")
        assertEquals("货币", s.originOf("\$"))
        assertEquals("角标", s.originOf("℃"))
        assertEquals(listOf(SymbolUsageStore.Entry("℃", "角标"), SymbolUsageStore.Entry("\$", "货币")), s.recentEntries())
    }

    @Test fun origin_persists_across_reload() {
        val dir = newDir()
        SymbolUsageStore(dir).apply { load(); record("\$", "货币"); record("π", "希腊") }
        SymbolUsageStore.flushPendingWrites()
        val reloaded = SymbolUsageStore(dir).apply { load() }
        assertEquals(listOf("π", "\$"), reloaded.recent())
        assertEquals("希腊", reloaded.originOf("π"))
        assertEquals("货币", reloaded.originOf("\$"))
    }

    @Test fun re_recording_keeps_the_latest_origin() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        s.record("\$", "英文")
        s.record("\$", "货币")
        assertEquals(listOf("\$"), s.recent())
        assertEquals("最后一次输入的来源生效", "货币", s.originOf("\$"))
    }


    @Test fun full_and_half_width_same_char_collapse_keeping_the_later_form() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        s.record("%", "英文")
        s.record("％", "数学")
        assertEquals(listOf("％"), s.recent())
        assertEquals("数学", s.originOf("％"))
        val t = SymbolUsageStore(newDir()).apply { load() }
        t.record("！", "中文"); t.record("!", "英文")
        assertEquals(listOf("!"), t.recent())
        assertEquals("英文", t.originOf("!"))
    }

    @Test fun cross_character_lookalikes_are_not_folded() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        s.record("–"); s.record("—"); s.record("·"); s.record("•"); s.record("×"); s.record("x"); s.record("㎡")
        assertEquals(setOf("–", "—", "·", "•", "×", "x", "㎡"), s.recent().toSet())
        assertEquals("no approximate form was collapsed", 7, s.recent().size)
    }

    @Test fun exact_same_codepoint_still_dedupes_keeping_the_later() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        s.record("\$", "英文"); s.record("¥", "货币"); s.record("\$", "货币")
        assertEquals(listOf("\$", "¥"), s.recent())
        assertEquals("货币", s.originOf("\$"))
    }


    @Test fun old_symbol_only_file_reads_back_with_null_origin() {
        val dir = newDir()
        File(dir, "symbol_usage.txt").writeText("\$\n℃\nπ")
        val s = SymbolUsageStore(dir).apply { load() }
        assertEquals(listOf("\$", "℃", "π"), s.recent())
        assertNull("legacy entries have no stored origin", s.originOf("\$"))
        assertNull(s.originOf("℃"))
    }

    @Test fun load_folds_full_half_width_duplicate_lines() {
        val dir = newDir()
        File(dir, "symbol_usage.txt").writeText("％\t数学\n%\t英文\n！\t中文")
        val s = SymbolUsageStore(dir).apply { load() }
        assertEquals("newest (top) survives the fold on load", listOf("％", "！"), s.recent())
        assertEquals("数学", s.originOf("％"))
    }


    @Test fun every_symbol_in_every_category_records_and_reports_its_origin() {
        var cases = 0
        for (cat in SymbolCatalog.categories) {
            for (sym in cat.symbols) {
                val s = SymbolUsageStore(newDir()).apply { load() }
                s.record(sym, cat.id)
                assertEquals("origin of $sym recorded from ${cat.id}", cat.id, s.originOf(sym))
                assertEquals(listOf(sym), s.recent())
                cases++
            }
        }
        assertTrue("covered the whole catalogue", cases >= 500)
    }

    @Test fun every_fullwidth_pair_collapses_to_the_later_form_in_both_orders() {
        val seen = HashSet<Char>()
        for (cat in SymbolCatalog.categories) {
            for (full in cat.symbols) {
                if (full.length != 1) continue
                val c = full[0].code
                if (c !in 0xFF01..0xFF5E || !seen.add(full[0])) continue
                val half = (c - 0xFEE0).toChar().toString()

                val a = SymbolUsageStore(newDir()).apply { load() }
                a.record(half, "英文"); a.record(full, "中文")
                assertEquals("$half then $full keeps $full", listOf(full), a.recent())
                assertEquals("中文", a.originOf(full))

                val b = SymbolUsageStore(newDir()).apply { load() }
                b.record(full, "中文"); b.record(half, "英文")
                assertEquals("$full then $half keeps $half", listOf(half), b.recent())
                assertEquals("英文", b.originOf(half))
            }
        }
        assertEquals("all 22 distinct catalogue pairs exercised", 22, seen.size)
    }

    @Test fun recording_a_symbol_does_not_write_on_the_thread_that_typed_it() {
        val dir = newDir()
        val s = SymbolUsageStore(dir).apply { load() }
        occupyTheWriteLane()

        s.record("★", "符号")

        assertFalse(
            "a symbol tap must be handed to the writer, not written where it was typed",
            File(dir, "symbol_usage.txt").exists(),
        )
        release?.countDown()
        SymbolUsageStore.flushPendingWrites()
        assertEquals(listOf("★"), SymbolUsageStore(dir).apply { load() }.recent())
    }

    @Test fun a_clear_never_loses_to_a_record_still_queued_behind_it() {
        val dir = newDir()
        val s = SymbolUsageStore(dir).apply { load() }
        occupyTheWriteLane()

        s.record("★", "符号")
        s.record("→", "符号")
        release?.countDown()
        s.clear()

        assertEquals("", File(dir, "symbol_usage.txt").readText())
        assertTrue(SymbolUsageStore(dir).apply { load() }.recent().isEmpty())
    }

    @Test fun two_symbol_stores_over_one_directory_never_share_a_temp_file() {
        val dir = newDir()
        assertNotEquals(SymbolUsageStore(dir).tempFile(), SymbolUsageStore(dir).tempFile())
    }

    @Test fun a_symbol_history_that_reads_fine_is_reported_as_readable() {
        val dir = newDir()
        SymbolUsageStore(dir).apply { load(); record("÷", "math") }
        SymbolUsageStore.flushPendingWrites()
        assertTrue(SymbolUsageStore(dir).apply { load() }.readable)
    }

    @Test fun a_symbol_history_that_was_never_written_is_reported_as_readable() {
        assertTrue(SymbolUsageStore(newDir()).apply { load() }.readable)
    }

    @Test fun a_symbol_history_that_cannot_be_read_is_reported_as_unreadable() {
        val dir = newDir()
        SymbolUsageStore(dir).apply { load(); record("÷", "math") }
        SymbolUsageStore.flushPendingWrites()
        val usage = File(dir, "symbol_usage.txt")
        assertTrue("precondition: the symbol history could be closed off", usage.setReadable(false, false))
        try {
            assertFalse(SymbolUsageStore(dir).apply { load() }.readable)
        } finally {
            usage.setReadable(true, true)
        }
    }
}
