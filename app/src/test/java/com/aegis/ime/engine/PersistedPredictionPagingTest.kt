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

import com.aegis.ime.user.StoredUsage
import com.aegis.ime.user.StoredWord
import com.aegis.ime.user.UserDataDatabase
import com.aegis.ime.user.UserDataSnapshot
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserLearningSnapshot
import com.aegis.ime.user.UserModel
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.exp
import kotlin.math.ln

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistedPredictionPagingTest {

    @Test fun learned_and_user_predictions_beyond_128_are_all_paged_in_existing_source_order() {
        val root = Files.createTempDirectory("persisted-prediction-page").toFile().also { it.deleteOnExit() }
        UserDataDatabase.open(root).use { database ->
            val learned = (0 until 220).map { "学习预测%03d".format(it) }
            val uniqueModel = (0 until 150).map { "用户预测%03d".format(it) }
            val modelOrder = learned.drop(100) + uniqueModel
            database.replaceLearning(
                UserLearningSnapshot(
                    formed = emptyMap(),
                    pending = emptyMap(),
                    follows = mapOf(
                        PREVIOUS to learned.mapIndexed { index, word ->
                            word to StoredUsage((learned.size - index).toDouble(), NOW)
                        }.toMap(LinkedHashMap()),
                    ),
                ),
            )
            database.replaceUserData(
                UserDataSnapshot(
                    words = (learned + uniqueModel).associateWith { StoredWord(1, NOW) },
                    bigrams = mapOf(
                        PREVIOUS to modelOrder.mapIndexed { index, word ->
                            word to modelOrder.size - index
                        }.toMap(LinkedHashMap()),
                    ),
                    readings = emptyMap(),
                ),
            )
            val model = UserModel({ NOW }, database)
            val learning = UserLearning({ NOW }, database)
            val engine = DictEngine(null, null, null, model, userLearning = learning)

            val actual = ArrayList<String>()
            val pageSizes = ArrayList<Int>()
            var page = engine.predictPage(PREVIOUS, inputEpoch = 71L)
            while (true) {
                pageSizes.add(page.items.size)
                actual.addAll(page.items)
                val continuation = page.continuation ?: break
                page = engine.continuePage(continuation, inputEpoch = 71L)
            }

            assertEquals(learned + uniqueModel, actual)
            assertEquals(List(12) { 30 } + 10, pageSizes)
            assertEquals(actual.size, actual.toSet().size)
            assertTrue(model.runtimeCacheSizesForTest().first <= 256)
            assertTrue(learning.runtimeCacheSizesForTest().second <= 256)
        }
    }

    @Test fun a_concurrent_database_version_change_ends_the_old_prediction_continuation_without_mixing() {
        val root = Files.createTempDirectory("persisted-prediction-version").toFile().also { it.deleteOnExit() }
        UserDataDatabase.open(root).use { database ->
            val original = (0 until 140).map { "原预测%03d".format(it) }
            database.replaceLearning(
                UserLearningSnapshot(
                    formed = emptyMap(),
                    pending = emptyMap(),
                    follows = mapOf(
                        PREVIOUS to original.mapIndexed { index, word ->
                            word to StoredUsage((original.size - index).toDouble(), NOW)
                        }.toMap(LinkedHashMap()),
                    ),
                ),
            )
            val model = UserModel({ NOW }, database)
            val learning = UserLearning({ NOW }, database)
            val engine = DictEngine(null, null, null, model, userLearning = learning)

            val first = engine.predictPage(PREVIOUS, inputEpoch = 72L)
            database.upsertFollowUsage(PREVIOUS, "并发新预测", StoredUsage(1_000.0, NOW))
            val second = engine.continuePage(first.continuation!!, inputEpoch = 72L)
            val third = engine.continuePage(second.continuation!!, inputEpoch = 72L)
            val emitted = first.items + second.items + third.items

            assertEquals(original.take(64), emitted)
            assertFalse("并发新预测" in emitted)
            assertNull(third.continuation)
        }
    }

    @Test fun persisted_follow_pages_keep_one_global_decay_ranking_across_raw_sql_pages() {
        val root = Files.createTempDirectory("persisted-prediction-ranking").toFile().also { it.deleteOnExit() }
        val queryNow = UserLearning.FOLLOW_HALF_LIFE_MILLIS * 10L
        data class Row(val word: String, val usage: StoredUsage)
        val rows = (0 until 150).map { index ->
            Row(
                word = "排序预测%03d".format(index),
                usage = if (index < 75) {
                    StoredUsage((1_000 - index).toDouble(), 0L)
                } else {
                    StoredUsage((200 - index).toDouble(), queryNow)
                },
            )
        }
        val expected = rows.sortedWith(
            compareByDescending<Row> {
                ln(it.usage.count) + ln(2.0) * it.usage.lastSeen.toDouble() / UserLearning.FOLLOW_HALF_LIFE_MILLIS
            }.thenBy { it.word },
        ).map { it.word }

        UserDataDatabase.open(root).use { database ->
            database.replaceLearning(
                UserLearningSnapshot(
                    formed = emptyMap(),
                    pending = emptyMap(),
                    follows = mapOf(PREVIOUS to rows.associate { it.word to it.usage }),
                ),
            )
            val engine = DictEngine(
                null,
                null,
                null,
                userLearning = UserLearning({ queryNow }, database),
            )
            val actual = ArrayList<String>()
            var page = engine.predictPage(PREVIOUS, inputEpoch = 73L)
            while (true) {
                actual.addAll(page.items)
                val continuation = page.continuation ?: break
                page = engine.continuePage(continuation, inputEpoch = 73L)
            }
            assertEquals(expected, actual)
        }
    }

    @Test fun persisted_user_bigram_pages_keep_one_global_frequency_and_recency_ranking() {
        val root = Files.createTempDirectory("persisted-bigram-ranking").toFile().also { it.deleteOnExit() }
        val halfLife = 7L * 24L * 60L * 60L * 1_000L
        val queryNow = halfLife * 10L
        data class Row(val word: String, val count: Int, val lastUsed: Long)
        val rows = (0 until 150).map { index ->
            Row(
                word = "二元预测%03d".format(index),
                count = if (index < 75) 10 else 9,
                lastUsed = if (index < 75) 0L else queryNow,
            )
        }
        val expected = rows.sortedWith(
            compareByDescending<Row> {
                3.5 * ln(1.0 + it.count) + if (it.lastUsed <= 0L) 0.0 else {
                    2.0 * exp(-ln(2.0) * (queryNow - it.lastUsed).coerceAtLeast(0L).toDouble() / halfLife)
                }
            }.thenBy { it.word },
        ).map { it.word }

        UserDataDatabase.open(root).use { database ->
            database.replaceUserData(
                UserDataSnapshot(
                    words = rows.associate { it.word to StoredWord(1, it.lastUsed) },
                    bigrams = mapOf(PREVIOUS to rows.associate { it.word to it.count }),
                    readings = emptyMap(),
                ),
            )
            val engine = DictEngine(null, null, null, UserModel({ queryNow }, database))
            val actual = ArrayList<String>()
            var page = engine.predictPage(PREVIOUS, inputEpoch = 74L)
            while (true) {
                actual.addAll(page.items)
                val continuation = page.continuation ?: break
                page = engine.continuePage(continuation, inputEpoch = 74L)
            }
            assertEquals(expected, actual)
        }
    }

    private companion object {
        const val PREVIOUS = "前"
        const val NOW = 10_000L
    }
}
