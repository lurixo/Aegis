package com.aegis.ime.engine

import com.aegis.ime.dict.BinaryDict

/**
 * English suggestions for the buffered EN mode: frequency-ranked prefix **completions** plus
 * edit-distance-1 **corrections** (Norvig-style), over a [BinaryDict] keyed by a word's letters.
 * Casing follows what the user typed.
 */
class EnglishEngine(private val dict: BinaryDict) {

    fun suggest(typed: String, limit: Int): List<String> {
        if (typed.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val out = LinkedHashSet<String>()
        for (wf in dict.prefixByFreq(lower, limit)) out.add(applyCase(typed, wf.word)) // freq-ranked completions
        if (out.size < limit) {
            for (w in corrections(lower)) {
                out.add(applyCase(typed, w))
                if (out.size >= limit) break
            }
        }
        return out.toList()
    }

    private fun corrections(word: String): List<String> {
        val results = ArrayList<Pair<String, Int>>()
        val seen = HashSet<String>()
        for (v in edits1(word)) {
            val e = dict.exact(v).firstOrNull() ?: continue
            if (seen.add(e.word)) results.add(e.word to e.freq)
        }
        results.sortByDescending { it.second }
        return results.map { it.first }
    }

    private fun edits1(w: String): Sequence<String> = sequence {
        val n = w.length
        for (i in 0 until n) yield(w.substring(0, i) + w.substring(i + 1))                    // deletions
        for (i in 0 until n - 1) yield(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2)) // transposes
        for (i in 0 until n) for (c in 'a'..'z') if (c != w[i]) yield(w.substring(0, i) + c + w.substring(i + 1)) // replaces
        for (i in 0..n) for (c in 'a'..'z') yield(w.substring(0, i) + c + w.substring(i))       // inserts
    }

    private fun applyCase(typed: String, suggestion: String): String = when {
        typed.length > 1 && typed.all { it.isUpperCase() } -> suggestion.uppercase()
        typed[0].isUpperCase() -> suggestion.replaceFirstChar { it.uppercaseChar() }
        else -> suggestion
    }
}
