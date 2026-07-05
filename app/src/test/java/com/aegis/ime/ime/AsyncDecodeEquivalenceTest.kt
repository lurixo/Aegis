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

import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

/**
 * ① ASYNC-EQUIVALENCE HARD GATE. The lane-driven controller must produce, for the same input sequence, the
 * EXACT same decode output — candidates + coverage + the direct-commit / prediction / calc tags — as the
 * synchronous controller. Proven over ALL ~415 runtime syllables on BOTH keyboards, a set of multi-syllable
 * phrases, and the locked-reading and 26-key drill paths, plus a fast-burst coalescing replay. Because both
 * controllers call the identical [computeDecode] over the identical per-keystroke snapshot, equivalence is by
 * construction; this test is the mechanical proof of that invariant (no sampling — the full syllable universe).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsyncDecodeEquivalenceTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val assets = File("src/main/assets")
    private fun assetsPresent() = File(assets, "aegis_dict.bin").exists() && File(assets, "aegis_t9.bin").exists()

    private open class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
        // Deterministic (empty) context so the ONLY variable between the two controllers is sync-vs-lane.
        override fun textBeforeCursor(n: Int): CharSequence = ""
    }

    // One shared engine: typing never commits here, so decode is a pure read and reuse keeps the 415-sweep fast.
    private val engine: CandidateEngine by lazy {
        DictEngine(
            BinaryDict.fromFile(File(assets, "aegis_dict.bin")),
            BinaryDict.fromFile(File(assets, "aegis_t9.bin")),
            CharBigramLM.fromFile(File(assets, "aegis_lm.bin")),
        )
    }

    /** A hand-driven [DecodeLane]: [drain] runs worker then main queues to quiescence, deterministically. */
    private class TestLane {
        val workerQ = ArrayDeque<Runnable>()
        val mainQ = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { workerQ.add(it) }, Executor { mainQ.add(it) })
        fun drain() {
            while (workerQ.isNotEmpty() || mainQ.isNotEmpty()) {
                while (workerQ.isNotEmpty()) workerQ.removeFirst().run()
                while (mainQ.isNotEmpty()) mainQ.removeFirst().run()
            }
        }
    }

    private fun syncController() = KeyboardController(Host(), engine).apply { attachView(InputView(ctx)) }
    private fun asyncController(lane: TestLane) =
        KeyboardController(Host(), engine, lane.lane).apply { attachView(InputView(ctx)) }

    private fun type(c: KeyboardController, s: String) =
        s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }
    private fun switchTo(c: KeyboardController, action: KeyAction) = c.onKey(Key("", action = action))
    private fun pick(c: KeyboardController, reading: String) =
        c.onKey(Key(reading, output = reading, action = KeyAction.PICK_READING))

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        return (f.get(T9Pinyin) as Set<String>).toList()
    }

    // ---------- n=1: every runtime syllable, both keyboards ----------

    @Test fun everySyllable_26key_syncEqualsAsync() {
        assumeTrue(assetsPresent())
        val syls = runtimeSyllables()
        assertTrue("runtime SYLLABLES ~415: ${syls.size}", syls.size in 400..430)
        val mismatches = ArrayList<String>()
        for (s in syls) {
            val sync = syncController().also { switchTo(it, KeyAction.SWITCH_ALPHA); type(it, s) }
            val lane = TestLane()
            val async = asyncController(lane).also { switchTo(it, KeyAction.SWITCH_ALPHA); type(it, s); lane.drain() }
            if (sync.decodeStateForTest() != async.decodeStateForTest()) mismatches.add("26:$s")
        }
        assertTrue("26-key async≠sync for: ${mismatches.take(20)} (${mismatches.size})", mismatches.isEmpty())
    }

    @Test fun everySyllable_9key_syncEqualsAsync() {
        assumeTrue(assetsPresent())
        val syls = runtimeSyllables()
        val mismatches = ArrayList<String>()
        for (s in syls) {
            val digits = T9Pinyin.toT9(s)
            if (digits.isEmpty()) continue
            val sync = syncController().also { switchTo(it, KeyAction.SWITCH_NINE); type(it, digits) }
            val lane = TestLane()
            val async = asyncController(lane).also { switchTo(it, KeyAction.SWITCH_NINE); type(it, digits); lane.drain() }
            if (sync.decodeStateForTest() != async.decodeStateForTest()) mismatches.add("9:$s($digits)")
        }
        assertTrue("9-key async≠sync for: ${mismatches.take(20)} (${mismatches.size})", mismatches.isEmpty())
    }

    // ---------- multi-syllable phrases, incremental (candidates match at EVERY prefix length) ----------

    @Test fun multiSyllable_everyPrefix_syncEqualsAsync() {
        assumeTrue(assetsPresent())
        val phrases = listOf(
            "nihao", "beijing", "zhongguo", "women", "shijian", "pengyou", "xiexie", "jintian",
            "gongzuo", "shenghuo", "diannao", "shouji", "xuexiao", "laoshi", "haode", "jia", "cheng",
        )
        val mismatches = ArrayList<String>()
        for (p in phrases) {
            for (layout in listOf(KeyAction.SWITCH_ALPHA, KeyAction.SWITCH_NINE)) {
                val is9 = layout == KeyAction.SWITCH_NINE
                val input = if (is9) T9Pinyin.toT9(p) else p
                val sync = syncController().also { switchTo(it, layout) }
                val lane = TestLane()
                val async = asyncController(lane).also { switchTo(it, layout) }
                // Type char by char, comparing after each keystroke (async drained) — the item-by-item-replay contract.
                for (ch in input) {
                    sync.onKey(Key(ch.toString(), output = ch.toString()))
                    async.onKey(Key(ch.toString(), output = ch.toString())); lane.drain()
                    if (sync.decodeStateForTest() != async.decodeStateForTest())
                        mismatches.add("${if (is9) "9" else "26"}:$p@${async.preeditForTest()}")
                }
            }
        }
        assertTrue("multi-syllable async≠sync: ${mismatches.take(20)} (${mismatches.size})", mismatches.isEmpty())
    }

    // ---------- locked-reading path (9-key ★E): pick a reading then keep typing ----------

    @Test fun lockedReading_path_syncEqualsAsync() {
        assumeTrue(assetsPresent())
        val sync = syncController().also { switchTo(it, KeyAction.SWITCH_NINE) }
        val lane = TestLane()
        val async = asyncController(lane).also { switchTo(it, KeyAction.SWITCH_NINE) }
        // type ni(64) → lock "ni" → type hao(426); the RICH locked decode must match on both.
        type(sync, "64"); type(async, "64"); lane.drain()
        pick(sync, "ni"); pick(async, "ni"); lane.drain()
        assertEquals("after lock", sync.decodeStateForTest(), async.decodeStateForTest())
        type(sync, "426"); type(async, "426"); lane.drain()
        assertEquals("after lock + more digits", sync.decodeStateForTest(), async.decodeStateForTest())
    }

    // ---------- 26-key drill path (UI-2): drill syllable 0's homophone grid ----------

    @Test fun drill_path_syncEqualsAsync() {
        assumeTrue(assetsPresent())
        val sync = syncController().also { switchTo(it, KeyAction.SWITCH_ALPHA); type(it, "nihao") }
        val lane = TestLane()
        val async = asyncController(lane).also { switchTo(it, KeyAction.SWITCH_ALPHA); type(it, "nihao"); lane.drain() }
        sync.onPickReadingIndex(0)             // drill syllable 0 → homophone grid
        async.onPickReadingIndex(0); lane.drain()
        assertEquals("drilled homophone grid matches", sync.decodeStateForTest(), async.decodeStateForTest())
    }

    // ---------- coalescing: a fast burst (no draining) still lands on the SAME final decode ----------

    @Test fun fastBurst_coalesces_to_the_same_final_decode() {
        assumeTrue(assetsPresent())
        val syls = runtimeSyllables()
        val phrases = listOf("nihaoshijie", "beijingdaxue", "zhongguoren") + syls.take(30)
        val mismatches = ArrayList<String>()
        for (p in phrases) {
            val input = p.filter { it in 'a'..'z' }
            if (input.isEmpty()) continue
            val sync = syncController().also { switchTo(it, KeyAction.SWITCH_ALPHA); type(it, input) }
            val lane = TestLane()
            // Type the WHOLE burst before draining once: only the final keystroke's decode should compute,
            // and it must equal the fully-synchronous result.
            val async = asyncController(lane).also { switchTo(it, KeyAction.SWITCH_ALPHA); type(it, input) }
            lane.drain()
            if (sync.decodeStateForTest() != async.decodeStateForTest()) mismatches.add(p)
        }
        assertTrue("burst-coalesced async≠sync: ${mismatches.take(20)} (${mismatches.size})", mismatches.isEmpty())
    }

    // ---------- a state teardown drops an in-flight decode (no stale candidates survive) ----------

    @Test fun reset_drops_an_inflight_decode_so_no_stale_candidates_survive() {
        assumeTrue(assetsPresent())
        val lane = TestLane()
        val c = asyncController(lane)
        switchTo(c, KeyAction.SWITCH_ALPHA)
        type(c, "ni"); lane.drain()
        assertTrue("precondition: candidates populated", c.candidateWords().isNotEmpty())
        type(c, "h")          // a fresh decode is now IN FLIGHT (not drained)
        c.reset()             // field switch mid-decode
        lane.drain()          // the in-flight worker result lands…
        assertTrue("a late decode must not repopulate candidates after reset", c.candidateWords().isEmpty())
    }

    @Test fun backspaceSwipe_clear_drops_an_inflight_decode() {
        assumeTrue(assetsPresent())
        val lane = TestLane()
        val c = asyncController(lane)
        switchTo(c, KeyAction.SWITCH_ALPHA)
        type(c, "ni"); lane.drain()
        assertTrue(c.candidateWords().isNotEmpty())
        type(c, "h")                 // in-flight decode
        assertTrue("up-swipe consumes the gesture and clears the buffer", c.onBackspaceSwipe(true))
        lane.drain()
        assertTrue("a late decode must not survive a backspace-swipe clear", c.candidateWords().isEmpty())
    }

    // ---------- space commit resolves an in-flight decode to the SAME candidate as sync ----------

    @Test fun spaceCommit_whileDecodePending_matchesSync() {
        assumeTrue(assetsPresent())
        val committedSync = ArrayList<CharSequence>()
        val committedAsync = ArrayList<CharSequence>()
        val syncHost = object : Host() { override fun commitText(text: CharSequence) { committedSync.add(text) } }
        val asyncHost = object : Host() { override fun commitText(text: CharSequence) { committedAsync.add(text) } }
        val sync = KeyboardController(syncHost, engine).apply { attachView(InputView(ctx)) }
        val lane = TestLane()
        val async = KeyboardController(asyncHost, engine, lane.lane).apply { attachView(InputView(ctx)) }
        switchTo(sync, KeyAction.SWITCH_ALPHA); switchTo(async, KeyAction.SWITCH_ALPHA)
        type(sync, "nihao")
        type(async, "nihao") // DELIBERATELY do NOT drain — the decode is still "in flight"
        // Space must commit the current best candidate on both; async resolves the pending decode synchronously.
        sync.onKey(Key("空格", output = " ", action = KeyAction.SPACE))
        async.onKey(Key("空格", output = " ", action = KeyAction.SPACE))
        assertEquals("space commits the same text with a pending async decode as sync", committedSync, committedAsync)
        assertTrue("something was actually committed", committedSync.isNotEmpty())
    }
}
