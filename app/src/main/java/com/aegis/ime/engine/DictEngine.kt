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
import com.aegis.ime.decoder.CANDIDATE_PAGE_SIZE
import com.aegis.ime.decoder.CandidatePage
import com.aegis.ime.decoder.CandidatePageSource
import com.aegis.ime.decoder.CandidateSlice
import com.aegis.ime.decoder.FilteringCandidatePageSource
import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.decoder.Syllable
import com.aegis.ime.decoder.firstCandidatePage
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel

class DictEngine(
    pinyinDict: BinaryDict?,
    t9Dict: BinaryDict?,
    lm: CharBigramLM?,
    private val userModel: UserModel? = null,
    fuzzyRules: Set<String> = emptySet(),
    initialsDict: BinaryDict? = null,
    octagram: OctagramReader? = null,
    private val userLearning: UserLearning? = null,
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
            octagram = octagram,
            aliasDict = pinyinDict,
            userLearning = userLearning,
        )
    }

    override val supportsChinese: Boolean = decoder != null || t9Decoder != null

    override fun requiredContextCodePoints(): Int = maxOf(
        decoder?.requiredContextCodePoints() ?: 0,
        t9Decoder?.requiredContextCodePoints() ?: 0,
        1,
    )

    override fun candidates(composing: String, t9: Boolean): List<String> =
        candidatesCovered(composing, t9).map { it.word }

    override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> {
        if (composing.isEmpty()) return emptyList()
        val d = if (t9) t9Decoder else decoder
        val out = d?.decodeCovered(composing, CANDIDATE_PAGE_SIZE, cuts, context) ?: emptyList()
        return if (t9) out.filterNot { c -> c.word.all { it.code < 128 } } else out
    }

    override fun candidatesCoveredPage(
        composing: String,
        t9: Boolean,
        inputEpoch: Long,
        cuts: Set<Int>,
        context: CharSequence,
        pageSize: Int,
    ): CandidatePage<Cand> {
        if (composing.isEmpty()) return CandidatePage(emptyList(), null, inputEpoch)
        val source = (if (t9) t9Decoder else decoder)
            ?.coveredCandidateSource(composing, cuts, context)
            ?: return CandidatePage(emptyList(), null, inputEpoch)
        val visible = if (t9) FilteringCandidatePageSource(source) { candidate ->
            !candidate.word.all { it.code < 128 }
        } else source
        return firstCandidatePage(visible, inputEpoch, pageSize)
    }

    override fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int>, context: CharSequence): List<Cand> {
        if (letters.isEmpty()) return emptyList()
        return decoder?.decodeCoveredAtomic(letters, CANDIDATE_PAGE_SIZE, cuts, context) ?: emptyList()
    }

    override fun candidatesForLockedReadingCoveredPage(
        letters: String,
        inputEpoch: Long,
        cuts: Set<Int>,
        context: CharSequence,
        pageSize: Int,
    ): CandidatePage<Cand> {
        if (letters.isEmpty()) return CandidatePage(emptyList(), null, inputEpoch)
        val source = decoder?.coveredCandidateSource(letters, cuts, context, atomic = true)
            ?: return CandidatePage(emptyList(), null, inputEpoch)
        return firstCandidatePage(source, inputEpoch, pageSize)
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
        userModel?.successors(prevWord, Int.MAX_VALUE)?.forEach(out::add)
        return out.toList()
    }

    override fun predictPage(
        prevWord: String?,
        inputEpoch: Long,
        pageSize: Int,
    ): CandidatePage<String> {
        if (prevWord.isNullOrEmpty()) return CandidatePage(emptyList(), null, inputEpoch)
        return firstCandidatePage(predictionSource(prevWord), inputEpoch, pageSize)
    }

    private fun predictionSource(previousWord: String): CandidatePageSource<String> = object : CandidatePageSource<String> {
        private val pending = ArrayDeque<String>()
        private var phase = if (userLearning == null) MODEL_PHASE else LEARNING_PHASE
        private var learningAfter: UserLearning.RankedFollow? = null
        private var learningVersion: Long? = null
        private var modelAfter: UserModel.RankedSuccessor? = null
        private var modelVersion: Long? = null
        private val learningRankingNow = userLearning?.rankingNow() ?: 0L
        private val modelRankingNow = userModel?.rankingNow() ?: 0L
        private var invalidated = false
        private val sharedDatabase = userLearning?.databaseIdentity?.let { identity ->
            identity === userModel?.databaseIdentity
        } == true

        override fun next(pageSize: Int): CandidateSlice<String> {
            while (pending.size < pageSize && phase < EXHAUSTED_PHASE && !invalidated) loadStoragePage()
            val count = minOf(pageSize, pending.size)
            val items = ArrayList<String>(count)
            repeat(count) { items.add(pending.removeFirst()) }
            return CandidateSlice(items, pending.isNotEmpty() || (!invalidated && phase < EXHAUSTED_PHASE))
        }

        private fun loadStoragePage() {
            when (phase) {
                LEARNING_PHASE -> loadLearningPage()
                MODEL_PHASE -> loadModelPage()
                else -> Unit
            }
        }

        private fun loadLearningPage() {
            val learning = userLearning ?: run { phase = MODEL_PHASE; return }
            val page = learning.rankedFollowsPageSnapshot(
                previousWord,
                learningAfter,
                PREDICTION_STORAGE_PAGE_SIZE,
                learningRankingNow,
                learningVersion,
            )
            if (page.restartRequired) {
                invalidated = true
                return
            }
            learningVersion = page.version
            for (item in page.items) pending.addLast(item.word)
            learningAfter = page.items.lastOrNull() ?: learningAfter
            if (page.items.size < PREDICTION_STORAGE_PAGE_SIZE) {
                phase = MODEL_PHASE
                if (sharedDatabase) modelVersion = learningVersion
            }
        }

        private fun loadModelPage() {
            val model = userModel ?: run { phase = EXHAUSTED_PHASE; return }
            val page = model.rankedSuccessorsPageSnapshot(
                previousWord,
                modelAfter,
                PREDICTION_STORAGE_PAGE_SIZE,
                modelRankingNow,
                modelVersion,
            )
            if (page.restartRequired) {
                invalidated = true
                return
            }
            modelVersion = page.version
            val words = page.items.map { it.word }
            val learnedDuplicates = userLearning
                ?.activeFollowWords(previousWord, words, learningRankingNow)
                .orEmpty()
            userLearning?.let { learning ->
                val verification = learning.followsPageSnapshot(
                    previousWord,
                    offset = 0,
                    limit = 0,
                    expectedVersion = if (sharedDatabase) modelVersion else learningVersion,
                )
                if (verification.restartRequired || sharedDatabase && verification.version != modelVersion) {
                    invalidated = true
                    return
                }
                learningVersion = verification.version
            }
            for (word in words) if (word !in learnedDuplicates) pending.addLast(word)
            modelAfter = page.items.lastOrNull() ?: modelAfter
            if (page.items.size < PREDICTION_STORAGE_PAGE_SIZE) {
                phase = EXHAUSTED_PHASE
            }
        }
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
        const val PREDICTION_STORAGE_PAGE_SIZE = 64
        const val LEARNING_PHASE = 0
        const val MODEL_PHASE = 1
        const val EXHAUSTED_PHASE = 2
    }
}
