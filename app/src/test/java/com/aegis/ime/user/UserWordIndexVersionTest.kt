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
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserWordIndexVersionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun committingKnownWordsLeavesTheReadingIndexAlone() {
        val m = UserModel { 10L }
        m.recordWord("ceshi", "测试", 1L, incrementCount = true)
        val indexed = m.readingsVersion
        m.record("你好", "测试", 2L)
        m.recordWord("ceshi", "测试", 3L, incrementCount = true)
        assertEquals(indexed, m.readingsVersion)
        m.recordWord("ceshi", "侧视", 4L, incrementCount = true)
        assertNotEquals(indexed, m.readingsVersion)
    }

    @Test fun savingLeavesLaterChangesUnsaved() {
        val m = UserModel { 10L }
        m.recordWord("ceshi", "测试", 1L, incrementCount = true)
        val file = tmp.newFile("userdb.txt")
        m.save(file)
        assertEquals(false, m.dirty)
        m.record(null, "测试", 2L)
        assertEquals(true, m.dirty)
        m.save(file)
        assertEquals(false, m.dirty)
        assertEquals(m.userWordEntries(), UserModel { 10L }.apply { load(file, sweepStale = false) }.userWordEntries())
    }
}
