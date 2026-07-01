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
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * debug.18 engine-fix test fixture: builds a tiny in-memory [BinaryDict] in the exact AEGD binary format
 * (mirrors tools/DictBuilder.writeBinary), so the engine fixes can be proven against the REAL decode path
 * with a dictionary that — unlike the committed freq≥400 seed asset — holds BOTH the target words and the
 * freq=1 SUPPLEMENTARY-PLANE (U+20000+) rare chars that reproduce the 生僻字泛滥 / 丢字 hard bugs.
 *
 * A supplementary-plane char has codePointCount(0,len)==1 but String.length==2 (surrogate pair) — exactly the
 * case the old `word.length == 1` "single char" test misclassified as a multi-char word.
 */
object EngineFixture {

    /** One dictionary row: an exact pinyin key, the word, and its corpus frequency. */
    data class Row(val key: String, val word: String, val freq: Int)

    /** A supplementary-plane CJK Extension-B char (U+20000 + [i]); length==2, codePointCount==1. */
    fun supplementary(i: Int): String = String(Character.toChars(0x20000 + i))

    /**
     * The committed debug.18 fixture dict. Common homophones carry high freqs; each of a few syllables also
     * gets a run of freq=1 supplementary-plane rares (the flood/loss triggers). Target words: 词库(ciku),
     * 九键(jiujian), 实现(shixian); plus 西安(xian) — a multi-char word keyed by a SINGLE syllable, which the
     * boundary-aligned locked decode must DROP (rule ②). Frequencies are chosen so the intended sentences win.
     */
    fun dict(): BinaryDict {
        val rows = ArrayList<Row>()
        // --- ci: common homophones (freq-desc) then 16 supplementary rares (freq=1) ---
        val ci = listOf("次" to 900, "此" to 850, "词" to 800, "刺" to 700, "辞" to 600, "磁" to 500,
            "慈" to 450, "茨" to 400, "瓷" to 350, "赐" to 300, "雌" to 250, "祠" to 200, "疵" to 150, "伺" to 100)
        ci.forEach { rows.add(Row("ci", it.first, it.second)) }
        for (i in 0 until 16) rows.add(Row("ci", supplementary(i), 1)) // 生僻字泛滥 trigger (16 > PREFIX_PER_LEN)
        // --- ku ---
        listOf("库" to 900, "苦" to 850, "哭" to 800, "酷" to 700, "裤" to 600, "窟" to 300)
            .forEach { rows.add(Row("ku", it.first, it.second)) }
        for (i in 0 until 4) rows.add(Row("ku", supplementary(100 + i), 1))
        // --- diu (essentially one homophone; no supplementary — too sparse a syllable to push it past rank 10) ---
        rows.add(Row("diu", "丢", 900))
        // --- zi ---
        listOf("字" to 900, "子" to 880, "自" to 860, "紫" to 700, "资" to 680, "仔" to 500, "籽" to 300)
            .forEach { rows.add(Row("zi", it.first, it.second)) }
        for (i in 0 until 3) rows.add(Row("zi", supplementary(210 + i), 1))
        // --- bu (10 common so its 3 supplementary rares land past rank 10 even with 不实现/不是 ahead) ---
        listOf("不" to 950, "部" to 900, "布" to 850, "步" to 800, "补" to 700, "捕" to 600,
            "卜" to 500, "哺" to 450, "埠" to 400, "簿" to 300).forEach { rows.add(Row("bu", it.first, it.second)) }
        for (i in 0 until 3) rows.add(Row("bu", supplementary(220 + i), 1))
        // --- shi (实 high so 不实现 is a strong beam path) ---
        listOf("是" to 950, "时" to 920, "实" to 900, "事" to 860, "市" to 840, "十" to 820, "始" to 700,
            "试" to 680, "视" to 660).forEach { rows.add(Row("shi", it.first, it.second)) }
        for (i in 0 until 3) rows.add(Row("shi", supplementary(230 + i), 1))
        // --- xian (现 high) + 西安 (multi-char word keyed by the SINGLE syllable xian → must be DROPPED) ---
        listOf("现" to 900, "县" to 850, "限" to 800, "先" to 780, "显" to 760, "鲜" to 700, "险" to 680, "嫌" to 500)
            .forEach { rows.add(Row("xian", it.first, it.second)) }
        for (i in 0 until 3) rows.add(Row("xian", supplementary(240 + i), 1))
        rows.add(Row("xian", "西安", 5000)) // 2-char word under one syllable key — the rule ② drop target
        // 西(xi) + 安(an): so a SINGLE locked xian can be internally re-split into xi|an -> 西安 by the old
        // "forbid only cross-cut" decode. The boundary-aligned locked decode must forbid that interior split.
        rows.add(Row("xi", "西", 850)); rows.add(Row("an", "安", 900))
        // --- xiang: regression fixture for a single locked reading. Shorter prefixes (xian/xia/xi) must not
        // leak into the selected xiang grid, while the common xiang homophones stay at the front.
        listOf("向" to 980, "想" to 930, "相" to 900, "像" to 860, "香" to 800, "响" to 760, "享" to 700)
            .forEach { rows.add(Row("xiang", it.first, it.second)) }
        listOf("下" to 900, "夏" to 760, "霞" to 700).forEach { rows.add(Row("xia", it.first, it.second)) }
        // --- jiu (no supplementary — kept sparse; jiu'jian ① is proven via the leading 九键 + common singles) ---
        listOf("九" to 900, "就" to 880, "久" to 860, "酒" to 840, "旧" to 800, "救" to 700)
            .forEach { rows.add(Row("jiu", it.first, it.second)) }
        // --- jian ---
        listOf("键" to 900, "见" to 880, "件" to 860, "间" to 840, "简" to 800, "减" to 700, "建" to 680)
            .forEach { rows.add(Row("jian", it.first, it.second)) }
        for (i in 0 until 3) rows.add(Row("jian", supplementary(260 + i), 1))
        // --- target multi-syllable WORDS (span ≥2 locked syllables) ---
        rows.add(Row("ciku", "词库", 850))
        rows.add(Row("jiujian", "九键", 850))
        rows.add(Row("shixian", "实现", 880))
        rows.add(Row("bushi", "不是", 800)) // a leading 2-syllable word inside bu'shi'xian
        return build(rows)
    }

    /** Serialise [rows] to the AEGD binary format and mmap it back as a [BinaryDict]. */
    fun build(rows: List<Row>): BinaryDict {
        // Group by key; within a key keep freq-descending; sort keys byte-lexicographically (US-ASCII).
        val byKey = LinkedHashMap<String, MutableList<Row>>()
        for (r in rows) byKey.getOrPut(r.key) { ArrayList() }.add(r)
        val keys = byKey.keys.sortedWith(compareBy({ it }, { it })) // ascii keys → natural order == byte-lex
        val keyBlob = ByteArrayOutputStream()
        val wordBlob = ByteArrayOutputStream()
        val keyArr = ArrayList<Int>()   // (keyOffset, keyLen, entryStart)
        val entryArr = ArrayList<Int>() // (wordOffset, wordLen, freq)
        var numEntries = 0
        var totalFreq = 0L
        val seenPerKey = HashSet<String>()
        for (key in keys) {
            val kb = key.toByteArray(Charsets.US_ASCII)
            keyArr.add(keyBlob.size()); keyArr.add(kb.size); keyArr.add(numEntries)
            keyBlob.write(kb)
            seenPerKey.clear()
            for (row in byKey.getValue(key).sortedByDescending { it.freq }) {
                if (!seenPerKey.add(row.word)) continue
                val wb = row.word.toByteArray(Charsets.UTF_8)
                entryArr.add(wordBlob.size()); entryArr.add(wb.size); entryArr.add(row.freq)
                wordBlob.write(wb)
                numEntries++
                totalFreq += row.freq.toLong()
            }
        }
        val keyBytes = keyBlob.toByteArray()
        val wordBytes = wordBlob.toByteArray()
        val out = ByteArrayOutputStream()
        fun le(v: Int) { out.write(v); out.write(v ushr 8); out.write(v ushr 16); out.write(v ushr 24) }
        fun leLong(v: Long) { for (s in 0 until 64 step 8) out.write((v ushr s).toInt() and 0xFF) }
        out.write(byteArrayOf('A'.code.toByte(), 'E'.code.toByte(), 'G'.code.toByte(), 'D'.code.toByte()))
        le(2)                      // version
        le(keys.size)              // numKeys
        le(numEntries)
        leLong(totalFreq)
        le(keyBytes.size); out.write(keyBytes)
        le(wordBytes.size); out.write(wordBytes)
        for (v in keyArr) le(v)
        for (v in entryArr) le(v)

        val file = File.createTempFile("aegis_fixture", ".bin")
        file.deleteOnExit()
        file.writeBytes(out.toByteArray())
        return BinaryDict.fromFile(file)
    }
}
