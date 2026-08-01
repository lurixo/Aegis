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
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        runWorker()
        lane.submit(compute = { 2 }, apply = { applied.add(it) })
        runWorker()
        mainQ.removeLast().run()
        mainQ.removeLast().run()
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
        runWorker()
        lane.markSatisfiedSynchronously()
        assertFalse("marking satisfied clears pending", lane.pending)
        runMain()
        assertTrue("…but is dropped, so no redundant apply happens", applied.isEmpty())
    }

    @Test fun a_compute_failure_reports_the_error_and_leaves_the_lane_ready() {
        val logged = ArrayList<Throwable>()
        val failing = DecodeLane(worker, main, logError = { logged.add(it) })
        val applied = ArrayList<Int>()
        val errors = ArrayList<Unit>()
        failing.submit(compute = { error("boom") }, apply = { applied.add(it) }, onError = { errors.add(Unit) })
        runWorker()
        runMain()
        assertEquals("the throwable is logged once", 1, logged.size)
        assertTrue("no result is applied on failure", applied.isEmpty())
        assertEquals("the caller is asked to clear candidates", 1, errors.size)
        assertFalse("the failed request is marked satisfied", failing.pending)

        failing.submit(compute = { 7 }, apply = { applied.add(it) })
        runWorker()
        runMain()
        assertEquals("a later keystroke still computes and applies", listOf(7), applied)
    }

    @Test fun a_real_single_thread_executor_survives_a_compute_exception() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val logged = java.util.concurrent.atomic.AtomicInteger(0)
            val realLane = DecodeLane(exec, exec, logError = { logged.incrementAndGet() })
            val firstRan = java.util.concurrent.CountDownLatch(1)
            realLane.submit(compute = { firstRan.countDown(); throw RuntimeException("boom") }, apply = { })
            assertTrue("the failing compute actually executed", firstRan.await(5, java.util.concurrent.TimeUnit.SECONDS))

            val secondRan = java.util.concurrent.CountDownLatch(1)
            realLane.submit(compute = { 1 }, apply = { secondRan.countDown() })
            assertTrue(
                "the executor thread keeps serving work after an exception",
                secondRan.await(5, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertEquals("the exception was caught and reported, not propagated", 1, logged.get())
        } finally {
            exec.shutdownNow()
        }
    }

    @Test fun a_new_request_interrupts_the_running_stale_compute() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val logged = java.util.concurrent.atomic.AtomicInteger(0)
            val applied = ArrayList<Int>()
            val firstStarted = CountDownLatch(1)
            val firstStopped = CountDownLatch(1)
            val secondApplied = CountDownLatch(1)
            val realLane = DecodeLane(exec, Executor { it.run() }, logError = { logged.incrementAndGet() })

            realLane.submit(
                compute = {
                    firstStarted.countDown()
                    try {
                        while (!Thread.currentThread().isInterrupted) Thread.yield()
                        throw CancellationException("superseded")
                    } finally {
                        firstStopped.countDown()
                    }
                },
                apply = { applied.add(it) },
            )
            assertTrue("the stale compute started", firstStarted.await(5, TimeUnit.SECONDS))

            realLane.submit(
                compute = { 2 },
                apply = { applied.add(it); secondApplied.countDown() },
            )

            assertTrue("the stale compute was interrupted", firstStopped.await(5, TimeUnit.SECONDS))
            assertTrue("the newest result applied", secondApplied.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(2), applied)
            assertEquals("supersession is control flow, not a decode failure", 0, logged.get())
            assertFalse(realLane.pending)
        } finally {
            exec.shutdownNow()
        }
    }
}
