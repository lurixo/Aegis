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
import com.aegis.ime.user.UserDataDatabase
import com.aegis.ime.user.UserDataSnapshot
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
        }
    }
}
