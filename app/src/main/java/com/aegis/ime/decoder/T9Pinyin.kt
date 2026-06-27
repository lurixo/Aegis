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

object T9Pinyin {

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

    private val DIGIT_INITIAL: Map<Char, Char> = mapOf(
        '2' to 'a', '3' to 'd', '4' to 'g', '5' to 'j', '6' to 'm', '7' to 'p', '8' to 't', '9' to 'w',
    )

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
    private const val LEN_BONUS = 480
    private const val SYLLABLE_PENALTY = 50.0
    private val maxDigits: Int = SYLLABLES.maxOf { toT9(it).length }

    private val byDigits: Map<String, List<String>> = run {
        val m = HashMap<String, MutableList<String>>()
        for (s in SYLLABLES) m.getOrPut(toT9(s)) { ArrayList() }.add(s)
        m.mapValues { (_, v) -> v.sortedBy { freqRank[it] ?: DEFAULT_RANK } }
    }

    private fun rankOf(s: String) = freqRank[s] ?: DEFAULT_RANK

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

    fun preedit(digits: String): String {
        if (digits.isEmpty()) return ""
        segment(digits)?.let { return it.joinToString("'") }
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
                if (sb.isNotEmpty()) sb.append('\'')
                sb.append(DIGIT_INITIAL[digits[i]] ?: digits[i]); i++
            }
        }
        return sb.toString()
    }

    fun longestDecodablePrefix(digits: String): String {
        for (p in digits.length downTo 1) {
            if (segment(digits.substring(0, p)) != null) return digits.substring(0, p)
        }
        return ""
    }

    data class Reading(val display: String, val letters: String)

    fun lockFirstReading(digits: String, firstReading: String): Reading? {
        val fd = toT9(firstReading)
        if (!digits.startsWith(fd)) return null
        val rest = digits.substring(fd.length)
        if (rest.isEmpty()) return Reading(firstReading, firstReading)
        val restDisplay = preedit(rest)
        return Reading("$firstReading'$restDisplay", firstReading + restDisplay.replace("'", ""))
    }

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
        return out.toList().sortedBy { rankOf(it) - LEN_BONUS * it.length }.take(limit)
    }
}
