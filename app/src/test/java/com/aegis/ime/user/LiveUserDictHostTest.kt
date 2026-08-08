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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private val saves = mutableListOf<Pair<Long?, Long?>>()
    private var userDbWatermark = 0L
    private var userLearnWatermark = 0L

    private fun watermark(userDbMtime: Long?, userLearnMtime: Long?) {
        saves += userDbMtime to userLearnMtime
        userDbMtime?.let { userDbWatermark = it }
        userLearnMtime?.let { userLearnWatermark = it }
    }

    private fun host(): LiveUserDictHost {
        db = File(tmp.root, "userdb.txt")
        return LiveUserDictHost(model, db, onSaved = ::watermark)
    }

    private fun reloadFromDisk() = UserModel().apply { if (db.exists()) load(db) }

    private fun glued(): UserLearning = UserLearning { CLOCK }.apply {
        repeat(8) {
            var prev: String? = null
            for ((word, reading) in listOf("你" to "ni", "呢" to "ne", "嗯" to "n")) {
                observeCommit(prev, word, reading, CLOCK)
                prev = word
            }
            observeBreak()
        }
    }

    private fun unwritable(name: String): File = File(tmp.newFile("blocker-$name"), name)

    private fun rewrittenOutside(mtime: Long) {
        UserModel().apply {
            if (db.exists()) load(db)
            addManualWord("waibu", "外部", 2L)
        }.save(db)
        db.setLastModified(mtime)
    }


    @Test fun add_lands_in_the_live_model_and_the_file_in_one_call() {
        val h = host()
        val v0 = model.version
        assertTrue(h.addWord("nihao", "你好", now = 1L))
        assertTrue("version must bump so the decoder rebuilds its recall index", model.version > v0)
        assertEquals(listOf("你好"), model.readingSnapshot()["nihao"])
        assertEquals(listOf("你好"), reloadFromDisk().readingSnapshot()["nihao"])
        assertFalse("saved → not dirty", model.dirty)
        assertEquals(1, saves.size)
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
        assertEquals(0, saves.size)
    }

    @Test fun a_blank_word_is_rejected_by_remove_without_touching_the_file() {
        val h = host()
        assertFalse(h.removeWord("nihao", "  "))
        assertFalse(db.exists())
        assertEquals(0, saves.size)
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

    @Test fun remove_scrubs_the_secondary_learning_store() {
        db = File(tmp.root, "userdb.txt")
        val userLearnFile = File(tmp.root, "userlearn.txt")
        val learning = UserLearning { 1_000L }
        repeat(3) {
            learning.observeCommit(null, "你", "ni", 1_000L)
            learning.observeCommit("你", "好", "hao", 1_000L)
            learning.observeBreak()
        }
        learning.observeCommit("前", "你好", "", 1_000L)
        val h = LiveUserDictHost(model, db, learning, userLearnFile)
        h.addWord("nihao", "你好", now = 1_000L)

        assertTrue(h.removeWord("nihao", "你好"))
        assertTrue(learning.formedWordsFor("nihao").isEmpty())
        assertTrue(learning.follows("前").isEmpty())
        val reloaded = UserLearning { 1_000L }.apply { load(userLearnFile) }
        assertTrue(reloaded.formedWordsFor("nihao").isEmpty())
        assertTrue(reloaded.follows("前").isEmpty())
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
        val savesAfterAdd = saves.size
        assertTrue(h.flush())
        assertEquals(savesAfterAdd, saves.size)
        model.record(null, "学习", 5L)
        assertTrue(h.flush())
        assertEquals(savesAfterAdd + 1, saves.size)
        assertTrue(reloadFromDisk().wordBoost("学习") > 0.0)
        assertFalse(model.dirty)
    }

    @Test fun flush_reports_success_when_there_is_nothing_to_write() {
        val h = host()
        assertTrue("an export with nothing pending is not a failure", h.flush())
        assertEquals(0, saves.size)
        assertFalse(db.exists())
    }

    @Test fun the_live_host_reports_learning_data_that_no_glued_word_shows() {
        db = File(tmp.root, "userdb.txt")
        val learnFile = File(tmp.root, "userlearn.txt")
        learnFile.writeText("aegis-userlearn 1\nC\t你\t好\t3.0\t1700000000000\n")
        val learning = UserLearning { CLOCK }.apply { load(learnFile) }
        val h = LiveUserDictHost(model, db, learning, learnFile, ::watermark)

        assertTrue("there is no glued word to list", h.learnedEntries().isEmpty())
        assertTrue("but the store is not empty", h.hasLearnedData())

        assertTrue(h.clearLearned())
        assertFalse("clearing empties it", h.hasLearnedData())
    }

    @Test fun the_live_host_reads_and_clears_the_learning_store_it_was_given() {
        db = File(tmp.root, "userdb.txt")
        val learnFile = File(tmp.root, "userlearn.txt")
        val learning = glued()
        val h = LiveUserDictHost(model, db, learning, learnFile, ::watermark)
        assertTrue(h.addWord("zwm", "张伟明", now = 1L))

        assertEquals(listOf("你呢嗯"), h.learnedEntries().map { it.word })
        assertTrue(h.hasLearnedData())

        assertTrue(h.removeLearned("你呢嗯", "ninen"))
        assertTrue("the removal reaches the live store", h.learnedEntries().isEmpty())
        assertTrue("and the file it was given", learnFile.readLines().none { it.startsWith("F\t") })

        assertTrue(h.clearLearned())
        assertFalse("clearing empties everything that is left", h.hasLearnedData())
        assertEquals(
            "the words the user added by hand are untouched",
            listOf("张伟明"),
            model.userWordEntries().map { it.word },
        )
    }

    @Test fun a_host_without_a_learning_store_answers_the_learned_calls_without_writing() {
        val h = host()
        assertEquals(emptyList<UserLearning.Formed>(), h.learnedEntries())
        assertFalse(h.hasLearnedData())
        assertTrue(h.removeLearned("你呢嗯", "ninen"))
        assertTrue(h.clearLearned())
        assertEquals(0, saves.size)
        assertFalse("with no learning store there is nothing to write", db.exists())
    }


    @Test fun saving_the_learning_store_leaves_the_user_dictionary_watermark_where_it_was() {
        db = File(tmp.root, "userdb.txt")
        val learnFile = File(tmp.root, "userlearn.txt")
        val learning = glued()
        val h = LiveUserDictHost(model, db, learning, learnFile, ::watermark)
        assertTrue(h.addWord("nihao", "你好", now = 1L))
        val loadedFrom = userDbWatermark
        rewrittenOutside(loadedFrom + 5_000L)

        assertTrue(h.clearLearned())

        assertNull("only the learning file was written", saves.last().first)
        assertNotNull("and its own watermark must move", saves.last().second)
        assertEquals("the user dictionary watermark must stay where the last real load left it", loadedFrom, userDbWatermark)
        assertTrue(
            "so the change made outside is still newer and gets picked up",
            db.lastModified() > userDbWatermark,
        )
        assertEquals(learnFile.lastModified(), userLearnWatermark)
    }

    @Test fun flushing_only_dirty_learning_leaves_the_user_dictionary_watermark_where_it_was() {
        db = File(tmp.root, "userdb.txt")
        val learnFile = File(tmp.root, "userlearn.txt")
        val learning = glued()
        val h = LiveUserDictHost(model, db, learning, learnFile, ::watermark)
        assertTrue(h.addWord("nihao", "你好", now = 1L))
        val loadedFrom = userDbWatermark
        rewrittenOutside(loadedFrom + 5_000L)

        learning.removeFormed("你呢嗯", "ninen")
        assertTrue("the learning store must have something to flush", learning.dirty)
        assertFalse("and the dictionary must have nothing", model.dirty)
        assertTrue(h.flush())

        assertNull(saves.last().first)
        assertEquals(loadedFrom, userDbWatermark)
        assertTrue(
            "an export must not swallow the change made outside",
            db.lastModified() > userDbWatermark,
        )
    }

    @Test fun saving_the_user_dictionary_does_not_move_the_watermark_of_a_clean_learning_store() {
        db = File(tmp.root, "userdb.txt")
        val learnFile = File(tmp.root, "userlearn.txt")
        val learning = glued()
        val h = LiveUserDictHost(model, db, learning, learnFile, ::watermark)
        assertTrue(h.addWord("nihao", "你好", now = 1L))
        assertNotNull("the first save writes both stores", saves.last().second)
        val learnedFrom = userLearnWatermark
        learnFile.setLastModified(learnedFrom + 5_000L)

        assertFalse(learning.dirty)
        assertTrue(h.addWord("ceshi", "测试", now = 2L))

        assertNull("a clean learning store must not be rewritten", saves.last().second)
        assertEquals(learnedFrom, userLearnWatermark)
        assertTrue(
            "so a learning file changed outside is still newer",
            learnFile.lastModified() > userLearnWatermark,
        )
    }


    @Test fun a_user_dictionary_write_that_fails_is_reported_and_keeps_the_word_recoverable() {
        db = unwritable("userdb.txt")
        val h = LiveUserDictHost(model, db, onSaved = ::watermark)

        assertFalse("a word that never reached the disk must not be reported as added", h.addWord("nihao", "你好", now = 1L))

        assertEquals("the word stays in the live model", listOf("你好"), model.readingSnapshot()["nihao"])
        assertTrue("and stays queued for the next save", model.dirty)
        assertEquals("a write that never happened must not move a watermark", 0, saves.size)
        assertEquals(0L, userDbWatermark)
        assertFalse(db.exists())
    }

    @Test fun a_remove_that_cannot_be_persisted_is_reported_as_a_failure() {
        db = unwritable("userdb.txt")
        val h = LiveUserDictHost(model, db, onSaved = ::watermark)
        model.addManualWord("nihao", "你好", 1L)

        assertFalse(h.removeWord("nihao", "你好"))
        assertTrue("the removal is still pending, not lost", model.dirty)
        assertEquals(0, saves.size)
    }

    @Test fun an_import_that_cannot_be_persisted_is_reported_as_a_failure() {
        db = unwritable("userdb.txt")
        val h = LiveUserDictHost(model, db, onSaved = ::watermark)
        val imported = tmp.newFile("import.txt").apply {
            writeText("aegis-userdb 1\nW\t测试\t3\t7\nR\tceshi\t测试\n")
        }

        assertFalse(h.importUserDict(imported, merge = true, now = 8L))

        assertEquals(listOf("测试"), model.readingSnapshot()["ceshi"])
        assertTrue("the import is still pending, not lost", model.dirty)
        assertEquals(0, saves.size)
    }

    @Test fun a_learning_write_that_fails_is_reported_and_leaves_the_file_intact() {
        db = File(tmp.root, "userdb.txt")
        val learnFile = unwritable("userlearn.txt")
        val learning = glued()
        val h = LiveUserDictHost(model, db, learning, learnFile, ::watermark)

        assertFalse(h.clearLearned())

        assertTrue("the store still knows it has unsaved work", learning.dirty)
        assertEquals(0, saves.size)
        assertEquals(0L, userLearnWatermark)
        assertFalse("a learning write must not create the dictionary file", db.exists())
        assertFalse(learnFile.exists())
        assertFalse("the half-written file must be gone", File(learnFile.path + ".tmp").exists())
    }

    @Test fun a_failed_dictionary_write_still_persists_the_learning_store() {
        db = unwritable("userdb.txt")
        val learnFile = File(tmp.root, "userlearn.txt")
        val learning = glued()
        val h = LiveUserDictHost(model, db, learning, learnFile, ::watermark)

        assertFalse("the caller must hear that half of it failed", h.removeWord("nihao", "你呢嗯"))

        assertTrue("the learning scrub still reached its own file", learnFile.exists())
        assertEquals(1, saves.size)
        assertNull(saves.last().first)
        assertEquals(learnFile.lastModified(), userLearnWatermark)
        assertEquals(0L, userDbWatermark)
    }

    @Test fun a_failed_write_is_carried_by_the_next_flush() {
        db = unwritable("userdb.txt")
        val failing = LiveUserDictHost(model, db, onSaved = ::watermark)
        assertFalse(failing.addWord("nihao", "你好", now = 1L))

        db = File(tmp.root, "userdb.txt")
        val recovered = LiveUserDictHost(model, db, onSaved = ::watermark)
        assertTrue(recovered.flush())

        assertEquals("nothing the user typed was lost", listOf("你好"), reloadFromDisk().readingSnapshot()["nihao"])
        assertFalse(model.dirty)
    }

    private companion object {
        const val CLOCK = 1_700_000_000_000L
    }
}
