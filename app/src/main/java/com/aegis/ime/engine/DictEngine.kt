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
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.decoder.WordAnnotator
import com.aegis.ime.decoder.WordReading
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.EnglishKey
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import com.aegis.ime.user.UserLexicon

class DictEngine(
    private val pinyinDict: BinaryDict?,
    t9Dict: BinaryDict?,
    lm: CharBigramLM?,
    private val userModel: UserModel? = null,
    fuzzyRules: Set<String> = emptySet(),
    private val initialsDict: BinaryDict? = null,
    octagram: OctagramReader? = null,
    private val userLearning: UserLearning? = null,
    private val englishDict: BinaryDict? = null,
    private val userLexicon: UserLexicon? = null,
) : CandidateEngine {
    private val decoder = pinyinDict?.let {
        PinyinDecoder(
            it,
            lm,
            userModel = userModel,
            fuzzyRules = fuzzyRules,
            initialsDict = initialsDict,
            octagram = octagram,
            userLearning = userLearning,
        )
    }
    private val t9Decoder = t9Dict?.let {
        PinyinDecoder(
            it,
            lm,
            userModel = userModel,
            fuzzyRules = fuzzyRules,
            octagram = octagram,
            aliasDict = pinyinDict,
            userLearning = userLearning,
            fuzzyVariants = { s, rules -> T9Pinyin.fuzzyVariants(s, rules) },
            fuzzyPenalty = T9_FUZZY_PENALTY,
            grammarCharPenalty = T9_GRAMMAR_CHAR_PENALTY,
        )
    }

    private val annotator = pinyinDict?.let(::WordAnnotator)

    override val supportsChinese: Boolean = decoder != null || t9Decoder != null

    override fun candidates(composing: String, t9: Boolean): List<String> =
        candidatesCovered(composing, t9).map { it.word }

    override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> {
        if (composing.isEmpty()) return emptyList()
        val d = if (t9) t9Decoder else decoder
        val out = d?.decodeCovered(composing, MAX_CANDIDATES, cuts, context) ?: emptyList()
        return if (t9) nineCandidates(composing, out, context) else out
    }

    private fun nineCandidates(digits: String, decoded: List<Cand>, context: CharSequence): List<Cand> =
        decoded.filterNot { c -> c.word.all { it.code < 128 } }.ifEmpty { initialCandidates(digits, context) }

    override fun initialWords(digits: String): List<Cand> {
        val source = initialsDict ?: return emptyList()
        if (digits.length < 2) return emptyList()
        val supply = CandidateEngine.WHOLE_INITIALS + CandidateEngine.SPELLED_BEFORE_INITIALS
        return source.exactAmong(T9Pinyin.initialLetters(digits), supply).map { Cand(it.word, digits.length) }
    }

    private fun initialCandidates(digits: String, context: CharSequence): List<Cand> {
        val source = initialsDict ?: return emptyList()
        val nine = t9Decoder ?: return emptyList()
        if (digits.length < 2 || digits.any { it !in '2'..'9' }) return emptyList()
        val out = ArrayList<Cand>()
        val seen = HashSet<String>()
        for (length in digits.length downTo 2) {
            var taken = 0
            for (hit in source.exactAmong(T9Pinyin.initialLetters(digits.substring(0, length)), INITIAL_WORDS + seen.size)) {
                if (taken == INITIAL_WORDS) break
                if (seen.add(hit.word)) { out.add(Cand(hit.word, length)); taken++ }
            }
        }
        for (c in nine.decodeCovered(digits.substring(0, 1), MAX_CANDIDATES, emptySet(), context)) {
            if (c.word.any { it.code >= 128 } && seen.add(c.word)) out.add(c)
        }
        return out
    }

    override fun rankedSentences(composing: String, t9: Boolean, context: CharSequence, limit: Int): List<Pair<String, Double>> {
        val d = (if (t9) t9Decoder else decoder) ?: return emptyList()
        return sentencePairs(d.rankedSentences(composing, context, limit), t9)
    }

    override fun candidatesCoveredWithSentences(
        composing: String,
        t9: Boolean,
        context: CharSequence,
        sentenceLimit: Int,
    ): Pair<List<Cand>, List<Pair<String, Double>>> {
        if (composing.isEmpty()) return emptyList<Cand>() to emptyList()
        val d = (if (t9) t9Decoder else decoder) ?: return emptyList<Cand>() to emptyList()
        val sink = PinyinDecoder.PathSink(sentenceLimit)
        val out = d.decodeCovered(composing, MAX_CANDIDATES, context, sink)
        val candidates = if (t9) nineCandidates(composing, out, context) else out
        return candidates to sentencePairs(sink.paths, t9)
    }

    private fun sentencePairs(paths: List<PinyinDecoder.SentencePath>, t9: Boolean): List<Pair<String, Double>> =
        paths.filterNot { path -> t9 && path.text.all { it.code < 128 } }.map { it.text to it.score }

    override fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int>, context: CharSequence): List<Cand> {
        if (letters.isEmpty()) return emptyList()
        return decoder?.decodeCoveredAtomic(letters, MAX_CANDIDATES, cuts, context) ?: emptyList()
    }

    override fun candidatesForLockedDigitsCovered(
        digits: String,
        readings: List<String>,
        cuts: Set<Int>,
        context: CharSequence,
    ): List<Cand> {
        val out = t9Decoder?.decodeLockedCovered(digits, readings, cuts, context) ?: emptyList()
        return out.filterNot { c -> c.word.all { it.code < 128 } }
    }

    override fun guessLockedWords(
        lockedLetters: String,
        active: String,
        t9Active: Boolean,
        cuts: Set<Int>,
        context: CharSequence,
    ): List<Cand> {
        if (active.isEmpty()) return emptyList()
        val out = decoder?.guessLockedWords(lockedLetters, active, t9Active, cuts, context, MAX_GUESSES) ?: emptyList()
        return if (t9Active) out.filterNot { c -> c.word.all { it.code < 128 } } else out
    }

    override fun syllables(composing: String, t9: Boolean): List<Syllable> =
        syllables(composing, t9, emptySet())

    override fun syllables(composing: String, t9: Boolean, cuts: Set<Int>): List<Syllable> {
        if (composing.isEmpty()) return emptyList()
        return (if (t9) t9Decoder else decoder)?.syllables(composing, cuts) ?: emptyList()
    }

    override fun homophonesAt(composing: String, t9: Boolean, index: Int): List<String> =
        homophonesAt(composing, t9, index, emptySet())

    override fun homophonesAt(composing: String, t9: Boolean, index: Int, cuts: Set<Int>): List<String> {
        if (composing.isEmpty()) return emptyList()
        return (if (t9) t9Decoder else decoder)?.homophonesAt(composing, index, cuts) ?: emptyList()
    }

    override fun readingParts(word: String, digits: String, cuts: Set<Int>): List<String> =
        (decoder ?: t9Decoder)?.readingParts(word, digits, cuts) ?: emptyList()

    override fun syllablesForReading(letters: String): List<Syllable> =
        syllablesForReading(letters, emptySet())

    override fun syllablesForReading(letters: String, cuts: Set<Int>): List<Syllable> =
        if (letters.isEmpty()) emptyList() else decoder?.syllables(letters, cuts) ?: emptyList()

    override fun homophonesForReadingAt(letters: String, index: Int): List<String> =
        homophonesForReadingAt(letters, index, emptySet())

    override fun homophonesForReadingAt(letters: String, index: Int, cuts: Set<Int>): List<String> =
        if (letters.isEmpty()) emptyList() else decoder?.homophonesAt(letters, index, cuts) ?: emptyList()

    override fun predict(prevWord: String?): List<String> {
        if (prevWord.isNullOrEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        userLearning?.follows(prevWord)?.forEach { out.add(it.first) }
        userModel?.successors(prevWord, MAX_PREDICTIONS)?.forEach(out::add)
        return out.take(MAX_PREDICTIONS)
    }

    override fun englishCompletions(typed: String): List<String> {
        val key = EnglishKey.normalize(typed)
        if (key.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val custom = userLexicon?.englishCompletions(key).orEmpty()
        val customKeys = custom.mapTo(HashSet()) { EnglishKey.normalize(it) }
        for (word in custom) {
            if (word != typed) out.add(word)
            if (out.size == MAX_CANDIDATES) return out.toList()
        }
        for (hit in englishDict?.prefixByFreq(key, ENGLISH_SUPPLY).orEmpty()) {
            if (hit.word == typed) continue
            if (EnglishKey.normalize(hit.word) in customKeys) continue
            out.add(hit.word)
            if (out.size == MAX_CANDIDATES) break
        }
        return out.toList()
    }

    override fun learn(prevWord: String?, word: String) {
        userModel?.record(prevWord, word, System.currentTimeMillis())
    }

    override fun learnWord(reading: String, word: String, assembled: Boolean) {
        val um = userModel ?: return
        um.recordWord(reading, word, System.currentTimeMillis(), incrementCount = assembled)
    }

    override fun learnWordUse(word: String, assembled: Boolean) {
        userModel?.recordWordUse(word, System.currentTimeMillis(), incrementCount = assembled)
    }

    override fun spelledReading(word: String, reading: String): String =
        decoder?.spelledReading(word, reading) ?: reading

    override fun isDictionaryWord(word: String, reading: String): Boolean =
        pinyinDict?.containsExactWord(reading, word) == true

    override fun annotateWord(word: String): WordReading? = annotator?.annotate(word)

    override fun isUserWord(word: String, reading: String): Boolean =
        userModel?.hasWord(reading, word) == true || userLearning?.isFormed(word, reading) == true

    fun storedReadingRepairs(): List<UserModel.ReadingRepair> {
        val model = userModel ?: return emptyList()
        val letters = decoder ?: return emptyList()
        return letters.storedReadingRepairs(model.unmarkedReadings())
    }

    override fun prepareUserWords() {
        decoder?.prepareUserIndex()
        t9Decoder?.prepareUserIndex()
    }

    override fun storedWords(composing: String, t9: Boolean): List<String> =
        (if (t9) t9Decoder else decoder)?.storedWords(composing).orEmpty()

    override fun setFuzzyRules(rules: Set<String>) {
        decoder?.setFuzzyRules(rules)
        t9Decoder?.setFuzzyRules(rules)
    }

    private companion object {
        const val MAX_CANDIDATES = 30
        const val MAX_GUESSES = 3
        const val INITIAL_WORDS = 20
        const val MAX_PREDICTIONS = 8
        const val ENGLISH_SUPPLY = 128
    }
}

internal const val T9_FUZZY_PENALTY = 6.0
internal const val T9_GRAMMAR_CHAR_PENALTY = 4.0
