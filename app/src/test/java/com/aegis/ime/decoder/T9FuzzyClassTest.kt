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

import com.aegis.ime.dict.Fuzzy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9FuzzyClassTest {

    private val allRules = Fuzzy.RULES.mapTo(LinkedHashSet()) { it.key }

    private fun letterPartners(s: String, rule: String): Set<String> =
        Fuzzy.variants(s, setOf(rule)).filterTo(LinkedHashSet()) { it != s && it in T9Pinyin.SYLLABLES }

    private val allSyllableDigits = T9Pinyin.SYLLABLES.mapTo(HashSet()) { T9Pinyin.toT9(it) }

    private fun decomposable(digits: String): Boolean {
        val n = digits.length
        val ok = BooleanArray(n + 1)
        ok[0] = true
        for (i in 1..n) {
            for (j in maxOf(0, i - 8) until i) {
                if (ok[j] && T9Pinyin.syllableReading(digits.substring(j, i)).isNotEmpty()) {
                    ok[i] = true
                    break
                }
            }
        }
        if (ok[n]) return true
        for (j in 0..n - 1) {
            if (!ok[j]) continue
            val tail = digits.substring(j)
            if (allSyllableDigits.any { it.length > tail.length && it.startsWith(tail) }) return true
        }
        return false
    }

    @Test fun every_letter_level_pair_is_reachable_in_digits() {
        for (rule in Fuzzy.RULES) {
            for (s in T9Pinyin.SYLLABLES) {
                val digits = T9Pinyin.toT9(s)
                val got = T9Pinyin.fuzzyVariants(digits, setOf(rule.key)).toSet()
                for (p in letterPartners(s, rule.key)) {
                    val pd = T9Pinyin.toT9(p)
                    if (pd != digits) assertTrue("$s must reach $p under ${rule.key}", pd in got)
                }
            }
        }
    }

    @Test fun every_variant_is_a_run_of_real_syllables_or_ends_typing_one() {
        for (rule in Fuzzy.RULES) {
            for (s in T9Pinyin.SYLLABLES) {
                for (v in T9Pinyin.fuzzyVariants(T9Pinyin.toT9(s), setOf(rule.key))) {
                    assertTrue("variant $v of $s under ${rule.key} must decompose into syllables", decomposable(v))
                }
            }
        }
    }

    @Test fun a_rule_stays_out_of_readings_it_does_not_pair() {
        assertEquals(
            "wo has no zh partner",
            emptyList<String>(),
            T9Pinyin.fuzzyVariants(T9Pinyin.toT9("wo"), setOf("zh")),
        )
        assertEquals(
            "no reading on the keys of ma ends in an, so ang touches nothing there",
            emptyList<String>(),
            T9Pinyin.fuzzyVariants(T9Pinyin.toT9("ma"), setOf("ang")),
        )
        val ma = T9Pinyin.fuzzyVariants(T9Pinyin.toT9("ma"), setOf("n_l", "l_r", "k_g"))
        assertEquals(
            "the keys of ma pair only through na and la, never across other initials",
            listOf(T9Pinyin.toT9("la")),
            ma,
        )
    }

    @Test fun a_multi_syllable_run_rewrites_only_the_syllable_the_rule_touches() {
        val zhongguo = T9Pinyin.toT9("zhong") + T9Pinyin.toT9("guo")
        val variants = T9Pinyin.fuzzyVariants(zhongguo, setOf("zh"))
        assertTrue(
            "zhongguo reaches zongguo",
            T9Pinyin.toT9("zong") + T9Pinyin.toT9("guo") in variants,
        )
        assertTrue(
            "nothing rewrites guo under zh",
            variants.all { it.endsWith(T9Pinyin.toT9("guo")) },
        )
    }

    @Test fun the_expansion_respects_its_cap_and_never_offers_the_identity() {
        for (s in T9Pinyin.SYLLABLES) {
            val digits = T9Pinyin.toT9(s)
            val variants = T9Pinyin.fuzzyVariants(digits, allRules)
            assertTrue("cap respected for $s", variants.size <= 64)
            assertTrue("identity is never offered as a variant of $s", digits !in variants)
        }
    }
}
