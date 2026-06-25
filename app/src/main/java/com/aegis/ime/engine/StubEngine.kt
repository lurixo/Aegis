package com.aegis.ime.engine

/**
 * P1 placeholder: echoes the composing buffer back as the sole candidate so the full
 * input pipeline (buffer -> candidate bar -> commit) is demonstrable without a real decoder.
 * Replaced by the self-built pinyin engine starting at P3.
 */
class StubEngine : CandidateEngine {
    override fun candidates(composing: String, t9: Boolean): List<String> {
        if (composing.isEmpty()) return emptyList()
        return listOf(composing)
    }
}
