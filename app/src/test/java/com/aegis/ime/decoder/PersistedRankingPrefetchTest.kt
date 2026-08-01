// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.

package com.aegis.ime.decoder

import com.aegis.ime.user.StoredUsage
import com.aegis.ime.user.StoredWord
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
class PersistedRankingPrefetchTest {

    @Test
    fun sparsePersistedBoostsAreReadInBoundedBatchesWithoutChangingRanking() {
        val rows = List(2_000) { index ->
            EngineFixture.Row(
                "a",
                "候选${EngineFixture.supplementary(index)}",
                10_000 - index,
            )
        }
        val dictionary = EngineFixture.build(rows)
        val expected = PinyinDecoder(dictionary).coveredCandidateSource("a").next(30).items.map { it.word }
        val root = Files.createTempDirectory("persisted-ranking-prefetch").toFile().also { it.deleteOnExit() }

        UserDataDatabase.open(root).use { database ->
            database.replaceUserData(
                UserDataSnapshot(
                    words = mapOf("无关用户词" to StoredWord(1_000_000_000, 2_000L)),
                    bigrams = emptyMap(),
                    readings = mapOf("z" to setOf("无关用户词")),
                ),
            )
            database.replaceLearning(
                UserLearningSnapshot(
                    formed = mapOf("无关学习词" to mapOf("z" to StoredUsage(1_000_000.0, 2_000L))),
                    pending = emptyMap(),
                    follows = emptyMap(),
                ),
            )
            val model = UserModel({ 2_000L }, database)
            val learning = UserLearning({ 2_000L }, database)
            database.resetRankingReadCountsForTest()

            val actual = PinyinDecoder(
                dictionary,
                userModel = model,
                userLearning = learning,
            ).coveredCandidateSource("a").next(30).items.map { it.word }

            assertEquals(expected, actual)
            val (scalarReads, batchReads) = database.rankingReadCountsForTest()
            assertEquals("candidate scoring must not issue one SQLite query per dictionary word", 0, scalarReads)
            assertTrue("batch reads must stay proportional to bounded prefetch windows: $batchReads", batchReads in 1..400)
            assertTrue(model.runtimeCacheSizesForTest().first <= 256)
            assertTrue(learning.runtimeCacheSizesForTest().first <= 256)
        }
    }
}
