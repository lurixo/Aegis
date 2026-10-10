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

package com.aegis.ime.neural

import com.aegis.ime.dict.DecodeCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeuralRerankerTest {

    private class FakeScorer(private val scores: Map<String, Double>) : SentenceScorer {
        val contexts = ArrayList<String>()
        val batches = ArrayList<List<String>>()
        var cancels = 0
        var aborted = false
        var closed = false

        override fun score(context: String, candidates: List<String>): DoubleArray? {
            contexts.add(context)
            batches.add(candidates)
            if (aborted) return null
            return DoubleArray(candidates.size) { scores.getValue(candidates[it]) }
        }

        override fun cancel() {
            cancels++
        }

        override fun close() {
            closed = true
        }
    }

    private class Queues {
        val worker = ArrayDeque<Runnable>()
        val main = ArrayDeque<Runnable>()
        val timers = ArrayList<Pair<Long, () -> Unit>>()
        val schedule: (Long, () -> Unit) -> Unit = { millis, task -> timers.add(millis to task) }
        fun runWorker() {
            while (worker.isNotEmpty()) worker.removeFirst().run()
        }
        fun runMain() {
            while (main.isNotEmpty()) main.removeFirst().run()
        }
        fun fireTimers() {
            val due = timers.toList()
            timers.clear()
            due.forEach { it.second() }
        }
    }

    private companion object {
        const val WAIT = 300L
        const val NONE = "-"
    }

    private val spec = NeuralModelSpec("test", alphaLetters = 5.0, alphaNine = 2.0, candidates = 10)
    private val paths = listOf("你好" to -10.0, "拟好" to -11.0, "你号" to -12.0)
    private val prefersSecond = mapOf("你好" to -5.0, "拟好" to -1.0, "你号" to -9.0)

    private fun started(q: Queues, scorer: SentenceScorer, clock: () -> Long = System::nanoTime): NeuralReranker {
        val r = NeuralReranker(spec, { scorer }, Executor { q.worker.add(it) }, Executor { q.main.add(it) }, q.schedule, clock)
        r.start()
        q.runWorker()
        return r
    }

    @Test fun choose_adds_the_weighted_model_score_to_the_decoder_score() {
        val base = doubleArrayOf(-10.0, -11.0, -12.0)
        val lm = doubleArrayOf(-5.0, -1.0, -9.0)
        assertEquals(1, NeuralReranker.choose(base, lm, 5.0))
        assertEquals(0, NeuralReranker.choose(base, lm, 0.0))
        assertEquals(0, NeuralReranker.choose(base, lm, 0.2))
    }

    @Test fun choose_keeps_the_earlier_path_on_a_tie() {
        assertEquals(0, NeuralReranker.choose(doubleArrayOf(-2.0, -1.0), doubleArrayOf(-1.0, -2.0), 1.0))
    }

    @Test fun a_scored_job_delivers_the_model_choice_on_the_main_executor() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        assertTrue(r.ready)
        val chosen = ArrayList<NeuralReranker.Choice?>()
        r.submit("上文", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add(it) }
        assertEquals(listOf(WAIT), q.timers.map { it.first })
        q.runWorker()
        assertTrue(chosen.isEmpty())
        q.runMain()
        assertEquals(1, chosen.size)
        assertEquals("拟好", chosen[0]?.best)
        assertEquals("你好", chosen[0]?.decoderBest)
        assertEquals(listOf("上文"), scorer.contexts)
        assertEquals(listOf("你好", "拟好", "你号"), scorer.batches.single())
        val snap = r.snapshot()
        assertEquals(1, snap.runs)
        assertEquals(1, snap.changed)
    }

    @Test fun the_nine_key_weight_applies_to_nine_key_jobs() {
        val q = Queues()
        val r = started(q, FakeScorer(mapOf("你好" to -5.0, "拟好" to -4.7, "你号" to -9.0)))
        val chosen = ArrayList<String>()
        r.submit("", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add(it?.best ?: NONE) }
        q.runWorker(); q.runMain()
        r.submit("", nine = true, waitMillis = WAIT, paths = { paths }) { chosen.add(it?.best ?: NONE) }
        q.runWorker(); q.runMain()
        assertEquals(listOf("拟好", "你好"), chosen)
    }

    @Test fun only_the_first_candidates_of_the_spec_are_scored() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = NeuralReranker(spec.copy(candidates = 2), { scorer }, Executor { q.worker.add(it) }, Executor { q.main.add(it) }, q.schedule)
        r.start(); q.runWorker()
        r.submit("", nine = false, waitMillis = WAIT, paths = { paths }) {}
        q.runWorker()
        assertEquals(listOf("你好", "拟好"), scorer.batches.single())
    }

    @Test fun invalidating_before_the_worker_runs_drops_the_job() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        r.submit("", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add(it?.best ?: NONE) }
        r.invalidate()
        q.runWorker(); q.runMain()
        assertTrue(chosen.isEmpty())
        assertTrue(scorer.batches.isEmpty())
        assertTrue(scorer.cancels >= 1)
    }

    @Test fun invalidating_after_scoring_drops_the_delivery() {
        val q = Queues()
        val r = started(q, FakeScorer(prefersSecond))
        val chosen = ArrayList<String>()
        r.submit("", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add(it?.best ?: NONE) }
        q.runWorker()
        r.invalidate()
        q.runMain()
        assertTrue(chosen.isEmpty())
    }

    @Test fun a_newer_job_supersedes_an_older_one() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        r.submit("old", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add("old:" + it?.best) }
        r.submit("new", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add("new:" + it?.best) }
        q.runWorker(); q.runMain()
        assertEquals(listOf("new:拟好"), chosen)
        assertEquals(listOf("new"), scorer.contexts)
    }

    private fun newerJobCancelsThePathGenerationInFlight(nine: Boolean) {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        var oldFinished = false
        r.submit("old", nine = nine, waitMillis = WAIT, paths = {
            r.submit("new", nine = nine, waitMillis = WAIT, paths = { paths }) { chosen.add("new:" + it?.best) }
            DecodeCancellation.checkpoint()
            oldFinished = true
            paths
        }) { chosen.add("old:" + it?.best) }
        q.runWorker(); q.runMain()
        assertFalse(oldFinished)
        assertEquals(listOf("new:拟好"), chosen)
        assertEquals(listOf("new"), scorer.contexts)
        assertEquals(1, r.snapshot().runs)
    }

    @Test fun a_newer_job_cancels_the_path_generation_in_flight_on_26key() = newerJobCancelsThePathGenerationInFlight(nine = false)

    @Test fun a_newer_job_cancels_the_path_generation_in_flight_on_9key() = newerJobCancelsThePathGenerationInFlight(nine = true)

    private fun invalidatingCancelsAPathGenerationThatHoldsTheDecodeLock(nine: Boolean) {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val r = NeuralReranker(spec, { scorer }, worker, Executor { q.main.add(it) }, q.schedule)
            r.start()
            val lock = Any()
            val entered = CountDownLatch(1)
            var ranOut = false
            val chosen = ArrayList<String>()
            r.submit("", nine = nine, waitMillis = WAIT, paths = {
                synchronized(lock) {
                    entered.countDown()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (System.nanoTime() < deadline) {
                        DecodeCancellation.checkpoint()
                        Thread.sleep(1)
                    }
                    ranOut = true
                    paths
                }
            }) { chosen.add(it?.best ?: NONE) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val before = System.nanoTime()
            r.invalidate()
            synchronized(lock) {}
            val waited = System.nanoTime() - before
            assertTrue("waited ${waited / 1_000_000L} ms for the lock", waited < TimeUnit.SECONDS.toNanos(2))
            worker.submit {}.get(15, TimeUnit.SECONDS)
            q.runMain()
            assertFalse(ranOut)
            assertTrue(chosen.isEmpty())
            assertTrue(scorer.batches.isEmpty())
            assertEquals(0, r.snapshot().runs)
        } finally {
            worker.shutdown()
            worker.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test fun invalidating_cancels_a_path_generation_that_holds_the_decode_lock_on_26key() =
        invalidatingCancelsAPathGenerationThatHoldsTheDecodeLock(nine = false)

    @Test fun invalidating_cancels_a_path_generation_that_holds_the_decode_lock_on_9key() =
        invalidatingCancelsAPathGenerationThatHoldsTheDecodeLock(nine = true)

    private fun currentJobPassesEveryPathCheckpoint(nine: Boolean) {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        r.submit("", nine = nine, waitMillis = WAIT, paths = {
            repeat(3) { DecodeCancellation.checkpoint() }
            paths
        }) { chosen.add(it?.best ?: NONE) }
        q.runWorker(); q.runMain()
        assertEquals(listOf("拟好"), chosen)
        assertEquals(listOf("你好", "拟好", "你号"), scorer.batches.single())
        assertEquals(1, r.snapshot().runs)
    }

    @Test fun a_current_job_passes_every_path_checkpoint_on_26key() = currentJobPassesEveryPathCheckpoint(nine = false)

    @Test fun a_current_job_passes_every_path_checkpoint_on_9key() = currentJobPassesEveryPathCheckpoint(nine = true)

    private fun failedPathGenerationDeliversNoChoice(nine: Boolean) {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        r.submit("", nine = nine, waitMillis = WAIT, paths = { error("broken lattice") }) { chosen.add(it?.best ?: NONE) }
        q.runWorker(); q.runMain()
        assertEquals(listOf(NONE), chosen)
        assertTrue(scorer.batches.isEmpty())
    }

    @Test fun a_failed_path_generation_delivers_no_choice_on_26key() = failedPathGenerationDeliversNoChoice(nine = false)

    @Test fun a_failed_path_generation_delivers_no_choice_on_9key() = failedPathGenerationDeliversNoChoice(nine = true)

    @Test fun an_aborted_score_delivers_nothing() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond).apply { aborted = true }
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        r.submit("", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add(it?.best ?: NONE) }
        q.runWorker(); q.runMain()
        assertTrue(chosen.isEmpty())
        assertEquals(0, r.snapshot().runs)
    }

    @Test fun fewer_than_two_paths_skip_the_model() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        r.submit("", nine = false, waitMillis = WAIT, paths = { paths.take(1) }) { chosen.add(it?.best ?: NONE) }
        q.runWorker(); q.runMain()
        assertTrue(scorer.batches.isEmpty())
        assertEquals(listOf(NONE), chosen)
        assertTrue(r.settle(0L))
        assertEquals(0, r.snapshot().timeouts)
    }

    @Test fun settle_runs_a_finished_delivery_before_the_main_executor() {
        val q = Queues()
        val r = started(q, FakeScorer(prefersSecond))
        val chosen = ArrayList<String>()
        r.submit("", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add(it?.best ?: NONE) }
        q.runWorker()
        assertTrue(r.settle(0L))
        assertEquals(listOf("拟好"), chosen)
        q.runMain()
        assertEquals(listOf("拟好"), chosen)
    }

    @Test fun settle_times_out_when_the_job_has_not_run() {
        val q = Queues()
        val r = started(q, FakeScorer(prefersSecond))
        r.submit("", nine = false, waitMillis = WAIT, paths = { paths }) {}
        assertFalse(r.settle(5L))
        assertEquals(1, r.snapshot().timeouts)
    }

    @Test fun settle_without_a_pending_job_returns_at_once() {
        val q = Queues()
        val r = started(q, FakeScorer(prefersSecond))
        r.invalidate()
        assertTrue(r.settle(1_000L))
        assertEquals(0, r.snapshot().timeouts)
    }

    @Test fun a_job_that_misses_its_wait_delivers_no_choice_and_drops_the_late_result() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        r.submit("", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add(it?.best ?: NONE) }
        val cancelsBefore = scorer.cancels
        q.fireTimers()
        q.runMain()
        assertEquals(listOf(NONE), chosen)
        assertEquals(1, r.snapshot().timeouts)
        assertEquals(cancelsBefore + 1, scorer.cancels)
        q.runWorker(); q.runMain()
        assertEquals(listOf(NONE), chosen)
        assertEquals(1, r.snapshot().timeouts)
    }

    @Test fun a_choice_that_arrives_in_time_makes_the_wait_a_no_op() {
        val q = Queues()
        val r = started(q, FakeScorer(prefersSecond))
        val chosen = ArrayList<String>()
        r.submit("", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add(it?.best ?: NONE) }
        q.runWorker()
        q.fireTimers()
        q.runMain()
        assertEquals(listOf("拟好"), chosen)
        assertEquals(0, r.snapshot().timeouts)
    }

    @Test fun the_wait_of_a_superseded_job_does_nothing() {
        val q = Queues()
        val r = started(q, FakeScorer(prefersSecond))
        val chosen = ArrayList<String>()
        r.submit("old", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add("old:" + it?.best) }
        val oldWait = q.timers.removeAt(0)
        r.submit("new", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add("new:" + it?.best) }
        oldWait.second()
        q.runMain()
        assertTrue(chosen.isEmpty())
        q.runWorker(); q.runMain()
        assertEquals(listOf("new:拟好"), chosen)
        assertEquals(0, r.snapshot().timeouts)
    }

    @Test fun settle_runs_the_timed_out_delivery() {
        val q = Queues()
        val r = started(q, FakeScorer(prefersSecond))
        val chosen = ArrayList<String>()
        r.submit("", nine = false, waitMillis = WAIT, paths = { paths }) { chosen.add(it?.best ?: NONE) }
        q.fireTimers()
        assertTrue(r.settle(0L))
        assertEquals(listOf(NONE), chosen)
        q.runMain()
        assertEquals(listOf(NONE), chosen)
    }

    @Test fun a_failed_load_reports_the_error_and_never_scores() {
        val q = Queues()
        val r = NeuralReranker(spec, { error("bad model") }, Executor { q.worker.add(it) }, Executor { q.main.add(it) }, q.schedule)
        r.start(); q.runWorker()
        assertFalse(r.ready)
        val snap = r.snapshot()
        assertEquals(NeuralReranker.Phase.FAILED, snap.phase)
        assertEquals("bad model", snap.error)
    }

    @Test fun closing_releases_the_scorer_on_the_worker() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        r.close()
        assertFalse(scorer.closed)
        q.runWorker()
        assertTrue(scorer.closed)
        assertEquals(NeuralReranker.Phase.CLOSED, r.snapshot().phase)
        assertFalse(r.ready)
    }

    @Test fun timings_are_recorded_per_run() {
        val q = Queues()
        var now = 0L
        val r = started(q, FakeScorer(prefersSecond)) { now }
        r.submit("", nine = false, waitMillis = WAIT, paths = { now += 40_000_000L; paths }) {}
        q.runWorker()
        r.submit("", nine = false, waitMillis = WAIT, paths = { now += 20_000_000L; paths }) {}
        q.runWorker()
        val snap = r.snapshot()
        assertEquals(2, snap.runs)
        assertEquals(20L, snap.lastMillis)
        assertEquals(30L, snap.meanMillis)
        assertEquals(40L, snap.maxMillis)
        assertNull(snap.error)
    }
}
