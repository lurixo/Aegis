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

import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.engine.DictEngine
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

    private fun rows() = listOf(
        EngineFixture.Row("ni", "你", 900),
        EngineFixture.Row("ne", "讷", 950),
        EngineFixture.Row("ne", "呢", 100),
        EngineFixture.Row("n", "嗯", 700),
        EngineFixture.Row("men", "们", 600),
        EngineFixture.Row("men", "门", 500),
        EngineFixture.Row("ni", "拟", 50),
        EngineFixture.Row("nimen", "你们", 500),
    )

    private fun decoder(learning: UserLearning?, model: UserModel? = null) =
        PinyinDecoder(EngineFixture.build(rows()), userModel = model, userLearning = learning)

    private fun words(d: PinyinDecoder, key: String) = d.decodeCovered(key, 40).map { it.word }

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

    @Test fun deleting_one_entry_drops_it_from_the_candidates_of_the_very_same_decoder() {
        assertFalse(
            "without learning the fixture never offers this word by itself",
            "你呢嗯" in words(decoder(null), "ninen"),
        )
        val learning = chain("你" to "ni", "呢" to "ne", "嗯" to "n")
        val d = decoder(learning)
        assertTrue("the glued word is offered while it is learned", "你呢嗯" in words(d, "ninen"))

        learning.removeFormed("你呢嗯", "ninen")

        assertFalse("the deleted word is gone right away", "你呢嗯" in words(d, "ninen"))
        assertTrue("deleting one entry clears its row", learning.formedEntries().none { it.word == "你呢嗯" })
    }

    @Test fun clearing_drops_every_learned_word_and_leaves_the_user_dictionary_alone() {
        val learning = chain("你" to "ni", "呢" to "ne", "嗯" to "n")
        val model = UserModel { clock }.apply { addManualWord("nimen", "拟门", clock) }
        val d = decoder(learning, model)
        assertTrue("the glued word starts out reachable", "你呢嗯" in words(d, "ninen"))

        learning.clear()

        assertEquals("nothing is left to show", emptyList<UserLearning.Formed>(), learning.formedEntries())
        assertTrue("clearing empties the learning store", learning.isEmpty())
        assertFalse("the cleared word is gone from candidates", "你呢嗯" in words(d, "ninen"))
        assertTrue("the word the user added by hand survives", "拟门" in words(d, "nimen"))
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

    @Test fun the_switch_stops_the_user_dictionary_chain_too() {
        val learning = UserLearning { clock }
        val model = UserModel { clock }
        val engine = DictEngine(null, null, null, model, userLearning = learning)

        learning.enabled = false
        model.autoLearnEnabled = false
        engine.learnWord("ninen", "你呢嗯", assembled = true)
        engine.learn("你", "呢")
        var prev: String? = null
        for ((word, reading) in listOf("你" to "ni", "呢" to "ne", "嗯" to "n")) {
            learning.observeCommit(prev, word, reading, clock)
            prev = word
        }
        learning.observeBreak()

        assertTrue("the user dictionary recorded nothing", model.isEmpty())
        assertTrue("the learning store recorded nothing", learning.isEmpty())

        learning.enabled = true
        model.autoLearnEnabled = true
        engine.learnWord("ninen", "你呢嗯", assembled = true)
        assertTrue(
            "switching it back on records again",
            model.userWordEntries().any { it.word == "你呢嗯" },
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

    @Test fun turning_auto_learning_off_keeps_the_data_and_takes_it_out_of_the_candidates() {
        val learning = chain("你" to "ni", "呢" to "ne", "嗯" to "n")
        val d = decoder(learning)
        assertTrue("the glued word is offered while the switch is on", "你呢嗯" in words(d, "ninen"))

        learning.enabled = false

        assertFalse("the glued word leaves the candidates", "你呢嗯" in words(d, "ninen"))
        assertEquals(
            "the learned data itself is kept so the page can still show and clear it",
            listOf("你呢嗯"),
            learning.formedEntries().map { it.word },
        )
        assertEquals("the boost goes with it", 0.0, learning.formedWeight("你呢嗯"), 0.0)

        learning.enabled = true

        assertTrue("turning it back on restores what was learned", "你呢嗯" in words(d, "ninen"))
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
