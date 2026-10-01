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
        override val supportsChinese: Boolean = true
        override fun candidates(composing: String, t9: Boolean): List<String> = words(composing, t9)
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
            words(composing, t9).map { Cand(it, composing.length) }
        override fun rankedSentences(composing: String, t9: Boolean, context: CharSequence, limit: Int): List<Pair<String, Double>> {
            rankedCalls.add("$composing|$t9|$context|$limit")
            return if (key(composing, t9)) listOf("你好" to -10.0, "拟好" to -11.0, "你号" to -12.0) else emptyList()
        }
        private fun key(composing: String, t9: Boolean) = composing == (if (t9) "64426" else "nihao")
        private fun words(composing: String, t9: Boolean): List<String> =
            if (key(composing, t9)) listOfNotNull(leading, "你好", "拟好", "你") else emptyList()
    }

    private class Scorer : SentenceScorer {
        val contexts = ArrayList<String>()
        override fun score(context: String, candidates: List<String>): DoubleArray {
            contexts.add(context)
            return DoubleArray(candidates.size) { if (candidates[it] == "拟好") -1.0 else -5.0 }
        }
        override fun cancel() {}
        override fun close() {}
    }

    private class Queues {
        val decodeWorker = ArrayDeque<Runnable>()
        val decodeMain = ArrayDeque<Runnable>()
        val neuralWorker = ArrayDeque<Runnable>()
        val neuralMain = ArrayDeque<Runnable>()
        val timers = ArrayList<() -> Unit>()
        val schedule: (Long, () -> Unit) -> Unit = { _, task -> timers.add(task) }
        val lane = DecodeLane(Executor { decodeWorker.add(it) }, Executor { decodeMain.add(it) })
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
        engine: Engine = Engine(),
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
