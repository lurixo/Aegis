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

package com.aegis.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedReadTest {

    private class Field(val text: String) {
        var caret = 0
        var parcel = Int.MAX_VALUE
        var snap = 0
        var reportsCaret = Int.MAX_VALUE
        val asked = ArrayList<Int>()

        fun select(target: Int): Int {
            caret = (target + snap).coerceIn(0, text.length)
            return caret
        }

        fun before(length: Int): CharSequence? {
            asked.add(length)
            if (length > parcel) return null
            return text.substring((caret - length).coerceAtLeast(0), caret)
        }
    }

    private class Outcome(val text: String, val whole: Boolean, val rounds: Int)

    private fun read(field: Field, from: Int, to: Int): Outcome {
        var caret = -1
        var text: String? = null
        var whole = false
        var rounds = 0
        val run = ChunkedRead(
            from,
            to,
            select = { target -> caret = field.select(target) },
            before = field::before,
            done = { read, complete -> text = read.toString(); whole = complete },
        )
        run.begin()
        while (run.pending && rounds < field.reportsCaret) {
            rounds++
            run.onCaret(caret)
        }
        run.giveUp()
        return Outcome(requireNotNull(text), whole, rounds)
    }

    private fun body(chars: Int): String = String(CharArray(chars) { '一' + (it % 2048) })

    @Test fun a_selection_wider_than_one_parcel_comes_back_character_for_character() {
        val field = Field(body(ChunkedRead.CHUNK * 2 + 777))

        val out = read(field, 0, field.text.length)

        assertTrue("a whole read has to say so", out.whole)
        assertEquals(field.text, out.text)
        assertTrue(
            "no single read may ask for more than one chunk, was " + out.rounds + " rounds",
            field.asked.all { it <= ChunkedRead.CHUNK },
        )
    }

    @Test fun only_the_selection_is_taken_never_the_text_around_it() {
        val field = Field(body(ChunkedRead.CHUNK * 3))
        val from = ChunkedRead.CHUNK / 2
        val to = ChunkedRead.CHUNK * 2 + 11

        val out = read(field, from, to)

        assertTrue(out.whole)
        assertEquals(field.text.substring(from, to), out.text)
    }

    @Test fun a_parcel_the_editor_will_not_fill_is_asked_for_again_in_halves() {
        val field = Field(body(ChunkedRead.CHUNK + 4096)).apply { parcel = 20_000 }

        val out = read(field, 0, field.text.length)

        assertTrue("a narrower chunk still finishes the read", out.whole)
        assertEquals(field.text, out.text)
        assertTrue(
            "the first ask has to be the widest one, was " + field.asked.take(2),
            field.asked.first() > 20_000,
        )
        assertTrue(
            "and every ask that came back had to fit the parcel",
            field.asked.filter { it <= 20_000 }.isNotEmpty(),
        )
    }

    @Test fun a_caret_the_editor_carries_past_the_end_never_takes_more_than_the_selection() {
        val field = Field(body(ChunkedRead.CHUNK * 2)).apply { snap = 3 }
        val to = ChunkedRead.CHUNK + 500

        val out = read(field, 0, to)

        assertTrue(out.whole)
        assertEquals("what the caret overshot must not land in the copy", field.text.substring(0, to), out.text)
    }

    @Test fun an_editor_that_stops_reporting_the_caret_keeps_what_was_already_read() {
        val field = Field(body(ChunkedRead.CHUNK * 4)).apply { reportsCaret = 2 }

        val out = read(field, 0, field.text.length)

        assertFalse("a read that never reached the end must not claim it did", out.whole)
        assertEquals(field.text.substring(0, ChunkedRead.CHUNK * 2), out.text)
    }

    @Test fun a_selection_narrower_than_a_chunk_is_one_round() {
        val field = Field(body(4096))

        val out = read(field, 0, field.text.length)

        assertTrue(out.whole)
        assertEquals(field.text, out.text)
        assertEquals(1, out.rounds)
    }

    @Test fun an_empty_selection_reads_nothing_and_says_it_is_whole() {
        val field = Field(body(4096))

        val out = read(field, 100, 100)

        assertTrue(out.whole)
        assertEquals("", out.text)
        assertEquals(0, out.rounds)
    }

    @Test fun a_parcel_too_small_for_even_the_narrowest_chunk_gives_up_without_a_hole() {
        val field = Field(body(ChunkedRead.CHUNK)).apply { parcel = 8 }

        val out = read(field, 0, field.text.length)

        assertFalse(out.whole)
        assertEquals("a read that never landed must not invent content", "", out.text)
    }
}
