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

import com.aegis.ime.dict.BinaryDict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingLookupCacheTest {

    private fun lookup(): Any {
        val type = PinyinDecoder::class.java.declaredClasses.single { it.simpleName == "ReadingLookup" }
        val constructor = type.getDeclaredConstructor(BinaryDict::class.java).apply { isAccessible = true }
        return constructor.newInstance(EngineFixture.build(listOf(
            EngineFixture.Row("ni", "你", 900), EngineFixture.Row("ni", "尼", 300),
            EngineFixture.Row("hao", "好", 700),
        )))
    }

    private fun matches(lookup: Any, input: String, t9: Boolean, prefix: Boolean): List<*> {
        val method = lookup.javaClass.getDeclaredMethod("matching", String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        return method.invoke(lookup, input, t9, prefix) as List<*>
    }

    private fun frequency(lookup: Any, reading: String, word: String): Int? {
        val method = lookup.javaClass.getDeclaredMethod("frequency", String::class.java, String::class.java)
            .apply { isAccessible = true }
        return method.invoke(lookup, reading, word) as Int?
    }

    @Test fun prefix_and_complete_queries_do_not_reuse_each_others_cached_matches() {
        for (t9 in listOf(false, true)) for (prefixFirst in listOf(false, true)) {
            val lookup = lookup()
            val input = if (t9) "6" else "n"
            val pair = "ni" to if (t9) "64" else "ni"
            val first = matches(lookup, input, t9, prefixFirst)
            val second = matches(lookup, input, t9, !prefixFirst)
            val prefix = if (prefixFirst) first else second
            val complete = if (prefixFirst) second else first
            assertTrue(prefix.contains(pair))
            assertFalse(complete.contains(pair))
            assertEquals(prefix, matches(lookup, input, t9, true))
            assertEquals(complete, matches(lookup, input, t9, false))
        }
    }

    @Test fun letter_and_digit_queries_do_not_share_a_cached_result() {
        for (digitsFirst in listOf(false, true)) {
            val lookup = lookup()
            matches(lookup, "64", digitsFirst, false)
            assertTrue(matches(lookup, "64", true, false).contains("ni" to "64"))
            assertTrue(matches(lookup, "64", false, false).isEmpty())
        }
    }

    @Test fun frequency_maps_remain_scoped_to_the_reading_and_word() {
        val lookup = lookup()
        assertEquals(900, frequency(lookup, "ni", "你"))
        assertEquals(300, frequency(lookup, "ni", "尼"))
        assertNull(frequency(lookup, "hao", "你"))
        assertEquals(700, frequency(lookup, "hao", "好"))
        assertNull(frequency(lookup, "ni", "好"))
        assertEquals(900, frequency(lookup, "ni", "你"))
    }
}
