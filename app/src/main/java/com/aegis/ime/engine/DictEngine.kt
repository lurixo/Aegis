package com.aegis.ime.engine

import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM

/**
 * P5 engine: lattice decoding for both layouts via [PinyinDecoder], with char-bigram context
 * scoring shared across 26-key (letter dict) and 9-key/T9 (digit dict). The bigram [lm] is over
 * hanzi, so it serves both input methods. n-gram only — the optional 32 GB-trained `.gram`
 * download is the top tier (not bundled).
 */
class DictEngine(
    pinyinDict: BinaryDict?,
    t9Dict: BinaryDict?,
    lm: CharBigramLM?,
) : CandidateEngine {
    private val decoder = pinyinDict?.let { PinyinDecoder(it, lm) }
    private val t9Decoder = t9Dict?.let { PinyinDecoder(it, lm) }

    override fun candidates(composing: String, t9: Boolean): List<String> {
        if (composing.isEmpty()) return emptyList()
        val d = if (t9) t9Decoder else decoder
        return d?.decode(composing, MAX_CANDIDATES) ?: emptyList()
    }

    private companion object {
        const val MAX_CANDIDATES = 30
    }
}
