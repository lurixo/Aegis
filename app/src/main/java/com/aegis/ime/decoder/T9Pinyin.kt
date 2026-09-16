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

    private val KEY_LETTERS: Map<Char, String> = mapOf(
        '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
    )

    internal val SYLLABLES: Set<String> = com.aegis.ime.dict.PinyinSyllables.ALL

    private val NASAL_CODAS: Set<String> = setOf("ng", "n", "m")

    private val freqRank: Map<String, Int> = listOf(
        "de", "shi", "yi", "bu", "le", "zai", "wo", "ni", "ng", "ta", "men", "zhe", "ge", "shang",
        "you", "he", "zhong", "da", "wei", "dao", "shuo", "guo", "jiu", "hai", "er", "na", "hao",
        "hen", "xia", "lai", "qu", "kan", "xiang", "hui", "neng", "dui", "jia", "xue", "gong",
        "fang", "dian", "yong", "fa", "xin", "zi", "ren", "sheng", "cheng", "ming", "mei", "hua",
        "dong", "xi", "ye", "yao", "qing", "wen", "ke", "zhi", "chu", "fen", "jian", "shou",
        "tian", "di", "gao", "xiao", "zhu", "kai", "dou", "wang", "yu", "li", "shen", "zui",
        "yue", "yan", "mian", "jin", "xian", "qian", "zhen", "san", "wan", "bian", "guan",
    ).withIndex().associate { (i, s) -> s to i }

    private const val NON_INITIAL_LETTERS = "iuv"

    private const val DEFAULT_RANK = 1000
    private const val FUZZY_VARIANT_CAP = 64
    private const val MAX_FUZZY_DIGITS = 40
    private const val UNKNOWN_LEN_BONUS = 240
    private const val LEN_BONUS = 480
    private const val SYLLABLE_PENALTY = 50.0
    private val maxDigits: Int = SYLLABLES.maxOf { toT9(it).length }
    private val maxLetters: Int = SYLLABLES.maxOf { it.length }

    private val byDigits: Map<String, List<String>> = run {
        val m = HashMap<String, MutableList<String>>()
        for (s in SYLLABLES) m.getOrPut(toT9(s)) { ArrayList() }.add(s)
        m.mapValues { (_, v) -> v.sortedBy { freqRank[it] ?: DEFAULT_RANK } }
    }

    private fun rankOf(s: String) = freqRank[s] ?: (DEFAULT_RANK - UNKNOWN_LEN_BONUS * s.length)

    private fun runRank(s: String): Int {
        val best = byDigits[toT9(s)]?.firstOrNull() ?: s
        return rankOf(best) - LEN_BONUS * s.length
    }

    private class PartnerTable(val enabled: Set<String>, val byDigit: Map<String, List<String>>)

    @Volatile private var partnerCache: PartnerTable? = null

    private fun partnersByDigit(enabled: Set<String>): Map<String, List<String>> {
        partnerCache?.let { if (it.enabled == enabled) return it.byDigit }
        val out = LinkedHashMap<String, MutableSet<String>>()
        for (s in com.aegis.ime.dict.Fuzzy.inputSyllables(enabled)) {
            val d = toT9(s)
            for (partner in com.aegis.ime.dict.Fuzzy.syllableVariants(s, enabled)) {
                val v = toT9(partner)
                if (v != d) out.getOrPut(d) { linkedSetOf() }.add(v)
            }
        }
        val table = out.mapValues { (_, v) -> v.toList() }
        partnerCache = PartnerTable(enabled.toSet(), table)
        return table
    }

    fun fuzzyVariants(digits: String, enabled: Set<String>, cap: Int = FUZZY_VARIANT_CAP): List<String> {
        if (cap <= 0 || enabled.isEmpty() || digits.isEmpty() || digits.length > MAX_FUZZY_DIGITS) return emptyList()
        if (digits.any { it < '2' || it > '9' }) return emptyList()
        val pairs = partnersByDigit(enabled)
        if (pairs.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        expandRuns(digits, 0, StringBuilder(), pairs, out, cap)
        out.remove(digits)
        return out.toList()
    }

    private fun expandRuns(
        digits: String,
        i: Int,
        prefix: StringBuilder,
        pairs: Map<String, List<String>>,
        out: MutableSet<String>,
        cap: Int,
    ) {
        if (out.size >= cap) return
        if (i == digits.length) {
            out.add(prefix.toString())
            return
        }
        var matched = false
        val hi = minOf(digits.length, i + maxDigits)
        for (k in hi downTo i + 1) {
            val run = digits.substring(i, k)
            if (!byDigits.containsKey(run) && !pairs.containsKey(run)) continue
            matched = true
            val keep = prefix.length
            prefix.append(run)
            expandRuns(digits, k, prefix, pairs, out, cap)
            prefix.setLength(keep)
            for (v in pairs[run].orEmpty()) {
                if (out.size >= cap) return
                prefix.append(v)
                expandRuns(digits, k, prefix, pairs, out, cap)
                prefix.setLength(keep)
            }
        }
        if (!matched) out.add(prefix.toString() + digits.substring(i))
    }

    private class Segmentation(val cost: Double, val parts: List<String>)

    private fun segmentDigits(digits: String): Segmentation? {
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
        return Segmentation(cost[n], out)
    }

    private fun segmentLetterRun(letters: String): Segmentation? {
        val n = letters.length
        if (n == 0 || letters.any { it < 'a' || it > 'z' }) return null
        val cost = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        val pick = arrayOfNulls<String>(n + 1)
        val back = IntArray(n + 1) { -1 }
        cost[0] = 0.0
        for (i in 1..n) {
            val lo = maxOf(0, i - maxLetters)
            for (j in lo until i) {
                if (cost[j] == Double.POSITIVE_INFINITY) continue
                val sub = letters.substring(j, i)
                if (sub !in SYLLABLES) continue
                if (sub in NASAL_CODAS && j > 0 &&
                    (lo until j).any { k -> cost[k] != Double.POSITIVE_INFINITY && letters.substring(k, i) in SYLLABLES }
                ) continue
                val c = cost[j] + rankOf(sub) + SYLLABLE_PENALTY
                if (c < cost[i]) { cost[i] = c; pick[i] = sub; back[i] = j }
            }
        }
        if (cost[n] == Double.POSITIVE_INFINITY) return null
        val out = ArrayList<String>()
        var i = n
        while (i > 0) { out.add(pick[i]!!); i = back[i] }
        out.reverse()
        return Segmentation(cost[n], out)
    }

    fun segment(digits: String): List<String>? = segmentDigits(digits)?.parts

    fun segmentLetters(letters: String): List<String>? = segmentLetterRun(letters)?.parts

    internal fun segmentCost(input: String): Double? =
        if (input.isNotEmpty() && input[0] in '2'..'9') segmentDigits(input)?.cost
        else segmentLetterRun(input)?.cost

    private val syllablePrefixes: Set<String> = buildSet {
        for (s in SYLLABLES) for (k in 1 until s.length) add(s.substring(0, k))
    }

    private val syllableDigitPrefixes: Set<String> = buildSet {
        for (s in SYLLABLES) {
            val d = toT9(s)
            for (k in 1 until d.length) add(d.substring(0, k))
        }
    }

    internal fun isSyllablePrefix(letters: String): Boolean = letters in syllablePrefixes

    internal fun isSyllableDigitPrefix(digits: String): Boolean = digits in syllableDigitPrefixes

    fun firstSyllableLetters(letters: String): String {
        val hi = minOf(letters.length, maxLetters)
        for (k in hi downTo 1) if (letters.substring(0, k) in SYLLABLES) return letters.substring(0, k)
        return ""
    }

    fun firstSyllableDigitLen(digits: String): Int {
        val hi = minOf(digits.length, maxDigits)
        for (k in hi downTo 1) if (byDigits.containsKey(digits.substring(0, k))) return k
        return 0
    }

    fun syllableReading(digitGroup: String): String = byDigits[digitGroup]?.firstOrNull() ?: ""

    fun leadingSyllableLetterLens(letters: String): List<Int> {
        val out = ArrayList<Int>()
        val hi = minOf(letters.length, maxLetters)
        for (k in hi downTo 1) if (letters.substring(0, k) in SYLLABLES) out.add(k)
        return out
    }

    fun leadingSyllableDigitLens(digits: String): List<Int> {
        val out = ArrayList<Int>()
        val hi = minOf(digits.length, maxDigits)
        for (k in hi downTo 1) if (byDigits.containsKey(digits.substring(0, k))) out.add(k)
        return out
    }

    fun preedit(digits: String): String {
        if (digits.isEmpty()) return ""
        syllableReading(digits).takeIf { it.isNotEmpty() }?.let { return it }
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
            digits.isNotEmpty() -> sb.append('\'')
        }
        return sb.toString()
    }

    fun preeditLetters(letters: String): String {
        if (letters.isEmpty()) return ""
        val parts = letters.split('\'')
        return buildString {
            parts.forEachIndexed { index, part ->
                if (index > 0) append('\'')
                append(preeditLetterChunk(part))
            }
        }
    }

    fun preeditLetters(letters: String, cuts: Set<Int>): String {
        if (cuts.isEmpty()) return preeditLetters(letters)
        val sb = StringBuilder()
        var prev = 0
        for (c in cuts.filter { it in 1..letters.length }.toSortedSet()) {
            if (c > prev) {
                if (sb.isNotEmpty()) sb.append('\'')
                sb.append(preeditLetters(letters.substring(prev, c)))
            }
            prev = c
        }
        when {
            prev < letters.length -> {
                if (sb.isNotEmpty()) sb.append('\'')
                sb.append(preeditLetters(letters.substring(prev)))
            }
            letters.isNotEmpty() -> sb.append('\'')
        }
        return sb.toString()
    }

    fun guessLetters(digits: String): String = preedit(digits).filter { it != '\'' }

    fun letterRuns(letters: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        var i = 0
        for (c in preeditLetters(letters)) {
            if (c == '\'') {
                if (i > start) out.add(start until i)
                start = i
            } else {
                i++
            }
        }
        if (i > start) out.add(start until i)
        return out
    }

    fun reviseLetters(prior: String, digits: String, at: Int, removed: Int, inserted: Int, chained: Boolean = false): String {
        val whole = guessLetters(digits)
        if (whole.length != digits.length) return whole
        if (prior.length != digits.length - inserted + removed || at < 0 || at + removed > prior.length) return whole
        val kept = prior.substring(0, at) + prior.substring(at + removed)
        if (inserted == 0) return kept
        if (inserted != 1 || removed != 0) return whole
        val runs = letterRuns(prior)
        val before = runs.firstOrNull { at - 1 in it }
        if (at == prior.length || chained) {
            val start = before?.first ?: at
            val guessed = guessLetters(digits.substring(start, at + 1))
            return if (guessed.length == at + 1 - start) kept.substring(0, start) + guessed + kept.substring(at) else whole
        }
        fun place(c: Char) = kept.substring(0, at) + c + kept.substring(at)
        fun agreeing(start: Int, end: Int): Char? {
            val g = guessLetters(digits.substring(start, end))
            val local = at - start
            if (g.length != end - start || g.removeRange(local, local + 1) != kept.substring(start, end - 1)) return null
            return g[local]
        }
        val after = runs.firstOrNull { at in it }
        val options = KEY_LETTERS[digits[at]].orEmpty()
        val lone = DIGIT_INITIAL[digits[at]] ?: digits[at]
        agreeing(0, digits.length)?.let { return place(it) }
        if (before != null && before == after) {
            agreeing(before.first, before.last + 2)?.let { return place(it) }
            options.firstOrNull { wellFormedLetters(place(it).substring(before.first, before.last + 2)) }?.let { return place(it) }
            return place(lone)
        }
        fun extendBefore(): String? = before?.let { run ->
            val text = prior.substring(run.first, run.last + 1)
            (agreeing(run.first, at + 1) ?: options.firstOrNull { syllabic(text + it) })?.let(::place)
        }
        fun joinAfter(): String? = after?.let { run ->
            val text = prior.substring(run.first, run.last + 1)
            (agreeing(at, run.last + 2) ?: options.firstOrNull { syllabic(it + text) })?.let(::place)
        }
        val incomplete = before != null && (before.first == before.last || prior.substring(before.first, before.last + 1) !in SYLLABLES)
        val joined = if (incomplete) extendBefore() ?: joinAfter() else joinAfter() ?: extendBefore()
        return joined ?: place(lone)
    }

    private fun syllabic(letters: String): Boolean = letters in SYLLABLES || isSyllablePrefix(letters)

    private fun wellFormedLetters(letters: String): Boolean {
        for (k in letters.length downTo 0) {
            if (k > 0 && segmentLetters(letters.substring(0, k)) == null) continue
            val tail = letters.substring(k)
            if (tail.isEmpty() || syllabic(tail)) return true
        }
        return false
    }

    private fun preeditLetterChunk(letters: String): String {
        if (letters.isEmpty()) return ""
        if (letters in SYLLABLES) return letters
        segmentLetters(letters)?.let { return it.joinToString("'") }
        for (end in letters.length - 1 downTo 1) {
            val prefix = segmentLetters(letters.substring(0, end)) ?: continue
            return prefix.joinToString("'") + "'" + letters.substring(end)
        }
        return letters
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

    fun leftColumnReadings(digits: String, limit: Int): List<String> {
        if (digits.isEmpty() || digits[0] < '2' || digits[0] > '9') return emptyList()
        val out = LinkedHashSet<String>()
        out.addAll(firstSyllableOptions(digits, limit))
        KEY_LETTERS[digits[0]]?.toList()
            ?.filterNot { it in NON_INITIAL_LETTERS }
            ?.sortedByDescending { it.toString() in SYLLABLES }
            ?.forEach { out.add(it.toString()) }
        return out.toList().take(limit)
    }

    fun leftColumnLetterReadings(letters: String, limit: Int): List<String> {
        if (letters.isEmpty() || letters.any { it !in 'a'..'z' }) return emptyList()
        val out = LinkedHashSet<String>()
        out.addAll(firstLetterSyllableOptions(letters))
        if (letters.first() !in NON_INITIAL_LETTERS) out.add(letters.first().toString())
        return out.toList().take(limit)
    }

    private fun firstLetterSyllableOptions(letters: String): List<String> {
        val reachable = BooleanArray(letters.length + 1)
        reachable[letters.length] = true
        for (start in letters.length - 1 downTo 0) {
            val hi = minOf(letters.length, start + maxLetters)
            for (end in start + 1..hi) {
                if (letters.substring(start, end) in SYLLABLES && reachable[end]) {
                    reachable[start] = true
                    break
                }
            }
        }
        val requireReach = reachable[0]
        val out = ArrayList<String>()
        val hi = minOf(letters.length, maxLetters)
        for (end in 1..hi) {
            val syllable = letters.substring(0, end)
            if (syllable !in SYLLABLES) continue
            if (requireReach && !reachable[end]) continue
            out.add(syllable)
        }
        return out.sortedBy { rankOf(it) - LEN_BONUS * it.length }
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
        return out.toList()
            .sortedWith(compareBy({ runRank(it) }, { freqRank[it] ?: DEFAULT_RANK }))
            .take(limit)
    }
}
