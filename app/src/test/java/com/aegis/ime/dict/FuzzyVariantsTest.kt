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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E4 — per-rule fuzzy variant expansion: rules act independently and the an⊂ang nesting is safe. */
class FuzzyVariantsTest {

    private val all = Fuzzy.RULES.map { it.key }.toSet()
    private fun vs(s: String, keys: Set<String>) = Fuzzy.variants(s, keys).toSet()

    @Test
    fun noRulesIsIdentity() {
        assertEquals(listOf("zhang"), Fuzzy.variants("zhang", emptySet()))
    }

    @Test
    fun allRulesGiveFullConfusionClass() {
        // zh↔z and ang↔an together → the four-way class.
        assertEquals(setOf("zhang", "zang", "zhan", "zan"), vs("zhang", all))
    }

    @Test
    fun rulesAreIndependent() {
        // Only 平翘舌 zh: just the initial flips, finals untouched.
        assertEquals(setOf("zhang", "zang"), vs("zhang", setOf("zh")))
        assertFalse("ang rule off → no an-final variant", vs("zhang", setOf("zh")).contains("zhan"))
        // Only 前后鼻音 ang: just the final flips, initial untouched.
        assertEquals(setOf("zhang", "zhan"), vs("zhang", setOf("ang")))
        assertFalse("zh rule off → no z-initial variant", vs("zhang", setOf("ang")).contains("zang"))
    }

    @Test
    fun nestingAnInAngIsSafe() {
        // "zang" + only ang↔an must yield exactly {zang, zan}; never the "zangg" overlap garbage.
        assertEquals(setOf("zang", "zan"), vs("zang", setOf("ang")))
        assertFalse(vs("zang", setOf("ang")).any { it.contains("gg") })
    }

    @Test
    fun frontBackNasalBothDirections() {
        // typing the short final still reaches the long one (xin ↔ xing) and vice versa.
        assertEquals(setOf("xin", "xing"), vs("xin", setOf("ing")))
        assertEquals(setOf("xing", "xin"), vs("xing", setOf("ing")))
    }

    @Test
    fun collapseMatchesLegacyNormalize() {
        for (s in listOf("zhang", "chengshi", "yingxiong", "nihao", "shangchang")) {
            assertEquals(Fuzzy.normalize(s), Fuzzy.collapse(s, all))
        }
    }

    @Test
    fun alwaysContainsInputAndIsBounded() {
        val out = Fuzzy.variants("shangchang", all)
        assertTrue("keeps the original spelling", out.contains("shangchang"))
        assertTrue("bounded by cap", out.size <= 24)
    }
}
