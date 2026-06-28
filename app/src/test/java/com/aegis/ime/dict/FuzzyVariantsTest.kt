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
        // zh↔z and ang↔an together → the four-way class. (Uses the explicit subset, not every rule, so
        // it stays exact as new rules — e.g. the n↔l 声母 rule, which also toggles "zhang"'s -n — join.)
        assertEquals(setOf("zhang", "zang", "zhan", "zan"), vs("zhang", setOf("zh", "ang")))
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
    fun normalizeIsTheFinalsCanonical() {
        // normalize is the 平翘舌/前后鼻音 canonical (identical to tools/Pinyin.fuzzyNormalize); the
        // single-letter 声母 rules are deliberately NOT part of the canonical (they would chain n→l→r).
        val finals = setOf("zh", "ch", "sh", "ang", "eng", "ing")
        for (s in listOf("zhang", "chengshi", "yingxiong", "nihao", "shangchang")) {
            assertEquals(Fuzzy.normalize(s), Fuzzy.collapse(s, finals))
        }
        assertEquals("zan", Fuzzy.normalize("zhang"))
        assertEquals("nihao", Fuzzy.normalize("nihao")) // 声母 n is NOT collapsed
    }

    @Test
    fun alwaysContainsInputAndIsBounded() {
        val out = Fuzzy.variants("shangchang", all)
        assertTrue("keeps the original spelling", out.contains("shangchang"))
        assertTrue("bounded by cap", out.size <= 64)
    }

    @Test
    fun longFuzzyRunIsBoundedAndNeverOverflows() {
        // ★HIGH regression: a long run of a fuzzy letter used to build a 2^n list; at n=31
        // `1 shl 31` went negative and `ArrayList(negative)` hard-crashed the IME. Must stay bounded.
        for (len in intArrayOf(20, 31, 35, 200)) {
            val s = "z".repeat(len)
            val out = Fuzzy.variants(s, setOf("zh")) // must not throw
            assertTrue("bounded at len=$len (got ${out.size})", out.size <= 64)
            assertTrue("keeps original at len=$len", out.contains(s))
        }
        // multi-occurrence final rule stays bounded too (15 "an" sites would be 2^15 unguarded)
        assertTrue(Fuzzy.variants("an".repeat(15), setOf("ang")).size <= 64)
    }

    @Test
    fun fuzzyDefaultsOff() {
        assertFalse("模糊拼音 must ship OFF by default", Fuzzy.DEFAULT_ON)
    }

    @Test
    fun initialConsonantRules_C4() {
        // Each 声母 rule resolves its intended confusion in BOTH directions, independently.
        assertTrue("n→l: nan reaches lan", vs("nan", setOf("n_l")).containsAll(setOf("nan", "lan")))
        assertTrue("l→n: lan reaches nan", vs("lan", setOf("n_l")).containsAll(setOf("lan", "nan")))
        assertTrue("f↔h: fan↔han", vs("fan", setOf("f_h")).containsAll(setOf("fan", "han")))
        assertTrue("l↔r: lan↔ran", vs("lan", setOf("l_r")).containsAll(setOf("lan", "ran")))
        assertTrue("k↔g: kan↔gan", vs("kan", setOf("k_g")).containsAll(setOf("kan", "gan")))
        // independence: with only n_l, the f/h rule must NOT fire (fan does not reach han).
        assertFalse("rules independent: n_l alone leaves f/h untouched", vs("fan", setOf("n_l")).contains("han"))
        // the original spelling always survives and the class stays bounded even for the new rules.
        assertTrue(vs("kan", setOf("k_g")).contains("kan"))
        assertTrue(vs("nan", setOf("n_l", "l_r", "f_h", "k_g")).size <= 64)
        // the four new rule keys are present and independently togglable.
        assertTrue(Fuzzy.RULES.map { it.key }.containsAll(listOf("n_l", "f_h", "l_r", "k_g")))
    }

    @Test
    fun allRulesTogether_resolveInitialConfusions_withoutRegressingFinals() {
        // ★HIGH regression guard (debug.13). With the master switch ON every per-rule toggle defaults to
        // true (AegisInputMethodService builds `RULES.filter { getBoolean(prefKey, true) }`), so ALL ten
        // rules fire together. The old collapse-then-expand chained n_l's short 'l' into l_r's long 'l'
        // (n→l→r), over-collapsing the canonical so the real spellings could never be restored:
        //   variants("nan", all) came out {lal,lar,nan,ral,rar} — no 南=兰 "lan", no "ran" — and, worse, it
        //   REGRESSED the previously-working 平翘舌/前后鼻音: variants("zhang", all) lost zang/zhan/zan.
        // These MEMBERSHIP asserts (not just a size bound) fail on the old chaining code and lock the fix.
        // (1) the new 声母 confusions all resolve at the 声母首位 even with every rule co-enabled:
        assertTrue("n↔l↔r at 首位 (南=兰)", vs("nan", all).containsAll(setOf("nan", "lan", "ran")))
        assertTrue("f↔h: fan→han", vs("fan", all).contains("han"))
        assertTrue("k↔g: kan→gan", vs("kan", all).contains("gan"))
        assertTrue("l↔r: lan→ran", vs("lan", all).contains("ran"))
        // (2) NO regression of the original 6 rules while the 声母 rules are also enabled:
        assertTrue("平翘舌+前后鼻音 still resolve: zhang⊇{zang,zhan,zan}", vs("zhang", all).containsAll(setOf("zang", "zhan", "zan")))
        assertTrue("shang→sang", vs("shang", all).contains("sang"))
        assertTrue("zheng→zeng", vs("zheng", all).contains("zeng"))
        // the 声母 rules stay at the 首位 only: an interior/final consonant must NOT toggle into garbage.
        assertFalse("声母 rule must not touch the final -n of nan", vs("nan", all).contains("nal"))
        // still bounded with everything on (OOM guard intact).
        assertTrue("bounded", vs("nan", all).size <= 64 && vs("shangchang", all).size <= 64)
    }
}
