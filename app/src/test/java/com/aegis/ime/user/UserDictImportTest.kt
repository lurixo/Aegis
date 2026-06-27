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

/** ④ — import 覆盖/合并 must apply cleanly and, above all, never silently erase the learned dict. */
class UserDictImportTest {

    private fun userdbWith(vararg words: String): File {
        val f = File.createTempFile("userdb", ".txt")
        UserModel().apply { for (w in words) record(null, w, 1) }.save(f)
        return f
    }

    private fun importFileWith(vararg words: String): File {
        val f = File.createTempFile("imp", ".txt")
        UserModel().apply { for (w in words) record(null, w, 1) }.save(f)
        return f
    }

    private fun boost(db: File, word: String) = UserModel().apply { load(db) }.wordBoost(word)

    @Test
    fun mergeAccumulates() {
        val live = userdbWith("旧"); val imp = importFileWith("新")
        assertTrue(UserDictImport.apply(imp, live, merge = true, now = 1))
        assertTrue(boost(live, "旧") > 0.0)
        assertTrue(boost(live, "新") > 0.0)
        live.delete(); imp.delete()
    }

    @Test
    fun overwriteReplaces() {
        val live = userdbWith("旧"); val imp = importFileWith("新")
        assertTrue(UserDictImport.apply(imp, live, merge = false, now = 1))
        assertEquals(0.0, boost(live, "旧"), 0.0)
        assertTrue(boost(live, "新") > 0.0)
        live.delete(); imp.delete()
    }

    @Test
    fun overwriteWithMissingImportNeverWipes() {
        val live = userdbWith("重要")
        val missing = File.createTempFile("imp", ".txt").apply { delete() }
        assertFalse(UserDictImport.apply(missing, live, merge = false, now = 1))
        assertTrue("dict untouched", boost(live, "重要") > 0.0)
        live.delete()
    }

    @Test
    fun overwriteWithEmptyImportNeverWipes() {
        val live = userdbWith("重要")
        val empty = File.createTempFile("imp", ".txt") // 0 bytes
        assertFalse(UserDictImport.apply(empty, live, merge = false, now = 1))
        assertTrue(boost(live, "重要") > 0.0)
        live.delete(); empty.delete()
    }

    @Test
    fun overwriteWithEntrylessImportNeverWipes() {
        val live = userdbWith("重要")
        val junk = File.createTempFile("imp", ".txt").apply { writeText("garbage line\nnot a userdb\n") }
        assertFalse(UserDictImport.apply(junk, live, merge = false, now = 1))
        assertTrue(boost(live, "重要") > 0.0)
        live.delete(); junk.delete()
    }
}
