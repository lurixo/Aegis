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

class FuzzyVariantsTest {

    private val all = Fuzzy.RULES.map { it.key }.toSet()
    private fun vs(s: String, keys: Set<String>) = Fuzzy.variants(s, keys).toSet()

    @Test
    fun noRulesIsIdentity() {
        assertEquals(listOf("zhang"), Fuzzy.variants("zhang", emptySet()))
    }

    @Test
    fun allRulesGiveFullConfusionClass() {
        assertEquals(setOf("zhang", "zang", "zhan", "zan"), vs("zhang", setOf("zh", "ang")))
    }

    @Test
    fun rulesAreIndependent() {
        assertEquals(setOf("zhang", "zang"), vs("zhang", setOf("zh")))
        assertFalse("ang rule off → no an-final variant", vs("zhang", setOf("zh")).contains("zhan"))
        assertEquals(setOf("zhang", "zhan"), vs("zhang", setOf("ang")))
        assertFalse("zh rule off → no z-initial variant", vs("zhang", setOf("ang")).contains("zang"))
    }

    @Test
    fun nestingAnInAngIsSafe() {
        assertEquals(setOf("zang", "zan"), vs("zang", setOf("ang")))
        assertFalse(vs("zang", setOf("ang")).any { it.contains("gg") })
    }

    @Test
    fun frontBackNasalBothDirections() {
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
        assertTrue("bounded by cap", out.size <= 64)
    }

    @Test
    fun longFuzzyRunIsBoundedAndNeverOverflows() {
        for (len in intArrayOf(20, 31, 35, 200)) {
            val s = "z".repeat(len)
            val out = Fuzzy.variants(s, setOf("zh"))
            assertTrue("bounded at len=$len (got ${out.size})", out.size <= 64)
            assertTrue("keeps original at len=$len", out.contains(s))
        }
        assertTrue(Fuzzy.variants("an".repeat(15), setOf("ang")).size <= 64)
    }

    @Test
    fun fuzzyDefaultsOff() {
        assertFalse("模糊拼音 must ship OFF by default", Fuzzy.DEFAULT_ON)
    }

    @Test
    fun initialConsonantRules_C4() {
        assertTrue("n→l: nan reaches lan", vs("nan", setOf("n_l")).containsAll(setOf("nan", "lan")))
        assertTrue("l→n: lan reaches nan", vs("lan", setOf("n_l")).containsAll(setOf("lan", "nan")))
        assertTrue("f↔h: fan↔han", vs("fan", setOf("f_h")).containsAll(setOf("fan", "han")))
        assertTrue("l↔r: lan↔ran", vs("lan", setOf("l_r")).containsAll(setOf("lan", "ran")))
        assertTrue("k↔g: kan↔gan", vs("kan", setOf("k_g")).containsAll(setOf("kan", "gan")))
        assertFalse("rules independent: n_l alone leaves f/h untouched", vs("fan", setOf("n_l")).contains("han"))
        assertTrue(vs("kan", setOf("k_g")).contains("kan"))
        assertTrue(vs("nan", setOf("n_l", "l_r", "f_h", "k_g")).size <= 64)
        assertTrue(Fuzzy.RULES.map { it.key }.containsAll(listOf("n_l", "f_h", "l_r", "k_g")))
    }
}
