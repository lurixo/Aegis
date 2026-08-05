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

    @After fun clearHost() {
        UserDictHot.host = null
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

    @Test fun editing_a_missing_learning_file_is_a_no_op_and_creates_nothing() {
        val file = File(tmp.root, "absent.txt")
        assertEquals(emptyList<UserLearning.Formed>(), UserLearnEdit.list(file))
        UserLearnEdit.remove(file, "你呢嗯", "ninen")
        UserLearnEdit.clear(file)
        assertFalse("no file is created for a no-op edit", file.exists())
    }
}
