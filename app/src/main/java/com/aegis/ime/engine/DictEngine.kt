package com.aegis.ime.engine

import com.aegis.ime.dict.BinaryDict

/**
 * P2 engine: real dictionary lookups against the prebuilt wanxiang trie.
 * Exact + prefix match on the full toneless pinyin, frequency-ordered.
 *
 * Not yet a decoder — it matches a word's *complete* pinyin (e.g. "nihao" -> 你好) or prefixes
 * of one. Syllable segmentation + Viterbi over partial input arrives at P3; T9 at P4.
 */
class DictEngine(private val dict: BinaryDict?) : CandidateEngine {
    override fun candidates(composing: String, t9: Boolean): List<String> {
        if (composing.isEmpty() || t9) return emptyList()
        val d = dict ?: return emptyList()
        return d.query(composing, MAX_CANDIDATES)
    }

    private companion object {
        const val MAX_CANDIDATES = 30
    }
}
