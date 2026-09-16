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

import com.aegis.ime.dict.DecodeCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
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

    @Test fun settle_applies_the_in_flight_result_on_the_calling_thread_without_computing_again() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val posted = ConcurrentLinkedQueue<Runnable>()
            val realLane = DecodeLane(exec, Executor { posted.add(it) }, settleMillis = 10_000L)
            val computes = AtomicInteger(0)
            val release = CountDownLatch(1)
            val applied = ArrayList<Int>()
            realLane.submit(compute = { computes.incrementAndGet(); release.await(5, TimeUnit.SECONDS); 7 }, apply = { applied.add(it) })
            Thread { Thread.sleep(40); release.countDown() }.start()
            assertTrue("the result of the same request arrives within the bound", realLane.settle())
            assertEquals("it is applied before settle returns", listOf(7), applied)
            assertFalse(realLane.pending)
            while (posted.isNotEmpty()) posted.poll().run()
            assertEquals("the queued main delivery of the same result is a no-op", listOf(7), applied)
            assertEquals("nothing was computed twice", 1, computes.get())
        } finally {
            exec.shutdownNow()
        }
    }

    @Test fun settle_gives_up_after_its_bound_when_the_result_does_not_arrive() {
        val bounded = DecodeLane(worker, main, settleMillis = 30L)
        val applied = ArrayList<Int>()
        var computes = 0
        bounded.submit(compute = { computes++; 1 }, apply = { applied.add(it) })
        val started = System.nanoTime()
        assertFalse("no worker ran, so nothing can be settled", bounded.settle())
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("the wait honours its bound: $waitedMillis ms", waitedMillis in 25L..2_000L)
        assertTrue("the request is still pending for the caller to satisfy", bounded.pending)
        bounded.markSatisfiedSynchronously()
        runWorker(); runMain()
        assertEquals("a request satisfied synchronously never computes on the worker", 0, computes)
        assertTrue(applied.isEmpty())
    }

    @Test fun settle_is_immediate_when_nothing_is_pending() {
        val started = System.nanoTime()
        assertTrue(DecodeLane(worker, main, settleMillis = 5_000L).settle())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000L)
    }

    @Test fun a_superseded_compute_stops_at_its_next_checkpoint_without_reporting_an_error() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val logged = ConcurrentLinkedQueue<Throwable>()
            val posted = ConcurrentLinkedQueue<Runnable>()
            val realLane = DecodeLane(exec, Executor { posted.add(it) }, logError = { logged.add(it) })
            val running = CountDownLatch(1)
            val finishedFirst = AtomicInteger(0)
            val checkpoints = AtomicInteger(0)
            val applied = ConcurrentLinkedQueue<String>()
            realLane.submit(
                compute = {
                    running.countDown()
                    repeat(10_000_000) { checkpoints.incrementAndGet(); DecodeCancellation.checkpoint(); Thread.sleep(0, 1) }
                    finishedFirst.incrementAndGet()
                    "stale"
                },
                apply = { applied.add(it) },
            )
            assertTrue(running.await(5, TimeUnit.SECONDS))
            val done = CountDownLatch(1)
            realLane.submit(compute = { done.countDown(); "fresh" }, apply = { applied.add(it) })
            assertTrue("the newer request runs as soon as the stale one yields", done.await(5, TimeUnit.SECONDS))
            exec.submit {}.get(5, TimeUnit.SECONDS)
            while (posted.isNotEmpty()) posted.poll().run()
            assertEquals("the stale compute never ran to completion", 0, finishedFirst.get())
            assertTrue("it stopped long before its end", checkpoints.get() < 10_000_000)
            assertEquals(listOf("fresh"), applied.toList())
            assertTrue("cancellation is not an error", logged.isEmpty())
        } finally {
            exec.shutdownNow()
        }
    }

    @Test fun marking_satisfied_cancels_the_in_flight_compute() {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val posted = ConcurrentLinkedQueue<Runnable>()
            val realLane = DecodeLane(exec, Executor { posted.add(it) })
            val running = CountDownLatch(1)
            val completed = AtomicInteger(0)
            realLane.submit(
                compute = {
                    running.countDown()
                    repeat(10_000_000) { DecodeCancellation.checkpoint(); Thread.sleep(0, 1) }
                    completed.incrementAndGet()
                },
                apply = { },
            )
            assertTrue(running.await(5, TimeUnit.SECONDS))
            realLane.markSatisfiedSynchronously()
            exec.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(0, completed.get())
            assertTrue("a cancelled compute delivers nothing", posted.isEmpty())
        } finally {
            exec.shutdownNow()
        }
    }

    @Test fun worker_tasks_run_in_submission_order_and_are_never_skipped() {
        val order = ArrayList<String>()
        lane.submit(compute = { order.add("decode 1") }, apply = { })
        lane.execute { order.add("task a") }
        lane.submit(compute = { order.add("decode 2") }, apply = { })
        lane.execute { order.add("task b") }
        runWorker(); runMain()
        assertEquals(listOf("task a", "decode 2", "task b"), order)
    }

    @Test fun a_failing_worker_task_is_logged_and_the_next_one_still_runs() {
        val logged = ArrayList<Throwable>()
        val logging = DecodeLane(worker, main, logError = { logged.add(it) })
        var ran = false
        logging.execute { error("boom") }
        logging.execute { ran = true }
        runWorker()
        assertEquals(1, logged.size)
        assertTrue(ran)
    }

}
