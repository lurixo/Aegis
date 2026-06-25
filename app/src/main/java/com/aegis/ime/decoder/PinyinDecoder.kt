package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import kotlin.math.ln

class PinyinDecoder(private val dict: BinaryDict) {

    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())

    fun decode(input: String, limit: Int): List<String> {
        if (input.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        bestSentence(input)?.let { out.add(it) }
        out.addAll(dict.query(input, limit))
        return if (out.size <= limit) out.toList() else out.toList().subList(0, limit)
    }

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
