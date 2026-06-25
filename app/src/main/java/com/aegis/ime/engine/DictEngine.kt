package com.aegis.ime.engine

import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.dict.BinaryDict

/**
 * P3 engine: full-pinyin decoding on the 26-key layout via [PinyinDecoder] (sentence + word
 * candidates over the wanxiang dict, unigram scored). Returns nothing for empty input or T9
 * (T9 disambiguation is P4); n-gram context scoring is P5.
 */
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
