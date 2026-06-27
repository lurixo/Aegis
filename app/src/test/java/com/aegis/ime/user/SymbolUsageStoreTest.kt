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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SymbolUsageStoreTest {

    private fun newDir(): File = Files.createTempDirectory("symusage").toFile()

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
        assertEquals(listOf("≈", "÷"), SymbolUsageStore(dir).apply { load() }.recent())
    }

    @Test fun caps_history_size() {
        val s = SymbolUsageStore(newDir()).apply { load() }
        for (i in 0 until 50) s.record("s$i")
        assertTrue("recent must be capped", s.recent(100).size <= 30)
        assertEquals("most recent stays at the front", "s49", s.recent().first())
    }
}
