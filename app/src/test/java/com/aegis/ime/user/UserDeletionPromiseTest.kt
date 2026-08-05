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

class UserDeletionPromiseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = 1_700_000_000_000L

    private fun glued(): UserLearning = UserLearning { clock }.apply {
        repeat(8) {
            var prev: String? = null
            for ((word, reading) in listOf("你" to "ni", "呢" to "ne", "嗯" to "n")) {
                observeCommit(prev, word, reading, clock)
                prev = word
            }
            observeBreak()
        }
    }

    private fun blockTheWriteTo(file: File) {
        val blocker = File(file.absoluteFile.parentFile, file.name + ".tmp")
        assertTrue(blocker.mkdir())
        assertTrue("a write that keeps failing is what a promise is for", File(blocker, "occupied").createNewFile())
    }

    private fun promisesIn(userDb: File) =
        UserModel { clock }.apply { load(userDb, sweepStale = false) }.tombstones()

    private fun learnedIn(userLearn: File) =
        UserLearning { clock }.apply { load(userLearn) }.formedEntries().map { it.word }

    private class Owed(val db: File, val learn: File, val model: UserModel, val learning: UserLearning)

    private fun owed(dir: String, word: String, reading: String): Owed {
        val root = tmp.newFolder(dir)
        val db = File(root, "userdb.txt")
        val learn = File(root, "userlearn.txt")
        val learning = glued().apply { save(learn) }
        val model = UserModel { clock }.apply {
            addManualWord("zwm", "张伟明", clock)
            assertTrue(addTombstone(word, reading))
            save(db)
        }
        return Owed(db, learn, model, learning)
    }

    @Test fun keeping_a_whole_word_promise_clears_every_learned_spelling_and_strikes_it_off() {
        val it = owed("keep-word", "你呢嗯", "")

        assertTrue(UserDeletionPromises.keep(it.model, it.db, it.learning, it.learn))

        assertEquals(emptyList<String>(), it.learning.formedEntries().map { e -> e.word })
        assertEquals(emptyList<String>(), learnedIn(it.learn))
        assertTrue(it.model.tombstones().isEmpty())
        assertTrue("the struck-off promise must be gone from the file too", promisesIn(it.db).isEmpty())
        assertEquals(
            "keeping a promise must not take the words the user added by hand with it",
            listOf("张伟明"),
            UserModel { clock }.apply { load(it.db, sweepStale = false) }.userWordEntries().map { e -> e.word },
        )
    }

    @Test fun keeping_a_reading_scoped_promise_takes_only_that_learned_entry() {
        val root = tmp.newFolder("keep-one-reading")
        val db = File(root, "userdb.txt")
        val learn = File(root, "userlearn.txt")
        learn.writeText(
            "aegis-userlearn 1\nF\tninen\t你呢嗯\t8.0\t$clock\nF\tniinen\t你呢嗯\t8.0\t$clock\n",
        )
        val learning = UserLearning { clock }.apply { load(learn) }
        assertEquals(
            "precondition: the same word is learned under two readings",
            listOf("niinen", "ninen"),
            learning.formedEntries().filter { e -> e.word == "你呢嗯" }.map { e -> e.reading }.sorted(),
        )
        val model = UserModel { clock }.apply {
            assertTrue(addTombstone("你呢嗯", "ninen"))
            save(db)
        }

        assertTrue(UserDeletionPromises.keep(model, db, learning, learn))

        assertEquals(
            "a promise naming one reading must leave the same word learned under another alone",
            listOf("niinen"),
            learning.formedEntries().filter { e -> e.word == "你呢嗯" }.map { e -> e.reading },
        )
        assertTrue(promisesIn(db).isEmpty())
    }

    @Test fun a_promise_for_something_already_gone_is_struck_off_all_the_same() {
        val root = tmp.newFolder("keep-nothing")
        val db = File(root, "userdb.txt")
        val learn = File(root, "userlearn.txt")
        val learning = UserLearning { clock }.apply { save(learn) }
        val model = UserModel { clock }.apply {
            assertTrue(addTombstone("早没了", ""))
            save(db)
        }

        assertTrue(UserDeletionPromises.keep(model, db, learning, learn))

        assertTrue("a promise nothing is left to keep is still kept", promisesIn(db).isEmpty())
    }

    @Test fun there_is_nothing_to_do_when_nothing_is_owed() {
        val root = tmp.newFolder("keep-idle")
        val db = File(root, "userdb.txt")
        val learn = File(root, "userlearn.txt")
        val learning = glued().apply { save(learn) }
        val model = UserModel { clock }.apply { addManualWord("zwm", "张伟明", clock); save(db) }
        val untouched = learn.lastModified()

        assertFalse("nothing owed means nothing written", UserDeletionPromises.keep(model, db, learning, learn))

        assertEquals(listOf("你呢嗯"), learnedIn(learn))
        assertEquals(untouched, learn.lastModified())
    }

    @Test fun a_promise_is_held_over_while_the_learned_data_cannot_be_read() {
        val root = tmp.newFolder("keep-unreadable")
        val db = File(root, "userdb.txt")
        val learn = File(root, "userlearn.txt")
        learn.writeText("not a learning file at all\n")
        val learning = UserLearning { clock }.apply { load(learn) }
        val model = UserModel { clock }.apply {
            assertTrue(addTombstone("你呢嗯", ""))
            save(db)
        }

        assertFalse(UserDeletionPromises.keep(model, db, learning, learn))

        assertEquals("a promise that could not be kept must stay on the page", listOf("你呢嗯" to ""), promisesIn(db))
        assertTrue("and the file it could not read is left as it was", learn.readText().startsWith("not a learning file"))
    }

    @Test fun a_promise_is_held_over_while_the_learned_data_cannot_be_written() {
        val it = owed("keep-blocked", "你呢嗯", "")
        blockTheWriteTo(it.learn)

        assertFalse(UserDeletionPromises.keep(it.model, it.db, it.learning, it.learn))

        assertEquals("a promise that could not be kept must stay on the page", listOf("你呢嗯" to ""), promisesIn(it.db))
        assertEquals("and what it promised to delete is still there", listOf("你呢嗯"), learnedIn(it.learn))
    }

    @Test fun owing_the_same_deletion_twice_still_reports_it_as_owed() {
        val m = UserModel { clock }
        assertTrue(m.addTombstone("你呢嗯", ""))
        assertTrue("the second deletion of a word already owed is owed just the same", m.addTombstone("你呢嗯", ""))
        assertEquals("but it is still one promise", listOf("你呢嗯" to ""), m.tombstones())
    }
}
