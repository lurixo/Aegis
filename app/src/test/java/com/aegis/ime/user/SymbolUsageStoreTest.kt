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
import java.util.concurrent.CopyOnWriteArrayList
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
        assertTrue("a clear that emptied the file must say it was taken", store.clear())
        SymbolUsageStore.flushPendingWrites()
        assertTrue(store.recent().isEmpty())
        assertTrue(store.recentEntries().isEmpty())
        assertTrue(SymbolUsageStore(dir).apply { load() }.recent().isEmpty())
        assertEquals("", File(dir, "symbol_usage.txt").readText())
    }

    @Test fun a_symbol_history_whose_bytes_went_bad_reads_as_one_nobody_could_read() {
        val dir = newDir()
        val file = File(dir, "symbol_usage.txt")
        val onDisk = "★\t符号\n".toByteArray(Charsets.UTF_8) + byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0xFF.toByte())
        file.writeBytes(onDisk)
        val s = SymbolUsageStore(dir).apply { load() }

        assertFalse("a file the app cannot decode is not a history it could read", s.readable)
        assertTrue("and nothing half decoded may stand in for it", s.recent().isEmpty())

        s.record("☆", "符号")
        SymbolUsageStore.flushPendingWrites()
        assertEquals("nor may it be written over", onDisk.toList(), file.readBytes().toList())
    }

    @Test fun a_symbol_history_nobody_could_read_is_never_written_over() {
        val dir = newDir()
        val file = File(dir, "symbol_usage.txt").apply { writeText("★\t符号\n") }
        assertTrue("precondition: the file cannot be read back", file.setReadable(false, false))
        val s = SymbolUsageStore(dir).apply { load() }
        assertFalse("precondition: the store knows it could not read the history", s.readable)

        s.record("☆", "符号")
        SymbolUsageStore.flushPendingWrites()
        assertTrue("a symbol used afterwards must not stand in for the history", s.recent().isEmpty())

        assertFalse("a clear over a history nobody could read must not be reported as done", s.clear())
        SymbolUsageStore.flushPendingWrites()
        assertFalse(
            "merging into a history nobody could read must not be reported as done",
            s.importEntries(listOf(SymbolUsageStore.Entry("☆", "符号")), merge = true),
        )

        assertTrue(file.setReadable(true, false))
        assertEquals("what could not be read must not be thrown away either", "★\t符号\n", file.readText())
    }

    @Test fun an_overwriting_import_takes_back_a_symbol_history_nobody_could_read() {
        val dir = newDir()
        val file = File(dir, "symbol_usage.txt").apply { writeText("★\t符号\n") }
        assertTrue("precondition: the file cannot be read back", file.setReadable(false, false))
        val s = SymbolUsageStore(dir).apply { load() }
        assertFalse("precondition: the store knows it could not read the history", s.readable)

        assertTrue(
            "a restore that replaces the file outright is what takes it back",
            s.importEntries(listOf(SymbolUsageStore.Entry("☆", "符号")), merge = false),
        )
        SymbolUsageStore.flushPendingWrites()

        assertTrue("and the store is readable again", s.readable)
        assertTrue(file.setReadable(true, false))
        assertEquals("☆\t符号", file.readText())
        s.record("★", "符号")
        SymbolUsageStore.flushPendingWrites()
        assertEquals(listOf("★", "☆"), s.recent())
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
        SymbolUsageStore.flushPendingWrites()

        assertEquals("", File(dir, "symbol_usage.txt").readText())
        assertTrue(SymbolUsageStore(dir).apply { load() }.recent().isEmpty())
    }

    @Test(timeout = 30_000) fun a_write_that_never_finishes_does_not_hold_up_loading_the_recents() {
        val dir = newDir()
        val s = SymbolUsageStore(dir).apply { load(); record("★", "符号") }
        occupyTheWriteLane()

        val startedAt = System.nanoTime()
        s.clear()
        SymbolUsageStore(dir).apply { load() }
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(
            "clearing and loading the recents waited ${waitedMillis}ms behind a write that never finishes",
            waitedMillis < 2_000,
        )
        release?.countDown()
    }

    @Test fun a_clear_that_never_reached_the_file_leaves_the_recents_where_they_were() {
        val dir = newDir()
        val s = SymbolUsageStore(dir).apply { load(); record("★", "符号") }
        SymbolUsageStore.flushPendingWrites()
        assertTrue("precondition: the clear cannot reach the disk", s.tempFile().mkdirs())
        val reported = CopyOnWriteArrayList<Boolean>()
        s.reportWritesTo({ it.run() }) { reported.add(it) }

        assertTrue("the store takes the clear before the file has had its say", s.clear())
        SymbolUsageStore.flushPendingWrites()

        assertFalse("a clear that never reached the file must not come back as one that did", reported.single())
        assertEquals(
            "and what is still on the disk must still be on the panel",
            listOf("★"),
            s.recent(),
        )
    }

    @Test fun a_load_takes_the_file_and_a_tap_it_overtook_never_writes_over_it() {
        val dir = newDir()
        val s = SymbolUsageStore(dir).apply { load() }
        occupyTheWriteLane()

        s.record("★", "符号")
        File(dir, "symbol_usage.txt").writeText("☆\t恢复\n")
        s.load()

        assertEquals("a load must take up what the file holds now", listOf("☆"), s.recent())
        release?.countDown()
        SymbolUsageStore.flushPendingWrites()
        assertEquals(
            "and a tap the load overtook must not write over what was just taken up",
            "☆\t恢复\n",
            File(dir, "symbol_usage.txt").readText(),
        )
    }

    @Test fun a_symbol_used_right_after_a_clear_is_the_only_one_left_on_the_file() {
        val dir = newDir()
        val s = SymbolUsageStore(dir).apply { load(); record("★", "符号"); record("→", "符号") }
        SymbolUsageStore.flushPendingWrites()
        occupyTheWriteLane()

        assertTrue(s.clear())
        s.record("☆", "符号")

        release?.countDown()
        SymbolUsageStore.flushPendingWrites()

        assertEquals("the panel must show only what was used after the clear", listOf("☆"), s.recent())
        assertEquals(
            "and a tap made after a clear must not carry the cleared list back to the file",
            "☆\t符号",
            File(dir, "symbol_usage.txt").readText(),
        )
        assertEquals(listOf("☆"), SymbolUsageStore(dir).apply { load() }.recent())
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
