package com.aegis.ime.engine

import com.aegis.ime.dict.BinaryDict

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
