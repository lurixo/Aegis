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
}
