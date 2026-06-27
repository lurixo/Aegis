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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClipboardRestoreWriteGuardTest {

    private val dirs = ArrayList<File>()
    private val stores = ArrayList<ClipboardStore>()

    @After fun letGo() {
        LiveUserData.restoreInProgress = false
        stores.forEach { it.stopSaving() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newDir(): File = Files.createTempDirectory("cliprestoreguard").toFile().also { dirs += it }

    private fun store(dir: File) = ClipboardStore(dir).apply { load() }.also { stores += it }

    @Test fun recording_a_symbol_during_a_restore_never_overwrites_the_restored_history() {
        val dir = newDir()
        val live = SymbolUsageStore(dir).apply { load(); record("★", "符号") }
        SymbolUsageStore.flushPendingWrites()

        LiveUserData.restoreInProgress = true
        SymbolUsageStore(dir).apply { load() }
            .importEntries(listOf(SymbolUsageStore.Entry("恢", "备份")), merge = false)
        live.record("→", "符号")
        SymbolUsageStore.flushPendingWrites()

        assertEquals(listOf("恢"), SymbolUsageStore(dir).apply { load() }.recent())
    }

    @Test fun a_panel_delete_during_a_restore_says_it_was_not_written() {
        val dir = newDir()
        val live = store(dir)
        live.record("旧一")
        live.record("旧二")
        live.flushPendingWrites()

        LiveUserData.restoreInProgress = true

        assertFalse("a delete nobody wrote must not be reported as done", live.deleteAll(listOf(live.historyKeys().first())))
        assertFalse(live.clearHistory())
    }

    @Test fun a_panel_delete_outside_a_restore_says_it_was_written() {
        val dir = newDir()
        val live = store(dir)
        live.record("旧一")
        live.record("旧二")
        live.flushPendingWrites()

        assertTrue(live.deleteAll(listOf(live.historyKeys().first())))
        assertTrue(live.clearHistory())
        assertTrue("a delete with nothing to remove has nothing to report", live.deleteAll(listOf("不存在")))
    }
}
