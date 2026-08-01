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
    fun normalizeIsTheFinalsCanonical() {
        val finals = setOf("zh", "ch", "sh", "ang", "eng", "ing")
        for (s in listOf("zhang", "chengshi", "yingxiong", "nihao", "shangchang")) {
            assertEquals(Fuzzy.normalize(s), Fuzzy.collapse(s, finals))
        }
        assertEquals("zan", Fuzzy.normalize("zhang"))
        assertEquals("nihao", Fuzzy.normalize("nihao"))
    }

    @Test
    fun alwaysContainsInputAndIsBounded() {
        val out = Fuzzy.variants("shangchang", all)
        assertTrue("keeps the original spelling", out.contains("shangchang"))
        assertTrue("bounded by cap", out.size <= 64)
    }

    @Test
    fun longFuzzyRunContinuesPastEveryFormerBound() {
        for (length in listOf(39, 40, 41, 80)) {
            val input = "z".repeat(length)
            val cursor = Fuzzy.variantCursor(input, setOf("zh"))
            val out = ArrayList<String>()
            repeat(130) { cursor.next()?.let(out::add) }
            assertEquals("input length $length", 130, out.size)
            assertEquals(input, out.first())
            assertTrue("a site beyond the former six-toggle window must change", out.any { it.takeLast(8).contains("zh") })
            assertTrue("input length $length must not disable fuzzy matching", out.any { it.length > input.length })
            assertTrue("generation must remain resumable after the first 64", cursor.peek() != null)
        }
    }

    @Test
    fun everyRepeatedSiteIsEventuallyReachable() {
        val cursor = Fuzzy.variantCursor("z".repeat(8), setOf("zh"))
        val out = LinkedHashSet<String>()
        while (true) out.add(cursor.next() ?: break)
        assertEquals(256, out.size)
        assertTrue(out.contains("zh".repeat(8)))
        assertEquals(null, cursor.peek())
    }

    @Test
    fun fuzzyDefaultsOff() {
        assertFalse("模糊拼音 must ship OFF by default", Fuzzy.DEFAULT_ON)
    }

    @Test
    fun activeRulesSelectsByMasterAndPerRuleToggles() {
        assertEquals(emptySet<String>(), Fuzzy.activeRules(masterOn = false) { true })
        assertEquals(all, Fuzzy.activeRules(masterOn = true) { true })
        assertEquals(emptySet<String>(), Fuzzy.activeRules(masterOn = true) { false })
        assertEquals(setOf("zh"), Fuzzy.activeRules(masterOn = true) { it == "zh" })
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

    @Test
    fun allRulesTogether_resolveInitialConfusions_withoutRegressingFinals() {
        assertTrue("n↔l↔r at 首位 (南=兰)", vs("nan", all).containsAll(setOf("nan", "lan", "ran")))
        assertTrue("f↔h: fan→han", vs("fan", all).contains("han"))
        assertTrue("k↔g: kan→gan", vs("kan", all).contains("gan"))
        assertTrue("l↔r: lan→ran", vs("lan", all).contains("ran"))
        assertTrue("平翘舌+前后鼻音 still resolve: zhang⊇{zang,zhan,zan}", vs("zhang", all).containsAll(setOf("zang", "zhan", "zan")))
        assertTrue("shang→sang", vs("shang", all).contains("sang"))
        assertTrue("zheng→zeng", vs("zheng", all).contains("zeng"))
        assertFalse("声母 rule must not touch the final -n of nan", vs("nan", all).contains("nal"))
        assertTrue("bounded", vs("nan", all).size <= 64 && vs("shangchang", all).size <= 64)
    }
}
