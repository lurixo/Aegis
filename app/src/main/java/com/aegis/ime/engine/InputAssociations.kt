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

/**
  * Chinese IME behavior note.
 * offered as candidates (e.g. haode → 👌, jia → +, sheshidu → ℃). Deliberately keyed on the WHOLE pinyin
 * (exact, no prefix) so it never fires on a half-typed syllable and never crowds normal candidates — the
 * controller injects at most [MAX_PER_QUERY] of these just after the top candidate.
 *
 * U23 data expansion: the table is data-driven — the original hand-picked entries ([legacy], kept verbatim
 * and merged FIRST so their glyph order is a locked regression surface) plus the full symbol table
 * ([SymbolAssociations], every nameable SymbolCatalog symbol) and the full emoji index
 * ([EmojiAssociations], every EmojiCatalog emoji with Chinese keywords). Keys are normalized with
 * [normalize]: separator-insensitive (she'shi'du ≡ sheshidu — the 9-key path already strips separators in
 * fullLetters(), the 26-key buffer never contains them, and lookup strips defensively) and lowercased.
 */
object InputAssociations {

    /**
     * Max associated glyphs surfaced for one buffer, so normal candidates are never pushed out of view.
     * Raised 2 → 3 with the full tables: many families now legitimately carry 2-3 glyphs for one key
     * (full-width mark + its half-width twin, greek lower + upper case, same-name emoji like 🐮/🐂), and the
     * data contract caps every glyph list at 3 so each stays reachable. "Never first" is structural (the
     * controller splices AFTER the top candidate) and unaffected by the cap; pre-existing keys keep their
     * original glyphs in the original order (legacy merges first), at most gaining a third entry.
     */
    const val MAX_PER_QUERY = 3

    /** The original hand-picked table, verbatim (regression-locked). First entries rank first. */
    private val legacy: List<Pair<String, List<String>>> = listOf(
        // — reactions / gestures —
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
        // — faces / moods —
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
        // — things / nature —
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
        // — math / currency symbols (jia → +, …) —
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

    // Keyed by normalized pinyin. Merge order defines glyph rank per key: legacy first (regression),
    // then the symbol table, then the emoji index; duplicates collapse onto their first (highest) slot.
    // The data objects expose rows as FUNCTIONS and the values are compacted with toList(), so after this
    // one-time merge the row objects (with their review-aid names) and the builder slack are all GC'd —
    // the resident footprint is just this map. Built lazily on the first composing keystroke.
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

    /** Lowercase and drop syllable separators, so she'shi'du and sheshidu are the same key.
     *  (Both stdlib calls return `this` when nothing changes, so the common case stays allocation-free.) */
    private fun normalize(pinyin: String): String = pinyin.replace("'", "").lowercase()

    /** Associated glyphs for the exact full [pinyin] (normalized), capped; empty when none. */
    fun lookup(pinyin: String): List<String> {
        if (pinyin.isEmpty()) return emptyList()
        return table[normalize(pinyin)].orEmpty().take(MAX_PER_QUERY)
    }

    /** Test hook: the full merged table (uncapped) — the no-sampling data audits iterate every key. */
    internal fun entriesForTest(): Map<String, List<String>> = table
}
