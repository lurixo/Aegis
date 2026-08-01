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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatePageTest {

    @Test
    fun continuationCollectsEveryItemInStableThirtyItemPages() {
        val expected = (0 until 95).toList()
        val pageSizes = ArrayList<Int>()
        val actual = ArrayList<Int>()
        var page = firstCandidatePage(ListCandidatePageSource(expected), inputEpoch = 41L)
        while (true) {
            assertEquals(41L, page.inputEpoch)
            pageSizes.add(page.items.size)
            actual.addAll(page.items)
            val continuation = page.continuation ?: break
            page = continueCandidatePage(continuation, inputEpoch = 41L)
        }

        assertEquals(listOf(30, 30, 30, 5), pageSizes)
        assertEquals(expected, actual)
    }

    @Test
    fun staleAndAlreadyConsumedContinuationsCannotMixResults() {
        val first = firstCandidatePage(ListCandidatePageSource((0 until 70).toList()), inputEpoch = 8L)
        val continuation = first.continuation!!
        val second = continueCandidatePage(continuation, inputEpoch = 8L)

        val reused = continueCandidatePage(continuation, inputEpoch = 8L)
        assertTrue(reused.items.isEmpty())
        assertNull(reused.continuation)

        val stale = continueCandidatePage(second.continuation!!, inputEpoch = 9L)
        assertEquals(9L, stale.inputEpoch)
        assertTrue(stale.items.isEmpty())
        assertNull(stale.continuation)
    }

    @Test
    fun onlyOneConcurrentCallerCanConsumeAContinuation() {
        val first = firstCandidatePage(ListCandidatePageSource((0 until 90).toList()), inputEpoch = 10L)
        val continuation = first.continuation!!
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                executor.submit<CandidatePage<Int>> {
                    start.await()
                    continueCandidatePage(continuation, inputEpoch = 10L)
                }
            }
            start.countDown()
            val pages = futures.map { it.get() }

            assertEquals(listOf(0, 30), pages.map { it.items.size }.sorted())
            assertEquals((30 until 60).toList(), pages.flatMap { it.items })
        } finally {
            executor.shutdownNow()
        }
    }
}
