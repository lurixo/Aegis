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
        val reads = ArrayList<Int>()
        var before = ""
        override fun commitText(text: CharSequence) {
            commits.add(text.toString())
            before += text
        }
        override fun deleteBackward() {}
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence {
            reads.add(n)
            return before.takeLast(n)
        }
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

    private class InitialsEngine(private val initials: List<String>) : CandidateEngine {
        val spelled = listOf("你好", "你号", "泥好", "尼豪", "拟好", "你")
        override val supportsChinese: Boolean = true
        override fun candidates(composing: String, t9: Boolean): List<String> =
            if (t9 && composing == "64426") spelled else emptyList()
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
            candidates(composing, t9).map { Cand(it, composing.length) }
        override fun rankedSentences(composing: String, t9: Boolean, context: CharSequence, limit: Int): List<Pair<String, Double>> =
            if (t9 && composing == "64426") listOf("你好" to -10.0, "拟好" to -11.0, "你号" to -12.0) else emptyList()
        override fun initialWords(digits: String): List<Cand> =
            if (digits == "64426") initials.map { Cand(it, digits.length) } else emptyList()
    }

    private class Scorer : SentenceScorer {
        val contexts = ArrayList<String>()
        val calls = ArrayList<String>()
        var cancels = 0
        override fun score(context: String, candidates: List<String>): DoubleArray {
            contexts.add(context)
            calls.add("score:$context")
            return DoubleArray(candidates.size) { if (candidates[it].startsWith("拟好")) -1.0 else -5.0 }
        }
        override fun warm(context: String) {
            calls.add("warm:$context")
        }
        override fun cancel() {
            cancels++
        }
        override fun resume() {}
        override fun close() {}
    }

    private class Queues(decodeExecutor: Executor? = null) {
        val decodeWorker = ArrayDeque<Runnable>()
        val decodeMain = ArrayDeque<Runnable>()
        val neuralWorker = ArrayDeque<Runnable>()
        val neuralMain = ArrayDeque<Runnable>()
        val timers = ArrayList<() -> Unit>()
        val delays = ArrayList<Long>()
        val schedule: (Long, () -> Unit) -> Unit = { millis, task ->
            delays.add(millis)
            timers.add(task)
        }
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

    private fun typeWarmed(c: KeyboardController, q: Queues, s: String) {
        type(c, s.take(1))
        q.neural()
        type(c, s.drop(1))
    }

    @Test fun the_decoded_list_shows_at_once_and_the_model_choice_moves_to_the_front() {
        val view = InputView(ctx)
        val (c, q, r) = setup(view = view)
        c.switchTextLayoutForTest(false)
        typeWarmed(c, q, "nihao")
        q.decode()
        assertEquals("the decoder order shows as soon as the decode lands", listOf("你好", "拟好", "你"), c.candidateWords())
        assertFalse("the list on screen does not wait for the model", view.candidatesPendingForTest())
        assertEquals("the model choice is moved within 100 ms of the submit", listOf(100L), q.delays)
        q.neural()
        assertEquals(listOf("拟好", "你好", "你"), c.candidateWords())
        assertFalse(view.candidatesPendingForTest())
        assertEquals(1, r.snapshot().runs)
        assertEquals(0, r.snapshot().timeouts)
    }

    @Test fun a_model_that_misses_the_wait_leaves_the_decoder_order_in_place() {
        val host = Host()
        val view = InputView(ctx)
        val (c, q, r) = setup(host = host, view = view)
        c.switchTextLayoutForTest(false)
        typeWarmed(c, q, "nihao")
        q.decode()
        assertEquals(listOf("你好", "拟好", "你"), c.candidateWords())
        q.fireTimers()
        q.neural()
        assertEquals("the late model choice does not move the list", listOf("你好", "拟好", "你"), c.candidateWords())
        assertFalse(view.candidatesPendingForTest())
        assertEquals(1, r.snapshot().timeouts)
        c.onKey(Key(" ", action = KeyAction.SPACE))
        assertEquals(listOf("你好"), host.commits)
    }

    @Test fun nine_key_input_is_reranked_with_its_own_paths() {
        val engine = Engine()
        val (c, q, _) = setup(engine)
        c.switchTextLayoutForTest(true)
        typeWarmed(c, q, "64426")
        q.decode()
        q.neural()
        assertEquals("拟好", c.candidateWords().first())
        assertTrue(engine.rankedCalls.last().startsWith("64426|true|"))
        assertTrue(engine.rankedCalls.last().endsWith("|20"))
    }

    @Test fun a_model_choice_from_past_the_third_candidate_leaves_three_ahead_of_the_initial_words() {
        val initials = (1..13).map { "简拼$it" }
        val engine = InitialsEngine(initials)
        val (c, q, _) = setup(engine)
        c.switchTextLayoutForTest(true)
        typeWarmed(c, q, "64426")
        q.decode()
        assertEquals(
            "the decoder order shows first",
            engine.spelled.take(3) + initials.take(10) + engine.spelled.drop(3),
            c.candidateWords(),
        )
        q.neural()
        assertEquals(listOf("拟好", "你好", "你号") + initials.take(10) + listOf("泥好", "尼豪", "你"), c.candidateWords())
    }

    @Test fun a_model_choice_that_is_an_initial_word_is_listed_once_and_the_initial_words_stay_ten() {
        val initials = listOf("简拼1", "拟好") + (3..13).map { "简拼$it" }
        val (c, q, _) = setup(InitialsEngine(initials))
        c.switchTextLayoutForTest(true)
        typeWarmed(c, q, "64426")
        q.decode()
        q.neural()
        assertEquals(
            listOf("拟好", "你好", "你号") + (initials - "拟好").take(10) + listOf("泥好", "尼豪", "你"),
            c.candidateWords(),
        )
        assertEquals(1, c.candidateWords().count { it == "拟好" })
    }

    @Test fun the_decoder_order_keeps_its_first_three_ahead_of_the_initial_words_when_the_model_misses_the_wait() {
        val initials = listOf("简拼1", "拟好") + (3..13).map { "简拼$it" }
        val engine = InitialsEngine(initials)
        val (c, q, _) = setup(engine)
        c.switchTextLayoutForTest(true)
        typeWarmed(c, q, "64426")
        q.decode()
        q.fireTimers()
        q.neural()
        assertEquals(engine.spelled.take(3) + initials.take(10) + listOf("尼豪", "你"), c.candidateWords())
    }

    @Test fun a_result_for_older_input_is_dropped() {
        val (c, q, _) = setup()
        c.switchTextLayoutForTest(false)
        typeWarmed(c, q, "nihao")
        q.decode()
        type(c, "n")
        q.neural()
        assertEquals(
            "the model choice for input a key has replaced does not move the list",
            listOf("你好", "拟好", "你"),
            c.candidateWords(),
        )
        q.decode()
        q.neural()
        assertTrue(c.candidateWords().isEmpty())
    }

    @Test fun a_leading_candidate_that_is_not_the_decoder_sentence_is_left_alone() {
        val engine = Engine().apply { leading = "你好啊" }
        val (c, q, _) = setup(engine)
        c.switchTextLayoutForTest(false)
        typeWarmed(c, q, "nihao")
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
        typeWarmed(c, q, "nihao")
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
            typeWarmed(c, q, input)
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
            type(c, "n")
            worker.submit {}.get(5, TimeUnit.SECONDS)
            type(c, "ihao")
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
        typeWarmed(c, q, input)
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
            assertEquals("$keys keys", 1, q.neuralWorker.size)
            assertTrue("$keys keys", q.neuralMain.isEmpty() && q.timers.isEmpty())
        }
        assertTrue("no paths are generated for input the model never sees", engine.rankedCalls.isEmpty())
        q.neural()
        type(c, input.substring(2))
        q.decode()
        assertEquals("the third key shows the decoder order at once", engine.words(input), decoded(c))
        assertFalse(view.candidatesPendingForTest())
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
            type(c, input.take(2))
            q.decode()
            q.neural()
            type(c, input.substring(2))
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
        type(c, input.take(2))
        q.decode()
        q.neural()
        type(c, input.substring(2))
        q.decode()
        assertEquals(engine.words(input), decoded(c))
        assertFalse(view.candidatesPendingForTest())
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

    private fun scoring(q: Queues, scorer: Scorer) =
        NeuralReranker(spec, { scorer }, Executor { r -> q.neuralWorker.add(r) }, Executor { r -> q.neuralMain.add(r) }, q.schedule)

    private fun shortInputWarmsTheContextTheThirdKeyScores(nine: Boolean) {
        val host = Host().apply { before = "今天我想说" }
        val engine = EveryInputEngine()
        val scorer = Scorer()
        val view = InputView(ctx)
        val (c, q, r) = setup(engine, host, view = view, reranker = { scoring(it, scorer) })
        c.switchTextLayoutForTest(nine)
        val input = if (nine) "644" else "nih"
        type(c, input.take(1))
        q.decode()
        assertEquals(1, q.neuralWorker.size)
        val cancels = scorer.cancels
        type(c, input.substring(1, 2))
        q.decode()
        assertEquals("the second key does not warm again", 1, q.neuralWorker.size)
        assertEquals("the second key leaves the warm running", cancels, scorer.cancels)
        assertTrue(q.neuralMain.isEmpty() && q.timers.isEmpty())
        assertFalse(view.candidatesPendingForTest())
        q.neural()
        assertEquals(listOf("warm:今天我想说"), scorer.calls)
        type(c, input.substring(2))
        q.decode()
        assertEquals(1, q.neuralWorker.size)
        assertEquals(engine.words(input), decoded(c))
        assertFalse(view.candidatesPendingForTest())
        q.neural()
        assertEquals(listOf("warm:今天我想说", "score:今天我想说"), scorer.calls)
        assertEquals(engine.reranked(input), decoded(c))
        assertFalse(view.candidatesPendingForTest())
        val snap = r.snapshot()
        assertEquals(1, snap.runs)
        assertEquals(0, snap.cancelled)
        assertEquals(0, snap.timeouts)
    }

    @Test fun short_input_warms_the_context_the_third_key_scores_on_26key() =
        shortInputWarmsTheContextTheThirdKeyScores(nine = false)

    @Test fun short_input_warms_the_context_the_third_key_scores_on_9key() =
        shortInputWarmsTheContextTheThirdKeyScores(nine = true)

    private fun keyTypedWhileItsContextLoadsShowsTheDecoderOrderAtOnce(nine: Boolean, late: Boolean) {
        val host = Host().apply { before = "今天我想说" }
        val engine = EveryInputEngine()
        val scorer = Scorer()
        val view = InputView(ctx)
        val (c, q, r) = setup(engine, host, view = view, reranker = { scoring(it, scorer) })
        c.switchTextLayoutForTest(nine)
        val input = if (nine) "6442" else "niha"
        type(c, input.take(2))
        q.decode()
        val cancels = scorer.cancels
        type(c, input.substring(2, 3))
        q.decode()
        assertEquals("the third key shows the decoder order", engine.words(input.take(3)), decoded(c))
        assertFalse(view.candidatesPendingForTest())
        assertEquals("the list is handed to the model behind the warm", 2, q.neuralWorker.size)
        assertEquals(listOf(100L), q.delays)
        assertEquals("the third key leaves the warm running", cancels, scorer.cancels)
        assertTrue(scorer.calls.isEmpty())
        if (late) q.fireTimers()
        q.neural()
        assertEquals(listOf("warm:今天我想说", "score:今天我想说"), scorer.calls)
        assertEquals(
            if (late) engine.words(input.take(3)) else engine.reranked(input.take(3)),
            decoded(c),
        )
        assertEquals(if (late) 1 else 0, r.snapshot().timeouts)
        type(c, input.substring(3))
        q.decode()
        assertEquals("the next key shows the decoder order", engine.words(input), decoded(c))
        assertFalse(view.candidatesPendingForTest())
        assertEquals(1, q.neuralWorker.size)
        q.neural()
        assertEquals(listOf("warm:今天我想说", "score:今天我想说", "score:今天我想说"), scorer.calls)
        assertEquals(engine.reranked(input), decoded(c))
        assertFalse(view.candidatesPendingForTest())
        val snap = r.snapshot()
        assertEquals(2, snap.runs)
        assertEquals(0, snap.cancelled)
        assertEquals(if (late) 1 else 0, snap.timeouts)
        assertEquals(1, snap.warms)
    }

    @Test fun a_key_typed_while_its_context_loads_shows_the_decoder_order_at_once_and_is_reranked_after_the_load_on_26key() =
        keyTypedWhileItsContextLoadsShowsTheDecoderOrderAtOnce(nine = false, late = false)

    @Test fun a_key_typed_while_its_context_loads_shows_the_decoder_order_at_once_and_is_reranked_after_the_load_on_9key() =
        keyTypedWhileItsContextLoadsShowsTheDecoderOrderAtOnce(nine = true, late = false)

    @Test fun a_key_whose_context_load_outlasts_the_wait_keeps_the_decoder_order_on_26key() =
        keyTypedWhileItsContextLoadsShowsTheDecoderOrderAtOnce(nine = false, late = true)

    @Test fun a_key_whose_context_load_outlasts_the_wait_keeps_the_decoder_order_on_9key() =
        keyTypedWhileItsContextLoadsShowsTheDecoderOrderAtOnce(nine = true, late = true)

    private fun spaceOnAListWhoseContextStillLoadsCommitsTheDecoderSentence(nine: Boolean) {
        val host = Host()
        val engine = EveryInputEngine()
        val scorer = Scorer()
        val (c, q, r) = setup(engine, host, reranker = { scoring(it, scorer) })
        c.switchTextLayoutForTest(nine)
        val input = if (nine) "644" else "nih"
        type(c, input)
        q.decode()
        assertEquals(engine.words(input), decoded(c))
        assertEquals("the list is handed to the model behind the warm", 2, q.neuralWorker.size)
        c.onKey(Key(" ", action = KeyAction.SPACE))
        assertEquals(listOf(engine.words(input).first()), host.commits)
        q.decode()
        q.neural()
        q.fireTimers()
        q.neural()
        assertEquals(listOf(engine.words(input).first()), host.commits)
        assertEquals(listOf("warm:"), scorer.calls)
        val snap = r.snapshot()
        assertEquals(0, snap.runs)
        assertEquals("the job the commit gave up on is dropped", 1, snap.cancelled)
        assertEquals("the commit that did not get the model choice counts once", 1, snap.timeouts)
    }

    @Test fun space_on_a_list_whose_context_still_loads_commits_the_decoder_sentence_on_26key() =
        spaceOnAListWhoseContextStillLoadsCommitsTheDecoderSentence(nine = false)

    @Test fun space_on_a_list_whose_context_still_loads_commits_the_decoder_sentence_on_9key() =
        spaceOnAListWhoseContextStillLoadsCommitsTheDecoderSentence(nine = true)

    private fun spaceOnShortInputDoesNotWaitForTheWarm(nine: Boolean) {
        val host = Host()
        val engine = EveryInputEngine()
        val scorer = Scorer()
        val (c, q, r) = setup(engine, host, reranker = { scoring(it, scorer) })
        c.switchTextLayoutForTest(nine)
        val input = if (nine) "64" else "ni"
        type(c, input)
        q.decode()
        assertEquals(1, q.neuralWorker.size)
        c.onKey(Key(" ", action = KeyAction.SPACE))
        assertEquals(listOf(engine.words(input).first()), host.commits)
        q.decode()
        q.neural()
        assertEquals("the text committed from short input is not warmed", listOf("warm:"), scorer.calls)
        val snap = r.snapshot()
        assertEquals(0, snap.runs)
        assertEquals(0, snap.cancelled)
        assertEquals(0, snap.timeouts)
    }

    @Test fun space_on_short_input_does_not_wait_for_the_warm_on_26key() = spaceOnShortInputDoesNotWaitForTheWarm(nine = false)

    @Test fun space_on_short_input_does_not_wait_for_the_warm_on_9key() = spaceOnShortInputDoesNotWaitForTheWarm(nine = true)

    private fun changedContextCancelsTheWarmAndWarmsAgain(nine: Boolean) {
        val host = Host().apply { before = "今天" }
        val scorer = Scorer()
        val (c, q, r) = setup(EveryInputEngine(), host, reranker = { scoring(it, scorer) })
        c.switchTextLayoutForTest(nine)
        val input = if (nine) "64" else "ni"
        type(c, input.take(1))
        q.decode()
        val cancels = scorer.cancels
        host.before = "明天"
        c.onEditorContextChanged()
        type(c, input.substring(1))
        q.decode()
        assertEquals(cancels + 1, scorer.cancels)
        assertEquals(2, q.neuralWorker.size)
        q.neural()
        assertEquals(listOf("warm:明天"), scorer.calls)
        assertEquals(0, r.snapshot().cancelled)
    }

    @Test fun a_changed_context_cancels_the_warm_and_warms_again_on_26key() = changedContextCancelsTheWarmAndWarmsAgain(nine = false)

    @Test fun a_changed_context_cancels_the_warm_and_warms_again_on_9key() = changedContextCancelsTheWarmAndWarmsAgain(nine = true)

    private fun inputTheModelNeverRanksIsNotWarmed(nine: Boolean) {
        val input = if (nine) "64" else "ni"
        val host = Host().apply { before = "今天" }
        val scorer = Scorer()
        val (c, q, _) = setup(EveryInputEngine(), host, reranker = { scoring(it, scorer) })
        c.switchTextLayoutForTest(nine)
        type(c, input.take(1))
        q.decode()
        q.neural()
        assertEquals(listOf("warm:今天"), scorer.calls)
        c.onKey(Key("", action = KeyAction.SEGMENT))
        host.before = "明天"
        c.onEditorContextChanged()
        type(c, input.substring(1))
        q.decode()
        assertTrue("input with a forced cut is not warmed", q.neuralWorker.isEmpty())
        assertEquals(listOf("warm:今天"), scorer.calls)

        val loading = Queues()
        val idle = Scorer()
        val unready = KeyboardController(Host(), EveryInputEngine(), loading.lane).apply { attachView(InputView(ctx)) }
        unready.neural = scoring(loading, idle).apply { start() }
        unready.switchTextLayoutForTest(nine)
        type(unready, input)
        loading.decode()
        assertEquals("only the model load is queued", 1, loading.neuralWorker.size)
        loading.neural()
        assertTrue(idle.calls.isEmpty())
    }

    @Test fun input_the_model_never_ranks_is_not_warmed_on_26key() = inputTheModelNeverRanksIsNotWarmed(nine = false)

    @Test fun input_the_model_never_ranks_is_not_warmed_on_9key() = inputTheModelNeverRanksIsNotWarmed(nine = true)

    private fun theInputViewStartLoadsTheContextTheFirstKeysUse(nine: Boolean) {
        val host = Host().apply { before = "今天我想说" }
        val engine = EveryInputEngine()
        val scorer = Scorer()
        val (c, q, r) = setup(engine, host, reranker = { scoring(it, scorer) })
        c.switchTextLayoutForTest(nine)
        q.decode()
        assertTrue("nothing is loaded before the input view starts", q.neuralWorker.isEmpty())
        c.onInputTargetChanged()
        host.reads.clear()
        c.onInputViewStarted()
        assertEquals("the view start reads the text before the cursor once, as the first key does", listOf(65), host.reads)
        assertEquals(1, q.neuralWorker.size)
        assertTrue(q.neuralMain.isEmpty() && q.timers.isEmpty())
        val cancels = scorer.cancels
        val input = if (nine) "644" else "nih"
        type(c, input.take(1))
        q.decode()
        assertEquals("the first key does not load its context again", 1, q.neuralWorker.size)
        assertEquals("the first key leaves the load running", cancels, scorer.cancels)
        q.neural()
        assertEquals(listOf("warm:今天我想说"), scorer.calls)
        type(c, input.substring(1, 2))
        q.decode()
        assertTrue("the second key finds its context loaded", q.neuralWorker.isEmpty())
        assertEquals("the keys reuse the text read when the view started", 1, host.reads.count { it == 65 })
        assertEquals(engine.words(input.take(2)), decoded(c))
        type(c, input.substring(2))
        q.decode()
        assertEquals(engine.words(input), decoded(c))
        q.neural()
        assertEquals(listOf("warm:今天我想说", "score:今天我想说"), scorer.calls)
        assertEquals(engine.reranked(input), decoded(c))
        val snap = r.snapshot()
        assertEquals(1, snap.runs)
        assertEquals(0, snap.cancelled)
        assertEquals(0, snap.timeouts)
    }

    @Test fun the_input_view_start_loads_the_context_the_first_keys_use_on_26key() =
        theInputViewStartLoadsTheContextTheFirstKeysUse(nine = false)

    @Test fun the_input_view_start_loads_the_context_the_first_keys_use_on_9key() =
        theInputViewStartLoadsTheContextTheFirstKeysUse(nine = true)

    private fun aViewThatStartsAgainOnTheSameTextLoadsNothing(nine: Boolean) {
        val host = Host().apply { before = "今天我想说" }
        val scorer = Scorer()
        val (c, q, _) = setup(EveryInputEngine(), host, reranker = { scoring(it, scorer) })
        c.switchTextLayoutForTest(nine)
        q.decode()
        c.onInputViewStarted()
        q.neural()
        assertEquals(listOf("warm:今天我想说"), scorer.calls)
        c.onInputTargetChanged()
        c.onInputViewStarted()
        assertTrue(q.neuralWorker.isEmpty())
        host.before = "明天"
        c.onInputTargetChanged()
        c.onInputViewStarted()
        q.neural()
        assertEquals(listOf("warm:今天我想说", "warm:明天"), scorer.calls)
    }

    @Test fun a_view_that_starts_again_on_the_same_text_loads_nothing_on_26key() =
        aViewThatStartsAgainOnTheSameTextLoadsNothing(nine = false)

    @Test fun a_view_that_starts_again_on_the_same_text_loads_nothing_on_9key() =
        aViewThatStartsAgainOnTheSameTextLoadsNothing(nine = true)

    private fun theInputViewStartLoadsNothing(nine: Boolean, before: String = "今天", prepare: (KeyboardController, Queues, Host) -> Unit) {
        val host = Host().apply { this.before = before }
        val scorer = Scorer()
        val (c, q, _) = setup(EveryInputEngine(), host, reranker = { scoring(it, scorer) })
        c.switchTextLayoutForTest(nine)
        q.decode()
        prepare(c, q, host)
        q.decode()
        q.neural()
        val calls = scorer.calls.toList()
        val cancels = scorer.cancels
        c.onInputViewStarted()
        assertTrue(q.neuralWorker.isEmpty())
        assertEquals(calls, scorer.calls)
        assertEquals(cancels, scorer.cancels)
    }

    private fun theInputViewStartLoadsNothingWithoutAReadyModel(nine: Boolean) {
        val bare = Host().apply { before = "今天" }
        val none = KeyboardController(bare, EveryInputEngine(), Queues().lane).apply { attachView(InputView(ctx)) }
        none.switchTextLayoutForTest(nine)
        bare.reads.clear()
        none.onInputViewStarted()
        assertTrue("a keyboard without a model reads nothing", bare.reads.isEmpty())

        val loading = Queues()
        val idle = Scorer()
        val host = Host().apply { before = "今天" }
        val unready = KeyboardController(host, EveryInputEngine(), loading.lane).apply { attachView(InputView(ctx)) }
        unready.neural = scoring(loading, idle).apply { start() }
        unready.switchTextLayoutForTest(nine)
        loading.decode()
        host.reads.clear()
        unready.onInputViewStarted()
        assertTrue("a model that is still loading reads nothing", host.reads.isEmpty())
        assertEquals("only the model load is queued", 1, loading.neuralWorker.size)
        loading.neural()
        assertTrue(idle.calls.isEmpty())
    }

    @Test fun the_input_view_start_loads_nothing_without_a_ready_model_on_26key() =
        theInputViewStartLoadsNothingWithoutAReadyModel(nine = false)

    @Test fun the_input_view_start_loads_nothing_without_a_ready_model_on_9key() =
        theInputViewStartLoadsNothingWithoutAReadyModel(nine = true)

    @Test fun the_input_view_start_loads_nothing_for_english_input_on_26key() =
        theInputViewStartLoadsNothing(nine = false) { c, _, _ -> c.onKey(Key("", action = KeyAction.TOGGLE_LANG)) }

    @Test fun the_input_view_start_loads_nothing_for_english_input_on_9key() =
        theInputViewStartLoadsNothing(nine = true) { c, _, _ -> c.onKey(Key("", action = KeyAction.TOGGLE_LANG)) }

    @Test fun the_input_view_start_loads_nothing_on_the_symbol_keyboard_on_26key() =
        theInputViewStartLoadsNothing(nine = false) { c, _, _ -> c.onKey(Key("", action = KeyAction.SWITCH_SYMBOLS)) }

    @Test fun the_input_view_start_loads_nothing_on_the_symbol_keyboard_on_9key() =
        theInputViewStartLoadsNothing(nine = true) { c, _, _ -> c.onKey(Key("", action = KeyAction.SWITCH_SYMBOLS)) }

    @Test fun the_input_view_start_loads_nothing_after_an_email_name_on_26key() =
        theInputViewStartLoadsNothing(nine = false, before = "name@") { c, _, _ -> c.setEmailAssociationsEnabled(true) }

    @Test fun the_input_view_start_loads_nothing_after_an_email_name_on_9key() =
        theInputViewStartLoadsNothing(nine = true, before = "name@") { c, _, _ -> c.setEmailAssociationsEnabled(true) }

    private fun theInputViewStartLoadsNothingDuringAComposition(nine: Boolean) =
        theInputViewStartLoadsNothing(nine) { c, _, host ->
            type(c, if (nine) "6" else "n")
            host.before = "明天"
            c.onEditorContextChanged()
        }

    @Test fun the_input_view_start_loads_nothing_during_a_composition_on_26key() =
        theInputViewStartLoadsNothingDuringAComposition(nine = false)

    @Test fun the_input_view_start_loads_nothing_during_a_composition_on_9key() =
        theInputViewStartLoadsNothingDuringAComposition(nine = true)

    private fun outrunInput(nine: Boolean) = if (nine) "6442664" else "nihaoma"

    private fun outrun(nine: Boolean): Outrun {
        val engine = EveryInputEngine()
        val scorer = Scorer()
        val view = InputView(ctx)
        val host = Host()
        val (c, q, r) = setup(engine, host, view = view, reranker = { scoring(it, scorer) })
        c.switchTextLayoutForTest(nine)
        val input = outrunInput(nine)
        type(c, input.take(2))
        q.decode()
        q.neural()
        val t = Outrun(c, q, r, engine, scorer, view, host, input)
        t.key(3)
        t.decode()
        assertEquals("the third key shows the decoder order at once", t.words(3), t.shown())
        assertFalse(view.candidatesPendingForTest())
        assertEquals(listOf(100L), q.delays)
        return t
    }

    private class Outrun(
        val c: KeyboardController,
        val q: Queues,
        val r: NeuralReranker,
        val engine: EveryInputEngine,
        val scorer: Scorer,
        val view: InputView,
        val host: Host,
        val input: String,
    ) {
        val lists = arrayListOf(shown())
        fun key(keys: Int) = step { c.onKey(Key(input.substring(keys - 1, keys), output = input.substring(keys - 1, keys))) }
        fun backspace() = step { c.onKey(Key("", action = KeyAction.BACKSPACE)) }
        fun shift() = step { c.onKey(Key("", action = KeyAction.SHIFT)) }
        fun clear() = step { c.onKey(Key("", action = KeyAction.CLEAR_COMPOSING)) }
        fun reset() = step { c.reset() }
        fun space() = step { c.onKey(Key(" ", action = KeyAction.SPACE)) }
        fun pick(index: Int) = step { c.onPickCandidate(index) }
        fun decode() = drain(q.decodeWorker, q.decodeMain)
        fun neural() = drain(q.neuralWorker, q.neuralMain)
        fun score() = drain(q.neuralWorker)
        fun deliver() = drain(q.neuralMain)
        fun expire() = step { q.fireTimers() }
        fun words(keys: Int) = engine.words(input.take(keys))
        fun reranked(keys: Int) = engine.reranked(input.take(keys))
        fun shown() = c.candidateWords().filter { it.last().isDigit() }
        private fun drain(vararg queues: ArrayDeque<Runnable>) {
            while (true) {
                val next = queues.firstOrNull { it.isNotEmpty() } ?: return
                step { next.removeFirst().run() }
            }
        }
        private fun step(action: () -> Unit) {
            action()
            val now = shown()
            if (lists.last() != now) lists.add(now)
        }
    }

    private fun aKeyThatOutrunsTheModelLeavesEachDecodedListInTheDecoderOrder(nine: Boolean) {
        val t = outrun(nine)
        t.key(4)
        assertEquals("the fourth key leaves the list of the third in place", t.words(3), t.shown())
        assertTrue(t.view.candidatesPendingForTest())
        t.decode()
        assertEquals("the list of the fourth key shows as soon as it is decoded", t.words(4), t.shown())
        assertFalse(t.view.candidatesPendingForTest())
        t.key(5)
        assertEquals(t.words(4), t.shown())
        t.key(6)
        t.decode()
        assertEquals("a key whose decode never landed has no list to show", t.words(6), t.shown())
        assertFalse(t.view.candidatesPendingForTest())
        assertTrue("no model answer and no expired wait moved a list", t.scorer.contexts.isEmpty())
        assertEquals(0, t.r.snapshot().timeouts)

        t.neural()
        assertEquals("only the list on screen takes its model choice", t.reranked(6), t.shown())
        assertFalse(t.view.candidatesPendingForTest())

        t.key(7)
        t.decode()
        assertEquals("a key after an answered model shows at once as before", t.words(7), t.shown())
        assertFalse(t.view.candidatesPendingForTest())
        t.neural()
        assertEquals(t.reranked(7), t.shown())

        t.space()
        assertEquals(listOf(t.reranked(7).first()), t.host.commits)
        assertEquals(
            listOf(t.words(2), t.words(3), t.words(4), t.words(6), t.reranked(6), t.words(7), t.reranked(7), emptyList()),
            t.lists,
        )
        val snap = t.r.snapshot()
        assertEquals(2, snap.runs)
        assertEquals(2, snap.cancelled)
        assertEquals(0, snap.timeouts)
    }

    @Test fun a_key_that_outruns_the_model_leaves_each_decoded_list_in_the_decoder_order_on_26key() =
        aKeyThatOutrunsTheModelLeavesEachDecodedListInTheDecoderOrder(nine = false)

    @Test fun a_key_that_outruns_the_model_leaves_each_decoded_list_in_the_decoder_order_on_9key() =
        aKeyThatOutrunsTheModelLeavesEachDecodedListInTheDecoderOrder(nine = true)

    private fun keysTheModelKeepsUpWithShowAtOnceAndAreThenReranked(nine: Boolean) {
        val t = outrun(nine)
        t.neural()
        assertEquals(t.reranked(3), t.shown())
        for (keys in 4..t.input.length) {
            t.key(keys)
            t.decode()
            assertEquals("$keys keys show the decoder order at once", t.words(keys), t.shown())
            assertFalse("$keys keys", t.view.candidatesPendingForTest())
            t.neural()
            assertEquals("$keys keys", t.reranked(keys), t.shown())
            assertFalse("$keys keys", t.view.candidatesPendingForTest())
        }
        t.space()
        assertEquals(listOf(t.reranked(t.input.length).first()), t.host.commits)
        assertEquals(
            listOf(t.words(2)) + (3..t.input.length).flatMap { listOf(t.words(it), t.reranked(it)) } + listOf(emptyList()),
            t.lists,
        )
        val snap = t.r.snapshot()
        assertEquals(t.input.length - 2, snap.runs)
        assertEquals(0, snap.cancelled)
        assertEquals(0, snap.timeouts)
    }

    @Test fun keys_the_model_keeps_up_with_show_at_once_and_are_then_reranked_on_26key() =
        keysTheModelKeepsUpWithShowAtOnceAndAreThenReranked(nine = false)

    @Test fun keys_the_model_keeps_up_with_show_at_once_and_are_then_reranked_on_9key() =
        keysTheModelKeepsUpWithShowAtOnceAndAreThenReranked(nine = true)

    private class AgreeingScorer : SentenceScorer {
        override fun score(context: String, candidates: List<String>): DoubleArray =
            DoubleArray(candidates.size) { if (candidates[it].startsWith("你好")) -1.0 else -5.0 }
        override fun warm(context: String) {}
        override fun cancel() {}
        override fun resume() {}
        override fun close() {}
    }

    private fun aModelChoiceThatAgreesWithTheDecoderLeavesTheListAsShown(nine: Boolean) {
        val host = Host()
        val engine = EveryInputEngine()
        val view = InputView(ctx)
        val (c, q, r) = setup(engine, host, view = view, reranker = {
            NeuralReranker(
                spec, { AgreeingScorer() }, Executor { r -> it.neuralWorker.add(r) }, Executor { r -> it.neuralMain.add(r) }, it.schedule,
            )
        })
        c.switchTextLayoutForTest(nine)
        val input = if (nine) "644" else "nih"
        typeWarmed(c, q, input)
        q.decode()
        assertEquals(engine.words(input), decoded(c))
        q.neural()
        assertEquals(engine.words(input), decoded(c))
        assertFalse(view.candidatesPendingForTest())
        val snap = r.snapshot()
        assertEquals(1, snap.runs)
        assertEquals(0, snap.changed)
        assertEquals(0, snap.timeouts)
        c.onKey(Key(" ", action = KeyAction.SPACE))
        assertEquals(listOf(engine.words(input).first()), host.commits)
    }

    @Test fun a_model_choice_that_agrees_with_the_decoder_leaves_the_list_as_shown_on_26key() =
        aModelChoiceThatAgreesWithTheDecoderLeavesTheListAsShown(nine = false)

    @Test fun a_model_choice_that_agrees_with_the_decoder_leaves_the_list_as_shown_on_9key() =
        aModelChoiceThatAgreesWithTheDecoderLeavesTheListAsShown(nine = true)

    private fun repeatedBackspaceThatOutrunsTheModelShowsEachListInTheDecoderOrder(nine: Boolean) {
        val t = outrun(nine)
        for (keys in 4..t.input.length) t.key(keys)
        t.decode()
        assertEquals(t.words(t.input.length), t.shown())
        t.neural()
        val full = t.reranked(t.input.length)
        assertEquals(full, t.shown())
        t.backspace()
        t.decode()
        assertEquals("the first backspace shows its list at once", t.words(t.input.length - 1), t.shown())
        assertFalse(t.view.candidatesPendingForTest())
        for (keys in t.input.length - 2 downTo 2) {
            t.backspace()
            assertEquals("$keys keys", t.words(keys + 1), t.shown())
            assertTrue("$keys keys", t.view.candidatesPendingForTest())
            t.decode()
            assertEquals("$keys keys", t.words(keys), t.shown())
            assertFalse("$keys keys", t.view.candidatesPendingForTest())
        }
        t.neural()
        t.expire()
        t.neural()
        assertEquals(t.words(2), t.shown())
        assertFalse(t.view.candidatesPendingForTest())
        assertEquals(
            listOf(t.words(2), t.words(3), t.words(t.input.length), full) + (t.input.length - 1 downTo 2).map { t.words(it) },
            t.lists,
        )
        val snap = t.r.snapshot()
        assertEquals(1, snap.runs)
        assertEquals(t.input.length - 2, snap.cancelled)
        assertEquals(0, snap.timeouts)
    }

    @Test fun repeated_backspace_that_outruns_the_model_shows_each_list_in_the_decoder_order_on_26key() =
        repeatedBackspaceThatOutrunsTheModelShowsEachListInTheDecoderOrder(nine = false)

    @Test fun repeated_backspace_that_outruns_the_model_shows_each_list_in_the_decoder_order_on_9key() =
        repeatedBackspaceThatOutrunsTheModelShowsEachListInTheDecoderOrder(nine = true)

    private fun aListAKeyHasReplacedIsNotReorderedByItsOwnModelChoice(nine: Boolean, scoredFirst: Boolean) {
        val t = outrun(nine)
        if (scoredFirst) {
            t.score()
            assertEquals(1, t.q.neuralMain.size)
        }
        t.key(4)
        assertEquals(t.words(3), t.shown())
        t.score()
        t.expire()
        t.deliver()
        assertEquals("the model choice for input a key has replaced does not move its list", t.words(3), t.shown())
        t.decode()
        assertEquals(t.words(4), t.shown())
        assertFalse(t.view.candidatesPendingForTest())
        t.neural()
        assertEquals(t.reranked(4), t.shown())
        assertFalse(t.view.candidatesPendingForTest())
        assertEquals(listOf(t.words(2), t.words(3), t.words(4), t.reranked(4)), t.lists)
        val snap = t.r.snapshot()
        assertEquals(if (scoredFirst) 2 else 1, snap.runs)
        assertEquals(if (scoredFirst) 0 else 1, snap.cancelled)
        assertEquals(0, snap.timeouts)
    }

    @Test fun a_list_a_key_has_replaced_is_not_reordered_by_its_queued_run_on_26key() =
        aListAKeyHasReplacedIsNotReorderedByItsOwnModelChoice(nine = false, scoredFirst = false)

    @Test fun a_list_a_key_has_replaced_is_not_reordered_by_its_queued_run_on_9key() =
        aListAKeyHasReplacedIsNotReorderedByItsOwnModelChoice(nine = true, scoredFirst = false)

    @Test fun a_list_a_key_has_replaced_is_not_reordered_by_its_finished_run_on_26key() =
        aListAKeyHasReplacedIsNotReorderedByItsOwnModelChoice(nine = false, scoredFirst = true)

    @Test fun a_list_a_key_has_replaced_is_not_reordered_by_its_finished_run_on_9key() =
        aListAKeyHasReplacedIsNotReorderedByItsOwnModelChoice(nine = true, scoredFirst = true)

    private fun aCompositionThatEndedIsNotReorderedByTheModelChoice(nine: Boolean, reset: Boolean) {
        for (scoredFirst in listOf(false, true)) {
            val t = outrun(nine)
            if (scoredFirst) {
                t.score()
                assertEquals(1, t.q.neuralMain.size)
            }
            if (reset) t.reset() else t.clear()
            t.decode()
            t.neural()
            t.expire()
            t.neural()
            assertTrue("scored first $scoredFirst", t.shown().isEmpty())
            assertEquals("scored first $scoredFirst", "", t.c.rawComposingForTest())
            assertEquals("scored first $scoredFirst", listOf(t.words(2), t.words(3), emptyList()), t.lists)
            val snap = t.r.snapshot()
            val dropped = !reset && !scoredFirst
            assertEquals(if (dropped) 0 else 1, snap.runs)
            assertEquals(if (dropped) 1 else 0, snap.cancelled)
            assertEquals(0, snap.timeouts)
        }
    }

    @Test fun a_cleared_composition_is_not_reordered_by_the_model_choice_on_26key() =
        aCompositionThatEndedIsNotReorderedByTheModelChoice(nine = false, reset = false)

    @Test fun a_cleared_composition_is_not_reordered_by_the_model_choice_on_9key() =
        aCompositionThatEndedIsNotReorderedByTheModelChoice(nine = true, reset = false)

    @Test fun a_reset_controller_is_not_reordered_by_the_model_choice_on_26key() =
        aCompositionThatEndedIsNotReorderedByTheModelChoice(nine = false, reset = true)

    @Test fun a_reset_controller_is_not_reordered_by_the_model_choice_on_9key() =
        aCompositionThatEndedIsNotReorderedByTheModelChoice(nine = true, reset = true)

    private fun theListAfterAnOutrunOneKeepsTheDecoderOrderWhenTheModelMissesTheWait(nine: Boolean, space: Boolean) {
        val t = outrun(nine)
        t.key(4)
        t.decode()
        assertEquals(t.words(4), t.shown())
        if (space) {
            t.space()
            assertEquals(listOf(t.words(4).first()), t.host.commits)
        }
        t.expire()
        t.neural()
        if (space) {
            assertEquals(listOf(t.words(4).first()), t.host.commits)
            assertEquals(listOf(t.words(2), t.words(3), t.words(4), emptyList()), t.lists)
        } else {
            assertEquals("a late model choice does not move the shown list", t.words(4), t.shown())
            assertFalse(t.view.candidatesPendingForTest())
            assertEquals(listOf(t.words(2), t.words(3), t.words(4)), t.lists)
            t.space()
            assertEquals(listOf(t.words(4).first()), t.host.commits)
        }
        assertEquals(1, t.r.snapshot().timeouts)
    }

    @Test fun the_list_after_an_outrun_one_keeps_the_decoder_order_when_the_model_misses_the_wait_on_26key() =
        theListAfterAnOutrunOneKeepsTheDecoderOrderWhenTheModelMissesTheWait(nine = false, space = false)

    @Test fun the_list_after_an_outrun_one_keeps_the_decoder_order_when_the_model_misses_the_wait_on_9key() =
        theListAfterAnOutrunOneKeepsTheDecoderOrderWhenTheModelMissesTheWait(nine = true, space = false)

    @Test fun space_after_an_outrun_list_commits_the_shown_sentence_when_the_model_never_answers_on_26key() =
        theListAfterAnOutrunOneKeepsTheDecoderOrderWhenTheModelMissesTheWait(nine = false, space = true)

    @Test fun space_after_an_outrun_list_commits_the_shown_sentence_when_the_model_never_answers_on_9key() =
        theListAfterAnOutrunOneKeepsTheDecoderOrderWhenTheModelMissesTheWait(nine = true, space = true)

    private fun spaceAfterAnOutrunListTakesTheFinishedModelChoice(nine: Boolean) {
        val t = outrun(nine)
        t.key(4)
        t.decode()
        assertEquals(t.words(4), t.shown())
        t.score()
        assertEquals("the model choice has not reached the list yet", t.words(4), t.shown())
        t.space()
        assertEquals(listOf(t.reranked(4).first()), t.host.commits)
        t.decode()
        t.neural()
        t.expire()
        t.neural()
        assertEquals(listOf(t.reranked(4).first()), t.host.commits)
        assertEquals(listOf(t.words(2), t.words(3), t.words(4), emptyList()), t.lists)
        assertEquals(0, t.r.snapshot().timeouts)
    }

    @Test fun space_after_an_outrun_list_takes_the_finished_model_choice_on_26key() =
        spaceAfterAnOutrunListTakesTheFinishedModelChoice(nine = false)

    @Test fun space_after_an_outrun_list_takes_the_finished_model_choice_on_9key() =
        spaceAfterAnOutrunListTakesTheFinishedModelChoice(nine = true)

    private fun Outrun.state() = "${host.commits}|${c.composingPrefix()}|${c.rawComposingForTest()}"

    private fun aListPicksTheStartOfTheInputWhileTheNextKeyDecodes(nine: Boolean) {
        val t = outrun(nine)
        t.key(4)
        assertEquals(t.words(3), t.shown())
        assertTrue(t.view.candidatesPendingForTest())
        t.pick(0)
        val rest = t.input.substring(3, 4)
        assertEquals("the pick takes the keys its list was decoded from", "[]|${t.words(3).first()}|$rest", t.state())
        t.decode()
        t.neural()
        assertEquals(t.engine.words(rest), t.shown())
        t.pick(0)
        assertEquals(listOf(t.words(3).first() + t.engine.words(rest).first()), t.host.commits)
    }

    @Test fun a_list_picks_the_start_of_the_input_while_the_next_key_decodes_on_26key() =
        aListPicksTheStartOfTheInputWhileTheNextKeyDecodes(nine = false)

    @Test fun a_list_picks_the_start_of_the_input_while_the_next_key_decodes_on_9key() =
        aListPicksTheStartOfTheInputWhileTheNextKeyDecodes(nine = true)

    private fun aListWaitingForTheModelIsPickedFromAsShown(nine: Boolean) {
        val t = outrun(nine)
        t.key(4)
        t.decode()
        assertEquals(t.words(4), t.shown())
        assertFalse(t.view.candidatesPendingForTest())
        t.pick(0)
        assertEquals("the pick takes the list on screen", "[${t.words(4).first()}]||", t.state())
        t.decode()
        t.neural()
        t.expire()
        t.neural()
        assertEquals("the model choice that arrives after the pick changes nothing", listOf(t.words(4).first()), t.host.commits)
        assertTrue(t.shown().isEmpty())
        assertEquals(0, t.r.snapshot().timeouts)
    }

    @Test fun a_list_waiting_for_the_model_is_picked_from_as_shown_on_26key() =
        aListWaitingForTheModelIsPickedFromAsShown(nine = false)

    @Test fun a_list_waiting_for_the_model_is_picked_from_as_shown_on_9key() =
        aListWaitingForTheModelIsPickedFromAsShown(nine = true)

    private fun aListForLongerInputIsNotPickedFrom(nine: Boolean) {
        val t = outrun(nine)
        t.backspace()
        assertEquals(t.words(3), t.shown())
        t.pick(0)
        assertEquals("a list for input that was deleted commits nothing", "[]||${t.input.take(2)}", t.state())
        assertEquals(t.words(3), t.shown())
        t.decode()
        assertEquals(t.words(2), t.shown())
        t.pick(0)
        assertEquals(listOf(t.words(2).first()), t.host.commits)
        assertEquals(listOf(t.words(2), t.words(3), t.words(2), emptyList()), t.lists)
    }

    @Test fun a_list_for_longer_input_is_not_picked_from_on_26key() =
        aListForLongerInputIsNotPickedFrom(nine = false)

    @Test fun a_list_for_longer_input_is_not_picked_from_on_9key() =
        aListForLongerInputIsNotPickedFrom(nine = true)

    private fun aRerankedListPicksTheStartOfTheInputWhileTheNextKeyDecodes(nine: Boolean) {
        val t = outrun(nine)
        t.neural()
        assertEquals(t.reranked(3), t.shown())
        t.key(4)
        assertTrue(t.view.candidatesPendingForTest())
        assertEquals(t.reranked(3), t.shown())
        t.pick(0)
        val rest = t.input.substring(3, 4)
        assertEquals("the pick takes the keys its list was decoded from", "[]|${t.reranked(3).first()}|$rest", t.state())
        t.decode()
        t.neural()
        assertEquals(t.engine.words(rest), t.shown())
    }

    @Test fun a_reranked_list_picks_the_start_of_the_input_while_the_next_key_decodes_on_26key() =
        aRerankedListPicksTheStartOfTheInputWhileTheNextKeyDecodes(nine = false)

    @Test fun a_reranked_list_picks_the_start_of_the_input_while_the_next_key_decodes_on_9key() =
        aRerankedListPicksTheStartOfTheInputWhileTheNextKeyDecodes(nine = true)

    private fun aListIsNotPickedFromWhileABackspaceDecodes(nine: Boolean) {
        val t = outrun(nine)
        t.neural()
        t.key(4)
        t.decode()
        t.neural()
        assertEquals(t.reranked(4), t.shown())
        t.backspace()
        assertTrue(t.view.candidatesPendingForTest())
        assertEquals(t.reranked(4), t.shown())
        t.pick(0)
        assertEquals("a list for input that was deleted commits nothing", "[]||${t.input.take(3)}", t.state())
        assertEquals(t.reranked(4), t.shown())
        t.decode()
        assertEquals("the list after the backspace shows at once", t.words(3), t.shown())
        assertFalse(t.view.candidatesPendingForTest())
        t.neural()
        assertEquals(t.reranked(3), t.shown())
        t.pick(0)
        assertEquals(listOf(t.reranked(3).first()), t.host.commits)
        assertEquals(
            listOf(t.words(2), t.words(3), t.reranked(3), t.words(4), t.reranked(4), t.words(3), t.reranked(3), emptyList()),
            t.lists,
        )
    }

    @Test fun a_list_is_not_picked_from_while_a_backspace_decodes_on_26key() =
        aListIsNotPickedFromWhileABackspaceDecodes(nine = false)

    @Test fun a_list_is_not_picked_from_while_a_backspace_decodes_on_9key() =
        aListIsNotPickedFromWhileABackspaceDecodes(nine = true)

    private fun aListIsPickedFromAsShownWhileUnchangedInputWaitsForTheModelAgain(nine: Boolean) {
        val t = outrun(nine)
        t.neural()
        assertEquals(t.reranked(3), t.shown())
        t.shift()
        assertTrue(t.view.candidatesPendingForTest())
        assertEquals(t.reranked(3), t.shown())
        t.decode()
        assertFalse(t.view.candidatesPendingForTest())
        assertEquals("the refreshed list shows in the decoder order until the model answers again", t.words(3), t.shown())
        t.pick(0)
        assertEquals("the list on screen is for the whole input", "[${t.words(3).first()}]||", t.state())
    }

    @Test fun a_list_is_picked_from_as_shown_while_unchanged_input_waits_for_the_model_again_on_26key() =
        aListIsPickedFromAsShownWhileUnchangedInputWaitsForTheModelAgain(nine = false)

    @Test fun a_list_is_picked_from_as_shown_while_unchanged_input_waits_for_the_model_again_on_9key() =
        aListIsPickedFromAsShownWhileUnchangedInputWaitsForTheModelAgain(nine = true)

    private fun aRefreshThatLeavesTheInputUnchangedIsRerankedAgain(nine: Boolean) {
        val t = outrun(nine)
        t.shift()
        assertEquals(t.words(3), t.shown())
        assertTrue(t.view.candidatesPendingForTest())
        t.decode()
        assertEquals(t.words(3), t.shown())
        assertFalse(t.view.candidatesPendingForTest())
        t.neural()
        assertEquals(t.reranked(3), t.shown())
        assertFalse(t.view.candidatesPendingForTest())
        assertEquals(listOf(t.words(2), t.words(3), t.reranked(3)), t.lists)
        val snap = t.r.snapshot()
        assertEquals("only the job of the refreshed list is scored", 1, snap.runs)
        assertEquals(1, snap.cancelled)
        assertEquals(0, snap.timeouts)
    }

    @Test fun a_refresh_that_leaves_the_input_unchanged_is_reranked_again_on_26key() =
        aRefreshThatLeavesTheInputUnchangedIsRerankedAgain(nine = false)

    @Test fun a_refresh_that_leaves_the_input_unchanged_is_reranked_again_on_9key() =
        aRefreshThatLeavesTheInputUnchangedIsRerankedAgain(nine = true)

    private fun firstKeyTimeStopsWhenTheDecodedListShowsAheadOfTheModel(nine: Boolean) {
        var now = 0L
        val engine = EveryInputEngine()
        val (c, q, r) = setup(engine, reranker = {
            NeuralReranker(spec, { Scorer() }, Executor { r -> it.neuralWorker.add(r) }, Executor { r -> it.neuralMain.add(r) }, it.schedule) { now }
        })
        c.switchTextLayoutForTest(nine)
        val input = outrunInput(nine)

        now = 100_000_000L
        typeWarmed(c, q, input.take(3))
        now = 120_000_000L
        q.decode()
        assertEquals(engine.words(input.take(3)), decoded(c))
        assertEquals("the list handed to the model is on screen already", 1, r.snapshot().firstKeys)
        assertEquals(20L, r.snapshot().lastFirstKeyMillis)
        now = 145_000_000L
        q.neural()
        assertEquals(engine.reranked(input.take(3)), decoded(c))
        assertEquals("the model answer is not part of the first key's time", 20L, r.snapshot().lastFirstKeyMillis)
        now = 150_000_000L
        type(c, input.substring(3, 4))
        now = 190_000_000L
        q.decode()
        q.neural()
        assertEquals(engine.reranked(input.take(4)), decoded(c))
        assertEquals("a composition is measured once", 1, r.snapshot().firstKeys)
        assertEquals(20L, r.snapshot().maxFirstKeyMillis)
    }

    @Test fun first_key_time_stops_when_the_decoded_list_shows_ahead_of_the_model_on_26key() =
        firstKeyTimeStopsWhenTheDecodedListShowsAheadOfTheModel(nine = false)

    @Test fun first_key_time_stops_when_the_decoded_list_shows_ahead_of_the_model_on_9key() =
        firstKeyTimeStopsWhenTheDecodedListShowsAheadOfTheModel(nine = true)

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
        typeWarmed(c, q, input)
        now = 1_020_000_000L
        q.decode()
        assertEquals("a list handed to the model is on screen already", 2, r.snapshot().firstKeys)
        assertEquals(20L, r.snapshot().lastFirstKeyMillis)
        now = 1_045_000_000L
        q.neural()
        assertEquals(engine.reranked(input), decoded(c))
        assertEquals(20L, r.snapshot().lastFirstKeyMillis)
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
        assertEquals(10L, snap.meanFirstKeyMillis)
        assertEquals(20L, snap.maxFirstKeyMillis)
    }

    @Test fun first_key_time_runs_until_the_candidates_of_the_composition_show_on_26key() =
        firstKeyTimeRunsUntilTheCandidatesOfTheCompositionShow(nine = false)

    @Test fun first_key_time_runs_until_the_candidates_of_the_composition_show_on_9key() =
        firstKeyTimeRunsUntilTheCandidatesOfTheCompositionShow(nine = true)

    private fun keyTimes(r: NeuralReranker): List<Long> = r.snapshot().let {
        listOf(it.keys.toLong(), it.meanKeyMillis, it.maxKeyMillis, it.slowKeys.toLong())
    }

    private fun keyTimeRunsFromEachAppendedKeyToItsCandidates(nine: Boolean) {
        var now = 0L
        val engine = EveryInputEngine()
        val (c, q, r) = setup(engine, reranker = {
            NeuralReranker(spec, { Scorer() }, Executor { r -> it.neuralWorker.add(r) }, Executor { r -> it.neuralMain.add(r) }, it.schedule) { now }
        })
        c.switchTextLayoutForTest(nine)
        val input = outrunInput(nine)
        fun key(keys: Int) = type(c, input.substring(keys - 1, keys))

        now = 100_000_000L
        key(1)
        now = 107_000_000L
        assertEquals("nothing is shown before the decode lands", listOf(0L, 0L, 0L, 0L), keyTimes(r))
        q.decode()
        q.neural()
        assertEquals(engine.words(input.take(1)), decoded(c))
        assertEquals(listOf(1L, 7L, 7L, 0L), keyTimes(r))

        now = 200_000_000L
        key(2)
        now = 204_000_000L
        q.decode()
        q.neural()
        assertEquals(engine.words(input.take(2)), decoded(c))
        assertEquals("every appended key is measured", listOf(2L, 5L, 7L, 0L), keyTimes(r))
        assertEquals("the first key of the composition is measured once", 1, r.snapshot().firstKeys)

        now = 300_000_000L
        key(3)
        now = 310_000_000L
        q.decode()
        assertEquals(engine.words(input.take(3)), decoded(c))
        assertEquals("a list handed to the model is on screen already", listOf(3L, 7L, 10L, 0L), keyTimes(r))
        now = 345_000_000L
        q.neural()
        assertEquals(engine.reranked(input.take(3)), decoded(c))
        assertEquals("the model answer is not part of the key's time", listOf(3L, 7L, 10L, 0L), keyTimes(r))

        now = 400_000_000L
        key(4)
        now = 420_000_000L
        key(5)
        assertEquals("the fifth key leaves the list on screen", engine.reranked(input.take(3)), decoded(c))
        assertEquals("a key replaced before its candidates showed is not counted on its own", 3L, keyTimes(r).first())
        now = 480_000_000L
        q.decode()
        assertEquals(engine.words(input.take(5)), decoded(c))
        assertEquals("a key is timed from its own press to its decoded list", listOf(4L, 20L, 60L, 1L), keyTimes(r))
        now = 495_000_000L
        q.fireTimers()
        q.neural()
        assertEquals(engine.words(input.take(5)), decoded(c))
        assertEquals(1, r.snapshot().timeouts)
        assertEquals("a model that misses its wait adds nothing", listOf(4L, 20L, 60L, 1L), keyTimes(r))

        now = 600_000_000L
        key(6)
        now = 650_000_000L
        c.onKey(Key("", action = KeyAction.CLEAR_COMPOSING))
        now = 700_000_000L
        q.decode()
        q.fireTimers()
        q.neural()
        assertEquals("", c.rawComposingForTest())
        assertEquals("a key whose composition was cleared is not counted", listOf(4L, 20L, 60L, 1L), keyTimes(r))

        now = 1_000_000_000L
        key(1)
        now = 1_003_000_000L
        q.decode()
        q.neural()
        assertEquals("the cleared key does not reach into the next composition", listOf(5L, 16L, 60L, 1L), keyTimes(r))

        now = 1_100_000_000L
        key(2)
        now = 1_150_000_000L
        c.onKey(Key("", action = KeyAction.BACKSPACE))
        now = 1_200_000_000L
        q.decode()
        q.neural()
        assertEquals(engine.words(input.take(1)), decoded(c))
        assertEquals("a key removed before its candidates showed is not counted", listOf(5L, 16L, 60L, 1L), keyTimes(r))

        now = 1_300_000_000L
        c.onKey(Key("", action = KeyAction.SHIFT))
        now = 1_400_000_000L
        q.decode()
        q.neural()
        assertEquals("a key that adds nothing to the composition is not measured", listOf(5L, 16L, 60L, 1L), keyTimes(r))
    }

    @Test fun key_time_runs_from_each_appended_key_to_its_candidates_on_26key() =
        keyTimeRunsFromEachAppendedKeyToItsCandidates(nine = false)

    @Test fun key_time_runs_from_each_appended_key_to_its_candidates_on_9key() =
        keyTimeRunsFromEachAppendedKeyToItsCandidates(nine = true)

    private fun nextKeyCancelsAStalePathGeneration(nine: Boolean) {
        val engine = Engine().apply { rankedEntered = CountDownLatch(1) }
        val decodeWorker = Executors.newSingleThreadExecutor()
        try {
            val (c, q, _) = setup(engine, q = Queues(decodeWorker))
            c.switchTextLayoutForTest(nine)
            val input = if (nine) "64426" else "nihao"
            type(c, input.dropLast(1))
            decodeWorker.submit {}.get(15, TimeUnit.SECONDS)
            q.decode()
            type(c, input.takeLast(1))
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
        typeWarmed(c, q, "nihao")
        q.decode()
        c.onKey(Key(" ", action = KeyAction.SPACE))
        assertEquals(listOf("你好"), host.commits)
        q.fireTimers()
        q.neural()
        assertEquals(listOf("你好"), host.commits)
        assertEquals(1, r.snapshot().timeouts)
    }

    private fun spaceWaitsFiftyMillisecondsAfterTheSubmitForTheModel(nine: Boolean) {
        val waits = List(3) {
            val host = Host()
            val (c, q, r) = setup(host = host)
            c.switchTextLayoutForTest(nine)
            typeWarmed(c, q, if (nine) "64426" else "nihao")
            val before = System.nanoTime()
            q.decode()
            assertEquals("the timer that ends the wait to move the model choice", listOf(100L), q.delays)
            assertEquals(listOf("你好", "拟好", "你"), c.candidateWords())
            c.onKey(Key(" ", action = KeyAction.SPACE))
            val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
            assertEquals(listOf("你好"), host.commits)
            assertEquals(1, r.snapshot().timeouts)
            waited
        }
        assertTrue("space waited $waits ms", waits.all { it >= 50L })
        assertTrue("space waited $waits ms", waits.min() < 100L)
    }

    @Test fun space_waits_50_ms_after_the_submit_for_the_model_on_26key() = spaceWaitsFiftyMillisecondsAfterTheSubmitForTheModel(nine = false)

    @Test fun space_waits_50_ms_after_the_submit_for_the_model_on_9key() = spaceWaitsFiftyMillisecondsAfterTheSubmitForTheModel(nine = true)

    private fun spaceWaitsOnlyForWhatIsLeftOfTheCommitWait(nine: Boolean) {
        fun waited(elapsedMillis: Long): Long = List(3) {
            var now = 0L
            val host = Host()
            val (c, q, r) = setup(host = host, reranker = {
                NeuralReranker(spec, { Scorer() }, Executor { r -> it.neuralWorker.add(r) }, Executor { r -> it.neuralMain.add(r) }, it.schedule) { now }
            })
            c.switchTextLayoutForTest(nine)
            typeWarmed(c, q, if (nine) "64426" else "nihao")
            q.decode()
            assertEquals(listOf(100L), q.delays)
            now += TimeUnit.MILLISECONDS.toNanos(elapsedMillis)
            val before = System.nanoTime()
            c.onKey(Key(" ", action = KeyAction.SPACE))
            val wait = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
            assertEquals(listOf("你好"), host.commits)
            assertEquals(1, r.snapshot().timeouts)
            q.neural()
            q.fireTimers()
            q.neural()
            assertEquals("the model choice the commit gave up on changes nothing", listOf("你好"), host.commits)
            assertEquals(1, r.snapshot().timeouts)
            wait
        }.min()
        val left = waited(elapsedMillis = 30L)
        assertTrue("space waited $left ms with 20 ms left", left in 20L until 40L)
        val none = waited(elapsedMillis = 50L)
        assertTrue("space waited $none ms with nothing left", none < 20L)
        val past = waited(elapsedMillis = 70L)
        assertTrue("space waited $past ms past the commit wait and inside the wait to move the choice", past < 20L)
    }

    @Test fun space_waits_only_for_what_is_left_of_the_commit_wait_on_26key() = spaceWaitsOnlyForWhatIsLeftOfTheCommitWait(nine = false)

    @Test fun space_waits_only_for_what_is_left_of_the_commit_wait_on_9key() = spaceWaitsOnlyForWhatIsLeftOfTheCommitWait(nine = true)

    @Test fun replacing_the_reranker_while_its_choice_is_awaited_leaves_the_decoder_order() {
        val host = Host()
        val view = InputView(ctx)
        val (c, q, _) = setup(host = host, view = view)
        c.switchTextLayoutForTest(false)
        typeWarmed(c, q, "nihao")
        q.decode()
        assertEquals(listOf("你好", "拟好", "你"), c.candidateWords())
        assertFalse(view.candidatesPendingForTest())

        c.neural = null
        q.neural()

        assertEquals("the replaced reranker no longer moves the list", listOf("你好", "拟好", "你"), c.candidateWords())
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
