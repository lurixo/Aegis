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
import java.io.ByteArrayInputStream
import java.io.File

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
        val empty = File.createTempFile("imp", ".txt")
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

    @Test
    fun overwriteWithATombstoneOnlyImportNeverWipes() {
        val live = userdbWith("重要")
        val donor = File.createTempFile("imp-tomb", ".txt")
        UserModel().apply { assertTrue(addTombstone("受害词", "")) }.save(donor)
        assertTrue(
            "precondition: the donor file reads back, it just carries no words",
            donor.readLines().any { it.startsWith("D\t受害词") },
        )
        assertTrue("precondition: the donor file parses into nothing", UserModel().apply { load(donor) }.isEmpty())

        assertFalse(UserDictImport.apply(donor, live, merge = false, now = 1))

        assertTrue("a donor file with no words of its own must not empty this phone", boost(live, "重要") > 0.0)
        live.delete(); donor.delete()
    }

    @Test
    fun invalidCountersAreRejectedWithoutChangingTheLiveDictionary() {
        val live = userdbWith("重要")
        val malformed = File.createTempFile("imp", ".txt").apply {
            writeText("aegis-userdb 1\nW\t毒化\t-2\t0\n")
        }
        assertFalse(UserDictImport.apply(malformed, live, merge = true, now = 1))
        assertTrue(boost(live, "重要") > 0.0)
        assertEquals(0.0, boost(live, "毒化"), 0.0)
        live.delete(); malformed.delete()
    }

    @Test
    fun mergeUsesSaturatingArithmeticAndKeepsScoresFinite() {
        val live = File.createTempFile("userdb", ".txt").apply {
            writeText("aegis-userdb 1\nW\t词\t999999999\t1\n")
        }
        val incoming = File.createTempFile("imp", ".txt").apply {
            writeText("aegis-userdb 1\nW\t词\t10\t2\n")
        }
        assertTrue(UserDictImport.apply(incoming, live, merge = true, now = 3))
        assertTrue(boost(live, "词").isFinite())
        assertTrue(live.readLines().contains("W\t词\t1000000000\t2"))
        live.delete(); incoming.delete()
    }

    @Test
    fun stagingKeepsAnInputFarPastTheOldSizeGate() {
        val staged = File.createTempFile("imp-stage", ".txt")
        val bytes = ByteArray(5 * 1024 * 1024)
        assertTrue(UserDictImport.stage(ByteArrayInputStream(bytes), staged))
        assertEquals("nothing is dropped on the way in", bytes.size.toLong(), staged.length())
        staged.delete()
    }

    @Test
    fun stagingAnEmptyInputStillFails() {
        val staged = File.createTempFile("imp-empty", ".txt")
        assertFalse(UserDictImport.stage(ByteArrayInputStream(ByteArray(0)), staged))
        assertFalse("nothing is staged", staged.exists())
        staged.delete()
    }
}
