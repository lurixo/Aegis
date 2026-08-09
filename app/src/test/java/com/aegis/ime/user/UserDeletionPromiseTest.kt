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
    private val hosts = ArrayList<LiveUserDictHost>()

    @After fun stopHosts() {
        UserDictHot.host = null
        hosts.forEach { runCatching { it.stopSaving() } }
    }

    private fun liveHost(model: UserModel, userDb: File, learning: UserLearning?, userLearn: File?) =
        LiveUserDictHost(model, userDb, learning, userLearn).also { hosts += it }

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

    private class Live(
        val db: File,
        val learn: File,
        val model: UserModel,
        val learning: UserLearning,
        val host: LiveUserDictHost,
    )

    private fun live(dir: String): Live {
        val root = tmp.newFolder(dir)
        val db = File(root, "userdb.txt")
        val learn = File(root, "userlearn.txt")
        val learning = glued().apply { save(learn) }
        val model = UserModel { clock }.apply {
            addManualWord("ninen", "你呢嗯", clock)
            save(db)
        }
        return Live(db, learn, model, learning, liveHost(model, db, learning, learn))
    }

    @Test fun a_live_deletion_the_learned_data_would_not_take_is_written_down_as_still_owed() {
        val it = live("live-word")
        blockTheWriteTo(it.learn)

        assertTrue("a deletion that is written down as owed will still happen", it.host.removeWord("ninen", "你呢嗯"))

        assertEquals(
            "a whole word is owed with no reading, so every learned spelling of it goes",
            listOf("你呢嗯" to ""),
            promisesIn(it.db),
        )
        assertEquals(
            "the learned copy is still in the file, which is exactly why the promise is needed",
            listOf("你呢嗯"),
            learnedIn(it.learn),
        )
        assertTrue(
            "the word itself really left the word list",
            UserModel { clock }.apply { load(it.db, sweepStale = false) }.userWordEntries().isEmpty(),
        )
    }

    @Test fun a_live_deletion_that_reached_both_stores_owes_nothing() {
        val it = live("live-clean")

        assertTrue(it.host.removeWord("ninen", "你呢嗯"))

        assertTrue("nothing is owed once both halves landed", promisesIn(it.db).isEmpty())
        assertEquals(emptyList<String>(), learnedIn(it.learn))
    }

    @Test fun a_live_deletion_that_never_reached_the_word_list_promises_nothing() {
        val it = live("live-blocked")
        blockTheWriteTo(it.db)

        assertFalse(it.host.removeWord("ninen", "你呢嗯"))

        assertEquals(
            "a promise nobody could write down is not a promise",
            emptyList<Pair<String, String>>(),
            it.model.tombstones(),
        )
    }

    @Test fun a_live_deletion_neither_store_would_take_leaves_no_promise_behind() {
        val it = live("live-both-blocked")
        blockTheWriteTo(it.db)
        blockTheWriteTo(it.learn)

        assertFalse("a deletion neither half took must not be reported as done", it.host.removeWord("ninen", "你呢嗯"))

        assertEquals(
            "a promise the word list could never take must not be left haunting the running list",
            emptyList<Pair<String, String>>(),
            it.model.tombstones(),
        )
    }

    @Test fun a_live_learned_deletion_the_file_would_not_take_is_owed_with_its_reading() {
        val it = live("live-learned")
        blockTheWriteTo(it.learn)

        assertTrue(it.host.removeLearned("你呢嗯", "ninen"))

        assertEquals(
            "one learned spelling was deleted, so only that spelling is owed",
            listOf("你呢嗯" to "ninen"),
            promisesIn(it.db),
        )
        assertEquals(listOf("你呢嗯"), learnedIn(it.learn))
    }

    @Test fun a_live_learned_deletion_that_landed_owes_nothing() {
        val it = live("live-learned-clean")

        assertTrue(it.host.removeLearned("你呢嗯", "ninen"))

        assertTrue(promisesIn(it.db).isEmpty())
        assertEquals(emptyList<String>(), learnedIn(it.learn))
    }

    private class Cold(val db: File, val learn: File)

    private fun cold(dir: String): Cold {
        val root = tmp.newFolder(dir)
        val db = File(root, "userdb.txt")
        val learn = File(root, "userlearn.txt")
        UserModel { clock }.apply { addManualWord("ninen", "你呢嗯", clock) }.save(db)
        glued().save(learn)
        return Cold(db, learn)
    }

    @Test fun a_settings_deletion_the_learned_data_would_not_take_is_written_down_as_still_owed() {
        val it = cold("cold-word")
        blockTheWriteTo(it.learn)

        assertTrue(UserDictEdit.remove(it.db, "ninen", "你呢嗯"))

        assertEquals(listOf("你呢嗯" to ""), promisesIn(it.db))
        assertEquals(listOf("你呢嗯"), learnedIn(it.learn))
    }

    @Test fun a_settings_deletion_against_learned_data_that_cannot_be_read_is_reported_as_a_failure() {
        val it = cold("cold-unreadable")
        it.learn.writeText("not a learning file at all\n")

        assertFalse(
            "a deletion the learned half can never take must not be reported as done",
            UserDictEdit.remove(it.db, "ninen", "你呢嗯"),
        )

        assertTrue(
            "a promise nobody can ever keep is not written down",
            it.db.readLines().none { line -> line.startsWith("D\t") },
        )
    }

    @Test fun a_live_deletion_against_learned_data_that_cannot_be_read_promises_nothing() {
        val root = tmp.newFolder("live-unreadable")
        val db = File(root, "userdb.txt")
        val learn = File(root, "userlearn.txt")
        learn.writeText("not a learning file at all\n")
        val learning = UserLearning { clock }.apply {
            load(learn)
            observeCommit(null, "你", "ni", clock)
            observeCommit("你", "呢", "ne", clock)
        }
        val model = UserModel { clock }.apply { addManualWord("ninen", "你呢嗯", clock) }
        val host = liveHost(model, db, learning, learn)

        assertFalse(
            "a deletion the learned half can never take must not be reported as done",
            host.removeWord("ninen", "你呢嗯"),
        )

        assertTrue("a promise nobody can ever keep is not written down", promisesIn(db).isEmpty())
    }

    @Test fun a_settings_deletion_the_learned_half_can_never_take_leaves_the_word_on_the_page() {
        val it = cold("cold-unreadable-kept")
        it.learn.writeText("not a learning file at all\n")

        assertFalse(UserDictEdit.remove(it.db, "ninen", "你呢嗯"))

        assertEquals(
            "a deletion reported as a write failure must leave the word where the user can still see it",
            listOf("你呢嗯"),
            UserModel { clock }.apply { load(it.db, sweepStale = false) }.userWordEntries().map { e -> e.word },
        )
    }

    @Test fun a_live_deletion_the_learned_half_can_never_take_leaves_the_word_on_the_page() {
        val root = tmp.newFolder("live-unreadable-kept")
        val db = File(root, "userdb.txt")
        val learn = File(root, "userlearn.txt")
        learn.writeText("not a learning file at all\n")
        val learning = UserLearning { clock }.apply { load(learn) }
        val model = UserModel { clock }.apply { addManualWord("ninen", "你呢嗯", clock); save(db) }
        val host = liveHost(model, db, learning, learn)

        assertFalse(host.removeWord("ninen", "你呢嗯"))

        assertEquals(
            "a deletion reported as a write failure must leave the word in the running list",
            listOf("你呢嗯"),
            model.userWordEntries().map { e -> e.word },
        )
        assertEquals(
            "and in the file behind it",
            listOf("你呢嗯"),
            UserModel { clock }.apply { load(db, sweepStale = false) }.userWordEntries().map { e -> e.word },
        )
    }

    @Test fun a_settings_learned_deletion_the_file_would_not_take_is_owed_with_its_reading() {
        val it = cold("cold-learned")
        blockTheWriteTo(it.learn)

        assertFalse(
            "the entry is still on the page it was deleted from, so this is not a deletion yet",
            UserLearnEdit.remove(it.learn, "你呢嗯", "ninen"),
        )

        assertEquals(
            "but the deletion is written down, so it is not lost either",
            listOf("你呢嗯" to "ninen"),
            promisesIn(it.db),
        )
        assertEquals(listOf("你呢嗯"), learnedIn(it.learn))
    }

    @Test fun a_settings_deletion_that_reached_both_stores_owes_nothing() {
        val it = cold("cold-clean")

        assertTrue(UserDictEdit.remove(it.db, "ninen", "你呢嗯"))

        assertTrue(promisesIn(it.db).isEmpty())
        assertEquals(emptyList<String>(), learnedIn(it.learn))
    }

    @Test fun owing_the_same_deletion_twice_still_reports_it_as_owed() {
        val m = UserModel { clock }
        assertTrue(m.addTombstone("你呢嗯", ""))
        assertTrue("the second deletion of a word already owed is owed just the same", m.addTombstone("你呢嗯", ""))
        assertEquals("but it is still one promise", listOf("你呢嗯" to ""), m.tombstones())
    }
}
