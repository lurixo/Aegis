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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Chinese IME behavior note. */
class SymbolUsageStoreTest {

    private fun newDir(): File = Files.createTempDirectory("symusage").toFile()

    @Test fun records_newest_first_and_dedupes() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        s.record("★"); s.record("→"); s.record("★") // re-using ★ moves it back to front
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
        assertEquals(listOf("≈", "÷"), SymbolUsageStore(dir).apply { load() }.recent())
    }

    @Test fun load_dedupes_a_file_with_duplicate_lines() {
        // U2: a pre-existing file with duplicate lines must surface each symbol only once.
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

    // --- origin badge (item ①) ---

    @Test fun records_carry_and_expose_their_origin_category() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        s.record("\$", "货币")   // the SAME $ entered from the currency tab
        s.record("℃", "角标")
        assertEquals("货币", s.originOf("\$"))
        assertEquals("角标", s.originOf("℃"))
        assertEquals(listOf(SymbolUsageStore.Entry("℃", "角标"), SymbolUsageStore.Entry("\$", "货币")), s.recentEntries())
    }

    @Test fun origin_persists_across_reload() {
        val dir = newDir()
        SymbolUsageStore(dir).apply { load(); record("\$", "货币"); record("π", "希腊") }
        val reloaded = SymbolUsageStore(dir).apply { load() }
        assertEquals(listOf("π", "\$"), reloaded.recent())
        assertEquals("希腊", reloaded.originOf("π"))
        assertEquals("货币", reloaded.originOf("\$"))
    }

    @Test fun re_recording_keeps_the_latest_origin() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        s.record("\$", "英文")
        s.record("\$", "货币")   // same code point, later tap from a different tab
        assertEquals(listOf("\$"), s.recent())
        assertEquals("最后一次输入的来源生效", "货币", s.originOf("\$"))
    }

    // --- full/half-width fold de-dup (item ②) ---

    @Test fun full_and_half_width_same_char_collapse_keeping_the_later_form() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        s.record("%", "英文")     // half-width first
        s.record("％", "数学")     // full-width later -> only the later one survives, in its real code point
        assertEquals(listOf("％"), s.recent())
        assertEquals("数学", s.originOf("％"))
        // and the other direction: full-width first, half-width later
        val t = SymbolUsageStore(newDir()).apply { load() }
        t.record("！", "中文"); t.record("!", "英文")
        assertEquals(listOf("!"), t.recent())
        assertEquals("英文", t.originOf("!"))
    }

    @Test fun cross_character_lookalikes_are_not_folded() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        // en-dash vs em-dash, middle dot vs bullet, multiplication sign vs letter x, ㎡ vs plain — all distinct.
        s.record("–"); s.record("—"); s.record("·"); s.record("•"); s.record("×"); s.record("x"); s.record("㎡")
        assertEquals(setOf("–", "—", "·", "•", "×", "x", "㎡"), s.recent().toSet())
        assertEquals("no approximate form was collapsed", 7, s.recent().size)
    }

    @Test fun exact_same_codepoint_still_dedupes_keeping_the_later() {
        // regression: the pre-existing exact-string de-dup must not be lost.
        val s = SymbolUsageStore(newDir()).apply { load() }
        s.record("\$", "英文"); s.record("¥", "货币"); s.record("\$", "货币")
        assertEquals(listOf("\$", "¥"), s.recent())
        assertEquals("货币", s.originOf("\$"))
    }

    // --- backward compatibility (item ①) ---

    @Test fun old_symbol_only_file_reads_back_with_null_origin() {
        val dir = newDir()
        File(dir, "symbol_usage.txt").writeText("\$\n℃\nπ")   // legacy format: bare symbols, no origin column
        val s = SymbolUsageStore(dir).apply { load() }
        assertEquals(listOf("\$", "℃", "π"), s.recent())
        assertNull("legacy entries have no stored origin", s.originOf("\$"))
        assertNull(s.originOf("℃"))
    }

    @Test fun load_folds_full_half_width_duplicate_lines() {
        val dir = newDir()
        File(dir, "symbol_usage.txt").writeText("％\t数学\n%\t英文\n！\t中文")  // ％ and % are the same folded key
        val s = SymbolUsageStore(dir).apply { load() }
        assertEquals("newest (top) survives the fold on load", listOf("％", "！"), s.recent())
        assertEquals("数学", s.originOf("％"))
    }

    // --- v2 exhaustive: no per-symbol special-casing, whole catalogue ---

    @Test fun every_symbol_in_every_category_records_and_reports_its_origin() {
        // 501 (tab, symbol) cases: recording ANY catalogue symbol from ANY tab must store and report that
        // tab as its origin. Fails on the first symbol that loses or mis-attributes its source.
        var cases = 0
        for (cat in SymbolCatalog.categories) {
            for (sym in cat.symbols) {
                val s = SymbolUsageStore(newDir()).apply { load() }
                s.record(sym, cat.title)
                assertEquals("origin of $sym recorded from ${cat.title}", cat.title, s.originOf(sym))
                assertEquals(listOf(sym), s.recent())
                cases++
            }
        }
        assertTrue("covered the whole catalogue", cases >= 500)
    }

    @Test fun every_fullwidth_pair_collapses_to_the_later_form_in_both_orders() {
        // Enumerate every full/half-width pair from the catalogue and assert de-dup keeps ONLY the later form,
        // with the later origin, whichever order it was entered — no symbol is special-cased.
        val seen = HashSet<Char>()   // ％ appears in two tabs; exercise each distinct pair once
        for (cat in SymbolCatalog.categories) {
            for (full in cat.symbols) {
                if (full.length != 1) continue
                val c = full[0].code
                if (c !in 0xFF01..0xFF5E || !seen.add(full[0])) continue
                val half = (c - 0xFEE0).toChar().toString()

                // half first, full later -> full survives
                val a = SymbolUsageStore(newDir()).apply { load() }
                a.record(half, "英文"); a.record(full, "中文")
                assertEquals("$half then $full keeps $full", listOf(full), a.recent())
                assertEquals("中文", a.originOf(full))

                // full first, half later -> half survives
                val b = SymbolUsageStore(newDir()).apply { load() }
                b.record(full, "中文"); b.record(half, "英文")
                assertEquals("$full then $half keeps $half", listOf(half), b.recent())
                assertEquals("英文", b.originOf(half))
            }
        }
        assertEquals("all 22 distinct catalogue pairs exercised", 22, seen.size)
    }
}
