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

package com.aegis.ime.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9ReadingColumnAgreementTest {

    @Test fun the_reading_column_leads_with_the_whole_syllable_the_preedit_shows() {
        val disagreements = LinkedHashSet<String>()
        for (s in T9Pinyin.SYLLABLES) {
            val digits = T9Pinyin.toT9(s)
            if (digits.any { it !in '2'..'9' }) continue
            val shown = T9Pinyin.segment(digits)?.singleOrNull() ?: continue
            val offered = T9Pinyin.leftColumnReadings(digits, 24).firstOrNull() ?: continue
            if (offered != shown) disagreements.add("$digits: preedit $shown, column leads $offered")
        }
        assertEquals(
            "the column must lead with the reading the preedit already committed to",
            emptyList<String>(),
            disagreements.toList(),
        )
    }

    @Test fun the_more_common_of_two_readings_on_the_same_keys_comes_first() {
        for ((digits, common, rare) in listOf(
            Triple("94664", "zhong", "xiong"),
            Triple("94264", "xiang", "zhang"),
            Triple("74264", "shang", "qiang"),
        )) {
            val col = T9Pinyin.leftColumnReadings(digits, 24)
            assertTrue("$digits must offer both readings, was $col", common in col && rare in col)
            assertTrue(
                "$digits must offer $common before $rare, was $col",
                col.indexOf(common) < col.indexOf(rare),
            )
        }
    }

    @Test fun the_letter_column_leads_with_the_syllable_the_preedit_shows() {
        val disagreements = ArrayList<String>()
        for (s in T9Pinyin.SYLLABLES) {
            val shown = T9Pinyin.segmentLetters(s)?.firstOrNull() ?: continue
            val offered = T9Pinyin.leftColumnLetterReadings(s, 24).firstOrNull() ?: continue
            if (offered != shown) disagreements.add("$s: preedit $shown, column leads $offered")
        }
        assertEquals(
            "the twenty-six key column must lead with the reading the preedit committed to",
            emptyList<String>(),
            disagreements,
        )
    }
}
