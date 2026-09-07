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

import com.aegis.ime.decoder.T9Pinyin
import org.junit.Assert.*
import org.junit.Test

class FuzzyRulesMatrixTest {
    private val cases = listOf(
        Triple("zh", "zhang", "zang"), Triple("ch", "chang", "cang"),
        Triple("sh", "shang", "sang"), Triple("n_l", "nan", "lan"),
        Triple("f_h", "han", "fan"), Triple("l_r", "ran", "lan"),
        Triple("k_g", "kan", "gan"), Triple("ang", "gang", "gan"),
        Triple("eng", "feng", "fen"), Triple("ing", "xing", "xin"),
        Triple("iang", "xiang", "xian"), Triple("uang", "huang", "huan"),
        Triple("un_ong", "dun", "dong"), Triple("on_ong", "dong", "don"),
        Triple("un_iong", "jun", "jiong"), Triple("eng_ong", "neng", "nong"),
        Triple("an_ai", "ban", "bai"), Triple("hui_fei", "hui", "fei"),
        Triple("hu_fu", "hu", "fu"), Triple("huang_wang", "huang", "wang"),
    )

    @Test fun twenty_rules_match_in_both_layouts_and_both_directions() {
        assertEquals(cases.map { it.first }.toSet(), Fuzzy.RULES.map { it.key }.toSet())
        for ((key, a, b) in cases) for ((source, target) in listOf(a to b, b to a)) {
            if (target !in PinyinSyllables.ALL) continue
            assertTrue("$key: $source -> $target", target in Fuzzy.variants(source, setOf(key)))
            val digits = T9Pinyin.toT9(source)
            assertTrue("nine $key: $source -> $target", T9Pinyin.toT9(target) in
                T9Pinyin.fuzzyVariants(digits, setOf(key)) + digits)
        }
    }

    @Test fun combinations_and_each_syllable_of_a_phrase_are_reachable() {
        for ((source, target, keys) in listOf(
            Triple("zhang", "zan", setOf("zh", "ang")),
            Triple("nan", "ran", setOf("n_l", "l_r")),
            Triple("nanning", "lanling", setOf("n_l")),
            Triple("huifei", "feihui", setOf("hui_fei")),
        )) {
            assertTrue("$source -> $target", target in Fuzzy.variants(source, keys))
            assertTrue("nine $source -> $target", T9Pinyin.toT9(target) in
                T9Pinyin.fuzzyVariants(T9Pinyin.toT9(source), keys) + T9Pinyin.toT9(source))
        }
        assertTrue("lan'ling" in Fuzzy.variants("nan'ning", setOf("n_l")))
    }

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
