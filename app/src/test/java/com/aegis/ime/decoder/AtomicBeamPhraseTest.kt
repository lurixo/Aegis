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
import com.aegis.ime.dict.CharBigramLM
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AtomicBeamPhraseTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")

    private val phrases: List<Pair<String, List<String>>> = listOf(
        "四十四只石狮子" to listOf("si", "shi", "si", "zhi", "shi", "shi", "zi"),
        "时间问题" to listOf("shi", "jian", "wen", "ti"),
        "早上好" to listOf("zao", "shang", "hao"),
        "现在时间" to listOf("xian", "zai", "shi", "jian"),
        "中国文化" to listOf("zhong", "guo", "wen", "hua"),
        "一个问题" to listOf("yi", "ge", "wen", "ti"),
        "今天天气怎么样" to listOf("jin", "tian", "tian", "qi", "zen", "me", "yang"),
        "明天见" to listOf("ming", "tian", "jian"),
        "晚上一起吃饭" to listOf("wan", "shang", "yi", "qi", "chi", "fan"),
        "我们一起去" to listOf("wo", "men", "yi", "qi", "qu"),
        "你在哪里" to listOf("ni", "zai", "na", "li"),
        "现在几点" to listOf("xian", "zai", "ji", "dian"),
        "马上就到" to listOf("ma", "shang", "jiu", "dao"),
        "没有问题" to listOf("mei", "you", "wen", "ti"),
        "不好意思" to listOf("bu", "hao", "yi", "si"),
        "谢谢大家" to listOf("xie", "xie", "da", "jia"),
        "祝你生日快乐" to listOf("zhu", "ni", "sheng", "ri", "kuai", "le"),
        "新年快乐" to listOf("xin", "nian", "kuai", "le"),
        "身体健康" to listOf("shen", "ti", "jian", "kang"),
        "工作顺利" to listOf("gong", "zuo", "shun", "li"),
        "学习进步" to listOf("xue", "xi", "jin", "bu"),
        "天气不错" to listOf("tian", "qi", "bu", "cuo"),
        "下雨了" to listOf("xia", "yu", "le"),
        "吃饭了吗" to listOf("chi", "fan", "le", "ma"),
        "我爱你" to listOf("wo", "ai", "ni"),
        "好久不见" to listOf("hao", "jiu", "bu", "jian"),
        "辛苦了" to listOf("xin", "ku", "le"),
        "慢慢来" to listOf("man", "man", "lai"),
        "没关系" to listOf("mei", "guan", "xi"),
        "对不起" to listOf("dui", "bu", "qi"),
        "打电话" to listOf("da", "dian", "hua"),
        "发短信" to listOf("fa", "duan", "xin"),
        "看电影" to listOf("kan", "dian", "ying"),
        "听音乐" to listOf("ting", "yin", "yue"),
        "去超市买东西" to listOf("qu", "chao", "shi", "mai", "dong", "xi"),
        "坐地铁上班" to listOf("zuo", "di", "tie", "shang", "ban"),
        "周末愉快" to listOf("zhou", "mo", "yu", "kuai"),
        "一路平安" to listOf("yi", "lu", "ping", "an"),
        "万事如意" to listOf("wan", "shi", "ru", "yi"),
        "心想事成" to listOf("xin", "xiang", "shi", "cheng"),
        "恭喜发财" to listOf("gong", "xi", "fa", "cai"),
        "中华人民共和国" to listOf("zhong", "hua", "ren", "min", "gong", "he", "guo"),
        "北京欢迎你" to listOf("bei", "jing", "huan", "ying", "ni"),
        "上海很大" to listOf("shang", "hai", "hen", "da"),
        "广州天气热" to listOf("guang", "zhou", "tian", "qi", "re"),
        "火车站在哪" to listOf("huo", "che", "zhan", "zai", "na"),
        "飞机晚点了" to listOf("fei", "ji", "wan", "dian", "le"),
        "高铁很快" to listOf("gao", "tie", "hen", "kuai"),
        "电脑坏了" to listOf("dian", "nao", "huai", "le"),
        "手机没电了" to listOf("shou", "ji", "mei", "dian", "le"),
    )

    @Test fun everyNaturalPhraseSurfacesAsAFullSentenceCompositeOnBothLayouts() {
        assumeTrue("full dict assets present", dictFile.exists() && t9File.exists() && lmFile.exists())
        val lm = CharBigramLM.fromFile(lmFile)
        val letterDict = BinaryDict.fromFile(dictFile)
        val d = PinyinDecoder(letterDict, lm)
        val t9 = PinyinDecoder(BinaryDict.fromFile(t9File), lm, aliasDict = letterDict)

        val misses = ArrayList<String>()
        val table = StringBuilder("phrase\tletters\t26k_index\t9k_index\n")
        for ((phrase, syls) in phrases) {
            val letters = syls.joinToString("")
            val digits = syls.joinToString("") { T9Pinyin.toT9(it) }
            val letterCands = d.decodeCoveredAtomic(letters, 30)
            val digitCands = t9.decodeCoveredAtomic(digits, 30)
            val li = letterCands.indexOfFirst { it.word == phrase && it.coveredLen == letters.length }
            val di = digitCands.indexOfFirst { it.word == phrase && it.coveredLen == digits.length }
            if (li < 0) misses.add("26-key $letters misses $phrase")
            if (di < 0) misses.add("9-key $digits misses $phrase")
            table.append("$phrase\t$letters\t$li\t$di\n")
        }
        File("build/atomic_beam_phrases.tsv").apply {
            parentFile?.mkdirs()
            writeText(table.toString())
        }
        assertEquals(50, phrases.size)
        assertTrue(
            "every natural phrase must be reachable as a full-sentence composite; missing: $misses",
            misses.isEmpty(),
        )
    }

    @Test fun pairInputsKeepTheCompactSentenceBlock() {
        assumeTrue("full dict assets present", dictFile.exists() && t9File.exists() && lmFile.exists())
        val lm = CharBigramLM.fromFile(lmFile)
        val d = PinyinDecoder(BinaryDict.fromFile(dictFile), lm)
        for (reading in listOf("nihao", "shijian", "fangan")) {
            val cands = d.decodeCoveredAtomic(reading, 30)
            val fullMulti = cands.count {
                it.coveredLen == reading.length && it.word.codePointCount(0, it.word.length) > 1
            }
            val fullWords = BinaryDict.fromFile(dictFile).exact(reading)
                .count { it.word.codePointCount(0, it.word.length) > 1 }
            assertTrue(
                "$reading: two-syllable inputs keep the compact sentence block, got $fullMulti composites",
                fullMulti <= PinyinDecoder.ATOMIC_BEAM_N + fullWords,
            )
        }
    }
}
