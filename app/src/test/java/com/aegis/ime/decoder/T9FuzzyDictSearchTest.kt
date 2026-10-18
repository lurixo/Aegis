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
import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class T9FuzzyDictSearchTest {

    private val readings = listOf(
        "zhongguo", "zongguo", "shixian", "sixiang", "shixiang", "xiangku", "zhangsan", "zanshi",
        "chengshi", "censhi", "shangban", "sanban", "xingqi", "xinqi", "zhengzai", "zenzai",
    )

    private val rows = readings.mapIndexed { i, r -> EngineFixture.Row(r, "词$i", 900 - i) } +
        listOf("zhong", "zong", "guo", "shi", "si", "xian", "xiang", "ku", "zhang", "san", "zan", "cheng", "cen",
            "shang", "ban", "xing", "xin", "qi", "zheng", "zen", "zai").mapIndexed { i, r ->
            EngineFixture.Row(r, EngineFixture.supplementary(300 + i), 500 - i)
        }

    private val t9 = EngineFixture.build(rows.map { it.copy(key = T9Pinyin.toT9(it.key)) })

    private val ruleSets = listOf(Fuzzy.DEFAULT_RULE_KEYS, Fuzzy.RULES.mapTo(LinkedHashSet()) { it.key }, setOf("zh"), setOf("ang"))

    private fun inputs(sources: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (s in sources) {
            val d = T9Pinyin.toT9(s)
            for (i in d.indices) for (j in i + 1..d.length) out.add(d.substring(i, j))
        }
        return out
    }

    private fun assertCoversCappedVariants(dict: BinaryDict, digits: Collection<String>, rules: Set<String>): Pair<Int, Int> {
        var exactMisses = 0
        var prefixMisses = 0
        for (d in digits) {
            val variants = T9Pinyin.fuzzyVariants(d, rules)
            val exact = T9Pinyin.fuzzyVariantInDict(d, rules, dict, false)
            val prefix = T9Pinyin.fuzzyVariantInDict(d, rules, dict, true)
            if (variants.any { dict.exact(it).isNotEmpty() }) assertTrue("$rules exact $d", exact)
            if (variants.any { dict.prefixByFreq(it, 1).isNotEmpty() }) assertTrue("$rules prefix $d", prefix)
            if (exact) assertTrue("$rules exact implies prefix $d", prefix)
            if (!exact) exactMisses++
            if (!prefix) prefixMisses++
        }
        return exactMisses to prefixMisses
    }

    @Test fun every_capped_variant_found_in_the_dictionary_is_reported() {
        val digits = inputs(readings + listOf("zhongguoshixian", "shangbanxingqizhengzai", "chengshizhangsanxiangku"))
        for (rules in ruleSets) {
            val (exactMisses, prefixMisses) = assertCoversCappedVariants(t9, digits, rules)
            assertTrue("$rules prunes some exact searches", exactMisses > digits.size / 2)
            assertTrue("$rules prunes some prefix searches", prefixMisses > 0)
        }
    }

    @Test fun reports_partner_keys_and_rejects_the_input_itself() {
        val zhong = T9Pinyin.toT9("zhong")
        assertTrue(T9Pinyin.fuzzyVariantInDict(zhong, setOf("zh"), t9, false))
        assertFalse(T9Pinyin.fuzzyVariantInDict(zhong, setOf("ang"), t9, false))
        val only = EngineFixture.build(listOf(EngineFixture.Row(zhong, "中", 900)))
        assertFalse(T9Pinyin.fuzzyVariantInDict(zhong, setOf("zh"), only, false))
        assertFalse(T9Pinyin.fuzzyVariantInDict(zhong, setOf("zh"), only, true))
        assertTrue(T9Pinyin.fuzzyVariantInDict(T9Pinyin.toT9("zong"), setOf("zh"), only, false))
        val longer = EngineFixture.build(listOf(EngineFixture.Row(T9Pinyin.toT9("zhongguo"), "中国", 900)))
        assertTrue(T9Pinyin.fuzzyVariantInDict(T9Pinyin.toT9("zong"), setOf("zh"), longer, true))
        assertFalse(T9Pinyin.fuzzyVariantInDict(T9Pinyin.toT9("zong"), setOf("zh"), longer, false))
        assertFalse(T9Pinyin.fuzzyVariantInDict(zhong, emptySet(), t9, false))
        assertFalse(T9Pinyin.fuzzyVariantInDict("94a", setOf("zh"), t9, true))
    }

    private val userModel = UserModel { 0L }.apply { recordWord("zanshi", "暂时", 1L, incrementCount = true) }

    private fun decoder(rules: Set<String>, pruned: Boolean) = if (pruned) {
        PinyinDecoder(
            t9, userModel = userModel, fuzzyRules = rules,
            fuzzyVariants = { s, r -> T9Pinyin.fuzzyVariants(s, r) },
            fuzzyVariantInDict = { dict, s, r, asPrefix -> T9Pinyin.fuzzyVariantInDict(s, r, dict, asPrefix) },
        )
    } else {
        PinyinDecoder(t9, userModel = userModel, fuzzyRules = rules, fuzzyVariants = { s, r -> T9Pinyin.fuzzyVariants(s, r) })
    }

    @Test fun pruned_decoder_answers_like_the_unpruned_one() {
        for (rules in ruleSets) {
            val pruned = decoder(rules, true)
            val plain = decoder(rules, false)
            for (source in readings + listOf("zhongguoshixian", "shangbanxingqizhengzai")) {
                val d = T9Pinyin.toT9(source)
                for (end in 1..d.length) for (context in listOf("", "实现")) {
                    val input = d.substring(0, end)
                    assertEquals("$rules $input after $context", plain.decodeCovered(input, LIMIT, context = context), pruned.decodeCovered(input, LIMIT, context = context))
                }
            }
        }
    }

    @Test fun warm_pruned_decoder_follows_typing_erasing_and_rule_changes() {
        val warm = decoder(ruleSets.first(), true)
        for (rules in ruleSets + listOf(emptySet(), ruleSets.first())) {
            warm.setFuzzyRules(rules)
            val plain = decoder(rules, false)
            for (source in listOf("zhongguoshixian", "shangbanxingqizhengzai", "zanshixiangku")) {
                val d = T9Pinyin.toT9(source)
                val n = d.length
                for (end in (1..n) + (n - 1 downTo 1) + (2..n)) {
                    val input = d.substring(0, end)
                    assertEquals("$rules $input", plain.decodeCovered(input, LIMIT), warm.decodeCovered(input, LIMIT))
                }
            }
        }
    }

    @Test fun full_dictionary_search_covers_every_capped_variant() {
        val dir = System.getenv("AEGIS_FULLDICT_DIR")
        assumeTrue("full-dict check only when AEGIS_FULLDICT_DIR is set", !dir.isNullOrEmpty())
        val dict = BinaryDict.fromFile(File(dir, "aegis_t9.bin"))
        val digits = inputs(FULL_SENTENCES)
        val (exactMisses, prefixMisses) = assertCoversCappedVariants(dict, digits, Fuzzy.DEFAULT_RULE_KEYS)
        assertTrue(exactMisses > 0 && prefixMisses > 0)
    }

    private companion object {
        const val LIMIT = 30
        val FULL_SENTENCES = listOf(
            "zhegewentizenmejiejue", "zhoumoyiqiquguangjieba", "shoujikuaimeidianlewoxianchongdian",
            "wozhidaolemingtianxiawusandiankaihui", "xiexienidebangzhuwojintianwanshangyaojiaban",
        )
    }
}
