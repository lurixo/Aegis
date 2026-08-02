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

package com.aegis.ime.decoder

import com.aegis.ime.user.StoredWord
import com.aegis.ime.user.StoredUsage
import com.aegis.ime.user.UserDataDatabase
import com.aegis.ime.user.UserDataSnapshot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserLearningSnapshot
import com.aegis.ime.user.UserModel
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistedUserCandidatePagingTest {

    @Test
    fun persistedHomophonesPageCompletelyWithBoundedActiveState() {
        val root = Files.createTempDirectory("persisted-candidate-page").toFile().also { it.deleteOnExit() }
        UserDataDatabase.open(root).use { database ->
            val words = LinkedHashMap<String, StoredWord>()
            val readingWords = LinkedHashSet<String>()
            repeat(2_000) { index ->
                val word = "候选${index.toString().padStart(5, '0')}"
                words[word] = StoredWord(1, index.toLong())
                readingWords.add(word)
            }
            database.replaceUserData(UserDataSnapshot(words, emptyMap(), mapOf("ma" to readingWords)))
            val dictionary = EngineFixture.build(listOf(EngineFixture.Row("ma", "吗", 1_000)))
            val decoder = PinyinDecoder(dictionary, null, userModel = UserModel(database = database))
            val source = decoder.coveredCandidateSource("ma")
            val emitted = LinkedHashSet<String>()
            var pages = 0
            var hasMore: Boolean
            do {
                val page = source.next(73)
                emitted.addAll(page.items.map { it.word })
                hasMore = page.hasMore
                pages++
            } while (hasMore && pages < 100)
            assertTrue(pages < 100)
            assertEquals(readingWords, emitted.filterTo(LinkedHashSet()) { it in readingWords })
            assertTrue(
                "active candidate state must stay bounded, peak=${decoder.peakActivePoolSizeForTest}",
                decoder.peakActivePoolSizeForTest <= 512,
            )
            assertTrue(
                "static single-frequency lookup must be reused across persisted words, misses=${decoder.singleFrequencyCacheMissesForTest}",
                decoder.singleFrequencyCacheMissesForTest <= 16,
            )
            assertTrue(decoder.singleFrequencyCacheSizeForTest() <= PinyinDecoder.SINGLE_FREQUENCY_CACHE_SIZE)
        }
    }

    @Test
    fun lockedAtomicCandidatesPageEveryPersistedUserAndLearnedWordBeyond128() {
        val root = Files.createTempDirectory("persisted-atomic-page").toFile().also { it.deleteOnExit() }
        UserDataDatabase.open(root).use { database ->
            val userWords = (0 until 1_000).map { "用户原子${it.toString().padStart(4, '0')}" }
            val learnedWords = (0 until 1_000).map { "学习原子${it.toString().padStart(4, '0')}" }
            database.replaceUserData(
                UserDataSnapshot(
                    words = userWords.mapIndexed { index, word -> word to StoredWord(10_000 - index, 1_000L) }
                        .toMap(LinkedHashMap()),
                    bigrams = emptyMap(),
                    readings = mapOf("ma" to userWords.toCollection(LinkedHashSet())),
                ),
            )
            database.replaceLearning(
                UserLearningSnapshot(
                    formed = learnedWords.mapIndexed { index, word ->
                        word to mapOf("ma" to StoredUsage((10_000 - index).toDouble(), 1_000L))
                    }.toMap(LinkedHashMap()),
                    pending = emptyMap(),
                    follows = emptyMap(),
                ),
            )
            val model = UserModel({ 1_000L }, database)
            val learning = UserLearning({ 1_000L }, database)
            val decoder = PinyinDecoder(
                EngineFixture.build(listOf(EngineFixture.Row("ma", "吗", 1_000))),
                userModel = model,
                userLearning = learning,
            )
            val source = decoder.coveredCandidateSource("ma", atomic = true)
            val emitted = LinkedHashSet<String>()
            var pages = 0
            var hasMore: Boolean
            do {
                val page = source.next(73)
                emitted.addAll(page.items.map { it.word })
                hasMore = page.hasMore
                pages++
            } while (hasMore && pages < 100)

            assertTrue(pages < 100)
            assertEquals(userWords.toSet(), emitted.filterTo(LinkedHashSet()) { it in userWords })
            assertEquals(learnedWords.toSet(), emitted.filterTo(LinkedHashSet()) { it in learnedWords })
            val expectedOrder = buildList {
                repeat(1_000) { index ->
                    add(userWords[index])
                    add(learnedWords[index])
                }
            }
            assertEquals(expectedOrder, emitted.filter { it in userWords || it in learnedWords })
            assertTrue(decoder.peakAtomicLeadPoolSizeForTest <= PinyinDecoder.USER_QUERY_LIMIT)
            assertTrue(model.runtimeCacheSizesForTest().first <= 256)
            assertTrue(learning.runtimeCacheSizesForTest().first <= 256)
        }
    }

    @Test
    fun lockedAtomicContinuationStopsInsteadOfMixingDatabaseVersions() {
        val root = Files.createTempDirectory("persisted-atomic-version").toFile().also { it.deleteOnExit() }
        UserDataDatabase.open(root).use { database ->
            val original = (0 until 300).map { "原子版本${it.toString().padStart(4, '0')}" }
            database.replaceUserData(
                UserDataSnapshot(
                    words = original.mapIndexed { index, word ->
                        word to StoredWord(original.size - index, 1_000L)
                    }.toMap(LinkedHashMap()),
                    bigrams = emptyMap(),
                    readings = mapOf("ma" to original.toCollection(LinkedHashSet())),
                ),
            )
            val decoder = PinyinDecoder(
                EngineFixture.build(listOf(EngineFixture.Row("ma", "吗", 1_000))),
                userModel = UserModel({ 1_000L }, database),
            )
            val source = decoder.coveredCandidateSource("ma", atomic = true)
            val first = source.next(30)
            assertTrue(first.hasMore)

            database.recordWord("并发新原子", "ma", null, 1_001L, incrementCount = true)
            val staleContinuation = source.next(30)

            assertTrue(first.items.isNotEmpty())
            assertEquals(emptyList<Cand>(), staleContinuation.items)
            assertTrue(!staleContinuation.hasMore)
        }
    }
}
