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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserDictEditDispatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After fun clearHost() {
        UserDictHot.host = null
    }

    private class RecordingHost : UserDictHot.Host {
        val calls = mutableListOf<String>()
        override fun addWord(reading: String, word: String, now: Long): Boolean {
            calls += "add:$reading:$word"; return true
        }
        override fun removeWord(reading: String, word: String): Boolean {
            calls += "remove:$reading:$word"; return true
        }
        override fun importUserDict(importFile: File, merge: Boolean, now: Long): Boolean {
            calls += "import:merge=$merge"; return true
        }
        override fun entries(): List<UserModel.Entry> {
            calls += "entries"
            return listOf(UserModel.Entry("live", "活词", 9))
        }
        override fun flush() {
            calls += "flush"
        }
    }

    @Test fun with_a_live_host_every_operation_routes_to_it_and_never_touches_the_file() {
        val live = RecordingHost()
        UserDictHot.host = live
        val db = File(tmp.root, "userdb.txt")

        assertTrue(UserDictEdit.add(db, "词", "ci", now = 1L))
        assertTrue(UserDictEdit.remove(db, "ci", "词"))
        val imp = tmp.newFile("import.txt").apply { writeText("aegis-userdb 1\nR\tci\t词\n") }
        assertTrue(UserDictEdit.applyImport(db, imp, merge = true, now = 2L))
        assertEquals(listOf(UserModel.Entry("live", "活词", 9)), UserDictEdit.list(db))
        UserDictEdit.flushBeforeExport(db)

        assertEquals(
            listOf("add:ci:词", "remove:ci:词", "import:merge=true", "entries", "flush"),
            live.calls,
        )
        assertTrue("the file path must not be used while a live host is registered", !db.exists())
    }

    @Test fun without_a_host_operations_fall_back_to_the_file_and_round_trip() {
        val db = File(tmp.root, "userdb.txt")
        assertTrue(UserDictEdit.add(db, "你好", "nihao", now = 1L))
        assertEquals(listOf("你好"), UserDictEdit.list(db).filter { it.reading == "nihao" }.map { it.word })

        val imp = tmp.newFile("import.txt").apply { writeText("aegis-userdb 1\nW\t测试\t3\t7\nR\tceshi\t测试\n") }
        assertTrue(UserDictEdit.applyImport(db, imp, merge = true, now = 2L))
        val readings = UserDictEdit.list(db).map { it.reading to it.word }
        assertTrue(("nihao" to "你好") in readings)
        assertTrue(("ceshi" to "测试") in readings)

        assertTrue(UserDictEdit.remove(db, "nihao", "你好"))
        assertTrue(UserDictEdit.list(db).none { it.reading == "nihao" })

        UserDictEdit.flushBeforeExport(db)
    }
}
