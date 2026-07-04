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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserLearnEditReadabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_700_000_000_000L

    @Before fun noLiveKeyboard() {
        UserDictHot.host = null
    }

    @After fun cleanup() {
        UserDictHot.host = null
    }

    private fun storeThatStillHoldsTheWordButCannotBeRead(): File {
        val file = File(tmp.root, "userlearn.txt")
        file.writeText("aegis-userlearn 1\nF\tninen\t你呢嗯\t4.0\t$now\nF\tzh")
        assertTrue("precondition: the word really is in the file", file.readText().contains("你呢嗯"))
        assertFalse(
            "precondition: this file does not parse",
            UserLearning { now }.apply { load(file) }.readable,
        )
        return file
    }

    @Test fun deleting_a_learned_word_from_a_store_that_cannot_be_read_is_reported_as_a_failure() {
        val userLearn = storeThatStillHoldsTheWordButCannotBeRead()
        val before = userLearn.readText()

        assertFalse(
            "a word still sitting in the file must never be reported as deleted",
            UserLearnEdit.remove(userLearn, "你呢嗯", "ninen"),
        )

        assertEquals("the file is left byte for byte as it was", before, userLearn.readText())
    }

    @Test fun deleting_a_learned_word_through_a_live_host_whose_store_cannot_be_read_is_reported_as_a_failure() {
        val userLearn = storeThatStillHoldsTheWordButCannotBeRead()
        val before = userLearn.readText()
        val db = File(tmp.root, "userdb.txt")
        val learning = UserLearning { now }.apply { load(userLearn) }
        val h = LiveUserDictHost(UserModel { now }, db, learning, userLearn)

        assertFalse(
            "a word still sitting in the file must never be reported as deleted",
            h.removeLearned("你呢嗯", "ninen"),
        )

        assertEquals("the file is left byte for byte as it was", before, userLearn.readText())
        assertFalse("and nothing is written to the word list on its behalf", db.exists())
    }

    @Test fun clearing_a_learned_store_that_cannot_be_read_is_still_the_way_out() {
        val userLearn = storeThatStillHoldsTheWordButCannotBeRead()

        assertTrue("throwing it away on purpose must stay open", UserLearnEdit.clear(userLearn))

        assertTrue(userLearn.readText().startsWith("aegis-userlearn"))
        assertFalse("the word the file was holding is gone", userLearn.readText().contains("你呢嗯"))
        assertFalse(UserLearnEdit.hasData(userLearn))
    }

    @Test fun clearing_through_a_live_host_whose_store_cannot_be_read_is_still_the_way_out() {
        val userLearn = storeThatStillHoldsTheWordButCannotBeRead()
        val db = File(tmp.root, "userdb.txt")
        val learning = UserLearning { now }.apply { load(userLearn) }
        val h = LiveUserDictHost(UserModel { now }, db, learning, userLearn)

        assertTrue("throwing it away on purpose must stay open", h.clearLearned())

        assertTrue(userLearn.readText().startsWith("aegis-userlearn"))
        assertTrue(learning.readable)
        assertTrue("and a removal reports honestly again afterwards", h.removeLearned("你呢嗯", "ninen"))
    }
}
