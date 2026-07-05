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
import java.util.concurrent.Executor

/**
 * ① [DecodeLane] in isolation: serial coalescing (only the last of a burst computes), latest-wins on the
 * apply side (a stale/out-of-order result is dropped), the [DecodeLane.pending] flag, and
 * [DecodeLane.markSatisfiedSynchronously]. Both executors are hand-driven queues so the ordering the real
 * single-thread-worker + main-Handler produce is reproduced deterministically on the JVM.
 */
class DecodeLaneTest {

    private val workerQ = ArrayDeque<Runnable>()
    private val mainQ = ArrayDeque<Runnable>()
    private val worker = Executor { workerQ.add(it) }
    private val main = Executor { mainQ.add(it) }
    private val lane = DecodeLane(worker, main)

    private fun runWorker() { while (workerQ.isNotEmpty()) workerQ.removeFirst().run() }
    private fun runMain() { while (mainQ.isNotEmpty()) mainQ.removeFirst().run() }

    @Test fun only_the_latest_of_a_burst_computes_and_applies() {
        var computes = 0
        val applied = ArrayList<Int>()
        // Five requests submitted before the worker runs any (a fast keystroke burst).
        repeat(5) { i -> lane.submit(compute = { computes++; i }, apply = { applied.add(it) }) }
        runWorker()
        runMain()
        assertEquals("only the final request of the burst pays for a compute", 1, computes)
        assertEquals("only the final result is applied", listOf(4), applied)
    }

    @Test fun each_request_computes_when_drained_between_submits() {
        var computes = 0
        val applied = ArrayList<Int>()
        for (i in 0 until 4) {
            lane.submit(compute = { computes++; i }, apply = { applied.add(it) })
            runWorker(); runMain()
        }
        assertEquals("draining between submits runs every decode", 4, computes)
        assertEquals(listOf(0, 1, 2, 3), applied)
    }

    @Test fun a_stale_result_delivered_late_is_dropped() {
        val applied = ArrayList<Int>()
        lane.submit(compute = { 1 }, apply = { applied.add(it) })
        runWorker() // computes gen1 → queues its main-apply
        lane.submit(compute = { 2 }, apply = { applied.add(it) })
        runWorker() // computes gen2 → queues its main-apply
        // Deliver the NEWER apply (gen2) first, then the STALE one (gen1): the stale must not overwrite it.
        mainQ.removeLast().run() // gen2 apply
        mainQ.removeLast().run() // gen1 apply (stale)
        assertEquals("the newer result wins; the late stale one is dropped", listOf(2), applied)
    }

    @Test fun pending_is_true_between_submit_and_apply() {
        assertFalse("nothing submitted yet", lane.pending)
        lane.submit(compute = { 1 }, apply = { })
        assertTrue("submitted but not yet applied", lane.pending)
        runWorker()
        assertTrue("computed on the worker but the main apply has not run", lane.pending)
        runMain()
        assertFalse("applied → no longer pending", lane.pending)
    }

    @Test fun markSatisfiedSynchronously_drops_the_inflight_apply() {
        val applied = ArrayList<Int>()
        lane.submit(compute = { 1 }, apply = { applied.add(it) })
        runWorker() // gen1 computed, main-apply queued
        lane.markSatisfiedSynchronously() // caller produced the result synchronously instead
        assertFalse("marking satisfied clears pending", lane.pending)
        runMain() // the queued gen1 apply now fires…
        assertTrue("…but is dropped, so no redundant apply happens", applied.isEmpty())
    }
}
