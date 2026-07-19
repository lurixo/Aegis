// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.engine

import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.decoder.Syllable
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.user.UserModel

class DictEngine(
    pinyinDict: BinaryDict?,
    t9Dict: BinaryDict?,
    lm: CharBigramLM?,
    private val userModel: UserModel? = null,
    fuzzyRules: Set<String> = emptySet(),
    initialsDict: BinaryDict? = null,
    octagram: OctagramReader? = null,
) : CandidateEngine {
    private val decoder = pinyinDict?.let {
        PinyinDecoder(it, lm, userModel = userModel, fuzzyRules = fuzzyRules, initialsDict = initialsDict, octagram = octagram)
    }
    private val t9Decoder = t9Dict?.let {
        PinyinDecoder(it, lm, userModel = userModel, octagram = octagram, aliasDict = pinyinDict)
    }

    override val supportsChinese: Boolean = decoder != null || t9Decoder != null

    override fun candidates(composing: String, t9: Boolean): List<String> =
        candidatesCovered(composing, t9).map { it.word }

    override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> {
        if (composing.isEmpty()) return emptyList()
        val d = if (t9) t9Decoder else decoder
        val out = d?.decodeCovered(composing, MAX_CANDIDATES, cuts, context) ?: emptyList()
        return if (t9) out.filterNot { c -> c.word.all { it.code < 128 } } else out
    }

    override fun candidatesForReading(letters: String): List<String> {
        if (letters.isEmpty()) return emptyList()
        return decoder?.decode(letters, MAX_CANDIDATES) ?: emptyList()
    }

    override fun candidatesForReadingCovered(letters: String, cuts: Set<Int>, context: CharSequence): List<Cand> {
        if (letters.isEmpty()) return emptyList()
        return decoder?.decodeCovered(letters, MAX_CANDIDATES, cuts, context) ?: emptyList()
    }

    override fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int>, context: CharSequence): List<Cand> {
        if (letters.isEmpty()) return emptyList()
        return decoder?.decodeCoveredAtomic(letters, MAX_CANDIDATES, cuts, context) ?: emptyList()
    }

    override fun syllables(composing: String, t9: Boolean): List<Syllable> {
        if (composing.isEmpty()) return emptyList()
        return (if (t9) t9Decoder else decoder)?.syllables(composing) ?: emptyList()
    }

    override fun homophonesAt(composing: String, t9: Boolean, index: Int): List<String> {
        if (composing.isEmpty()) return emptyList()
        return (if (t9) t9Decoder else decoder)?.homophonesAt(composing, index) ?: emptyList()
    }

    override fun syllablesForReading(letters: String): List<Syllable> =
        if (letters.isEmpty()) emptyList() else decoder?.syllables(letters) ?: emptyList()

    override fun homophonesForReadingAt(letters: String, index: Int): List<String> =
        if (letters.isEmpty()) emptyList() else decoder?.homophonesAt(letters, index) ?: emptyList()

    override fun predict(prevWord: String?): List<String> {
        if (prevWord.isNullOrEmpty()) return emptyList()
        return userModel?.successors(prevWord, MAX_PREDICTIONS) ?: emptyList()
    }

    override fun learn(prevWord: String?, word: String) {
        userModel?.record(prevWord, word, System.currentTimeMillis())
    }

    override fun learnWord(reading: String, word: String, assembled: Boolean) {
        val um = userModel ?: return
        um.recordWord(reading, word, System.currentTimeMillis(), incrementCount = assembled)
    }

    override fun setFuzzyRules(rules: Set<String>) {
        decoder?.setFuzzyRules(rules)
    }

    private companion object {
        const val MAX_CANDIDATES = 30
        const val MAX_PREDICTIONS = 8
    }
}
