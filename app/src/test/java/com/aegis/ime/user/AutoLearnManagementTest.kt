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
import org.junit.Test

class AutoLearnManagementTest {

    private val clock = 1_700_000_000_000L

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
}
