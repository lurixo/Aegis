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

package com.aegis.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoreQuarantineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun a_damaged_store_is_moved_aside_and_its_bytes_survive() {
        val file = File(tmp.root, "userdb.txt").apply { writeText("poison") }
        assertTrue(quarantineCorruptStore(file))
        assertFalse("the damaged file no longer blocks the store path", file.exists())
        val aside = tmp.root.listFiles().orEmpty().single()
        assertTrue(aside.name.startsWith("userdb.txt.corrupt-"))
        assertEquals("poison", aside.readText())
    }

    @Test fun an_absent_store_needs_no_quarantine() {
        assertFalse(quarantineCorruptStore(File(tmp.root, "gone.txt")))
    }
}
