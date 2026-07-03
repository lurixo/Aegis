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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LockBoundaryTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val jianpinFile = File("src/main/assets/aegis_jianpin.bin")

    private fun decoder(): PinyinDecoder {
        assumeTrue("assets present", dictFile.exists() && lmFile.exists() && jianpinFile.exists())
        return PinyinDecoder(
            BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile),
            initialsDict = BinaryDict.fromFile(jianpinFile),
        )
    }

    private fun words(cands: List<Cand>) = cands.map { it.word }


    @Test fun lockFangAn_filtersFanGanWord_keepsFangAnWord() {
        val d = decoder()
        val locked = words(d.decodeCoveredAtomic("fangan", 30, setOf(4)))
        assertTrue("锁 fang+an 不得出现跨界词 反感 (fan+gan): $locked", "反感" !in locked)
        assertTrue("锁 fang+an 保留对齐词 方案 (fang+an): $locked", "方案" in locked)
    }

    @Test fun lockFanGan_filtersFangAnWord_keepsFanGanWord() {
        val d = decoder()
        val locked = words(d.decodeCoveredAtomic("fangan", 30, setOf(3)))
        assertTrue("锁 fan+gan 不得出现跨界词 方案 (fang+an): $locked", "方案" !in locked)
        assertTrue("锁 fan+gan 保留对齐词 反感 (fan+gan): $locked", "反感" in locked)
    }

    @Test fun lockMinGan_filtersMingAnWords() {
        val d = decoder()
        val locked = words(d.decodeCoveredAtomic("mingan", 30, setOf(3)))
        assertTrue("锁 min+gan 不得出现 明暗 (ming+an): $locked", "明暗" !in locked)
        assertTrue("锁 min+gan 不得出现 命案 (ming+an): $locked", "命案" !in locked)
        val dual = words(d.decodeCoveredAtomic("mingan", 30, setOf(4)))
        assertTrue("锁 ming+an 保留 明暗/命案: $dual", "明暗" in dual || "命案" in dual)
        assertTrue("锁 ming+an 不得出现 敏感 (min+gan): $dual", "敏感" !in dual)
    }


    @Test fun freeTyping_unaffected() {
        val d = decoder()
        val free = words(d.decodeCovered("fangan", 30))
        assertTrue("free typing fangan 照常出 反感: ${free.take(8)}", "反感" in free)
        assertTrue("free typing fangan 照常出 方案", "方案" in free)
        val ming = words(d.decodeCovered("mingan", 30))
        assertTrue("free typing mingan 照常出 敏感/明暗", "敏感" in ming || "明暗" in ming)
    }


    @Test fun alignedCommonWords_surviveTheirLock() {
        val d = decoder()
        val checks = listOf(
            Triple("nihao", setOf(2), "你好"),
            Triple("xiansheng", setOf(4), "先生"),
            Triple("zhongguo", setOf(5), "中国"),
            Triple("pengyou", setOf(4), "朋友"),
            Triple("xuexiao", setOf(3), "学校"),
            Triple("chaici", setOf(4), "拆次"),
        )
        val bad = ArrayList<String>()
        for ((input, cuts, word) in checks) {
            val got = words(d.decodeCoveredAtomic(input, 30, cuts))
            if (word !in got) bad.add("$input cuts=$cuts lost $word: ${got.take(6)}")
        }
        assertTrue("对齐锁定的常用词不得丢失: $bad", bad.isEmpty())
    }

    @Test fun unprovableWords_areKept() {
        val d = decoder()
        val got = words(d.decodeCoveredAtomic("xingxing", 30, setOf(4)))
        assertTrue("反查不出切分的词(猩猩)疑罪从无保留: ${got.take(8)}", "猩猩" in got)
    }


    @Test fun fullDictAndGram_lockBoundary_targetedCheck() {
        val dir = System.getenv("AEGIS_FULLDICT_DIR")
        assumeTrue("full-dict check only when AEGIS_FULLDICT_DIR is set", !dir.isNullOrEmpty())
        val fDict = File(dir!!, "aegis_dict.bin")
        val fJp = File(dir, "aegis_jianpin.bin")
        assumeTrue("full dict bins present", fDict.exists() && fJp.exists() && lmFile.exists())
        val gramPath = System.getenv("AEGIS_GRAM")
        val gram = if (!gramPath.isNullOrEmpty() && File(gramPath).exists())
            OctagramReader.fromFile(File(gramPath)) else null
        val d = PinyinDecoder(
            BinaryDict.fromFile(fDict), CharBigramLM.fromFile(lmFile),
            initialsDict = BinaryDict.fromFile(fJp), octagram = gram,
        )
        val bad = ArrayList<String>()
        val a = words(d.decodeCoveredAtomic("fangan", 30, setOf(4)))
        if ("反感" in a) bad.add("full: 锁 fang+an 仍出 反感")
        if ("方案" !in a) bad.add("full: 锁 fang+an 丢 方案")
        val b = words(d.decodeCoveredAtomic("mingan", 30, setOf(3)))
        if ("明暗" in b || "命案" in b) bad.add("full: 锁 min+gan 仍出 明暗/命案")
        val c = words(d.decodeCovered("fangan", 30))
        if ("反感" !in c) bad.add("full: free typing 反感 丢失")
        for ((input, cuts, word) in listOf(Triple("nihao", setOf(2), "你好"), Triple("xiansheng", setOf(4), "先生"))) {
            if (word !in words(d.decodeCoveredAtomic(input, 30, cuts))) bad.add("full: $input lost $word")
        }
        assertTrue("full-config lock-boundary check failed: $bad", bad.isEmpty())
    }
}
