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

package com.aegis.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsyncDecodeEquivalenceTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private class TestLane {
        val workerQ = ArrayDeque<Runnable>()
        val mainQ = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { workerQ.add(it) }, Executor { mainQ.add(it) })
        fun runNextWorker() = workerQ.removeFirst().run()
        fun runNextMain() = mainQ.removeFirst().run()
    }

    @Test fun computed_stale_main_is_dropped_before_apply() {
        val lane = TestLane()
        val grid = CandidateGridView(ctx)
        grid.setCandidates(listOf("visible"))
        val rebuildsBefore = grid.candidateRebuildsForTest()
        var decodes = 0
        var applies = 0

        lane.lane.submit(
            compute = { decodes++; "old" },
            apply = { applies++; grid.setCandidates(listOf(it)) },
        )
        lane.runNextWorker()
        assertEquals(1, decodes)
        assertEquals(1, lane.mainQ.size)

        lane.lane.submit(
            compute = { decodes++; "current" },
            apply = { applies++; grid.setCandidates(listOf(it)) },
        )
        lane.runNextMain()

        assertEquals(1, decodes)
        assertEquals(0, applies)
        assertEquals(listOf("visible"), grid.renderedCandidateTextsForTest())
        assertEquals(rebuildsBefore, grid.candidateRebuildsForTest())
        assertTrue(lane.lane.pending)

        lane.runNextWorker()

        assertEquals(2, decodes)
        assertEquals(0, applies)
        assertEquals(listOf("visible"), grid.renderedCandidateTextsForTest())
        assertEquals(rebuildsBefore, grid.candidateRebuildsForTest())

        lane.runNextMain()

        assertEquals(2, decodes)
        assertEquals(1, applies)
        assertEquals(listOf("current"), grid.renderedCandidateTextsForTest())
        assertEquals(rebuildsBefore + 1, grid.candidateRebuildsForTest())
        assertFalse(lane.lane.pending)
    }
}
