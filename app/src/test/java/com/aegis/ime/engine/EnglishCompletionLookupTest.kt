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

package com.aegis.ime.engine

import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.dict.EnglishKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishCompletionLookupTest {

    private fun row(word: String, freq: Int) = EngineFixture.Row(EnglishKey.normalize(word), word, freq)

    private fun englishEngine(vararg rows: EngineFixture.Row): DictEngine =
        DictEngine(null, null, null, englishDict = EngineFixture.build(rows.toList()))

    private val words = englishEngine(
        row("or", 4000),
        row("order", 3000),
        row("orange", 2000),
        row("ordinary", 1000),
        row("organ", 500),
        row("other", 9000),
    )

    @Test
    fun a_typed_prefix_completes_into_the_words_that_extend_it() {
        assertTrue("orange" in words.englishCompletions("or"))
        assertEquals(listOf("order", "orange", "ordinary", "organ"), words.englishCompletions("or"))
    }

    @Test
    fun completions_arrive_in_frequency_order() {
        val engine = englishEngine(row("orbit", 10), row("orchid", 300), row("ordeal", 70))
        assertEquals(listOf("orchid", "ordeal", "orbit"), engine.englishCompletions("or"))
    }

    @Test
    fun the_word_the_user_already_typed_is_not_offered_back() {
        assertFalse("or" in words.englishCompletions("or"))
        assertEquals(emptyList<String>(), words.englishCompletions("other"))
    }

    @Test
    fun an_uppercase_prefix_still_finds_the_lowercase_entries() {
        assertEquals(listOf("order", "orange", "ordinary", "organ"), words.englishCompletions("OR"))
        assertEquals(listOf("orange"), words.englishCompletions("Oran"))
    }

    @Test
    fun a_stripped_key_keeps_its_punctuated_word_reachable_from_the_letters_before_it() {
        val engine = englishEngine(row("don't", 900), row("done", 800))
        assertEquals(listOf("don't", "done"), engine.englishCompletions("don"))
    }

    @Test
    fun a_word_that_does_not_literally_extend_the_typed_prefix_is_withheld() {
        val engine = englishEngine(row("déjà vu", 900), row("dejected", 800))
        assertEquals(listOf("dejected"), engine.englishCompletions("dej"))
    }

    @Test
    fun without_an_english_table_there_are_no_completions() {
        val engine = DictEngine(null, null, null)
        assertEquals(emptyList<String>(), engine.englishCompletions("or"))
    }

    @Test
    fun a_prefix_with_no_key_characters_asks_the_table_for_nothing() {
        assertEquals(emptyList<String>(), words.englishCompletions(""))
        assertEquals(emptyList<String>(), words.englishCompletions("!!"))
    }

    @Test
    fun the_completion_list_stops_at_the_candidate_bar_supply() {
        val many = (0 until 60).map { row("or" + "x".repeat(it + 1), 5000 - it) }
        val engine = englishEngine(*many.toTypedArray())
        assertEquals(30, engine.englishCompletions("or").size)
    }
}
