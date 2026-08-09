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

class UserDictImportPromiseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = 1_700_000_000_000L
    private val hosts = ArrayList<LiveUserDictHost>()

    @After fun stopHosts() {
        UserDictHot.host = null
        hosts.forEach { runCatching { it.stopSaving() } }
    }

    private lateinit var db: File
    private lateinit var learn: File
    private lateinit var donor: File

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

    private fun promisesIn(file: File) =
        UserModel { clock }.apply { load(file, sweepStale = false) }.tombstones()

    private fun learnedIn(file: File) =
        UserLearning { clock }.apply { load(file) }.formedEntries().map { it.word }

    private fun stage(dir: String): UserLearning {
        val root = tmp.newFolder(dir)
        db = File(root, "userdb.txt")
        learn = File(root, "userlearn.txt")
        donor = File(root, "donor.txt")
        val learning = glued().apply { save(learn) }
        UserModel { clock }.apply { addManualWord("zwm", "张伟明", clock) }.save(db)
        UserModel { clock }.apply {
            addManualWord("wlc", "外来词", clock)
            assertTrue(addTombstone("你呢嗯", ""))
            save(donor)
        }
        assertEquals("precondition: the donor really asks for a deletion", listOf("你呢嗯" to ""), promisesIn(donor))
        assertEquals("precondition: this phone has learned the word the donor wants gone", listOf("你呢嗯"), learnedIn(learn))
        return learning
    }

    private fun nothingCanActOnTheDonorsBehalf(learning: UserLearning) {
        assertTrue("no deletion from someone else's file may be taken on", promisesIn(db).isEmpty())
        assertFalse(
            "and with nothing owed there is nothing for the keeper to carry out",
            UserDeletionPromises.keep(UserModel { clock }.apply { load(db, sweepStale = false) }, db, learning, learn),
        )
        assertEquals("this phone's learned data is untouched", listOf("你呢嗯"), learnedIn(learn))
        assertEquals(listOf("你呢嗯"), learning.formedEntries().map { it.word })
    }

    @Test fun merging_someone_elses_word_list_into_the_files_deletes_nothing_of_this_phones() {
        val learning = stage("merge-file")

        assertTrue(UserDictEdit.applyImport(db, donor, merge = true, now = clock))

        assertTrue("the words themselves do arrive", UserDictEdit.list(db).any { it.word == "外来词" })
        nothingCanActOnTheDonorsBehalf(learning)
    }

    @Test fun overwriting_the_files_with_someone_elses_word_list_deletes_nothing_of_this_phones() {
        val learning = stage("overwrite-file")

        assertTrue(UserDictEdit.applyImport(db, donor, merge = false, now = clock))

        assertEquals(listOf("外来词"), UserDictEdit.list(db).map { it.word })
        nothingCanActOnTheDonorsBehalf(learning)
    }

    @Test fun merging_someone_elses_word_list_into_a_live_keyboard_deletes_nothing_of_this_phones() {
        val learning = stage("merge-live")
        val model = UserModel { clock }.apply { load(db) }
        UserDictHot.host = LiveUserDictHost(model, db, learning, learn).also { hosts += it }

        assertTrue(UserDictEdit.applyImport(db, donor, merge = true, now = clock))

        assertTrue(model.userWordEntries().any { it.word == "外来词" })
        nothingCanActOnTheDonorsBehalf(learning)
        assertTrue("the live word list must not be holding one either", model.tombstones().isEmpty())
    }

    @Test fun overwriting_a_live_keyboards_word_list_with_someone_elses_deletes_nothing_of_this_phones() {
        val learning = stage("overwrite-live")
        val model = UserModel { clock }.apply { load(db) }
        UserDictHot.host = LiveUserDictHost(model, db, learning, learn).also { hosts += it }

        assertTrue(UserDictEdit.applyImport(db, donor, merge = false, now = clock))

        assertEquals(listOf("外来词"), model.userWordEntries().map { it.word })
        nothingCanActOnTheDonorsBehalf(learning)
        assertTrue("the live word list must not be holding one either", model.tombstones().isEmpty())
    }

    @Test fun an_overwrite_does_not_cancel_a_deletion_this_phone_already_owed() {
        stage("overwrite-keeps-own")
        UserModel { clock }.apply {
            load(db, sweepStale = false)
            assertTrue(addTombstone("欠着的", ""))
            save(db)
        }

        assertTrue(UserDictEdit.applyImport(db, donor, merge = false, now = clock))

        assertEquals(
            "someone else's word list arriving is not a reason to forget a deletion this phone owes",
            listOf("欠着的" to ""),
            promisesIn(db),
        )
    }

    @Test fun a_live_overwrite_does_not_cancel_a_deletion_this_phone_already_owed() {
        val learning = stage("overwrite-live-keeps-own")
        val model = UserModel { clock }.apply { load(db) }
        assertTrue(model.addTombstone("欠着的", ""))
        UserDictHot.host = LiveUserDictHost(model, db, learning, learn).also { hosts += it }

        assertTrue(UserDictEdit.applyImport(db, donor, merge = false, now = clock))

        assertEquals(listOf("欠着的" to ""), model.tombstones())
        assertEquals(listOf("欠着的" to ""), promisesIn(db))
    }
}
