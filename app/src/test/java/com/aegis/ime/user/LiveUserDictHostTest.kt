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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LiveUserDictHostTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: File
    private val model = UserModel()
    private val savedMtimes = mutableListOf<Long>()

    private fun host(): LiveUserDictHost {
        db = File(tmp.root, "userdb.txt")
        return LiveUserDictHost(model, db) { savedMtimes += it }
    }

    private fun reloadFromDisk() = UserModel().apply { if (db.exists()) load(db) }


    @Test fun add_lands_in_the_live_model_and_the_file_in_one_call() {
        val h = host()
        val v0 = model.version
        assertTrue(h.addWord("nihao", "你好", now = 1L))
        assertTrue("version must bump so the decoder rebuilds its recall index", model.version > v0)
        assertEquals(listOf("你好"), model.readingSnapshot()["nihao"])
        assertEquals(listOf("你好"), reloadFromDisk().readingSnapshot()["nihao"])
        assertFalse("saved → not dirty", model.dirty)
        assertEquals(1, savedMtimes.size)
    }

    @Test fun add_while_dirty_keeps_the_unsaved_learning_and_the_new_word() {
        val h = host()
        model.record(null, "学习", 5L)
        assertTrue(model.dirty)
        assertTrue(h.addWord("ceshi", "测试", now = 6L))
        assertTrue(model.wordBoost("学习") > 0.0)
        assertEquals(listOf("测试"), model.readingSnapshot()["ceshi"])
        val disk = reloadFromDisk()
        assertTrue("unsaved learning must be persisted, not clobbered", disk.wordBoost("学习") > 0.0)
        assertEquals(listOf("测试"), disk.readingSnapshot()["ceshi"])
    }

    @Test fun blank_word_is_rejected_without_touching_the_file() {
        val h = host()
        assertFalse(h.addWord("nihao", "  ", now = 1L))
        assertFalse(db.exists())
        assertEquals(0, savedMtimes.size)
    }


    @Test fun remove_deletes_from_the_live_model_and_the_file() {
        val h = host()
        h.addWord("nihao", "你好", now = 1L)
        h.addWord("nihao", "尼豪", now = 2L)
        assertTrue(h.removeWord("nihao", "尼豪"))
        assertEquals(listOf("你好"), model.readingSnapshot()["nihao"])
        assertEquals(listOf("你好"), reloadFromDisk().readingSnapshot()["nihao"])
    }

    @Test fun remove_while_dirty_keeps_the_unsaved_learning() {
        val h = host()
        h.addWord("nihao", "你好", now = 1L)
        model.record(null, "学习", 5L)
        assertTrue(h.removeWord("nihao", "你好"))
        val disk = reloadFromDisk()
        assertTrue(disk.wordBoost("学习") > 0.0)
        assertTrue(disk.readingSnapshot()["nihao"] == null)
    }


    @Test fun import_merge_while_dirty_unions_unsaved_learning_with_the_import() {
        val h = host()
        h.addWord("nihao", "你好", now = 1L)
        model.record(null, "学习", 5L)
        val imported = tmp.newFile("import.txt").apply {
            writeText("aegis-userdb 1\nW\t测试\t3\t7\nR\tceshi\t测试\n")
        }
        assertTrue(h.importUserDict(imported, merge = true, now = 8L))
        val disk = reloadFromDisk()
        assertTrue("unsaved learning survives a merge import", disk.wordBoost("学习") > 0.0)
        assertEquals("pre-existing entries survive", listOf("你好"), disk.readingSnapshot()["nihao"])
        assertEquals("imported entries arrive", listOf("测试"), disk.readingSnapshot()["ceshi"])
        assertFalse(model.dirty)
    }


    @Test fun import_overwrite_replaces_the_live_model_wholesale() {
        val h = host()
        h.addWord("nihao", "你好", now = 1L)
        val imported = tmp.newFile("import.txt").apply {
            writeText("aegis-userdb 1\nW\t测试\t3\t7\nR\tceshi\t测试\n")
        }
        assertTrue(h.importUserDict(imported, merge = false, now = 8L))
        assertEquals(null, model.readingSnapshot()["nihao"])
        assertEquals(listOf("测试"), model.readingSnapshot()["ceshi"])
        assertEquals(listOf("测试"), reloadFromDisk().readingSnapshot()["ceshi"])
    }

    @Test fun junk_import_never_wipes_the_live_dictionary() {
        val h = host()
        h.addWord("nihao", "你好", now = 1L)
        val missing = File(tmp.root, "missing.txt")
        assertFalse(h.importUserDict(missing, merge = true, now = 2L))
        val empty = tmp.newFile("empty.txt")
        assertFalse(h.importUserDict(empty, merge = false, now = 3L))
        val invalid = tmp.newFile("invalid.txt").apply { writeText("not a userdb at all\n") }
        assertFalse("overwrite with no valid entries must be rejected", h.importUserDict(invalid, merge = false, now = 4L))
        assertEquals(listOf("你好"), model.readingSnapshot()["nihao"])
        assertEquals(listOf("你好"), reloadFromDisk().readingSnapshot()["nihao"])
    }


    @Test fun entries_reflect_the_live_model_not_the_file() {
        val h = host()
        h.addWord("nihao", "你好", now = 1L)
        model.recordWord("ceshi", "测试", 2L, incrementCount = true)
        val entries = h.entries()
        assertTrue(entries.any { it.reading == "nihao" && it.word == "你好" })
        assertTrue("the settings list must see unsaved live words", entries.any { it.reading == "ceshi" && it.word == "测试" })
    }

    @Test fun flush_persists_unsaved_learning_before_an_export_and_noops_when_clean() {
        val h = host()
        h.addWord("nihao", "你好", now = 1L)
        val savesAfterAdd = savedMtimes.size
        h.flush()
        assertEquals(savesAfterAdd, savedMtimes.size)
        model.record(null, "学习", 5L)
        h.flush()
        assertEquals(savesAfterAdd + 1, savedMtimes.size)
        assertTrue(reloadFromDisk().wordBoost("学习") > 0.0)
        assertFalse(model.dirty)
    }
}
