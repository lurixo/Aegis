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

import com.aegis.ime.decoder.Cand
import com.aegis.ime.dict.DecodeCancellation
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.neural.NeuralModelSpec
import com.aegis.ime.neural.NeuralReranker
import com.aegis.ime.neural.SentenceScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeuralRerankControllerTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private class Host : ImeHost {
        val commits = ArrayList<String>()
        var before = ""
        override fun commitText(text: CharSequence) {
            commits.add(text.toString())
            before += text
        }
        override fun deleteBackward() {}
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = before.takeLast(n)
    }

    private class Engine : CandidateEngine {
        val rankedCalls = ArrayList<String>()
        var leading: String? = null
        var rankedEntered: CountDownLatch? = null
        @Volatile var rankedRanOut = false
        override val supportsChinese: Boolean = true
        override fun candidates(composing: String, t9: Boolean): List<String> = words(composing, t9)
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
            words(composing, t9).map { Cand(it, composing.length) }
        override fun rankedSentences(composing: String, t9: Boolean, context: CharSequence, limit: Int): List<Pair<String, Double>> {
            rankedCalls.add("$composing|$t9|$context|$limit")
            rankedEntered?.takeIf { key(composing, t9) }?.let { entered ->
                entered.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline) {
                    DecodeCancellation.checkpoint()
                    Thread.sleep(1)
                }
                rankedRanOut = true
            }
            return if (key(composing, t9)) listOf("你好" to -10.0, "拟好" to -11.0, "你号" to -12.0) else emptyList()
        }
        private fun key(composing: String, t9: Boolean) = composing == (if (t9) "64426" else "nihao")
        private fun words(composing: String, t9: Boolean): List<String> =
            if (key(composing, t9)) listOfNotNull(leading, "你好", "拟好", "你") else emptyList()
    }

    private class EveryInputEngine(private val paths: Int = 3) : CandidateEngine {
        val rankedCalls = ArrayList<String>()
        override val supportsChinese: Boolean = true
        override fun candidates(composing: String, t9: Boolean): List<String> = words(composing)
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
            words(composing).map { Cand(it, composing.length) }
        override fun rankedSentences(composing: String, t9: Boolean, context: CharSequence, limit: Int): List<Pair<String, Double>> {
            rankedCalls.add("$composing|$t9")
            return listOf("你好" to -10.0, "拟好" to -11.0, "你号" to -12.0).take(paths).map { tag(it.first, composing) to it.second }
        }
        fun words(composing: String): List<String> =
            if (composing.isEmpty()) emptyList() else listOf("你好", "拟好", "你").map { tag(it, composing) }
        fun reranked(composing: String): List<String> = listOf("拟好", "你好", "你").map { tag(it, composing) }
        private fun tag(word: String, composing: String) = word + composing.length
    }

    private class Scorer : SentenceScorer {
        val contexts = ArrayList<String>()
        override fun score(context: String, candidates: List<String>): DoubleArray {
            contexts.add(context)
            return DoubleArray(candidates.size) { if (candidates[it].startsWith("拟好")) -1.0 else -5.0 }
        }
        override fun cancel() {}
        override fun close() {}
    }

    private class Queues(decodeExecutor: Executor? = null) {
        val decodeWorker = ArrayDeque<Runnable>()
        val decodeMain = ArrayDeque<Runnable>()
        val neuralWorker = ArrayDeque<Runnable>()
        val neuralMain = ArrayDeque<Runnable>()
        val timers = ArrayList<() -> Unit>()
        val schedule: (Long, () -> Unit) -> Unit = { _, task -> timers.add(task) }
        val lane = DecodeLane(decodeExecutor ?: Executor { decodeWorker.add(it) }, Executor { decodeMain.add(it) })
        fun decode() {
            while (decodeWorker.isNotEmpty() || decodeMain.isNotEmpty()) {
                while (decodeWorker.isNotEmpty()) decodeWorker.removeFirst().run()
                while (decodeMain.isNotEmpty()) decodeMain.removeFirst().run()
            }
        }
        fun neural() {
            while (neuralWorker.isNotEmpty() || neuralMain.isNotEmpty()) {
                while (neuralWorker.isNotEmpty()) neuralWorker.removeFirst().run()
                while (neuralMain.isNotEmpty()) neuralMain.removeFirst().run()
            }
        }
        fun fireTimers() {
            val due = timers.toList()
            timers.clear()
            due.forEach { it() }
        }
    }

    private val spec = NeuralModelSpec("test", alphaLetters = 5.0, alphaNine = 5.0, candidates = 10)

    private fun setup(
        engine: CandidateEngine = Engine(),
        host: Host = Host(),
        q: Queues = Queues(),
        view: InputView = InputView(ctx),
        reranker: (Queues) -> NeuralReranker = {
            NeuralReranker(spec, { Scorer() }, Executor { r -> it.neuralWorker.add(r) }, Executor { r -> it.neuralMain.add(r) }, it.schedule)
        },
    ): Triple<KeyboardController, Queues, NeuralReranker> {
        val c = KeyboardController(host, engine, q.lane).apply { attachView(view) }
        val r = reranker(q)
        r.start()
        q.neural()
        c.neural = r
        return Triple(c, q, r)
    }

    private fun decoded(c: KeyboardController): List<String> = c.candidateWords().filter { it.last().isDigit() }

    private fun type(c: KeyboardController, s: String) = s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }

    @Test fun the_decoded_list_waits_for_the_model_choice_and_shows_once() {
        val view = InputView(ctx)
        val (c, q, _) = setup(view = view)
        c.switchTextLayoutForTest(false)
        type(c, "nihao")
        q.decode()
        assertTrue("the decoder order is never shown on its own", c.candidateWords().isEmpty())
        assertTrue("the list on screen waits for the model", view.candidatesPendingForTest())
        q.neural()
        assertEquals(listOf("拟好", "你好", "你"), c.candidateWords())
        assertFalse(view.candidatesPendingForTest())
    }

    @Test fun a_model_that_misses_the_wait_leaves_the_decoder_order_in_place() {
        val host = Host()
        val view = InputView(ctx)
        val (c, q, r) = setup(host = host, view = view)
        c.switchTextLayoutForTest(false)
        type(c, "nihao")
        q.decode()
        q.fireTimers()
        q.neural()
        assertEquals(listOf("你好", "拟好", "你"), c.candidateWords())
        assertFalse(view.candidatesPendingForTest())
        assertEquals(1, r.snapshot().timeouts)
        c.onKey(Key(" ", action = KeyAction.SPACE))
        assertEquals(listOf("你好"), host.commits)
    }

    @Test fun nine_key_input_is_reranked_with_its_own_paths() {
        val engine = Engine()
        val (c, q, _) = setup(engine)
        c.switchTextLayoutForTest(true)
        type(c, "64426")
        q.decode()
        q.neural()
        assertEquals("拟好", c.candidateWords().first())
        assertTrue(engine.rankedCalls.last().startsWith("64426|true|"))
        assertTrue(engine.rankedCalls.last().endsWith("|20"))
    }

    @Test fun a_result_for_older_input_is_dropped() {
        val (c, q, _) = setup()
        c.switchTextLayoutForTest(false)
        type(c, "nihao")
        q.decode()
        type(c, "n")
        q.neural()
        q.decode()
        q.neural()
        assertTrue(c.candidateWords().isEmpty())
    }

    @Test fun a_leading_candidate_that_is_not_the_decoder_sentence_is_left_alone() {
        val engine = Engine().apply { leading = "你好啊" }
        val (c, q, _) = setup(engine)
        c.switchTextLayoutForTest(false)
        type(c, "nihao")
        q.decode()
        q.neural()
        assertEquals(listOf("你好啊", "你好", "拟好", "你"), c.candidateWords())
    }

    @Test fun the_model_reads_the_text_before_the_cursor() {
        val host = Host().apply { before = "今天我想说" }
        val scorer = Scorer()
        val (c, q, _) = setup(host = host, reranker = {
            NeuralReranker(spec, { scorer }, Executor { r -> it.neuralWorker.add(r) }, Executor { r -> it.neuralMain.add(r) }, it.schedule)
        })
        c.switchTextLayoutForTest(false)
        type(c, "nihao")
        q.decode()
        q.neural()
        assertEquals(listOf("今天我想说"), scorer.contexts)
    }

    private fun theModelContextStaysStableAcrossCommits(nine: Boolean) {
        val host = Host().apply { before = "会议记录已经整理好了。我们今天下午三点在学校门口集合，然后一起坐车去" }
        val scorer = Scorer()
        val (c, q, _) = setup(host = host, reranker = {
            NeuralReranker(spec, { scorer }, Executor { r -> it.neuralWorker.add(r) }, Executor { r -> it.neuralMain.add(r) }, it.schedule)
        })
        c.switchTextLayoutForTest(nine)
        val input = if (nine) "64426" else "nihao"
        repeat(6) {
            type(c, input)
            q.decode()
            q.neural()
            c.onKey(Key(" ", action = KeyAction.SPACE))
            q.decode()
            q.neural()
        }
        assertEquals(List(6) { "拟好" }, host.commits)
        val opening = "我们今天下午三点在学校门口集合，然后一起坐车去"
        assertEquals(List(5) { opening + "拟好".repeat(it) } + ("然后一起坐车去" + "拟好".repeat(5)), scorer.contexts)
    }

    @Test fun the_model_context_stays_stable_across_commits_on_26key() = theModelContextStaysStableAcrossCommits(nine = false)

    @Test fun the_model_context_stays_stable_across_commits_on_9key() = theModelContextStaysStableAcrossCommits(nine = true)

    @Test fun space_waits_for_the_pending_model_choice() {
        val host = Host()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val (c, q, r) = setup(host = host, reranker = {
                NeuralReranker(spec, { Scorer() }, worker, Executor { r -> it.neuralMain.add(r) }, it.schedule)
            })
            worker.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(r.ready)
            c.switchTextLayoutForTest(false)
            type(c, "nihao")
            q.decode()
            c.onKey(Key(" ", action = KeyAction.SPACE))
            assertEquals(listOf("拟好"), host.commits)
        } finally {
            worker.shutdown()
            worker.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun theDecodeCarriesThePathsTheModelScores(nine: Boolean) {
        val engine = Engine()
        val (c, q, _) = setup(engine)
        c.switchTextLayoutForTest(nine)
        val input = if (nine) "64426" else "nihao"
        type(c, input)
        q.decode()
        assertEquals(listOf("$input|$nine||20"), engine.rankedCalls)
        q.neural()
        assertEquals(listOf("$input|$nine||20"), engine.rankedCalls)
        assertEquals("拟好", c.candidateWords().first())
    }

    @Test fun the_decode_carries_the_paths_the_model_scores_on_26key() = theDecodeCarriesThePathsTheModelScores(nine = false)

    @Test fun the_decode_carries_the_paths_the_model_scores_on_9key() = theDecodeCarriesThePathsTheModelScores(nine = true)

    private fun inputOfOneOrTwoKeysIsShownWithoutTheModel(nine: Boolean) {
        val engine = EveryInputEngine()
        val scorer = Scorer()
        val view = InputView(ctx)
        val (c, q, r) = setup(engine, view = view, reranker = {
            NeuralReranker(spec, { scorer }, Executor { r -> it.neuralWorker.add(r) }, Executor { r -> it.neuralMain.add(r) }, it.schedule)
        })
        c.switchTextLayoutForTest(nine)
        val input = if (nine) "644" else "nih"
        for (keys in 1..2) {
            type(c, input.substring(keys - 1, keys))
            q.decode()
            assertEquals("$keys keys", engine.words(input.take(keys)), decoded(c))
            assertFalse("$keys keys", view.candidatesPendingForTest())
            assertTrue("$keys keys", q.neuralWorker.isEmpty() && q.neuralMain.isEmpty() && q.timers.isEmpty())
        }
        assertTrue("no paths are generated for input the model never sees", engine.rankedCalls.isEmpty())
        type(c, input.substring(2))
        q.decode()
        assertEquals("the third key is held for the model", engine.words(input.take(2)), decoded(c))
        assertTrue(view.candidatesPendingForTest())
        assertEquals(listOf("$input|$nine"), engine.rankedCalls)
        assertEquals(1, q.neuralWorker.size)
        q.neural()
        assertEquals(engine.reranked(input), decoded(c))
        assertFalse(view.candidatesPendingForTest())
        assertEquals(1, scorer.contexts.size)
        assertEquals(1, r.snapshot().runs)
    }

    @Test fun input_of_one_or_two_keys_is_shown_without_the_model_on_26key() =
        inputOfOneOrTwoKeysIsShownWithoutTheModel(nine = false)

    @Test fun input_of_one_or_two_keys_is_shown_without_the_model_on_9key() =
        inputOfOneOrTwoKeysIsShownWithoutTheModel(nine = true)

    private fun aDecodeWithFewerThanTwoPathsIsShownWithoutTheModel(nine: Boolean) {
        val input = if (nine) "64426" else "nihao"
        for (paths in 0..1) {
            val engine = EveryInputEngine(paths)
            val view = InputView(ctx)
            val (c, q, r) = setup(engine, view = view)
            c.switchTextLayoutForTest(nine)
            type(c, input)
            q.decode()
            assertEquals("$paths paths", listOf("$input|$nine"), engine.rankedCalls)
            assertEquals("$paths paths", engine.words(input), decoded(c))
            assertFalse("$paths paths", view.candidatesPendingForTest())
            assertTrue("$paths paths", q.neuralWorker.isEmpty() && q.neuralMain.isEmpty() && q.timers.isEmpty())
            assertEquals(0, r.snapshot().runs)
        }
    }

    @Test fun a_decode_with_fewer_than_two_paths_is_shown_without_the_model_on_26key() =
        aDecodeWithFewerThanTwoPathsIsShownWithoutTheModel(nine = false)

    @Test fun a_decode_with_fewer_than_two_paths_is_shown_without_the_model_on_9key() =
        aDecodeWithFewerThanTwoPathsIsShownWithoutTheModel(nine = true)

    private fun shortInputIsNotOverwrittenByAnOlderModelChoice(nine: Boolean, scoredFirst: Boolean) {
        val engine = EveryInputEngine()
        val scorer = Scorer()
        val view = InputView(ctx)
        val (c, q, r) = setup(engine, view = view, reranker = {
            NeuralReranker(spec, { scorer }, Executor { r -> it.neuralWorker.add(r) }, Executor { r -> it.neuralMain.add(r) }, it.schedule)
        })
        c.switchTextLayoutForTest(nine)
        val input = if (nine) "644" else "nih"
        type(c, input)
        q.decode()
        assertTrue(view.candidatesPendingForTest())
        assertEquals(1, q.neuralWorker.size)
        if (scoredFirst) {
            q.neuralWorker.removeFirst().run()
            assertEquals(1, q.neuralMain.size)
        }
        c.onKey(Key("", action = KeyAction.BACKSPACE))
        q.decode()
        val shown = engine.words(input.take(2))
        assertEquals(shown, decoded(c))
        assertFalse(view.candidatesPendingForTest())
        q.neural()
        q.fireTimers()
        q.neural()
        assertEquals(shown, decoded(c))
        assertFalse(view.candidatesPendingForTest())
        assertEquals(if (scoredFirst) 1 else 0, scorer.contexts.size)
        assertEquals(if (scoredFirst) 0 else 1, r.snapshot().cancelled)
        assertEquals(0, r.snapshot().timeouts)
    }

    @Test fun short_input_is_not_overwritten_by_an_older_queued_run_on_26key() =
        shortInputIsNotOverwrittenByAnOlderModelChoice(nine = false, scoredFirst = false)

    @Test fun short_input_is_not_overwritten_by_an_older_queued_run_on_9key() =
        shortInputIsNotOverwrittenByAnOlderModelChoice(nine = true, scoredFirst = false)

    @Test fun short_input_is_not_overwritten_by_an_older_finished_run_on_26key() =
        shortInputIsNotOverwrittenByAnOlderModelChoice(nine = false, scoredFirst = true)

    @Test fun short_input_is_not_overwritten_by_an_older_finished_run_on_9key() =
        shortInputIsNotOverwrittenByAnOlderModelChoice(nine = true, scoredFirst = true)

    private fun firstKeyTimeRunsUntilTheCandidatesOfTheCompositionShow(nine: Boolean) {
        var now = 0L
        val engine = EveryInputEngine()
        val (c, q, r) = setup(engine, reranker = {
            NeuralReranker(spec, { Scorer() }, Executor { r -> it.neuralWorker.add(r) }, Executor { r -> it.neuralMain.add(r) }, it.schedule) { now }
        })
        c.switchTextLayoutForTest(nine)
        val input = if (nine) "644" else "nih"

        now = 100_000_000L
        type(c, input.take(1))
        now = 107_000_000L
        assertEquals("nothing is shown before the decode lands", 0, r.snapshot().firstKeys)
        q.decode()
        assertEquals(engine.words(input.take(1)), decoded(c))
        assertEquals(1, r.snapshot().firstKeys)
        assertEquals(7L, r.snapshot().lastFirstKeyMillis)
        now = 200_000_000L
        type(c, input.substring(1, 2))
        now = 250_000_000L
        q.decode()
        assertEquals("later keys of a composition are not measured", 1, r.snapshot().firstKeys)
        c.onKey(Key(" ", action = KeyAction.SPACE))
        q.decode()
        q.neural()

        now = 1_000_000_000L
        type(c, input)
        now = 1_020_000_000L
        q.decode()
        assertEquals("a list held for the model is not on screen yet", 1, r.snapshot().firstKeys)
        now = 1_045_000_000L
        q.neural()
        assertEquals(engine.reranked(input), decoded(c))
        c.onKey(Key(" ", action = KeyAction.SPACE))
        q.decode()
        q.neural()

        now = 2_000_000_000L
        type(c, input.take(1))
        c.onKey(Key("", action = KeyAction.BACKSPACE))
        now = 2_500_000_000L
        q.decode()
        q.neural()
        type(c, input.take(1))
        now = 2_503_000_000L
        q.decode()

        val snap = r.snapshot()
        assertEquals("a composition abandoned before its candidates showed is not counted", 3, snap.firstKeys)
        assertEquals(3L, snap.lastFirstKeyMillis)
        assertEquals(18L, snap.meanFirstKeyMillis)
        assertEquals(45L, snap.maxFirstKeyMillis)
    }

    @Test fun first_key_time_runs_until_the_candidates_of_the_composition_show_on_26key() =
        firstKeyTimeRunsUntilTheCandidatesOfTheCompositionShow(nine = false)

    @Test fun first_key_time_runs_until_the_candidates_of_the_composition_show_on_9key() =
        firstKeyTimeRunsUntilTheCandidatesOfTheCompositionShow(nine = true)

    private fun nextKeyCancelsAStalePathGeneration(nine: Boolean) {
        val engine = Engine().apply { rankedEntered = CountDownLatch(1) }
        val decodeWorker = Executors.newSingleThreadExecutor()
        try {
            val (c, q, _) = setup(engine, q = Queues(decodeWorker))
            c.switchTextLayoutForTest(nine)
            val input = if (nine) "64426" else "nihao"
            type(c, input)
            assertTrue(engine.rankedEntered!!.await(5, TimeUnit.SECONDS))
            val before = System.nanoTime()
            type(c, input.take(1))
            decodeWorker.submit {}.get(15, TimeUnit.SECONDS)
            val waited = System.nanoTime() - before
            assertTrue("waited ${waited / 1_000_000L} ms to decode", waited < TimeUnit.SECONDS.toNanos(2))
            q.decode()
            q.neural()
            assertFalse(engine.rankedRanOut)
            assertEquals(1, engine.rankedCalls.count { it.startsWith("$input|$nine|") })
            assertTrue(engine.rankedCalls.last().startsWith("$input${input.take(1)}|$nine|"))
            assertTrue(c.candidateWords().isEmpty())
        } finally {
            decodeWorker.shutdown()
            decodeWorker.awaitTermination(15, TimeUnit.SECONDS)
        }
    }

    @Test fun the_next_key_cancels_a_stale_path_generation_on_the_decode_lane_on_26key() =
        nextKeyCancelsAStalePathGeneration(nine = false)

    @Test fun the_next_key_cancels_a_stale_path_generation_on_the_decode_lane_on_9key() =
        nextKeyCancelsAStalePathGeneration(nine = true)

    @Test fun space_falls_back_to_the_decoder_sentence_when_the_model_never_answers() {
        val host = Host()
        val (c, q, r) = setup(host = host)
        c.switchTextLayoutForTest(false)
        type(c, "nihao")
        q.decode()
        c.onKey(Key(" ", action = KeyAction.SPACE))
        assertEquals(listOf("你好"), host.commits)
        q.fireTimers()
        q.neural()
        assertEquals(listOf("你好"), host.commits)
        assertEquals(1, r.snapshot().timeouts)
    }

    @Test fun replacing_the_reranker_while_a_list_waits_shows_the_decoder_order() {
        val host = Host()
        val view = InputView(ctx)
        val (c, q, _) = setup(host = host, view = view)
        c.switchTextLayoutForTest(false)
        type(c, "nihao")
        q.decode()
        assertTrue(view.candidatesPendingForTest())

        c.neural = null

        assertEquals(listOf("你好", "拟好", "你"), c.candidateWords())
        assertFalse(view.candidatesPendingForTest())
        c.onKey(Key(" ", action = KeyAction.SPACE))
        assertEquals(listOf("你好"), host.commits)
    }

    @Test fun without_a_reranker_the_decoder_order_is_kept() {
        val host = Host()
        val q = Queues()
        val c = KeyboardController(host, Engine(), q.lane).apply { attachView(InputView(ctx)) }
        c.switchTextLayoutForTest(false)
        type(c, "nihao")
        q.decode()
        c.onKey(Key(" ", action = KeyAction.SPACE))
        assertEquals(listOf("你好"), host.commits)
    }
}
