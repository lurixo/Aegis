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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UserDictExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun shared(text: String): String {
        val out = ByteArrayOutputStream()
        UserDictExport.copyWithoutTombstones(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), out)
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    @Test fun a_dictionary_that_owes_deletions_is_shared_without_them() {
        val shared = shared(
            "aegis-userdb 4\nG\t2\nD\t你呢嗯\t\nD\t早没了\tzao\nW\t词\t1\t1000\nR\tci\t词\nM\tci\t词\n",
        )

        assertEquals("aegis-userdb 3\nG\t2\nW\t词\t1\t1000\nR\tci\t词\nM\tci\t词\n", shared)
    }

    @Test fun a_dictionary_that_owes_deletions_is_shared_as_something_a_reader_can_take() {
        val db = tmp.newFile("shared.txt")
        val owing = UserModel().apply {
            addManualWord("ci", "词", 1_000L)
            assertTrue(addTombstone("你呢嗯", ""))
        }
        owing.save(db)
        assertTrue("precondition: the device file says it owes deletions", db.readText().startsWith("aegis-userdb 4\n"))

        val out = tmp.newFile("taken.txt")
        db.inputStream().use { source -> out.outputStream().use { sink -> UserDictExport.copyWithoutTombstones(source, sink) } }

        assertFalse("a shared dictionary must not carry deletions", out.readText().contains("\nD\t"))
        assertFalse("nor say that it does", out.readText().startsWith("aegis-userdb 4"))
        val taken = UserModel().apply { load(out, sweepStale = false) }
        assertEquals(listOf("词"), taken.userWordEntries().map { it.word })
        assertTrue("and it must owe nothing itself", taken.tombstones().isEmpty())
    }

    @Test fun a_dictionary_that_owes_nothing_is_shared_byte_for_byte() {
        val text = "aegis-userdb 2\nW\t词\t1\t1000\nR\tci\t词\n"

        assertEquals(text, shared(text))
    }

    @Test fun a_dictionary_that_does_not_end_in_a_line_break_keeps_its_last_row() {
        assertEquals("aegis-userdb 3\nW\t词\t1\t1000", shared("aegis-userdb 4\nD\t早没了\t\nW\t词\t1\t1000"))
    }

    @Test fun a_header_with_nothing_after_it_is_stepped_down_all_the_same() {
        assertEquals("aegis-userdb 3", shared("aegis-userdb 4"))
        assertEquals("aegis-userdb 3\n", shared("aegis-userdb 4\n"))
    }

    @Test fun an_empty_dictionary_is_shared_as_an_empty_one() {
        assertEquals("", shared(""))
    }
}
