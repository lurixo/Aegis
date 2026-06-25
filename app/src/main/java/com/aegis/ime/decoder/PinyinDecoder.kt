package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.user.UserModel
import kotlin.math.ln

class PinyinDecoder(
    private val dict: BinaryDict,
    private val lm: CharBigramLM? = null,
    private val lambda: Double = DEFAULT_LAMBDA,
    private val userModel: UserModel? = null,
) {
    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())
    private val edgeN = if (lm != null) EDGE_N else 1

    fun decode(input: String, limit: Int): List<String> {
        if (input.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        bestSentence(input)?.let { out.add(it) }
        out.addAll(dict.query(input, limit))
        return if (out.size <= limit) out.toList() else out.toList().subList(0, limit)
    }

    private class Cell(val score: Double, val prevPos: Int, val prevChar: Int, val word: String)

    private fun bestSentence(input: String): String? {
        val n = input.length
        val dp = Array(n + 1) { HashMap<Int, Cell>() }
        dp[0][BOS] = Cell(0.0, -1, BOS, "")

        for (q in 1..n) {
            for (p in 0 until q) {
                val from = dp[p]
                if (from.isEmpty()) continue
                val words = dict.exact(input.substring(p, q))
                if (words.isEmpty()) continue
                var taken = 0
                for (wf in words) {
                    if (taken++ >= edgeN) break
                    val w = wf.word
                    val uni = ln(wf.freq.toDouble()) - lnTotal
                    val boost = userModel?.wordBoost(w) ?: 0.0
                    val firstCp = w.codePointAt(0)
                    val lastCp = w.codePointBefore(w.length)
                    for ((prevChar, cell) in from) {
                        val bi = if (lm == null || prevChar == BOS) 0.0
                        else lambda * lm.logCond(prevChar, firstCp)
                        val score = cell.score + uni + bi + boost
                        val cur = dp[q][lastCp]
                        if (cur == null || score > cur.score) {
                            dp[q][lastCp] = Cell(score, p, prevChar, w)
                        }
                    }
                }
            }
        }

        val end = dp[n]
        if (end.isEmpty()) return null
        var bestChar = BOS
        var bestScore = Double.NEGATIVE_INFINITY
        for ((cp, cell) in end) if (cell.score > bestScore) { bestScore = cell.score; bestChar = cp }

        val parts = ArrayList<String>()
        var q = n
        var cp = bestChar
        while (q > 0) {
            val cell = dp[q][cp]!!
            parts.add(cell.word)
            val pp = cell.prevPos
            cp = cell.prevChar
            q = pp
        }
        parts.reverse()
        return parts.joinToString("")
    }

    private companion object {
        const val BOS = -1
        const val EDGE_N = 8
        const val DEFAULT_LAMBDA = 1.0
    }
}
