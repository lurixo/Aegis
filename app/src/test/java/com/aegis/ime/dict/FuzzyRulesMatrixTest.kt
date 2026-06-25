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

import org.junit.Assert.*
import org.junit.Test

class FuzzyRulesMatrixTest {
    @Test fun exact_finals_and_whole_syllables_do_not_bleed_into_other_rules() {
        assertEquals(listOf("xian"), Fuzzy.variants("xian", setOf("ang")))
        assertEquals(listOf("juan"), Fuzzy.variants("juan", setOf("ang")))
        assertEquals(listOf("huang"), Fuzzy.variants("huang", setOf("hu_fu")))
        assertEquals(listOf("shang"), Fuzzy.variants("shang", setOf("ang").minus("ang")))
        assertFalse("nal" in Fuzzy.variants("nan", setOf("n_l")))
        assertTrue("leng" in Fuzzy.variants("len", setOf("eng")))
        assertTrue("dong" in Fuzzy.variants("don", setOf("on_ong")))
        assertFalse("dong" in Fuzzy.variants("don", emptySet()))
    }

    @Test fun originals_are_first_and_small_caps_and_invalid_targets_are_safe() {
        val all = Fuzzy.RULES.map { it.key }.toSet()
        for (cap in listOf(1, 2, 8, 64)) {
            val out = Fuzzy.variants("zhangnanning", all, cap)
            assertEquals("zhangnanning", out.first())
            assertTrue(out.size <= cap)
        }
        assertEquals(emptyList<String>(), Fuzzy.variants("zhang", all, 0))
        assertFalse("juang" in Fuzzy.variants("juan", setOf("uang")))
    }
}
