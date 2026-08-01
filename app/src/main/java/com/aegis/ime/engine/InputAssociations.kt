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

package com.aegis.ime.engine

import com.aegis.ime.decoder.CANDIDATE_PAGE_SIZE
import com.aegis.ime.decoder.CandidatePage
import com.aegis.ime.decoder.ListCandidatePageSource
import com.aegis.ime.decoder.firstCandidatePage

object InputAssociations {

    private val legacy: List<Pair<String, List<String>>> = listOf(
        "haode" to listOf("👌"),
        "hao" to listOf("👍"),
        "zan" to listOf("👍"),
        "bang" to listOf("👍"),
        "guzhang" to listOf("👏"),
        "zaijian" to listOf("👋"),
        "baibai" to listOf("👋"),
        "xiexie" to listOf("🙏"),
        "xie" to listOf("🙏"),
        "qiu" to listOf("🙏"),
        "haha" to listOf("😂"),
        "xiao" to listOf("😄", "😂"),
        "kaixin" to listOf("😄"),
        "ku" to listOf("😭"),
        "shangxin" to listOf("😢"),
        "nu" to listOf("😡"),
        "shengqi" to listOf("😡"),
        "ai" to listOf("❤️"),
        "aini" to listOf("❤️"),
        "xin" to listOf("❤️"),
        "shuijiao" to listOf("😴"),
        "huo" to listOf("🔥"),
        "xing" to listOf("⭐"),
        "yueliang" to listOf("🌙"),
        "taiyang" to listOf("☀️"),
        "yu" to listOf("☔"),
        "xue" to listOf("❄️"),
        "hua" to listOf("🌸"),
        "liwu" to listOf("🎁"),
        "dangao" to listOf("🎂"),
        "shengri" to listOf("🎂", "🎉"),
        "qingzhu" to listOf("🎉"),
        "yinyue" to listOf("🎵"),
        "qian" to listOf("💰"),
        "diannao" to listOf("💻"),
        "shouji" to listOf("📱"),
        "jia" to listOf("+"),
        "jian" to listOf("−"),
        "cheng" to listOf("×"),
        "chu" to listOf("÷"),
        "dengyu" to listOf("="),
        "deng" to listOf("="),
        "baifen" to listOf("%"),
        "baifenzhi" to listOf("%"),
        "du" to listOf("°"),
        "renminbi" to listOf("￥"),
        "meiyuan" to listOf("\$"),
        "ouyuan" to listOf("€"),
    )

    private val table: Map<String, List<String>> by lazy {
        val m = LinkedHashMap<String, MutableList<String>>()
        fun add(key: String, glyphs: List<String>) {
            val list = m.getOrPut(key) { mutableListOf() }
            for (g in glyphs) if (g !in list) list.add(g)
        }
        for ((key, glyphs) in legacy) add(key, glyphs)
        for (row in SymbolAssociations.rows()) for (key in row.keyList) add(key, row.glyphList)
        for (row in EmojiAssociations.rows()) for (key in row.keyList) add(key, listOf(row.emoji))
        m.mapValuesTo(LinkedHashMap(m.size * 2)) { (_, glyphs) -> glyphs.toList() }
    }

    private fun normalize(pinyin: String): String = pinyin.replace("'", "").lowercase()

    fun lookup(pinyin: String): List<String> {
        if (pinyin.isEmpty()) return emptyList()
        return table[normalize(pinyin)].orEmpty()
    }

    fun lookupPage(
        pinyin: String,
        inputEpoch: Long,
        pageSize: Int = CANDIDATE_PAGE_SIZE,
    ): CandidatePage<String> = firstCandidatePage(
        ListCandidatePageSource(lookup(pinyin)),
        inputEpoch,
        pageSize,
    )

    internal fun entriesForTest(): Map<String, List<String>> = table
}
