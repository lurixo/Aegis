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
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.user.UserModel
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class T9RerankEvalTest {

    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val evalFile = File("src/test/resources/eval_set.tsv")
    private val gramFile =
        System.getenv("AEGIS_GRAM")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File("build/nonexistent-aegis-gram")

    private fun toT9(letters: String): String {
        val map = HashMap<Char, Char>()
        "abc".forEach { map[it] = '2' }; "def".forEach { map[it] = '3' }
        "ghi".forEach { map[it] = '4' }; "jkl".forEach { map[it] = '5' }
        "mno".forEach { map[it] = '6' }; "pqrs".forEach { map[it] = '7' }
        "tuv".forEach { map[it] = '8' }; "wxyz".forEach { map[it] = '9' }
        return buildString { letters.forEach { append(map[it] ?: it) } }
    }

    private val t9Set = listOf(
        "nihao" to "你好", "woshi" to "我是", "zhongguo" to "中国", "beijing" to "北京",
        "ceshi" to "测试", "xuanze" to "选择", "shijian" to "时间", "zenme" to "怎么",
        "shenme" to "什么", "women" to "我们", "gongzuo" to "工作", "wenti" to "问题",
        "keyi" to "可以", "meiyou" to "没有", "yinwei" to "因为", "suoyi" to "所以",
        "zhidao" to "知道", "haode" to "好的", "xuyao" to "需要", "yiqi" to "一起",
        "kaishi" to "开始", "xianzai" to "现在", "shihou" to "时候", "keneng" to "可能",
        "yinggai" to "应该", "renwei" to "认为", "name" to "那么", "haishi" to "还是",
        "xiexie" to "谢谢", "mama" to "妈妈", "baba" to "爸爸", "gege" to "哥哥",
        "didi" to "弟弟", "jiejie" to "姐姐", "kankan" to "看看", "shishi" to "试试",
        "xiangxiang" to "想想", "tiantian" to "天天", "ganggang" to "刚刚",
        "jintiantianqi" to "今天天气", "shoujihaoma" to "手机号码", "xiexieni" to "谢谢你",
        "woaini" to "我爱你",
    )

    private fun pairs(): List<Pair<String, String>> =
        evalFile.readLines().mapNotNull {
            val t = it.indexOf('\t'); if (t <= 0) null else it.substring(0, t) to it.substring(t + 1)
        }

    private fun t9Top1(d: PinyinDecoder): Int =
        t9Set.count { (py, exp) -> d.decodeCovered(toT9(py), 30).firstOrNull()?.word == exp }

    private fun pyTop1(d: PinyinDecoder, ps: List<Pair<String, String>>): Pair<Int, Int> {
        var dec = 0; var cov = 0
        for ((p, e) in ps) {
            if (d.decode(p, 5).firstOrNull() == e) dec++
            if (d.decodeCovered(p, 30).firstOrNull()?.word == e) cov++
        }
        return dec to cov
    }

    @Test
    fun reportT9AndRegression() {
        assumeTrue("assets present", t9File.exists() && dictFile.exists() && lmFile.exists() && evalFile.exists())
        val t9 = BinaryDict.fromFile(t9File)
        val dict = BinaryDict.fromFile(dictFile)
        val lm = CharBigramLM.fromFile(lmFile)
        val ps = pairs()
        val oct = if (gramFile.exists()) OctagramReader.fromFile(gramFile) else null

        val sb = StringBuilder("T9 set=${t9Set.size}, full-pinyin set=${ps.size}, octagram=${oct != null}\n\n")
        sb.append(String.format("%-24s %-15s %-16s %-16s%n", "config", "T9 top1", "PY decode top1", "PY covered top1"))
        fun row(label: String, t9d: PinyinDecoder, pyd: PinyinDecoder) {
            val t = t9Top1(t9d); val (d, c) = pyTop1(pyd, ps)
            sb.append(String.format("%-24s %-15s %-16s %-16s%n", label,
                "${t}/${t9Set.size} (${"%.1f".format(100.0 * t / t9Set.size)}%)",
                "${d}/${ps.size} (${"%.1f".format(100.0 * d / ps.size)}%)",
                "${c}/${ps.size} (${"%.1f".format(100.0 * c / ps.size)}%)"))
        }

        row("bundled (bigram)", PinyinDecoder(t9, lm), PinyinDecoder(dict, lm))
        if (oct != null) row("+ octagram", PinyinDecoder(t9, lm, octagram = oct), PinyinDecoder(dict, lm, octagram = oct))
        val um5 = UserModel().apply { t9Set.forEach { repeat(5) { _ -> record(null, it.second, 1) } } }
        row("+ learning x5", PinyinDecoder(t9, lm, userModel = um5), PinyinDecoder(dict, lm))

        sb.append("\n943943 list (bundled)   = ").append(PinyinDecoder(t9, lm).decodeCovered("943943", 8).map { it.word })
        val umXie = UserModel().apply { repeat(30) { record(null, "谢谢", 1) } }
        sb.append("\n943943 list (learn 谢谢) = ")
            .append(PinyinDecoder(t9, lm, userModel = umXie).decodeCovered("943943", 8).map { it.word }).append("\n")

        println(sb)
        File("build/t9_report.txt").apply { parentFile?.mkdirs(); writeText(sb.toString()) }
    }

    private val flipSet = listOf(
        Triple("非常", "xiexie", "谢谢"), Triple("不用", "xiexie", "谢谢"),
        Triple("真的", "xiexie", "谢谢"), Triple("说声", "xiexie", "谢谢"),
        Triple("两个", "gege", "哥哥"), Triple("我", "gege", "哥哥"),
        Triple("我们", "yiqi", "一起"), Triple("有意思", "yiqi", "一起"),
    )
    private val controlSet = listOf(
        Triple("我喜欢", "xiexie", "这些"), Triple("看看", "xiexie", "这些"),
        Triple("买", "xiexie", "这些"), Triple("送你", "xiexie", "这些"),
    )

    @Test
    fun reportContextDisambiguation() {
        assumeTrue("assets present", t9File.exists() && lmFile.exists())
        val t9 = BinaryDict.fromFile(t9File)
        val lm = CharBigramLM.fromFile(lmFile)
        val oct = if (gramFile.exists()) OctagramReader.fromFile(gramFile) else null

        val sb = StringBuilder("③ context-aware T9 — contextWeight sweep (octagram=${oct != null})\n")
        sb.append("flip set=${flipSet.size} (context-correct word), control set=${controlSet.size} (freq word correct)\n\n")
        val plain = PinyinDecoder(t9, lm, octagram = oct)
        val flipBefore = flipSet.count { (_, py, e) -> plain.decodeCovered(toT9(py), 30).firstOrNull()?.word == e }
        sb.append("③前(无上下文): flip ${flipBefore}/${flipSet.size}\n")
        sb.append(String.format("%-6s %-18s %-18s%n", "ctxW", "flip(↑good)", "control(keep good)"))
        for (w in listOf(1.0, 2.0, 3.0, 4.0)) {
            val d = PinyinDecoder(t9, lm, octagram = oct, contextWeight = w)
            val f = flipSet.count { (c, py, e) -> d.decodeCovered(toT9(py), 30, context = c).firstOrNull()?.word == e }
            val k = controlSet.count { (c, py, e) -> d.decodeCovered(toT9(py), 30, context = c).firstOrNull()?.word == e }
            sb.append(String.format("%-6.1f %-18s %-18s%n", w,
                "${f}/${flipSet.size} (${"%.0f".format(100.0 * f / flipSet.size)}%)",
                "${k}/${controlSet.size} (${"%.0f".format(100.0 * k / controlSet.size)}%)"))
        }
        sb.append("\n--- detail @ contextWeight=2.0 ---\n")
        val dd = PinyinDecoder(t9, lm, octagram = oct, contextWeight = 2.0)
        for ((c, py, e) in flipSet + controlSet) {
            val code = toT9(py)
            val before = plain.decodeCovered(code, 30).firstOrNull()?.word
            val after = dd.decodeCovered(code, 30, context = c).firstOrNull()?.word
            sb.append("  「$c」+$code 前:$before 后:$after (exp $e)${if (after == e && before != e) " ✦flip" else if (after != e) " ✗" else ""}\n")
        }
        println(sb)
        File("build/t9_context_report.txt").apply { parentFile?.mkdirs(); writeText(sb.toString()) }
    }

    @Test
    fun probeContextCharBigramOnly() {
        assumeTrue("assets present", t9File.exists() && lmFile.exists())
        val t9 = BinaryDict.fromFile(t9File)
        val lm = CharBigramLM.fromFile(lmFile)
        val d = PinyinDecoder(t9, lm)
        val cases = listOf(
            "大" to ("gege" to "哥哥"), "我" to ("gege" to "哥哥"), "两个" to ("gege" to "哥哥"),
            "表" to ("gege" to "哥哥"), "亲" to ("gege" to "哥哥"),
            "非常" to ("xiexie" to "谢谢"), "不用" to ("xiexie" to "谢谢"), "说" to ("xiexie" to "谢谢"),
            "一" to ("qishi" to "其实"), "看" to ("dianshi" to "电视"), "我们" to ("yiqi" to "一起"),
        )
        val sb = StringBuilder("char-bigram-only context (no octagram) — does it flip #1?\n")
        var flips = 0
        for ((ctx, pe) in cases) {
            val (py, exp) = pe; val code = toT9(py)
            val before = d.decodeCovered(code, 30).firstOrNull()?.word
            val after = d.decodeCovered(code, 30, context = ctx).firstOrNull()?.word
            if (after == exp && before != exp) flips++
            sb.append("  「$ctx」+$code 前:$before 后:$after (want $exp)${if (after == exp && before != exp) " ✦" else ""}\n")
        }
        sb.append("char-bigram-only flips: $flips/${cases.size}\n")
        println(sb)
        File("build/t9_context_nooct.txt").apply { parentFile?.mkdirs(); writeText(sb.toString()) }
    }

    @Test
    fun probeOctagramWordScores() {
        assumeTrue("t9 + octagram present", t9File.exists() && gramFile.exists())
        val t9 = BinaryDict.fromFile(t9File)
        val oct = OctagramReader.fromFile(gramFile)
        val sb = StringBuilder("octagram word-level rawScore probe (does it separate same-code words?)\n")
        for ((code, label) in listOf("943943" to "xiexie", "4343" to "gege", "9474" to "yiqi")) {
            sb.append("code $code ($label):\n")
            for (wf in t9.exact(code).sortedByDescending { it.freq }.take(6)) {
                sb.append(String.format("  %-6s freq=%-9d oct=%s%n", wf.word, wf.freq, oct.rawScore(wf.word)))
            }
        }
        println(sb)
        File("build/oct_probe.txt").apply { parentFile?.mkdirs(); writeText(sb.toString()) }
    }
}
