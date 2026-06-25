package com.aegis.ime.engine

/**
 * Produces candidates for a composing buffer.
 *
 * P2 ships [DictEngine] (exact + prefix dictionary lookup). The decoder arrives incrementally:
 *  - P3: DAG segmentation + Viterbi/beam with unigram scoring over the wanxiang dict trie (26-key).
 *  - P4: T9 digit lattice feeding the same decoder.
 *  - P5: n-gram (LM) context scoring.
 */
interface CandidateEngine {
    /**
     * @param composing raw pinyin (26-key) or digit string (T9).
     * @param t9 true when [composing] is a T9 digit sequence rather than letters.
     * @return ordered candidates, best first; empty when there is nothing to show.
     */
    fun candidates(composing: String, t9: Boolean): List<String>

    /** Learned next-word predictions to show on an empty buffer after [prevWord]. */
    fun predict(prevWord: String?): List<String> = emptyList()

    /** Record that the user committed [word] after [prevWord] (for adaptation). */
    fun learn(prevWord: String?, word: String) {}
}
