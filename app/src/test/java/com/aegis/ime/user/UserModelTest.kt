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

class UserModelTest {

    @Test
    fun learnsBoostAndPredicts() {
        val m = UserModel()
        assertEquals(0.0, m.wordBoost("你好"), 0.0)
        m.record(null, "你好", 1000)
        m.record("你好", "世界", 1001)
        m.record("你好", "世界", 1002)
        m.record("你好", "啊", 1003)
        assertTrue("seen word gets positive boost", m.wordBoost("你好") > 0.0)
        assertEquals(listOf("世界", "啊"), m.successors("你好", 8))
        assertTrue(m.dirty)
    }

    @Test
    fun saveLoadRoundtrip() {
        val m = UserModel()
        m.record(null, "测试", 5)
        m.record("测试", "用例", 6)
        val f = File.createTempFile("userdb", ".txt")
        m.save(f)
        assertFalse("save clears dirty", m.dirty)
        val m2 = UserModel().apply { load(f) }
        assertEquals(m.wordBoost("测试"), m2.wordBoost("测试"), 1e-9)
        assertEquals(listOf("用例"), m2.successors("测试", 8))
        f.delete()
    }

    @Test
    fun importOverwriteReplaces() {
        val userDb = File.createTempFile("userdb", ".txt")
        UserModel().apply { record(null, "旧", 1); record("旧", "话", 1) }.save(userDb)
        val importFile = File.createTempFile("imp", ".txt")
        UserModel().apply { record(null, "新", 3); record("新", "词", 3) }.save(importFile)

        UserModel().apply { load(importFile) }.save(userDb)

        val reloaded = UserModel().apply { load(userDb) }
        assertEquals("overwrite drops old entries", 0.0, reloaded.wordBoost("旧"), 0.0)
        assertTrue("overwrite keeps imported entries", reloaded.wordBoost("新") > 0.0)
        assertEquals(listOf("词"), reloaded.successors("新", 8))
        assertTrue("no old successors survive", reloaded.successors("旧", 8).isEmpty())
        userDb.delete(); importFile.delete()
    }

    @Test
    fun importMerges() {
        val a = UserModel().apply { record(null, "词", 1) }
        val b = UserModel().apply { record(null, "词", 1); record("词", "条", 1) }
        val f = File.createTempFile("udb", ".txt")
        b.save(f)
        val before = a.wordBoost("词")
        a.importFrom(f, 9)
        assertTrue("merged count raises boost", a.wordBoost("词") > before)
        assertEquals(listOf("条"), a.successors("词", 8))
        f.delete()
    }
}
