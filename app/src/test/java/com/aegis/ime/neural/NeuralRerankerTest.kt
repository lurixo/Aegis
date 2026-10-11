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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeuralRerankerTest {

    private class FakeScorer(
        private val scores: Map<String, Double>,
        private val elapse: () -> Unit = {},
    ) : SentenceScorer {
        val contexts = ArrayList<String>()
        val batches = ArrayList<List<String>>()
        val calls = ArrayList<String>()
        var warmFails = false
        var cancels = 0
        var aborted = false
        var closed = false

        override fun score(context: String, candidates: List<String>): DoubleArray? {
            contexts.add(context)
            batches.add(candidates)
            calls.add("score:$context")
            elapse()
            if (aborted) return null
            return DoubleArray(candidates.size) { scores.getValue(candidates[it]) }
        }

        override fun warm(context: String) {
            calls.add("warm:$context")
            if (warmFails) error("bad prefix")
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

    private val letters = ('a'..'z').joinToString("") + ('A'..'Z').joinToString("")

    private val prose = "我们今天下午三点在学校门口集合，然后一起坐公交车去博物馆参观新开的恐龙化石展览。看完以后大家可以自由活动，五点之前回到门口"

    private fun window(doc: String) = NeuralReranker.contextWindow(doc.takeLast(65), fromStart = doc.length <= 64)

    private fun windowBreaks(doc: String): Int {
        var previous = ""
        var breaks = 0
        for (n in 2..doc.length step 2) {
            val w = window(doc.take(n))
            if (!w.startsWith(previous)) breaks++
            previous = w
        }
        return breaks
    }

    @Test fun an_empty_context_gives_an_empty_window() {
        assertEquals("", NeuralReranker.contextWindow("", fromStart = true))
        assertEquals("", NeuralReranker.contextWindow("", fromStart = false))
    }

    @Test fun text_from_the_start_up_to_the_maximum_is_kept_whole() {
        assertEquals("今天我想说", NeuralReranker.contextWindow("今天我想说", fromStart = true))
        val full = prose.take(NeuralReranker.MAX_CONTEXT_CHARS)
        assertEquals(full, NeuralReranker.contextWindow(full, fromStart = true))
    }

    @Test fun the_window_starts_after_the_earliest_punctuation_within_the_maximum() {
        val text = "会议记录已经整理好了。我们今天下午三点在学校门口集合，然后一起坐车去"
        assertEquals("我们今天下午三点在学校门口集合，然后一起坐车去", NeuralReranker.contextWindow(text, fromStart = false))
        assertEquals("b".repeat(20), NeuralReranker.contextWindow("a".repeat(20) + "\n" + "b".repeat(20), fromStart = false))
    }

    @Test fun without_punctuation_the_start_snaps_to_blocks_from_the_document_start() {
        assertEquals(letters.substring(16, 33), NeuralReranker.contextWindow(letters.take(33), fromStart = true))
        assertEquals(letters.substring(16, 47), NeuralReranker.contextWindow(letters.take(47), fromStart = true))
        assertEquals(letters.substring(32, 48), NeuralReranker.contextWindow(letters.take(48), fromStart = true))
    }

    @Test fun a_recent_punctuation_snaps_the_start_one_block_before_it() {
        val text = letters.take(30) + "，" + letters.substring(30, 35)
        assertEquals(text.substring(15), NeuralReranker.contextWindow(text, fromStart = false))
    }

    @Test fun a_read_without_any_anchor_keeps_the_last_maximum() {
        assertEquals(letters.substring(8, 40), NeuralReranker.contextWindow(letters.take(40), fromStart = false))
    }

    @Test fun the_window_is_a_suffix_between_the_minimum_and_the_maximum() {
        for (doc in listOf(prose.repeat(3), letters.repeat(3))) {
            for (n in 0..doc.length) {
                val typed = doc.take(n)
                val w = window(typed)
                assertTrue(typed.endsWith(w))
                assertTrue(w.length <= NeuralReranker.MAX_CONTEXT_CHARS)
                assertTrue(w.length >= minOf(n, NeuralReranker.MIN_CONTEXT_CHARS))
            }
        }
    }

    @Test fun consecutive_commits_extend_the_previous_window_between_coarse_steps() {
        assertEquals(16, windowBreaks(prose.repeat(3)))
        assertEquals(3, windowBreaks(letters.repeat(2).take(64)))
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
        r.submit("上文", nine = false, waitMillis = WAIT, paths = paths) { chosen.add(it) }
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
        r.submit("", nine = false, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
        q.runWorker(); q.runMain()
        r.submit("", nine = true, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
        q.runWorker(); q.runMain()
        assertEquals(listOf("拟好", "你好"), chosen)
    }

    @Test fun only_the_first_candidates_of_the_spec_are_scored() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = NeuralReranker(spec.copy(candidates = 2), { scorer }, Executor { q.worker.add(it) }, Executor { q.main.add(it) }, q.schedule)
        r.start(); q.runWorker()
        r.submit("", nine = false, waitMillis = WAIT, paths = paths) {}
        q.runWorker()
        assertEquals(listOf("你好", "拟好"), scorer.batches.single())
    }

    @Test fun invalidating_before_the_worker_runs_drops_the_job() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        r.submit("", nine = false, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
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
        r.submit("", nine = false, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
        q.runWorker()
        r.invalidate()
        q.runMain()
        assertTrue(chosen.isEmpty())
    }

    private fun newerJobSupersedesAnOlderOne(nine: Boolean) {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        r.submit("old", nine = nine, waitMillis = WAIT, paths = paths) { chosen.add("old:" + it?.best) }
        r.submit("new", nine = nine, waitMillis = WAIT, paths = paths) { chosen.add("new:" + it?.best) }
        q.runWorker(); q.runMain()
        assertEquals(listOf("new:拟好"), chosen)
        assertEquals(listOf("new"), scorer.contexts)
        assertEquals(1, r.snapshot().cancelled)
    }

    @Test fun a_newer_job_supersedes_an_older_one_on_26key() = newerJobSupersedesAnOlderOne(nine = false)

    @Test fun a_newer_job_supersedes_an_older_one_on_9key() = newerJobSupersedesAnOlderOne(nine = true)

    private fun abortedScoreDeliversNothing(nine: Boolean) {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond).apply { aborted = true }
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        r.submit("", nine = nine, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
        q.runWorker(); q.runMain()
        assertTrue(chosen.isEmpty())
        assertEquals(0, r.snapshot().runs)
        assertEquals(0, r.snapshot().cancelled)
    }

    @Test fun an_aborted_score_delivers_nothing_on_26key() = abortedScoreDeliversNothing(nine = false)

    @Test fun an_aborted_score_delivers_nothing_on_9key() = abortedScoreDeliversNothing(nine = true)

    private fun scoreAbortedByANewerKeyCountsAsCancelled(nine: Boolean) {
        val q = Queues()
        val chosen = ArrayList<String>()
        lateinit var r: NeuralReranker
        val scorer = object : SentenceScorer {
            override fun score(context: String, candidates: List<String>): DoubleArray? {
                if (context == "old") {
                    r.submit("new", nine = nine, waitMillis = WAIT, paths = paths) { chosen.add("new:" + it?.best) }
                    return null
                }
                return DoubleArray(candidates.size) { prefersSecond.getValue(candidates[it]) }
            }
            override fun warm(context: String) {}
            override fun cancel() {}
            override fun close() {}
        }
        r = started(q, scorer)
        r.submit("old", nine = nine, waitMillis = WAIT, paths = paths) { chosen.add("old:" + it?.best) }
        q.runWorker(); q.runMain()
        assertEquals(listOf("new:拟好"), chosen)
        val snap = r.snapshot()
        assertEquals(1, snap.runs)
        assertEquals(1, snap.cancelled)
    }

    @Test fun a_score_aborted_by_a_newer_key_counts_as_cancelled_on_26key() = scoreAbortedByANewerKeyCountsAsCancelled(nine = false)

    @Test fun a_score_aborted_by_a_newer_key_counts_as_cancelled_on_9key() = scoreAbortedByANewerKeyCountsAsCancelled(nine = true)

    private fun invalidatingAQueuedJobCountsItAsCancelled(nine: Boolean) {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        r.submit("", nine = nine, waitMillis = WAIT, paths = paths) {}
        r.invalidate()
        q.runWorker(); q.runMain()
        assertTrue(scorer.batches.isEmpty())
        assertEquals(0, r.snapshot().runs)
        assertEquals(1, r.snapshot().cancelled)
    }

    @Test fun invalidating_a_queued_job_counts_it_as_cancelled_on_26key() =
        invalidatingAQueuedJobCountsItAsCancelled(nine = false)

    @Test fun invalidating_a_queued_job_counts_it_as_cancelled_on_9key() =
        invalidatingAQueuedJobCountsItAsCancelled(nine = true)

    @Test fun fewer_than_two_paths_skip_the_model() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        r.submit("", nine = false, waitMillis = WAIT, paths = paths.take(1)) { chosen.add(it?.best ?: NONE) }
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
        r.submit("", nine = false, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
        q.runWorker()
        assertTrue(r.settle(0L))
        assertEquals(listOf("拟好"), chosen)
        q.runMain()
        assertEquals(listOf("拟好"), chosen)
    }

    @Test fun settle_times_out_when_the_job_has_not_run() {
        val q = Queues()
        val r = started(q, FakeScorer(prefersSecond))
        r.submit("", nine = false, waitMillis = WAIT, paths = paths) {}
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

    private fun jobThatMissesItsWaitDeliversNoChoiceAndDropsTheLateResult(nine: Boolean) {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        val chosen = ArrayList<String>()
        r.submit("", nine = nine, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
        val cancelsBefore = scorer.cancels
        q.fireTimers()
        q.runMain()
        assertEquals(listOf(NONE), chosen)
        assertEquals(1, r.snapshot().timeouts)
        assertEquals(cancelsBefore + 1, scorer.cancels)
        q.runWorker(); q.runMain()
        assertEquals(listOf(NONE), chosen)
        assertEquals(1, r.snapshot().timeouts)
        assertEquals(0, r.snapshot().cancelled)
    }

    @Test fun a_job_that_misses_its_wait_delivers_no_choice_and_drops_the_late_result_on_26key() =
        jobThatMissesItsWaitDeliversNoChoiceAndDropsTheLateResult(nine = false)

    @Test fun a_job_that_misses_its_wait_delivers_no_choice_and_drops_the_late_result_on_9key() =
        jobThatMissesItsWaitDeliversNoChoiceAndDropsTheLateResult(nine = true)

    @Test fun a_choice_that_arrives_in_time_makes_the_wait_a_no_op() {
        val q = Queues()
        val r = started(q, FakeScorer(prefersSecond))
        val chosen = ArrayList<String>()
        r.submit("", nine = false, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
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
        r.submit("old", nine = false, waitMillis = WAIT, paths = paths) { chosen.add("old:" + it?.best) }
        val oldWait = q.timers.removeAt(0)
        r.submit("new", nine = false, waitMillis = WAIT, paths = paths) { chosen.add("new:" + it?.best) }
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
        r.submit("", nine = false, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
        q.fireTimers()
        assertTrue(r.settle(0L))
        assertEquals(listOf(NONE), chosen)
        q.runMain()
        assertEquals(listOf(NONE), chosen)
    }

    @Test fun a_warm_loads_the_context_once_outside_the_statistics() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        r.warm("上文")
        r.warm("上文")
        assertEquals(1, q.worker.size)
        assertTrue(q.timers.isEmpty())
        assertTrue(r.settle(1_000L))
        q.runWorker()
        r.warm("上文")
        assertTrue(q.worker.isEmpty())
        assertTrue(q.main.isEmpty())
        assertEquals(listOf("warm:上文"), scorer.calls)
        val snap = r.snapshot()
        assertEquals(0, snap.runs)
        assertEquals(0, snap.timeouts)
        assertEquals(0, snap.cancelled)
        assertNull(snap.error)
    }

    private fun jobForTheWarmedContextRunsBehindTheWarm(nine: Boolean) {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        r.warm("上文")
        val cancels = scorer.cancels
        r.invalidate()
        r.warm("上文")
        r.invalidate()
        val chosen = ArrayList<String>()
        r.submit("上文", nine = nine, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
        assertEquals("later keys leave the warm running", cancels, scorer.cancels)
        assertEquals(2, q.worker.size)
        q.runWorker(); q.runMain()
        assertEquals(listOf("warm:上文", "score:上文"), scorer.calls)
        assertEquals(listOf("拟好"), chosen)
        assertEquals(1, r.snapshot().runs)
        assertEquals(0, r.snapshot().cancelled)
        r.invalidate()
        assertEquals("a finished warm no longer shields the scorer", cancels + 1, scorer.cancels)
    }

    @Test fun a_job_for_the_warmed_context_runs_behind_the_warm_on_26key() = jobForTheWarmedContextRunsBehindTheWarm(nine = false)

    @Test fun a_job_for_the_warmed_context_runs_behind_the_warm_on_9key() = jobForTheWarmedContextRunsBehindTheWarm(nine = true)

    @Test fun a_warm_for_another_context_replaces_the_one_in_flight() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        r.warm("旧")
        val cancels = scorer.cancels
        r.invalidate()
        r.warm("新")
        assertEquals(cancels + 1, scorer.cancels)
        q.runWorker()
        assertEquals(listOf("warm:新"), scorer.calls)
        assertEquals(0, r.snapshot().cancelled)
    }

    private fun jobForAnotherContextCancelsTheWarm(nine: Boolean) {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        r.warm("旧")
        val cancels = scorer.cancels
        r.invalidate()
        val chosen = ArrayList<String>()
        r.submit("新", nine = nine, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
        assertEquals(cancels + 1, scorer.cancels)
        q.runWorker(); q.runMain()
        assertEquals(listOf("score:新"), scorer.calls)
        assertEquals(listOf("拟好"), chosen)
        r.warm("旧")
        q.runWorker()
        assertEquals(listOf("score:新", "warm:旧"), scorer.calls)
        assertEquals(0, r.snapshot().cancelled)
    }

    @Test fun a_job_for_another_context_cancels_the_warm_on_26key() = jobForAnotherContextCancelsTheWarm(nine = false)

    @Test fun a_job_for_another_context_cancels_the_warm_on_9key() = jobForAnotherContextCancelsTheWarm(nine = true)

    @Test fun a_wait_that_runs_out_behind_a_warm_leaves_the_warm_running() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = started(q, scorer)
        r.warm("上文")
        val chosen = ArrayList<String>()
        r.submit("上文", nine = false, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
        val cancels = scorer.cancels
        q.fireTimers()
        q.runMain()
        assertEquals(listOf(NONE), chosen)
        assertEquals(1, r.snapshot().timeouts)
        assertEquals(cancels, scorer.cancels)
    }

    @Test fun a_failed_warm_is_silent_and_scoring_still_works() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond).apply { warmFails = true }
        val r = started(q, scorer)
        r.warm("上文")
        q.runWorker(); q.runMain()
        assertNull(r.snapshot().error)
        assertTrue(r.ready)
        val chosen = ArrayList<String>()
        r.submit("上文", nine = false, waitMillis = WAIT, paths = paths) { chosen.add(it?.best ?: NONE) }
        q.runWorker(); q.runMain()
        assertEquals(listOf("拟好"), chosen)
        assertEquals(listOf("warm:上文", "score:上文"), scorer.calls)
    }

    @Test fun a_reranker_that_is_not_ready_does_not_warm() {
        val q = Queues()
        val scorer = FakeScorer(prefersSecond)
        val r = NeuralReranker(spec, { scorer }, Executor { q.worker.add(it) }, Executor { q.main.add(it) }, q.schedule)
        r.start()
        r.warm("上文")
        assertEquals(1, q.worker.size)
        q.runWorker()
        r.warm("上文")
        r.close()
        q.runWorker()
        assertTrue(scorer.calls.isEmpty())
        assertTrue(scorer.closed)
        r.warm("上文")
        assertTrue(q.worker.isEmpty())
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

    @Test fun first_key_time_runs_from_the_key_to_its_candidates() {
        var now = 0L
        val r = started(Queues(), FakeScorer(prefersSecond)) { now }
        r.firstKeyShown()
        assertEquals(0, r.snapshot().firstKeys)
        now = 10_000_000L
        r.firstKeyDown()
        now = 40_000_000L
        r.firstKeyShown()
        now = 90_000_000L
        r.firstKeyShown()
        assertEquals(1, r.snapshot().firstKeys)
        now = 100_000_000L
        r.firstKeyDown()
        now = 105_000_000L
        r.firstKeyDown()
        now = 115_000_000L
        r.firstKeyShown()
        now = 200_000_000L
        r.firstKeyDown()
        r.firstKeyDropped()
        now = 900_000_000L
        r.firstKeyShown()
        val snap = r.snapshot()
        assertEquals(2, snap.firstKeys)
        assertEquals(10L, snap.lastFirstKeyMillis)
        assertEquals(20L, snap.meanFirstKeyMillis)
        assertEquals(30L, snap.maxFirstKeyMillis)
        assertEquals(0, snap.runs)
    }

    @Test fun timings_are_recorded_per_run() {
        val q = Queues()
        var now = 0L
        var step = 40_000_000L
        val r = started(q, FakeScorer(prefersSecond) { now += step }) { now }
        r.submit("", nine = false, waitMillis = WAIT, paths = paths) {}
        q.runWorker()
        step = 20_000_000L
        r.submit("", nine = false, waitMillis = WAIT, paths = paths) {}
        q.runWorker()
        val snap = r.snapshot()
        assertEquals(2, snap.runs)
        assertEquals(20L, snap.lastMillis)
        assertEquals(30L, snap.meanMillis)
        assertEquals(40L, snap.maxMillis)
        assertNull(snap.error)
    }

    private fun queueWaitRunsFromSubmitToTheStartOfTheRun(nine: Boolean) {
        val q = Queues()
        var now = 0L
        var step = 5_000_000L
        val r = started(q, FakeScorer(prefersSecond) { now += step }) { now }
        r.submit("", nine = nine, waitMillis = WAIT, paths = paths) {}
        now += 30_000_000L
        q.runWorker()
        step = 7_000_000L
        r.submit("", nine = nine, waitMillis = WAIT, paths = paths) {}
        now += 10_000_000L
        q.runWorker()
        val snap = r.snapshot()
        assertEquals(2, snap.runs)
        assertEquals(10L, snap.lastQueueMillis)
        assertEquals(20L, snap.meanQueueMillis)
        assertEquals(30L, snap.maxQueueMillis)
        assertEquals(7L, snap.lastMillis)
        assertEquals(6L, snap.meanMillis)
        assertEquals(7L, snap.maxMillis)
        assertEquals(0, snap.cancelled)
    }

    @Test fun queue_wait_runs_from_submit_to_the_start_of_the_run_on_26key() = queueWaitRunsFromSubmitToTheStartOfTheRun(nine = false)

    @Test fun queue_wait_runs_from_submit_to_the_start_of_the_run_on_9key() = queueWaitRunsFromSubmitToTheStartOfTheRun(nine = true)
}
