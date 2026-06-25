package com.aegis.ime.engine

import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.dict.BinaryDict

/**
 * P4 engine: full-pinyin decoding for both layouts via [PinyinDecoder].
 *  - 26-key: letter-keyed dict (composing = toneless pinyin letters).
 *  - 9-key (T9): digit-keyed dict (composing = phone digits 2–9); the decoder is identical,
 *    disambiguation falls out of the digit→words aggregation in the prebuilt dict.
 *
 * n-gram context scoring is P5.
 */
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
