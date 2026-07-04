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

/** The settings-screen manual add/delete writes the SAME userdb.txt the IME and import/export share. */
class UserDictEditTest {

    @Test fun add_persists_and_isReadableByTheModel() {
        val db = File.createTempFile("userdb-edit", ".txt").also { it.deleteOnExit() }
        assertTrue(UserDictEdit.add(db, "测试", "ceshi", 1))
        assertTrue(UserDictEdit.add(db, "北京", "beijing", 2))

        val m = UserModel().apply { load(db) }
        assertEquals(listOf("测试"), m.readingSnapshot()["ceshi"])
        assertEquals(listOf("北京"), m.readingSnapshot()["beijing"])
        assertTrue("added word is boosted", m.wordBoost("测试") > 0.0)

        assertEquals("both listed", 2, UserDictEdit.list(db).size)
    }

    @Test fun remove_dropsFromRecallAndBoost() {
        val db = File.createTempFile("userdb-edit2", ".txt").also { it.deleteOnExit() }
        UserDictEdit.add(db, "测试", "ceshi", 1)
        UserDictEdit.add(db, "北京", "beijing", 2)

        assertTrue(UserDictEdit.remove(db, "ceshi", "测试"))
        val m = UserModel().apply { load(db) }
        assertEquals("removed word gone", null, m.readingSnapshot()["ceshi"])
        assertEquals("removed word not boosted", 0.0, m.wordBoost("测试"), 0.0)
        assertEquals("the other word remains", listOf("北京"), m.readingSnapshot()["beijing"])
        assertFalse("removed word not listed", UserDictEdit.list(db).any { it.word == "测试" })
    }

    @Test fun remove_isReadingScoped_keepsOtherReadingsOfTheSameWord() {
        // The same word committed under two readings appears as two list rows; deleting one row must keep the other.
        val db = File.createTempFile("userdb-edit5", ".txt").also { it.deleteOnExit() }
        UserDictEdit.add(db, "长", "chang", 1)
        UserDictEdit.add(db, "长", "zhang", 2) // 长 is a heteronym: two readings, one word
        assertTrue(UserDictEdit.remove(db, "chang", "长"))
        val m = UserModel().apply { load(db) }
        assertEquals("only the chang reading dropped", null, m.readingSnapshot()["chang"])
        assertEquals("the zhang reading of the same word survives", listOf("长"), m.readingSnapshot()["zhang"])
        assertTrue("word still boosted while a reading recalls it", m.wordBoost("长") > 0.0)
    }

    @Test fun add_isConsistentWithImportedEntries() {
        // A word added manually and a word arriving via import must land in the SAME store, deduped.
        val db = File.createTempFile("userdb-edit3", ".txt").also { it.deleteOnExit() }
        UserDictEdit.add(db, "测试", "ceshi", 1)
        val incoming = File.createTempFile("incoming", ".txt").also { it.deleteOnExit() }
        UserModel().apply { recordWord("beijing", "北京", 1, incrementCount = true) }.save(incoming)

        assertTrue(UserDictImport.apply(incoming, db, merge = true, now = 2))
        val m = UserModel().apply { load(db) }
        assertEquals(listOf("测试"), m.readingSnapshot()["ceshi"])
        assertEquals(listOf("北京"), m.readingSnapshot()["beijing"])
    }

    @Test fun blankWord_isRejected() {
        val db = File.createTempFile("userdb-edit4", ".txt").also { it.deleteOnExit() }
        assertFalse(UserDictEdit.add(db, "   ", "ceshi", 1))
    }
}
