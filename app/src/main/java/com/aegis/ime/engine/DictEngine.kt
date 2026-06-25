package com.aegis.ime.engine

import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.dict.BinaryDict

class DictEngine(dict: BinaryDict?) : CandidateEngine {
    private val decoder = dict?.let { PinyinDecoder(it) }

    override fun candidates(composing: String, t9: Boolean): List<String> {
        if (composing.isEmpty() || t9) return emptyList()
        return decoder?.decode(composing, MAX_CANDIDATES) ?: emptyList()
    }

    private companion object {
        const val MAX_CANDIDATES = 30
    }
}
