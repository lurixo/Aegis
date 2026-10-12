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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DecodeStarvationTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private companion object {
        const val RERANK_MIN_KEYS = 3
    }

    private class Host : ImeHost {
        val commits = ArrayList<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = ""
    }

    private class SlowEngine(private val typist: Thread) : CandidateEngine {
        private val gates = ConcurrentHashMap<String, CountDownLatch>()
        private val entered = ConcurrentHashMap<String, CountDownLatch>()
        val decoded = ConcurrentLinkedQueue<String>()
        @Volatile private var slow = false
        override val supportsChinese: Boolean = true
        override fun candidates(composing: String, t9: Boolean): List<String> = words(composing.length)
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> {
            if (Thread.currentThread() !== typist) {
                entered(composing).countDown()
                if (slow) {
                    val gate = gate(composing)
                    while (!gate.await(1, TimeUnit.MILLISECONDS)) DecodeCancellation.checkpoint()
                }
                decoded.add(composing)
            }
            return words(composing.length).map { Cand(it, composing.length) }
        }
        override fun rankedSentences(composing: String, t9: Boolean, context: CharSequence, limit: Int): List<Pair<String, Double>> =
            listOf("你好" to -10.0, "拟好" to -11.0, "你号" to -12.0).map { it.first + composing.length to it.second }
        fun words(keys: Int): List<String> = if (keys == 0) emptyList() else listOf("你好", "拟好", "你").map { it + keys }
        fun reranked(keys: Int): List<String> = listOf("拟好", "你好", "你").map { it + keys }
        private fun gate(input: String) = gates.computeIfAbsent(input) { CountDownLatch(1) }
        private fun entered(input: String) = entered.computeIfAbsent(input) { CountDownLatch(1) }
        fun slowDown() {
            gates.clear()
            entered.clear()
            decoded.clear()
            slow = true
        }
        fun awaitDecoding(input: String) =
            assertTrue("the decode of $input starts on the worker", entered(input).await(5, TimeUnit.SECONDS))
        fun finish(input: String) = gate(input).countDown()
        fun speedUp() {
            slow = false
            gates.values.forEach { it.countDown() }
        }
    }

    private class Scorer : SentenceScorer {
        val contexts = ConcurrentLinkedQueue<String>()
        override fun score(context: String, candidates: List<String>): DoubleArray {
            contexts.add(context)
            return DoubleArray(candidates.size) { if (candidates[it].startsWith("拟好")) -1.0 else -5.0 }
        }
        override fun warm(context: String) {}
        override fun cancel() {}
        override fun resume() {}
        override fun close() {}
    }

    private inner class Typing(nine: Boolean, private val model: Boolean, settleMillis: Long = DecodeLane.SETTLE_MILLIS) : AutoCloseable {
        private val keys = if (nine) "646484265426" else "nihaoshijiea"
        private val worker = Executors.newSingleThreadExecutor()
        private val mainQ = ConcurrentLinkedQueue<Runnable>()
        private val neuralWorker = ArrayDeque<Runnable>()
        private val neuralMain = ArrayDeque<Runnable>()
        val lane = DecodeLane(worker, Executor { mainQ.add(it) }, settleMillis = settleMillis)
        val engine = SlowEngine(Thread.currentThread())
        val host = Host()
        val scorer = Scorer()
        val c = KeyboardController(host, engine, lane).apply { attachView(InputView(ctx)) }

        init {
            if (model) {
                val spec = NeuralModelSpec("test", alphaLetters = 5.0, alphaNine = 5.0, candidates = 10)
                val r = NeuralReranker(spec, { scorer }, Executor { neuralWorker.add(it) }, Executor { neuralMain.add(it) }, { _, _ -> })
                r.start()
                neural()
                c.neural = r
            }
            c.switchTextLayoutForTest(nine)
            settle()
        }

        fun main() {
            while (true) (mainQ.poll() ?: break).run()
        }

        private fun neural() {
            while (neuralWorker.isNotEmpty() || neuralMain.isNotEmpty()) {
                while (neuralWorker.isNotEmpty()) neuralWorker.removeFirst().run()
                while (neuralMain.isNotEmpty()) neuralMain.removeFirst().run()
            }
        }

        fun idle() {
            worker.submit {}.get(5, TimeUnit.SECONDS)
        }

        fun settle() {
            idle()
            main()
            neural()
        }

        fun input(n: Int): String = keys.take(n)
        fun press(n: Int) = c.onKey(Key(keys[n - 1].toString(), output = keys[n - 1].toString()))
        fun pressAheadOfMain(n: Int) {
            val late = generateSequence { mainQ.poll() }.toList()
            press(n)
            late.forEach { it.run() }
        }
        fun backspace() = c.onKey(Key("", action = KeyAction.BACKSPACE))
        fun shown(): List<String> = c.candidateWords().filter { it.last().isDigit() }
        fun settled(n: Int): List<String> = if (model && n >= RERANK_MIN_KEYS) engine.reranked(n) else engine.words(n)
        fun state() = "${host.commits}|${c.composingPrefix()}|${c.rawComposingForTest()}"

        override fun close() {
            engine.speedUp()
            worker.shutdownNow()
        }
    }

    private fun aDecodeSlowerThanTheKeysStillMovesTheListForward(nine: Boolean, model: Boolean) =
        Typing(nine, model).use { t ->
            t.engine.slowDown()
            t.press(1)
            t.engine.awaitDecoding(t.input(1))
            for (n in 2..12 step 2) {
                t.press(n)
                t.engine.awaitDecoding(t.input(n))
                if (n == 12) break
                t.press(n + 1)
                t.engine.finish(t.input(n))
                t.engine.awaitDecoding(t.input(n + 1))
                t.main()
                assertEquals("key ${n + 1} leaves the list of the first $n keys on screen", t.engine.words(n), t.shown())
                assertTrue("the decode of the latest key is still owed", t.lane.pending)
            }
            t.engine.finish(t.input(12))
            t.settle()
            assertEquals("the last key ends on the list of the whole input", t.settled(12), t.shown())
            assertFalse(t.lane.pending)
            assertEquals(
                "no more than one decode is given up between two lists",
                (2..12 step 2).map { t.input(it) },
                t.engine.decoded.toList(),
            )
            if (model) assertEquals("only the list of the whole input goes to the model", 1, t.scorer.contexts.size)
        }

    @Test fun a_decode_slower_than_the_keys_still_moves_the_list_forward_on_26key() =
        aDecodeSlowerThanTheKeysStillMovesTheListForward(nine = false, model = false)

    @Test fun a_decode_slower_than_the_keys_still_moves_the_list_forward_on_9key() =
        aDecodeSlowerThanTheKeysStillMovesTheListForward(nine = true, model = false)

    @Test fun a_decode_slower_than_the_keys_still_moves_the_list_forward_with_the_model_on_26key() =
        aDecodeSlowerThanTheKeysStillMovesTheListForward(nine = false, model = true)

    @Test fun a_decode_slower_than_the_keys_still_moves_the_list_forward_with_the_model_on_9key() =
        aDecodeSlowerThanTheKeysStillMovesTheListForward(nine = true, model = true)

    private fun aFinishedDecodeOvertakenOnTheMainThreadStillMovesTheListForward(nine: Boolean, model: Boolean) =
        Typing(nine, model).use { t ->
            t.press(1)
            for (n in 2..12) {
                t.idle()
                t.pressAheadOfMain(n)
                if (n % 2 == 1) assertEquals("key $n leaves the list of the first ${n - 1} keys on screen", t.engine.words(n - 1), t.shown())
            }
            t.settle()
            assertEquals("the last key ends on the list of the whole input", t.settled(12), t.shown())
        }

    @Test fun a_finished_decode_overtaken_on_the_main_thread_still_moves_the_list_forward_on_26key() =
        aFinishedDecodeOvertakenOnTheMainThreadStillMovesTheListForward(nine = false, model = false)

    @Test fun a_finished_decode_overtaken_on_the_main_thread_still_moves_the_list_forward_on_9key() =
        aFinishedDecodeOvertakenOnTheMainThreadStillMovesTheListForward(nine = true, model = false)

    @Test fun a_finished_decode_overtaken_on_the_main_thread_still_moves_the_list_forward_with_the_model_on_26key() =
        aFinishedDecodeOvertakenOnTheMainThreadStillMovesTheListForward(nine = false, model = true)

    @Test fun a_finished_decode_overtaken_on_the_main_thread_still_moves_the_list_forward_with_the_model_on_9key() =
        aFinishedDecodeOvertakenOnTheMainThreadStillMovesTheListForward(nine = true, model = true)

    private fun keysTheDecodeKeepsUpWithAreEachDecodedAndShown(nine: Boolean, model: Boolean) =
        Typing(nine, model).use { t ->
            for (n in 1..12) {
                t.press(n)
                t.settle()
                assertEquals(t.settled(n), t.shown())
                assertFalse(t.lane.pending)
            }
            assertEquals((1..12).map { t.input(it) }, t.engine.decoded.toList())
            if (model) assertEquals(12 - RERANK_MIN_KEYS + 1, t.scorer.contexts.size)
        }

    @Test fun keys_the_decode_keeps_up_with_are_each_decoded_and_shown_on_26key() =
        keysTheDecodeKeepsUpWithAreEachDecodedAndShown(nine = false, model = false)

    @Test fun keys_the_decode_keeps_up_with_are_each_decoded_and_shown_on_9key() =
        keysTheDecodeKeepsUpWithAreEachDecodedAndShown(nine = true, model = false)

    @Test fun keys_the_decode_keeps_up_with_are_each_decoded_and_shown_with_the_model_on_26key() =
        keysTheDecodeKeepsUpWithAreEachDecodedAndShown(nine = false, model = true)

    @Test fun keys_the_decode_keeps_up_with_are_each_decoded_and_shown_with_the_model_on_9key() =
        keysTheDecodeKeepsUpWithAreEachDecodedAndShown(nine = true, model = true)

    private fun oneKeyThatOvertakesADecodeStillCancelsIt(nine: Boolean) =
        Typing(nine, model = false).use { t ->
            for (n in 1..4) {
                t.press(n)
                t.settle()
            }
            t.engine.slowDown()
            t.press(5)
            t.engine.awaitDecoding(t.input(5))
            t.press(6)
            t.engine.awaitDecoding(t.input(6))
            t.main()
            assertEquals("the list on screen is one key old, so nothing is shown in between", t.engine.words(4), t.shown())
            t.engine.finish(t.input(6))
            t.settle()
            assertEquals(t.engine.words(6), t.shown())
            assertEquals("the overtaken decode was given up", listOf(t.input(6)), t.engine.decoded.toList())
        }

    @Test fun one_key_that_overtakes_a_decode_still_cancels_it_on_26key() = oneKeyThatOvertakesADecodeStillCancelsIt(nine = false)

    @Test fun one_key_that_overtakes_a_decode_still_cancels_it_on_9key() = oneKeyThatOvertakesADecodeStillCancelsIt(nine = true)

    private fun repeatedBackspaceFasterThanTheDecodeStillMovesTheListBack(nine: Boolean, model: Boolean) =
        Typing(nine, model).use { t ->
            for (n in 1..8) {
                t.press(n)
                t.settle()
            }
            t.engine.slowDown()
            t.backspace()
            t.engine.awaitDecoding(t.input(7))
            t.backspace()
            t.engine.awaitDecoding(t.input(6))
            t.backspace()
            t.engine.finish(t.input(6))
            t.engine.awaitDecoding(t.input(5))
            t.main()
            assertEquals("the third backspace leaves the list of the first 6 keys on screen", t.engine.words(6), t.shown())
            val before = t.state()
            t.c.onPickCandidate(0)
            assertEquals("a list for longer input than is typed is not picked from", before, t.state())
            t.engine.finish(t.input(5))
            t.settle()
            assertEquals(t.settled(5), t.shown())
            assertEquals(listOf(t.input(6), t.input(5)), t.engine.decoded.toList())
        }

    @Test fun repeated_backspace_faster_than_the_decode_still_moves_the_list_back_on_26key() =
        repeatedBackspaceFasterThanTheDecodeStillMovesTheListBack(nine = false, model = false)

    @Test fun repeated_backspace_faster_than_the_decode_still_moves_the_list_back_on_9key() =
        repeatedBackspaceFasterThanTheDecodeStillMovesTheListBack(nine = true, model = false)

    @Test fun repeated_backspace_faster_than_the_decode_still_moves_the_list_back_with_the_model_on_26key() =
        repeatedBackspaceFasterThanTheDecodeStillMovesTheListBack(nine = false, model = true)

    @Test fun repeated_backspace_faster_than_the_decode_still_moves_the_list_back_with_the_model_on_9key() =
        repeatedBackspaceFasterThanTheDecodeStillMovesTheListBack(nine = true, model = true)

    private fun aListShownAheadOfTheLatestKeyPicksTheStartOfTheInput(nine: Boolean, model: Boolean) =
        Typing(nine, model).use { t ->
            t.engine.slowDown()
            t.press(1)
            t.engine.awaitDecoding(t.input(1))
            t.press(2)
            t.engine.awaitDecoding(t.input(2))
            t.press(3)
            t.engine.finish(t.input(2))
            t.engine.awaitDecoding(t.input(3))
            t.main()
            assertEquals(t.engine.words(2), t.shown())
            t.c.onPickCandidate(0)
            val picked = "[]|${t.engine.words(2).first()}|${t.input(3).drop(2)}"
            assertEquals("the pick takes the keys its list covers and leaves the key typed after it", picked, t.state())
            t.engine.speedUp()
            t.settle()
            assertEquals("no late decode undoes the pick", picked, t.state())
        }

    @Test fun a_list_shown_ahead_of_the_latest_key_picks_the_start_of_the_input_on_26key() =
        aListShownAheadOfTheLatestKeyPicksTheStartOfTheInput(nine = false, model = false)

    @Test fun a_list_shown_ahead_of_the_latest_key_picks_the_start_of_the_input_on_9key() =
        aListShownAheadOfTheLatestKeyPicksTheStartOfTheInput(nine = true, model = false)

    @Test fun a_list_shown_ahead_of_the_latest_key_picks_the_start_of_the_input_with_the_model_on_26key() =
        aListShownAheadOfTheLatestKeyPicksTheStartOfTheInput(nine = false, model = true)

    @Test fun a_list_shown_ahead_of_the_latest_key_picks_the_start_of_the_input_with_the_model_on_9key() =
        aListShownAheadOfTheLatestKeyPicksTheStartOfTheInput(nine = true, model = true)

    private fun spaceCommitsTheWholeInputWithoutWaitingForAnOvertakenDecode(nine: Boolean, model: Boolean) =
        Typing(nine, model, settleMillis = 10_000L).use { t ->
            t.engine.slowDown()
            t.press(1)
            t.engine.awaitDecoding(t.input(1))
            t.press(2)
            t.engine.awaitDecoding(t.input(2))
            t.press(3)
            t.engine.finish(t.input(3))
            val started = System.nanoTime()
            t.c.onKey(Key(" ", action = KeyAction.SPACE))
            val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals("space commits the first choice of the whole input", listOf(t.engine.words(3).first()), t.host.commits)
            assertTrue("space did not wait for the overtaken decode: $waited ms", waited < 5_000L)
            assertEquals("the worker decoded the whole input and gave the overtaken decode up", listOf(t.input(3)), t.engine.decoded.toList())
        }

    @Test fun space_commits_the_whole_input_without_waiting_for_an_overtaken_decode_on_26key() =
        spaceCommitsTheWholeInputWithoutWaitingForAnOvertakenDecode(nine = false, model = false)

    @Test fun space_commits_the_whole_input_without_waiting_for_an_overtaken_decode_on_9key() =
        spaceCommitsTheWholeInputWithoutWaitingForAnOvertakenDecode(nine = true, model = false)

    @Test fun space_commits_the_whole_input_without_waiting_for_an_overtaken_decode_with_the_model_on_26key() =
        spaceCommitsTheWholeInputWithoutWaitingForAnOvertakenDecode(nine = false, model = true)

    @Test fun space_commits_the_whole_input_without_waiting_for_an_overtaken_decode_with_the_model_on_9key() =
        spaceCommitsTheWholeInputWithoutWaitingForAnOvertakenDecode(nine = true, model = true)
}
