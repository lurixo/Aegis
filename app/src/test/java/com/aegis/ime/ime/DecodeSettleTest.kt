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
import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.DecodeCancellation
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DecodeSettleTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val mainThread: Thread = Thread.currentThread()

    private inner class Editor : ImeHost {
        val text = StringBuilder()
        val commits = ArrayList<String>()
        val reads = AtomicInteger(0)
        val offMain = ConcurrentLinkedQueue<String>()

        private fun onMain(call: String) {
            if (Thread.currentThread() !== mainThread) offMain.add(call)
        }

        override fun commitText(text: CharSequence) {
            onMain("commitText")
            commits.add(text.toString())
            this.text.append(text)
        }

        override fun deleteBackward() {
            onMain("deleteBackward")
            if (text.isNotEmpty()) text.setLength(text.length - 1)
        }

        override fun performEnter() = onMain("performEnter")

        override fun textBeforeCursor(n: Int): CharSequence {
            onMain("textBeforeCursor")
            reads.incrementAndGet()
            return text.takeLast(n).toString()
        }

        override fun hasSelection(): Boolean {
            onMain("hasSelection")
            return false
        }
    }

    private inner class Tracking(private val inner: CandidateEngine) : CandidateEngine by inner {
        val mainDecodes = AtomicInteger(0)
        val workerDecodes = AtomicInteger(0)
        val contexts = ConcurrentLinkedQueue<String>()
        val spelledOnMain = AtomicInteger(0)
        val spelledOffMain = AtomicInteger(0)
        val learned = ConcurrentLinkedQueue<String>()
        @Volatile var hold = false
        val entered = Semaphore(0)
        @Volatile var release = CountDownLatch(1)

        private fun decoding(context: CharSequence) {
            contexts.add(context.toString())
            if (Thread.currentThread() === mainThread) {
                mainDecodes.incrementAndGet()
                return
            }
            if (hold) {
                entered.release()
                while (release.count > 0L) {
                    DecodeCancellation.checkpoint()
                    Thread.sleep(1)
                }
            }
            workerDecodes.incrementAndGet()
        }

        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> {
            decoding(context)
            return inner.candidatesCovered(composing, t9, cuts, context)
        }

        override fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int>, context: CharSequence): List<Cand> {
            decoding(context)
            return inner.candidatesForLockedReadingCovered(letters, cuts, context)
        }

        override fun spelledReading(word: String, reading: String): String {
            if (Thread.currentThread() === mainThread) spelledOnMain.incrementAndGet() else spelledOffMain.incrementAndGet()
            return inner.spelledReading(word, reading)
        }

        override fun learnWord(reading: String, word: String, assembled: Boolean) {
            learned.add(word)
            inner.learnWord(reading, word, assembled)
        }
    }

    private class ThreadLane(settleMillis: Long) {
        val worker = Executors.newSingleThreadExecutor()
        val posted = ConcurrentLinkedQueue<Runnable>()
        val lane = DecodeLane(worker, Executor { posted.add(it) }, settleMillis = settleMillis)

        fun drain() {
            while (true) {
                worker.submit {}.get(60, TimeUnit.SECONDS)
                if (posted.isEmpty()) return
                while (posted.isNotEmpty()) posted.poll().run()
            }
        }

        fun close() {
            worker.shutdownNow()
        }
    }

    private class QueueLane(settleMillis: Long = DecodeLane.SETTLE_MILLIS) {
        val workerQ = ArrayDeque<Runnable>()
        val mainQ = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { workerQ.add(it) }, Executor { mainQ.add(it) }, settleMillis = settleMillis)

        fun drain() {
            while (workerQ.isNotEmpty() || mainQ.isNotEmpty()) {
                while (workerQ.isNotEmpty()) workerQ.removeFirst().run()
                while (mainQ.isNotEmpty()) mainQ.removeFirst().run()
            }
        }
    }

    private val fixtureRows = listOf(
        EngineFixture.Row("ni", "你", 900), EngineFixture.Row("hao", "好", 900), EngineFixture.Row("nihao", "你好", 950),
        EngineFixture.Row("ci", "次", 900), EngineFixture.Row("ci", "此", 850), EngineFixture.Row("shi", "是", 950),
        EngineFixture.Row("shi", "时", 920), EngineFixture.Row("ciku", "词库", 850),
    )

    private fun fixtureEngine(um: UserModel? = null) = DictEngine(
        EngineFixture.build(fixtureRows),
        EngineFixture.build(fixtureRows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) }),
        null,
        um,
    )

    private val fullAssets = FullDictTestAssets.directory
    private val fullEngine: CandidateEngine by lazy {
        DictEngine(
            BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.DICT)),
            BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.T9)),
            CharBigramLM.fromFile(File(fullAssets, FullDictTestAssets.LM)),
            initialsDict = BinaryDict.fromFile(File(fullAssets, FullDictTestAssets.JIANPIN)),
        )
    }

    private fun assumeFullAssets() = assumeTrue(
        FullDictTestAssets.available(
            File(fullAssets, FullDictTestAssets.DICT),
            File(fullAssets, FullDictTestAssets.T9),
            File(fullAssets, FullDictTestAssets.LM),
            File(fullAssets, FullDictTestAssets.JIANPIN),
        ),
    )

    private fun controller(host: ImeHost, engine: CandidateEngine, lane: DecodeLane?, nine: Boolean) =
        KeyboardController(host, engine, lane).apply {
            attachView(InputView(ctx))
            switchTextLayoutForTest(nine)
        }

    private val space = Key("空格", output = " ", action = KeyAction.SPACE)

    private fun keys(nine: Boolean, letters: String) = if (nine) T9Pinyin.toT9(letters) else letters

    private fun type(c: KeyboardController, s: String) = s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }

    private fun pick(c: KeyboardController, word: String) {
        val i = c.candidateWords().indexOf(word)
        assertTrue("candidate $word present in ${c.candidateWords()}", i >= 0)
        c.onPickCandidate(i)
    }

    private fun fastTypingWithSpacesMatchesSync(nine: Boolean) {
        assumeFullAssets()
        val script = listOf("nihao", "shijie", "women", "zhongguoren", "jintiantianqi", "xian")
        val syncHost = Editor()
        val sync = controller(syncHost, fullEngine, null, nine)
        for (word in script) { type(sync, keys(nine, word)); sync.onKey(space) }
        type(sync, keys(nine, "henhao"))

        val lane = ThreadLane(settleMillis = 60_000L)
        try {
            val host = Editor()
            val engine = Tracking(fullEngine)
            val async = controller(host, engine, lane.lane, nine)
            lane.drain()
            for (word in script) { type(async, keys(nine, word)); async.onKey(space) }
            type(async, keys(nine, "henhao"))
            lane.drain()
            assertEquals("every space commits exactly what per-key synchronous decoding commits", syncHost.commits, host.commits)
            assertTrue("something was committed", host.commits.size >= script.size)
            assertEquals("the input that followed each space lands in the same state", sync.decodeStateForTest(), async.decodeStateForTest())
            assertEquals(sync.preeditForTest(), async.preeditForTest())
            assertEquals("no space recomputed a decode on the main thread", 0, engine.mainDecodes.get())
            assertTrue("the worker never touched the input connection: ${host.offMain}", host.offMain.isEmpty())
        } finally {
            lane.close()
        }
    }

    @Test fun fast_typing_with_spaces_commits_like_sync_decoding_on_26key() = fastTypingWithSpacesMatchesSync(nine = false)

    @Test fun fast_typing_with_spaces_commits_like_sync_decoding_on_9key() = fastTypingWithSpacesMatchesSync(nine = true)

    private fun spaceWaitsForTheInFlightDecode(nine: Boolean) {
        val syncHost = Editor()
        val sync = controller(syncHost, fixtureEngine(), null, nine)
        type(sync, keys(nine, "ni")); sync.onKey(space); type(sync, keys(nine, "hao")); sync.onKey(space)

        val lane = ThreadLane(settleMillis = 60_000L)
        try {
            val host = Editor()
            val engine = Tracking(fixtureEngine())
            val c = controller(host, engine, lane.lane, nine)
            lane.drain()
            engine.hold = true
            val input = keys(nine, "ni")
            c.onKey(Key(input.substring(0, 1), output = input.substring(0, 1)))
            assertTrue(engine.entered.tryAcquire(10, TimeUnit.SECONDS))
            c.onKey(Key(input.substring(1), output = input.substring(1)))
            assertTrue("the newest decode is running and has not returned", engine.entered.tryAcquire(10, TimeUnit.SECONDS))
            assertEquals("the superseded decode stopped at a checkpoint instead of finishing", 0, engine.workerDecodes.get())
            val gate = engine.release
            Thread { Thread.sleep(80); gate.countDown() }.start()
            c.onKey(space)
            engine.hold = false
            assertEquals("space committed the decode it waited for", listOf("你"), host.commits)
            assertEquals("only the newest decode ran to completion", 1, engine.workerDecodes.get())
            type(c, keys(nine, "hao")); c.onKey(space)
            lane.drain()
            assertEquals(syncHost.commits, host.commits)
            assertEquals(sync.decodeStateForTest(), c.decodeStateForTest())
            assertEquals("nothing was decoded on the main thread", 0, engine.mainDecodes.get())
        } finally {
            lane.close()
        }
    }

    @Test fun space_waits_for_a_decode_that_has_not_returned_on_26key() = spaceWaitsForTheInFlightDecode(nine = false)

    @Test fun space_waits_for_a_decode_that_has_not_returned_on_9key() = spaceWaitsForTheInFlightDecode(nine = true)

    private fun spaceFallsBackAfterTheBound(nine: Boolean) {
        val syncHost = Editor()
        val sync = controller(syncHost, fixtureEngine(), null, nine)
        type(sync, keys(nine, "ni")); sync.onKey(space); type(sync, keys(nine, "hao")); sync.onKey(space)

        val lane = ThreadLane(settleMillis = 40L)
        try {
            val host = Editor()
            val engine = Tracking(fixtureEngine())
            val c = controller(host, engine, lane.lane, nine)
            lane.drain()
            engine.hold = true
            type(c, keys(nine, "ni"))
            assertTrue(engine.entered.tryAcquire(10, TimeUnit.SECONDS))
            val started = System.nanoTime()
            c.onKey(space)
            val spaceMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            engine.hold = false
            engine.release.countDown()
            assertEquals("the bounded wait ends in the synchronous decode", listOf("你"), host.commits)
            assertTrue("the space gave up after its bound: $spaceMillis ms", spaceMillis in 35L..10_000L)
            assertTrue("the fallback decoded on the main thread", engine.mainDecodes.get() >= 1)
            lane.drain()
            assertEquals("the abandoned worker decode never completed", 0, engine.workerDecodes.get())
            assertEquals("the late worker result neither commits nor replaces anything", listOf("你"), host.commits)
            type(c, keys(nine, "hao")); c.onKey(space)
            lane.drain()
            assertEquals(syncHost.commits, host.commits)
            assertEquals(sync.decodeStateForTest(), c.decodeStateForTest())
        } finally {
            lane.close()
        }
    }

    @Test fun space_falls_back_after_its_bound_on_26key() = spaceFallsBackAfterTheBound(nine = false)

    @Test fun space_falls_back_after_its_bound_on_9key() = spaceFallsBackAfterTheBound(nine = true)

    private fun spaceFallbackWithoutAnyWorker(nine: Boolean) {
        val syncHost = Editor()
        val sync = controller(syncHost, fixtureEngine(), null, nine)
        type(sync, keys(nine, "nihao")); sync.onKey(space)
        val queues = QueueLane(settleMillis = 20L)
        val host = Editor()
        val c = controller(host, fixtureEngine(), queues.lane, nine)
        queues.drain()
        type(c, keys(nine, "nihao"))
        c.onKey(space)
        assertEquals(syncHost.commits, host.commits)
        queues.drain()
        assertEquals("draining the stale work changes nothing", syncHost.commits, host.commits)
        assertEquals(sync.decodeStateForTest(), c.decodeStateForTest())
    }

    @Test fun space_without_a_running_worker_commits_like_sync_on_26key() = spaceFallbackWithoutAnyWorker(nine = false)

    @Test fun space_without_a_running_worker_commits_like_sync_on_9key() = spaceFallbackWithoutAnyWorker(nine = true)

    private fun assembledWordIsLearnedOnTheWorker(nine: Boolean) {
        val um = UserModel()
        val queues = QueueLane()
        val host = Editor()
        val engine = Tracking(fixtureEngine(um))
        val c = controller(host, engine, queues.lane, nine)
        type(c, keys(nine, "cishi")); queues.drain()
        pick(c, "此"); queues.drain()
        pick(c, "是")
        assertEquals("此是", host.text.toString())
        assertEquals("the dictionary is not consulted on the main thread", 0, engine.spelledOnMain.get())
        assertTrue("the word is learned only once the worker runs", um.readingSnapshot().values.none { "此是" in it })
        queues.drain()
        assertEquals(listOf("此是"), engine.learned.toList())
        assertTrue(um.readingSnapshot().values.any { "此是" in it })
        assertEquals("the dictionary is consulted once, by the queued job", 1, engine.spelledOnMain.get() + engine.spelledOffMain.get())
        type(c, keys(nine, "cishi")); queues.drain()
        assertTrue("the next decode already sees the learned word: ${c.candidateWords()}", "此是" in c.candidateWords())
        assertEquals("learned exactly once", listOf("此是"), engine.learned.toList())
    }

    @Test fun an_assembled_word_is_learned_on_the_worker_on_26key() = assembledWordIsLearnedOnTheWorker(nine = false)

    @Test fun an_assembled_word_is_learned_on_the_worker_on_9key() = assembledWordIsLearnedOnTheWorker(nine = true)

    private fun pendingLearningIsNeitherLostNorRepeated(nine: Boolean) {
        val syncModel = UserModel()
        val syncHost = Editor()
        val syncEngine = Tracking(fixtureEngine(syncModel))
        val sync = controller(syncHost, syncEngine, null, nine)
        type(sync, keys(nine, "cishi")); pick(sync, "此"); pick(sync, "是")
        type(sync, keys(nine, "cishi")); sync.onKey(space)

        val um = UserModel()
        val queues = QueueLane(settleMillis = 20L)
        val host = Editor()
        val engine = Tracking(fixtureEngine(um))
        val c = controller(host, engine, queues.lane, nine)
        type(c, keys(nine, "cishi")); queues.drain()
        pick(c, "此"); queues.drain()
        pick(c, "是")
        type(c, keys(nine, "cishi"))
        c.onKey(space)
        assertEquals("the fallback decode learned first, exactly as the synchronous path did", syncHost.commits, host.commits)
        assertEquals("the pending word was learned before the fallback decode", listOf("此是"), engine.learned.toList())
        queues.drain()
        assertEquals("every commit is learned exactly once", syncEngine.learned.toList(), engine.learned.toList())
        assertEquals(syncModel.userWordEntries(), um.userWordEntries())

        val resetModel = UserModel()
        val resetQueues = QueueLane()
        val resetEngine = Tracking(fixtureEngine(resetModel))
        val r = controller(Editor(), resetEngine, resetQueues.lane, nine)
        type(r, keys(nine, "cishi")); resetQueues.drain()
        pick(r, "此"); resetQueues.drain()
        pick(r, "是")
        r.reset()
        assertEquals("leaving the field finishes pending learning", listOf("此是"), resetEngine.learned.toList())
        resetQueues.drain()
        assertEquals(listOf("此是"), resetEngine.learned.toList())
    }

    @Test fun pending_learning_is_neither_lost_nor_repeated_on_26key() = pendingLearningIsNeitherLostNorRepeated(nine = false)

    @Test fun pending_learning_is_neither_lost_nor_repeated_on_9key() = pendingLearningIsNeitherLostNorRepeated(nine = true)

    private fun userWordsArePreparedAfterPredictionsAreDelivered(nine: Boolean) {
        val queues = QueueLane()
        val prepared = ArrayList<Int>()
        val engine = object : CandidateEngine by fixtureEngine() {
            override fun predict(prevWord: String?): List<String> = listOf("吗")
            override fun prepareUserWords() { prepared.add(queues.mainQ.size) }
        }
        val c = controller(Editor(), engine, queues.lane, nine)
        type(c, keys(nine, "nihao")); queues.drain()
        prepared.clear()
        c.onKey(space)
        assertTrue("nothing runs before the worker does", prepared.isEmpty())
        while (queues.workerQ.isNotEmpty()) queues.workerQ.removeFirst().run()
        assertEquals("user words are prepared once, after the predictions were handed to the main thread", listOf(1), prepared)
        queues.drain()
        assertEquals(listOf("吗"), c.candidateWords())
    }

    @Test fun user_words_are_prepared_after_predictions_are_delivered_on_26key() = userWordsArePreparedAfterPredictionsAreDelivered(nine = false)

    @Test fun user_words_are_prepared_after_predictions_are_delivered_on_9key() = userWordsArePreparedAfterPredictionsAreDelivered(nine = true)

    private fun cursorContextIsReadOnMainAndRefreshed(nine: Boolean) {
        val lane = ThreadLane(settleMillis = 60_000L)
        try {
            val host = Editor().apply { text.append("今天天气") }
            val engine = Tracking(fixtureEngine())
            val c = controller(host, engine, lane.lane, nine)
            lane.drain()
            c.onInputTargetChanged()
            host.reads.set(0)
            engine.contexts.clear()
            type(c, keys(nine, "nihao")); lane.drain()
            assertEquals("one read serves every keystroke of the word", 1, host.reads.get())
            assertEquals(setOf("今天天气"), engine.contexts.toSet())
            c.onKey(space); lane.drain()
            assertEquals("今天天气你好", host.text.toString())
            val afterCommit = host.reads.get()
            assertEquals("the commit forced a fresh read", 2, afterCommit)
            engine.contexts.clear()
            type(c, keys(nine, "ni")); lane.drain()
            assertEquals(afterCommit, host.reads.get())
            assertEquals(setOf("今天天气你好"), engine.contexts.toSet())

            host.text.setLength(0); host.text.append("别处")
            c.onEditorContextChanged()
            engine.contexts.clear()
            type(c, keys(nine, "hao")); lane.drain()
            assertEquals("a cursor move is read once more", afterCommit + 1, host.reads.get())
            assertEquals(setOf("别处"), engine.contexts.toSet())

            host.text.setLength(0); host.text.append("新框")
            c.onInputTargetChanged()
            engine.contexts.clear()
            type(c, keys(nine, "a")); lane.drain()
            assertEquals(setOf("新框"), engine.contexts.toSet())

            host.text.setLength(0); host.text.append("另一个输入框里已经写了很多很多字的一段话")
            c.reset()
            c.switchTextLayoutForTest(nine)
            engine.contexts.clear()
            type(c, keys(nine, "ni")); lane.drain()
            assertEquals(setOf("另一个输入框里已经写了很多很多字的一段话".takeLast(16)), engine.contexts.toSet())
            assertTrue("the worker never read the input connection: ${host.offMain}", host.offMain.isEmpty())
        } finally {
            lane.close()
        }
    }

    @Test fun the_cursor_context_is_cached_on_main_and_refreshed_on_26key() = cursorContextIsReadOnMainAndRefreshed(nine = false)

    @Test fun the_cursor_context_is_cached_on_main_and_refreshed_on_9key() = cursorContextIsReadOnMainAndRefreshed(nine = true)
}
