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

class UserLearningReloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_700_000_000_000L

    private fun typeRun(l: UserLearning, vararg commits: Pair<String, String>) {
        var prev: String? = null
        for ((word, reading) in commits) {
            l.observeCommit(prev, word, reading, now)
            prev = word
        }
        l.observeBreak()
    }

    private fun glued(vararg commits: Pair<String, String>): UserLearning =
        UserLearning { now }.apply { repeat(3) { typeRun(this, *commits) } }

    private fun broken(name: String): File =
        File(tmp.root, name).apply { writeText("not a learning file at all\n") }

    @Test fun a_reload_that_cannot_parse_keeps_the_learning_data_already_in_memory() {
        val l = glued("张" to "zhang", "伟" to "wei")
        assertEquals("precondition: there is something to lose", listOf("张伟"), l.formedWordsFor("zhangwei"))

        l.load(broken("userlearn.txt"))

        assertEquals(
            "the keyboard keeps typing with what it already had",
            listOf("张伟"),
            l.formedWordsFor("zhangwei"),
        )
        assertEquals(listOf(UserLearning.Formed("张伟", "zhangwei")), l.formedEntries())
        assertFalse(l.isEmpty())
    }

    @Test fun a_reload_that_cannot_parse_does_not_drop_data_still_waiting_to_be_saved() {
        val l = glued("张" to "zhang", "伟" to "wei")
        assertTrue("precondition: this work has not reached any file yet", l.dirty)

        l.load(broken("userlearn.txt"))

        assertTrue("work that never reached a file must stay queued for one that can take it", l.dirty)

        val elsewhere = File(tmp.root, "elsewhere.txt")
        l.save(elsewhere)
        assertEquals(
            listOf("张伟"),
            UserLearning { now }.apply { load(elsewhere) }.formedWordsFor("zhangwei"),
        )
    }

    @Test fun a_reload_that_cannot_parse_marks_the_store_unreadable() {
        val l = glued("张" to "zhang", "伟" to "wei")
        val file = broken("userlearn.txt")
        val before = file.readText()

        l.load(file)

        assertFalse("a file that would not parse must not pass for a readable store", l.readable)
        assertTrue(
            "writing back over what could not be read must fail loudly",
            runCatching { l.save(file) }.isFailure,
        )
        assertEquals("the unreadable file is left byte for byte as it was", before, file.readText())
    }

    @Test fun a_reload_that_parses_still_replaces_everything_it_held() {
        val file = File(tmp.root, "userlearn.txt")
        glued("李" to "li", "雷" to "lei").save(file)

        val l = glued("张" to "zhang", "伟" to "wei")
        l.load(file)

        assertEquals("what the file holds is what the store holds", listOf("李雷"), l.formedWordsFor("lilei"))
        assertTrue("a reload that worked is still a wholesale replacement", l.formedWordsFor("zhangwei").isEmpty())
        assertFalse("and it leaves nothing waiting to be written", l.dirty)
        assertTrue(l.readable)
    }
}
