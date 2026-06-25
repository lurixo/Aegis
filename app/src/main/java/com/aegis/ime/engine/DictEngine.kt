package com.aegis.ime.engine

import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.dict.BinaryDict

class DictEngine(pinyinDict: BinaryDict?, t9Dict: BinaryDict?) : CandidateEngine {
    private val decoder = pinyinDict?.let { PinyinDecoder(it) }
    private val t9Decoder = t9Dict?.let { PinyinDecoder(it) }

    override fun candidates(composing: String, t9: Boolean): List<String> {
        if (composing.isEmpty()) return emptyList()
        val d = if (t9) t9Decoder else decoder
        return d?.decode(composing, MAX_CANDIDATES) ?: emptyList()
    }

    private companion object {
        const val MAX_CANDIDATES = 30
    }
}
