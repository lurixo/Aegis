package com.aegis.ime.engine

import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.user.UserModel

/**
 * Lattice decoding for both layouts via [PinyinDecoder], with char-bigram context shared across
 * 26-key (letter dict) and 9-key/T9 (digit dict), plus optional on-device user adaptation
 * ([userModel]): user-preferred words get a ranking boost, and learned next-word predictions show
 * on an empty buffer.
 */
class DictEngine(
    pinyinDict: BinaryDict?,
    t9Dict: BinaryDict?,
    lm: CharBigramLM?,
    private val userModel: UserModel? = null,
    fuzzyDict: BinaryDict? = null,
) : CandidateEngine {
    // Fuzzy index applies to 26-key only (T9 is already lossy).
    private val decoder = pinyinDict?.let {
        PinyinDecoder(it, lm, userModel = userModel, fuzzyDict = fuzzyDict)
    }
    private val t9Decoder = t9Dict?.let { PinyinDecoder(it, lm, userModel = userModel) }

    override fun candidates(composing: String, t9: Boolean): List<String> {
        if (composing.isEmpty()) return emptyList()
        val d = if (t9) t9Decoder else decoder
        return d?.decode(composing, MAX_CANDIDATES) ?: emptyList()
    }

    override fun predict(prevWord: String?): List<String> {
        if (prevWord.isNullOrEmpty()) return emptyList()
        return userModel?.successors(prevWord, MAX_PREDICTIONS) ?: emptyList()
    }

    override fun learn(prevWord: String?, word: String) {
        userModel?.record(prevWord, word, System.currentTimeMillis())
    }

    private companion object {
        const val MAX_CANDIDATES = 30
        const val MAX_PREDICTIONS = 8
    }
}
