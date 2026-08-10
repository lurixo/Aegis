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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files

class UserDictEditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun an_export_carries_what_the_word_list_holds_right_now() {
        val db = tmp.newFile("userdb-export.txt")
        assertTrue(UserDictEdit.add(db, "测试", "ceshi", 1))
        val out = ByteArrayOutputStream()

        assertEquals(UserDictEdit.ExportResult.WRITTEN, UserDictEdit.exportDictionary(db, out))

        assertEquals(db.readText(), out.toString(Charsets.UTF_8.name()))
    }

    @Test fun an_export_leaves_behind_the_words_a_deletion_is_still_owed_for() {
        val db = tmp.newFile("userdb-export-tombstone.txt")
        UserModel().apply {
            addManualWord("liuxia", "留下", 1L)
            addTombstone("刚删掉的", "gangshandiaode")
        }.save(db)
        val onDevice = db.readText()
        assertTrue(
            "precondition: the word list carries the deletion as plain text",
            onDevice.contains("D\t刚删掉的\tgangshandiaode\n"),
        )
        val out = ByteArrayOutputStream()

        assertEquals(UserDictEdit.ExportResult.WRITTEN, UserDictEdit.exportDictionary(db, out))

        val exported = out.toString(Charsets.UTF_8.name())
        assertFalse(
            "a word the user asked to delete must not go out with the file they share",
            exported.contains("刚删掉的"),
        )
        assertEquals(
            "and nothing else about the file may change",
            onDevice.split("\n").filterNot { it.startsWith("D\t") }.joinToString("\n"),
            exported,
        )
        assertEquals("the deletion the device still owes stays on the device", onDevice, db.readText())
    }

    @Test fun an_export_with_no_word_list_behind_it_is_reported_rather_than_passed_off_as_done() {
        val out = ByteArrayOutputStream()
        val missing = File(tmp.root, "never-written.txt")

        assertFalse("precondition: there is no word list to export", UserDictEdit.hasDictionaryToExport(missing))
        assertEquals(
            "an empty document is not a word list, and the reason for it is not the picked file",
            UserDictEdit.ExportResult.NOTHING_TO_EXPORT,
            UserDictEdit.exportDictionary(missing, out),
        )

        assertEquals("", out.toString(Charsets.UTF_8.name()))
    }

    @Test fun an_export_that_could_not_be_written_is_reported_rather_than_passed_off_as_done() {
        val db = tmp.newFile("userdb-export-refused.txt")
        assertTrue(UserDictEdit.add(db, "测试", "ceshi", 1))

        assertTrue("precondition: there is a word list to export", UserDictEdit.hasDictionaryToExport(db))
        assertEquals(
            "nowhere to write means nothing was exported",
            UserDictEdit.ExportResult.NOT_WRITTEN,
            UserDictEdit.exportDictionary(db, null),
        )
        assertEquals(
            "a sink that breaks part way through must not be reported as a finished export",
            UserDictEdit.ExportResult.NOT_WRITTEN,
            UserDictEdit.exportDictionary(
                db,
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("the document went away")
                    override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("the document went away")
                },
            ),
        )
    }

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
        val db = File.createTempFile("userdb-edit5", ".txt").also { it.deleteOnExit() }
        UserDictEdit.add(db, "长", "chang", 1)
        UserDictEdit.add(db, "长", "zhang", 2)
        assertTrue(UserDictEdit.remove(db, "chang", "长"))
        val m = UserModel().apply { load(db) }
        assertEquals("only the chang reading dropped", null, m.readingSnapshot()["chang"])
        assertEquals("the zhang reading of the same word survives", listOf("长"), m.readingSnapshot()["zhang"])
        assertTrue("word still boosted while a reading recalls it", m.wordBoost("长") > 0.0)
    }

    @Test fun file_fallback_remove_scrubs_and_persists_user_learning() {
        val dir = Files.createTempDirectory("userdict-edit-learning").toFile().also { it.deleteOnExit() }
        val db = File(dir, "userdb.txt")
        UserModel().apply { addManualWord("nihao", "你好", 1_000L) }.save(db)
        val userLearn = File(dir, "userlearn.txt")
        UserLearning { 1_000L }.apply {
            repeat(3) {
                observeCommit(null, "你", "ni", 1_000L)
                observeCommit("你", "好", "hao", 1_000L)
                observeBreak()
            }
            observeCommit("前", "你好", "", 1_000L)
            save(userLearn)
        }

        assertTrue(UserDictEdit.remove(db, "nihao", "你好"))
        val reloaded = UserLearning { 1_000L }.apply { load(userLearn) }
        assertTrue(reloaded.formedWordsFor("nihao").isEmpty())
        assertTrue(reloaded.follows("前").isEmpty())
    }

    @Test fun add_isConsistentWithImportedEntries() {
        val db = File.createTempFile("userdb-edit3", ".txt").also { it.deleteOnExit() }
        UserDictEdit.add(db, "测试", "ceshi", 1)
        val incoming = File.createTempFile("incoming", ".txt").also { it.deleteOnExit() }
        UserModel().apply { recordWord("beijing", "北京", 1, incrementCount = true) }.save(incoming)

        assertTrue(UserDictImport.apply(incoming, db, merge = true, now = 2))
        val m = UserModel { 10L }.apply { load(db) }
        assertEquals(listOf("测试"), m.readingSnapshot()["ceshi"])
        assertEquals(listOf("北京"), m.readingSnapshot()["beijing"])
    }

    @Test fun blankWord_isRejected() {
        val db = File.createTempFile("userdb-edit4", ".txt").also { it.deleteOnExit() }
        assertFalse(UserDictEdit.add(db, "   ", "ceshi", 1))
    }

    @Test fun a_word_that_cannot_be_written_is_reported_and_keeps_the_saved_ones() {
        val db = File(tmp.newFolder("userdict-add"), "userdb.txt")
        assertTrue(UserDictEdit.add(db, "北京", "beijing", 1))
        blockTheWriteTo(db)

        assertFalse("the user must be told the word never reached the disk", UserDictEdit.add(db, "测试", "ceshi", 2))

        assertEquals("what was already saved survives", listOf("北京"), UserDictEdit.list(db).map { it.word })
    }

    @Test fun a_word_the_dictionary_cannot_hold_is_reported_as_not_added() {
        val db = File(tmp.newFolder("userdict-reject"), "userdb.txt")
        assertTrue(UserDictEdit.add(db, "北京", "beijing", 1))
        val before = db.readText()

        assertFalse(
            "a word the format cannot hold never reaches the file, so it must not be reported as added",
            UserDictEdit.add(db, "词".repeat(257), "ceshi", 2),
        )

        assertEquals("the file is left byte for byte as it was", before, db.readText())
        assertEquals(listOf("北京"), UserDictEdit.list(db).map { it.word })
    }

    @Test fun a_removal_that_cannot_be_written_is_reported_and_keeps_the_entry() {
        val db = File(tmp.newFolder("userdict-remove"), "userdb.txt")
        assertTrue(UserDictEdit.add(db, "测试", "ceshi", 1))
        blockTheWriteTo(db)

        assertFalse("the user must be told the word is still there", UserDictEdit.remove(db, "ceshi", "测试"))

        assertEquals("the entry survives a removal that never reached the disk", listOf("测试"), UserDictEdit.list(db).map { it.word })
    }

    @Test fun a_learning_removal_that_cannot_be_written_is_reported_and_keeps_the_data() {
        val userLearn = File(tmp.newFolder("userlearn-remove"), "userlearn.txt")
        seedLearning(userLearn)
        blockTheWriteTo(userLearn)

        assertFalse(UserLearnEdit.remove(userLearn, "你好", "nihao"))

        assertTrue("nothing may be dropped by a removal that failed", UserLearnEdit.hasData(userLearn))
    }

    @Test fun a_learning_clear_that_cannot_be_written_is_reported_and_keeps_the_data() {
        val userLearn = File(tmp.newFolder("userlearn-clear"), "userlearn.txt")
        seedLearning(userLearn)
        blockTheWriteTo(userLearn)

        assertFalse(UserLearnEdit.clear(userLearn))

        assertTrue("nothing may be dropped by a clear that failed", UserLearnEdit.hasData(userLearn))
    }

    private fun blockTheWriteTo(file: File) {
        assertTrue(File(file.parentFile, file.name + ".tmp").mkdir())
    }

    private fun seedLearning(userLearn: File) {
        UserLearning { 1_000L }.apply {
            repeat(3) {
                observeCommit(null, "你", "ni", 1_000L)
                observeCommit("你", "好", "hao", 1_000L)
                observeBreak()
            }
            save(userLearn)
        }
        assertTrue(UserLearnEdit.hasData(userLearn))
    }
}
