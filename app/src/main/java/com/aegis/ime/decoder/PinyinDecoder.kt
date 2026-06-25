package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import kotlin.math.ln

/**
 * P3 prototype decoder for the 26-key full-pinyin layout.
 *
 * Over the input's character positions it runs a word-lattice Viterbi: an edge p→q exists when
 * input[p,q) is an exact dictionary key (a word's full toneless pinyin), scored by the unigram
 * log-probability ln(freq/totalFreq). Summed over a path, the −ln(totalFreq) term per word is the
 * cost that resists over-segmentation into common single chars. The best full-coverage path
 * becomes the sentence candidate; word-by-word options follow from the dictionary's prefix query.
 *
 * Unigram only — context (n-gram) scoring is P5, which is what properly fixes cases unigram
 * still gets wrong. Toneless syllable validity is implicit: only real dict keys form edges.
 */
class PinyinDecoder(private val dict: BinaryDict) {

    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())

    /** Candidates for [input] (lowercase toneless pinyin): best sentence first, then word options. */
    fun decode(input: String, limit: Int): List<String> {
        if (input.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        bestSentence(input)?.let { out.add(it) }
        out.addAll(dict.query(input, limit))
        return if (out.size <= limit) out.toList() else out.toList().subList(0, limit)
    }

    /** 1-best multi-word segmentation covering the whole input, or null if it doesn't fully decode. */
    private fun bestSentence(input: String): String? {
        val n = input.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val prev = IntArray(n + 1) { -1 }
        val word = arrayOfNulls<String>(n + 1)
        best[0] = 0.0
        for (q in 1..n) {
            for (p in 0 until q) {
                if (best[p] == Double.NEGATIVE_INFINITY) continue
                val top = dict.exact(input.substring(p, q)).firstOrNull() ?: continue
                val score = best[p] + (ln(top.freq.toDouble()) - lnTotal)
                if (score > best[q]) {
                    best[q] = score
                    prev[q] = p
                    word[q] = top.word
                }
            }
        }
        if (best[n] == Double.NEGATIVE_INFINITY) return null
        val parts = ArrayList<String>()
        var q = n
        while (q > 0) {
            parts.add(word[q]!!)
            q = prev[q]
        }
        parts.reverse()
        return parts.joinToString("")
    }
}
