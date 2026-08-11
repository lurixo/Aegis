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

package com.aegis.ime.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishKeyTest {

    @Test
    fun keys_are_lowercased_ascii_letters_and_digits() {
        assertEquals("orange", EnglishKey.normalize("Orange"))
        assertEquals("orange", EnglishKey.normalize("ORANGE"))
        assertEquals("mp3", EnglishKey.normalize("MP3"))
    }

    @Test
    fun accented_letters_fold_onto_their_ascii_base() {
        assertEquals("e", EnglishKey.normalize("é"))
        assertEquals("c", EnglishKey.normalize("ç"))
        assertEquals("dejavu", EnglishKey.normalize("déjà vu"))
    }

    @Test
    fun punctuation_and_spacing_drop_out_of_the_key() {
        assertEquals("dont", EnglishKey.normalize("don't"))
        assertEquals("heisup", EnglishKey.normalize("He'is'up"))
        assertEquals("carbondioxide", EnglishKey.normalize("carbon dioxide"))
        assertEquals("csgo", EnglishKey.normalize("CS:GO"))
        assertEquals("c", EnglishKey.normalize("C++"))
        assertEquals("eg", EnglishKey.normalize("e.g"))
        assertEquals("", EnglishKey.normalize("·"))
    }

    @Test
    fun a_key_never_carries_anything_the_binary_dictionary_cannot_encode() {
        val samples = listOf("Orange", "déjà vu", "don't", "CS:GO", "·", "мир", "汉字", "ﬁre", "Ⅷ")
        for (sample in samples) {
            val key = EnglishKey.normalize(sample)
            assertTrue(
                "'$sample' produced '$key'",
                key.all { it in 'a'..'z' || it in '0'..'9' },
            )
        }
    }

    @Test
    fun an_empty_or_unusable_input_produces_an_empty_key() {
        assertEquals("", EnglishKey.normalize(""))
        assertEquals("", EnglishKey.normalize("汉字"))
        assertEquals("", EnglishKey.normalize("мир"))
    }
}
