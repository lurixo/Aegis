package com.aegis.ime.engine

import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.OctagramReader
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
    initialsDict: BinaryDict? = null,
    octagram: OctagramReader? = null,
    enDict: BinaryDict? = null,
) : CandidateEngine {
    private val englishEngine = enDict?.let { EnglishEngine(it) }
    // Fuzzy + 简拼 indexes apply to 26-key only (T9 is already lossy); octagram context serves both.
    private val decoder = pinyinDict?.let {
        PinyinDecoder(it, lm, userModel = userModel, fuzzyDict = fuzzyDict, initialsDict = initialsDict, octagram = octagram)
    }
    private val t9Decoder = t9Dict?.let {
        PinyinDecoder(it, lm, userModel = userModel, octagram = octagram)
    }

    override fun candidates(composing: String, t9: Boolean): List<String> {
        if (composing.isEmpty()) return emptyList()
        val d = if (t9) t9Decoder else decoder
        return d?.decode(composing, MAX_CANDIDATES) ?: emptyList()
    }

    override fun english(typed: String): List<String> =
        englishEngine?.suggest(typed, MAX_CANDIDATES) ?: emptyList()

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
