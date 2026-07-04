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

class AutoLearnManagementTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = 1_700_000_000_000L
    private val hosts = ArrayList<LiveUserDictHost>()

    private fun liveHost(
        model: UserModel,
        userDb: File,
        userLearning: UserLearning? = null,
        userLearnFile: File? = null,
        onSaved: (Long?, Long?) -> Unit = { _, _ -> },
    ): LiveUserDictHost =
        LiveUserDictHost(model, userDb, userLearning, userLearnFile, onSaved).also { hosts += it }

    @After fun clearHost() {
        UserDictHot.host = null
        hosts.forEach { runCatching { it.stopSaving() } }
    }

    private fun chain(vararg steps: Pair<String, String>): UserLearning {
        val learning = UserLearning { clock }
        repeat(8) {
            var prev: String? = null
            for ((word, reading) in steps) {
                learning.observeCommit(prev, word, reading, clock)
                prev = word
            }
            learning.observeBreak()
        }
        return learning
    }

    private fun typeRun(learning: UserLearning, vararg steps: Pair<String, String>) {
        var prev: String? = null
        for ((word, reading) in steps) {
            learning.observeCommit(prev, word, reading, clock)
            prev = word
        }
        learning.observeBreak()
    }

    @Test fun the_page_lists_every_glued_word_with_the_reading_it_was_glued_under() {
        val learning = chain("你" to "ni", "呢" to "ne", "嗯" to "n")
        val entries = learning.formedEntries()
        assertTrue(
            "the glued word is listed with its own reading, was $entries",
            UserLearning.Formed("你呢嗯", "ninen") in entries,
        )
        for (e in entries) {
            assertTrue("no entry may be blank, was $e", e.word.isNotEmpty() && e.reading.isNotEmpty())
        }
    }

    @Test fun the_settings_path_edits_the_learning_file_when_no_keyboard_is_live() {
        val file = File(tmp.root, "userlearn.txt")
        chain("你" to "ni", "呢" to "ne", "嗯" to "n").save(file)

        assertTrue(
            "the file arm lists what was saved",
            UserLearnEdit.list(file).any { it.word == "你呢嗯" && it.reading == "ninen" },
        )

        UserLearnEdit.remove(file, "你呢嗯", "ninen")
        assertTrue("the removal reaches the file", UserLearnEdit.list(file).none { it.word == "你呢嗯" })
        assertTrue("a reload sees the removal too", UserLearning().apply { load(file) }.formedEntries().none { it.word == "你呢嗯" })

        chain("你" to "ni", "呢" to "ne", "嗯" to "n").save(file)
        UserLearnEdit.clear(file)
        assertEquals("clearing reaches the file", emptyList<UserLearning.Formed>(), UserLearnEdit.list(file))
        assertTrue("a reload sees an empty store", UserLearning().apply { load(file) }.isEmpty())
    }

    @Test fun the_settings_path_deletes_several_learned_words_in_one_stroke() {
        val file = File(tmp.root, "userlearn.txt")
        val now = System.currentTimeMillis()
        val learning = UserLearning { now }
        for (steps in listOf(
            arrayOf("张" to "zhang", "伟" to "wei"),
            arrayOf("李" to "li", "雷" to "lei"),
            arrayOf("韩" to "han", "梅" to "mei"),
        )) repeat(3) {
            var prev: String? = null
            for ((word, reading) in steps) {
                learning.observeCommit(prev, word, reading, now)
                prev = word
            }
            learning.observeBreak()
        }
        learning.save(file)
        assertEquals("precondition: three glued words are on file", 3, UserLearnEdit.list(file).size)

        assertTrue(
            UserLearnEdit.removeAll(
                file,
                listOf(UserLearning.Formed("张伟", "zhangwei"), UserLearning.Formed("李雷", "lilei")),
            ),
        )

        assertEquals("only the word left unticked survives", listOf("韩梅"), UserLearnEdit.list(file).map { it.word })
        assertEquals(
            "a reload sees the removals too",
            listOf("韩梅"),
            UserLearning().apply { load(file) }.formedEntries().map { it.word },
        )
    }

    @Test fun a_batch_removal_reaches_the_live_store_for_both_kinds_of_words() {
        val db = File(tmp.root, "userdb.txt")
        val learnFile = File(tmp.root, "userlearn.txt")
        val learning = chain("你" to "ni", "呢" to "ne", "嗯" to "n")
        val model = UserModel { clock }.apply {
            addManualWord("nihao", "你好", clock)
            addManualWord("zaijian", "再见", clock)
        }
        UserDictHot.host = liveHost(model, db, learning, learnFile)

        assertTrue(
            UserDictEdit.removeAll(
                db,
                listOf(UserModel.Entry("nihao", "你好", 1), UserModel.Entry("zaijian", "再见", 1)),
            ),
        )
        assertTrue(UserLearnEdit.removeAll(learnFile, listOf(UserLearning.Formed("你呢嗯", "ninen"))))

        assertTrue("the live model dropped both words", model.userWordEntries().isEmpty())
        assertTrue("the live learning store dropped the glued word", learning.formedEntries().isEmpty())
        assertTrue(
            "the removals reached the word list file",
            UserModel().apply { load(db) }.userWordEntries().isEmpty(),
        )
        assertTrue(
            "the removals reached the learning file",
            UserLearning().apply { if (learnFile.exists()) load(learnFile) }.formedEntries().isEmpty(),
        )
    }

    @Test fun turning_auto_learning_off_records_nothing_new() {
        val learning = UserLearning { clock }
        learning.enabled = false
        repeat(8) {
            var prev: String? = null
            for ((word, reading) in listOf("你" to "ni", "呢" to "ne", "嗯" to "n")) {
                learning.observeCommit(prev, word, reading, clock)
                prev = word
            }
            learning.observeBreak()
        }
        assertEquals("nothing was recorded while the switch was off", emptyList<UserLearning.Formed>(), learning.formedEntries())
        assertTrue("nothing was recorded while the switch was off", learning.isEmpty())
        assertFalse("no write is owed", learning.dirty)
    }

    @Test fun the_count_rises_once_per_new_word_and_never_for_a_word_typed_again() {
        val file = File(tmp.root, "userlearn.txt")
        val learning = UserLearning { clock }
        val first = arrayOf("张" to "zhang", "伟" to "wei", "明" to "ming")
        val second = arrayOf("李" to "li", "雷" to "lei")
        val third = arrayOf("韩" to "han", "梅" to "mei")
        for (word in listOf(first, second, third)) repeat(3) { typeRun(learning, *word) }
        learning.save(file)

        assertEquals(
            "three words each spelled out three times are three entries",
            listOf("张伟明", "李雷", "韩梅").sorted(),
            UserLearnEdit.view(file).entries.map { it.word }.sorted(),
        )

        repeat(5) { typeRun(learning, *first) }
        learning.save(file)

        assertEquals(
            "spelling out a word that is already counted adds no entry",
            3,
            UserLearnEdit.view(file).entries.size,
        )
    }

    @Test fun there_is_still_something_to_clear_when_only_the_next_word_data_is_left() {
        val file = File(tmp.root, "userlearn.txt")
        file.writeText("aegis-userlearn 1\nC\t你\t好\t3.0\t$clock\n")

        assertTrue("the glued word list is empty", UserLearnEdit.list(file).isEmpty())
        assertTrue("but there is learned data to clear", UserLearnEdit.hasData(file))

        UserLearnEdit.clear(file)

        assertFalse("clearing really empties it", UserLearnEdit.hasData(file))
    }

    @Test fun the_switch_never_blocks_a_word_the_user_adds_by_hand() {
        val db = File(tmp.root, "userdb.txt")
        val model = UserModel { clock }.apply { autoLearnEnabled = false }
        UserDictHot.host = liveHost(model, db, UserLearning { clock }, File(tmp.root, "userlearn.txt"))

        assertTrue(UserDictEdit.add(db, "张伟明", "zwm", clock))

        assertEquals(listOf("张伟明"), model.userWordEntries().map { it.word })
        assertEquals("and it counts as added by hand", mapOf("zwm" to setOf("张伟明")), model.manualSnapshot())
        assertTrue("the store reached the file", db.readLines().contains("M\tzwm\t张伟明"))
    }

    @Test fun a_chain_in_flight_when_the_switch_goes_off_is_not_promoted_later() {
        fun typeRipeChain(learning: UserLearning) {
            var prev: String? = null
            repeat(4) {
                for ((word, reading) in listOf("你" to "ni", "呢" to "ne")) {
                    learning.observeCommit(prev, word, reading, clock)
                    prev = word
                }
            }
        }

        val control = UserLearning { clock }
        typeRipeChain(control)
        control.observeBreak()
        assertTrue(
            "the control arm proves this chain is ripe enough to be promoted on a break",
            control.formedEntries().isNotEmpty(),
        )

        val learning = UserLearning { clock }
        typeRipeChain(learning)
        learning.enabled = false
        learning.enabled = true
        learning.observeBreak()
        assertTrue("the chain typed before the switch died with it", learning.formedEntries().isEmpty())
    }

    @Test fun editing_a_missing_learning_file_is_a_no_op_and_creates_nothing() {
        val file = File(tmp.root, "absent.txt")
        assertEquals(emptyList<UserLearning.Formed>(), UserLearnEdit.list(file))
        UserLearnEdit.remove(file, "你呢嗯", "ninen")
        UserLearnEdit.clear(file)
        assertFalse("no file is created for a no-op edit", file.exists())
    }
}
