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

/**
 * Reverse T9: turn a 9-key digit buffer back into a readable pinyin string for the preedit, and
 * enumerate the syllable readings of its first segment for the left column (issue #1/#2/#3).
 *
 * The T9 binary dict is keyed by digit strings (the pinyin's phone-keypad encoding) with the actual
 * pinyin reading discarded, so the digit buffer alone (e.g. "6433") is meaningless to show. Here we
 * segment the digits back into known toneless syllables (preferring common ones) so the user sees
 * "ni'de" instead of "6433". Pure logic — no Android deps, unit-tested.
 */
object T9Pinyin {

    /** a/b/c -> 2 ... w/x/y/z -> 9 (mirrors tools `Pinyin.toT9`). */
    private val letterToDigit: Map<Char, Char> = buildMap {
        "abc".forEach { put(it, '2') }; "def".forEach { put(it, '3') }
        "ghi".forEach { put(it, '4') }; "jkl".forEach { put(it, '5') }
        "mno".forEach { put(it, '6') }; "pqrs".forEach { put(it, '7') }
        "tuv".forEach { put(it, '8') }; "wxyz".forEach { put(it, '9') }
    }

    fun toT9(letters: String): String {
        val sb = StringBuilder(letters.length)
        for (c in letters) sb.append(letterToDigit[c] ?: c)
        return sb.toString()
    }

    /** Representative pinyin initial per T9 key — shows an in-progress (not-yet-segmentable) syllable
     *  as a letter instead of the raw 2-9 digit (★N). */
    private val DIGIT_INITIAL: Map<Char, Char> = mapOf(
        '2' to 'a', '3' to 'd', '4' to 'g', '5' to 'j', '6' to 'm', '7' to 'p', '8' to 't', '9' to 'w',
    )

    /** Letters printed on each T9 key — the 首键字母 fallback options for the left column. */
    private val KEY_LETTERS: Map<Char, String> = mapOf(
        '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
    )

    /** Canonical toneless Mandarin syllables (ported from tools `Pinyin.canonicalSyllables`). */
    private val SYLLABLES: Set<String> = """
        a o e ai ei ao ou an en ang eng er
        yi ya yo ye yao you yan yin yang ying yong
        wu wa wo wai wei wan wen wang weng
        yu yue yuan yun
        ba bo bai bei bao ban ben bang beng bi bie biao bian bin bing bu
        pa po pai pei pao pou pan pen pang peng pi pie piao pian pin ping pu
        ma mo me mai mei mao mou man men mang meng mi mie miao miu mian min ming mu
        fa fo fei fou fan fen fang feng fu
        da de dai dei dao dou dan den dang deng dong di dia die diao diu dian ding du duo dui duan dun
        ta te tai tao tou tan tang teng tong ti tie tiao tian ting tu tuo tui tuan tun
        na ne nai nei nao nou nan nen nang neng nong ni nie niao niu nian nin niang ning nu nuo nuan nun nv nve
        la lo le lai lei lao lou lan lang leng long li lia lie liao liu lian lin liang ling lu luo luan lun lv lve
        ga ge gai gei gao gou gan gen gang geng gong gu gua guo guai gui guan gun guang
        ka ke kai kei kao kou kan ken kang keng kong ku kua kuo kuai kui kuan kun kuang
        ha he hai hei hao hou han hen hang heng hong hu hua huo huai hui huan hun huang
        ji jia jie jiao jiu jian jin jiang jing jiong ju jue juan jun
        qi qia qie qiao qiu qian qin qiang qing qiong qu que quan qun
        xi xia xie xiao xiu xian xin xiang xing xiong xu xue xuan xun
        zha zhe zhi zhai zhei zhao zhou zhan zhen zhang zheng zhong zhu zhua zhuo zhuai zhui zhuan zhun zhuang
        cha che chi chai chao chou chan chen chang cheng chong chu chua chuo chuai chui chuan chun chuang
        sha she shi shai shei shao shou shan shen shang sheng shu shua shuo shuai shui shuan shun shuang
        re ri rao rou ran ren rang reng rong ru rua ruo rui ruan run
        za ze zi zai zei zao zou zan zen zang zeng zong zu zuo zui zuan zun
        ca ce ci cai cao cou can cen cang ceng cong cu cuo cui cuan cun
        sa se si sai sao sou san sen sang seng song su suo sui suan sun
    """.trim().split(Regex("\\s+")).toSet()

    /** Frequency-ish ordering so an ambiguous digit group resolves to its common reading first. */
    private val freqRank: Map<String, Int> = listOf(
        "de", "shi", "yi", "bu", "le", "zai", "wo", "ni", "ta", "men", "zhe", "ge", "shang",
        "you", "he", "zhong", "da", "wei", "dao", "shuo", "guo", "jiu", "hai", "er", "na", "hao",
        "hen", "xia", "lai", "qu", "kan", "xiang", "hui", "neng", "dui", "jia", "xue", "gong",
        "fang", "dian", "yong", "fa", "xin", "zi", "ren", "sheng", "cheng", "ming", "mei", "hua",
        "dong", "xi", "ye", "yao", "qing", "wen", "ke", "zhi", "chu", "fen", "jian", "shou",
        "tian", "di", "gao", "xiao", "zhu", "kai", "dou", "wang", "yu", "li", "shen", "zui",
        "yue", "yan", "mian", "jin", "xian", "qian", "zhen", "san", "wan", "bian", "guan",
    ).withIndex().associate { (i, s) -> s to i }

    private const val DEFAULT_RANK = 1000
    private const val LEN_BONUS = 480 // ★T/xuan: per-letter bonus so full syllables (xuan/yuan…) beat 2-letter prefixes
    private const val SYLLABLE_PENALTY = 50.0 // bias toward fewer, longer syllables
    private val maxDigits: Int = SYLLABLES.maxOf { toT9(it).length }

    /** digit group -> syllables encoding to it, e.g. "64" -> [ni, mi, ...]. */
    private val byDigits: Map<String, List<String>> = run {
        val m = HashMap<String, MutableList<String>>()
        for (s in SYLLABLES) m.getOrPut(toT9(s)) { ArrayList() }.add(s)
        m.mapValues { (_, v) -> v.sortedBy { freqRank[it] ?: DEFAULT_RANK } }
    }

    private fun rankOf(s: String) = freqRank[s] ?: DEFAULT_RANK

    /** Lowest-cost segmentation of [digits] into known syllables, or null if none fits exactly. */
    fun segment(digits: String): List<String>? {
        val n = digits.length
        if (n == 0 || digits.any { it < '2' || it > '9' }) return null
        val cost = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        val pick = arrayOfNulls<String>(n + 1)
        val back = IntArray(n + 1) { -1 }
        cost[0] = 0.0
        for (i in 1..n) {
            val lo = maxOf(0, i - maxDigits)
            for (j in lo until i) {
                if (cost[j] == Double.POSITIVE_INFINITY) continue
                val best = byDigits[digits.substring(j, i)]?.firstOrNull() ?: continue
                val c = cost[j] + rankOf(best) + SYLLABLE_PENALTY
                if (c < cost[i]) { cost[i] = c; pick[i] = best; back[i] = j }
            }
        }
        if (cost[n] == Double.POSITIVE_INFINITY) return null
        val out = ArrayList<String>()
        var i = n
        while (i > 0) { out.add(pick[i]!!); i = back[i] }
        out.reverse()
        return out
    }

    /**
     * Pinyin preedit for a digit buffer: best segmentation joined by apostrophes ("ni'de").
     * Falls back to a partial best-effort, then to the raw digits, so something pinyin-like always
     * shows while the user is still mid-syllable.
     */
    fun preedit(digits: String): String {
        if (digits.isEmpty()) return ""
        segment(digits)?.let { return it.joinToString("'") }
        // Partial: greedily eat the longest leading syllable, show the rest verbatim.
        val sb = StringBuilder()
        var i = 0
        while (i < digits.length) {
            var matched = false
            val hi = minOf(digits.length, i + maxDigits)
            for (k in hi downTo i + 1) {
                val g = byDigits[digits.substring(i, k)]?.firstOrNull()
                if (g != null) {
                    if (sb.isNotEmpty()) sb.append('\'')
                    sb.append(g); i = k; matched = true; break
                }
            }
            if (!matched) {
                // ★N: never surface the raw 2-9 digit; show the key's representative initial so the
                // preedit stays pinyin-like ("ni'p") instead of "pinyin+digits" ("ni7").
                if (sb.isNotEmpty()) sb.append('\'')
                sb.append(DIGIT_INITIAL[digits[i]] ?: digits[i]); i++
            }
        }
        return sb.toString()
    }

    /**
     * Preedit with explicit forced syllable boundaries [cuts] (positions in [digits]) rendered as the
     * 隔音符 ' — including a TRAILING ' when a boundary sits at the very end, so pressing 分词 immediately
     * shows the split (e.g. "ni'") even before the next syllable is typed.
     */
    fun preedit(digits: String, cuts: Set<Int>): String {
        if (cuts.isEmpty()) return preedit(digits)
        val sb = StringBuilder()
        var prev = 0
        for (c in cuts.filter { it in 1..digits.length }.toSortedSet()) {
            if (c > prev) {
                if (sb.isNotEmpty()) sb.append('\'')
                sb.append(preedit(digits.substring(prev, c)))
            }
            prev = c
        }
        when {
            prev < digits.length -> { if (sb.isNotEmpty()) sb.append('\''); sb.append(preedit(digits.substring(prev))) }
            digits.isNotEmpty() -> sb.append('\'') // boundary at the very end → trailing 隔音符
        }
        return sb.toString()
    }

    /** The longest prefix of [digits] that fully segments into known syllables, or "" if none (★N). */
    fun longestDecodablePrefix(digits: String): String {
        for (p in digits.length downTo 1) {
            if (segment(digits.substring(0, p)) != null) return digits.substring(0, p)
        }
        return ""
    }

    /** A locked-first-syllable reading of a digit buffer: [display] is apostrophe-joined, [letters] for decoding. */
    data class Reading(val display: String, val letters: String)

    /**
     * Lock the first syllable to [firstReading] (a value from [firstSyllableOptions]) and keep the rest
     * of [digits] segmented normally, so picking a reading re-ranks the WHOLE buffer instead of
     * collapsing to a single syllable (issue #12b). e.g. ("6433","ni") -> display "ni'de", letters "nide".
     */
    fun lockFirstReading(digits: String, firstReading: String): Reading? {
        val fd = toT9(firstReading)
        if (!digits.startsWith(fd)) return null
        val rest = digits.substring(fd.length)
        if (rest.isEmpty()) return Reading(firstReading, firstReading)
        val restDisplay = preedit(rest)
        return Reading("$firstReading'$restDisplay", firstReading + restDisplay.replace("'", ""))
    }

    /**
     * The 9-key left-column readings for the active syllable. REAL options only,
     * deterministic, with NO padding: the canonical syllable readings that can begin [digits] (ranked by
     * [firstSyllableOptions]), followed by the individual letters of the FIRST digit key (首键字母) not
     * already present — letters that are themselves syllables (a/o/e…) ordered ahead of the rest, so e.g.
     * "23"→[ce, a, b, c] and "64…"→[ni, mi, o, m, n]. A bare ambiguous key with no
     * syllable still yields its letters ("9"→[w, x, y, z]), never an empty slot. The caller renders
     * EXACTLY this list (the column height follows the count) — it must never inject blanks or punctuation.
     */
    fun leftColumnReadings(digits: String, limit: Int): List<String> {
        if (digits.isEmpty() || digits[0] < '2' || digits[0] > '9') return emptyList()
        val out = LinkedHashSet<String>()
        out.addAll(firstSyllableOptions(digits, limit))
        KEY_LETTERS[digits[0]]?.toList()
            ?.sortedByDescending { it.toString() in SYLLABLES } // vowel/syllable letters first, keypad order kept (stable)
            ?.forEach { out.add(it.toString()) }
        return out.toList().take(limit)
    }

    /** Distinct syllable readings that can begin a segmentation of [digits] (for the left column). */
    fun firstSyllableOptions(digits: String, limit: Int): List<String> {
        val n = digits.length
        if (n == 0 || digits.any { it < '2' || it > '9' }) return emptyList()
        val reachable = BooleanArray(n + 1)
        reachable[n] = true
        for (j in n - 1 downTo 0) {
            val hi = minOf(n, j + maxDigits)
            for (k in j + 1..hi) {
                if (byDigits.containsKey(digits.substring(j, k)) && reachable[k]) { reachable[j] = true; break }
            }
        }
        val out = LinkedHashSet<String>()
        val requireReach = reachable[0]
        val hi = minOf(n, maxDigits)
        for (k in 1..hi) {
            if (requireReach && !reachable[k]) continue
            byDigits[digits.substring(0, k)]?.let { out.addAll(it) }
        }
        // ★T/xuan: prefer LONGER syllables so a full single syllable (xuan/yuan/xian…) surfaces instead of
        // only its 2-letter prefixes (yu/wu/xu/zu). Tuned to outrank same-prefix common short syllables
        // without burying a genuinely top-ranked shorter reading.
        return out.toList().sortedBy { rankOf(it) - LEN_BONUS * it.length }.take(limit)
    }
}
