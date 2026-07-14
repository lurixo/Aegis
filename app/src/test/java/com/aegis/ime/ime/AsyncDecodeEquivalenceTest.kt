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
import com.aegis.ime.decoder.Syllable
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
        override fun textBeforeCursor(n: Int): CharSequence = ""
    }

    private val engine: CandidateEngine by lazy {
        DictEngine(
            BinaryDict.fromFile(File(assets, "aegis_dict.bin")),
            BinaryDict.fromFile(File(assets, "aegis_t9.bin")),
            CharBigramLM.fromFile(File(assets, "aegis_lm.bin")),
        )
    }

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


    @Test fun lockedReading_path_syncEqualsAsync() {
        assumeTrue(assetsPresent())
        val sync = syncController().also { switchTo(it, KeyAction.SWITCH_NINE) }
        val lane = TestLane()
        val async = asyncController(lane).also { switchTo(it, KeyAction.SWITCH_NINE) }
        type(sync, "64"); type(async, "64"); lane.drain()
        pick(sync, "ni"); pick(async, "ni"); lane.drain()
        assertEquals("after lock", sync.decodeStateForTest(), async.decodeStateForTest())
        type(sync, "426"); type(async, "426"); lane.drain()
        assertEquals("after lock + more digits", sync.decodeStateForTest(), async.decodeStateForTest())
    }


    @Test fun drill_path_syncEqualsAsync() {
        assumeTrue(assetsPresent())
        val sync = syncController().also { switchTo(it, KeyAction.SWITCH_ALPHA); type(it, "nihao") }
        val lane = TestLane()
        val async = asyncController(lane).also { switchTo(it, KeyAction.SWITCH_ALPHA); type(it, "nihao"); lane.drain() }
        sync.onPickReadingIndex(0)
        async.onPickReadingIndex(0); lane.drain()
        assertEquals("drilled homophone grid matches", sync.decodeStateForTest(), async.decodeStateForTest())
    }

    @Test fun partial_homophone_pick_keeps_the_expanded_candidates_stable() {
        val candidateEngine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) =
                candidatesCovered(composing, t9).map { it.word }

            override fun candidatesCovered(
                composing: String,
                t9: Boolean,
                cuts: Set<Int>,
                context: CharSequence,
            ) = when (composing) {
                "nihao" -> listOf(Cand("你好", 5), Cand("你", 2))
                "hao" -> listOf(Cand("好", 3), Cand("号", 3))
                else -> emptyList()
            }

            override fun syllablesForReading(letters: String) = when (letters) {
                "nihao" -> listOf(
                    Syllable("ni", 0, 2),
                    Syllable("hao", 2, 5),
                )
                "hao" -> listOf(Syllable("hao", 0, 3))
                else -> emptyList()
            }

            override fun homophonesForReadingAt(letters: String, index: Int) =
                if (letters == "nihao" && index == 0) listOf("你", "尼", "泥", "拟", "妮") else emptyList()
        }
        val host = object : Host() {
            val commits = mutableListOf<String>()
            override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        }
        val lane = TestLane()
        val view = InputView(ctx)
        val controller = KeyboardController(host, candidateEngine, lane.lane)
        view.onPickCandidate = { controller.onPickCandidate(it) }
        view.onPickReading = { controller.onPickReadingIndex(it) }
        view.onExpandClosed = { controller.clearDrill() }
        var panelChanges = 0
        view.onPanelChanged = { panelChanges++ }
        controller.attachView(view)
        switchTo(controller, KeyAction.SWITCH_ALPHA)
        type(controller, "nihao")
        lane.drain()
        controller.onPickReadingIndex(0)
        lane.drain()
        view.showExpandedCandidates()
        val grid = view.expandedGridForTest()
        val rebuildsBefore = grid.candidateRebuildsForTest()
        val allocationsBefore = grid.chipsAllocatedForTest()
        val panelChangesBefore = panelChanges

        assertTrue(grid.tapCandidateForTest(controller.candidateWords().indexOf("你")))

        assertEquals("你hao", controller.preeditForTest())
        assertEquals("你", controller.composingPrefix())
        assertEquals(listOf("hao"), controller.expandedReadings())
        assertTrue(host.commits.isEmpty())
        assertTrue(controller.candidateWords().isNotEmpty())
        assertEquals("好", controller.candidateWords().first())
        assertEquals(controller.candidateWords(), grid.renderedCandidateTextsForTest())
        assertTrue(view.shownCandidateCount() > 0)
        assertTrue(grid.selectionContentVisibleForTest())
        assertTrue(view.isPanelShowing(grid))
        assertEquals("⌃", view.barChevronGlyph())
        assertEquals(panelChangesBefore, panelChanges)
        assertEquals(rebuildsBefore + 1, grid.candidateRebuildsForTest())
        assertEquals(allocationsBefore, grid.chipsAllocatedForTest())

        lane.drain()

        assertEquals("你hao", controller.preeditForTest())
        assertEquals("好", controller.candidateWords().first())
        assertEquals(controller.candidateWords(), grid.renderedCandidateTextsForTest())
        assertTrue(view.shownCandidateCount() > 0)
        assertTrue(grid.selectionContentVisibleForTest())
        assertTrue(view.isPanelShowing(grid))
        assertEquals(panelChangesBefore, panelChanges)
        assertEquals(rebuildsBefore + 1, grid.candidateRebuildsForTest())
        assertEquals(allocationsBefore, grid.chipsAllocatedForTest())
    }


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
            val async = asyncController(lane).also { switchTo(it, KeyAction.SWITCH_ALPHA); type(it, input) }
            lane.drain()
            if (sync.decodeStateForTest() != async.decodeStateForTest()) mismatches.add(p)
        }
        assertTrue("burst-coalesced async≠sync: ${mismatches.take(20)} (${mismatches.size})", mismatches.isEmpty())
    }


    @Test fun reset_drops_an_inflight_decode_so_no_stale_candidates_survive() {
        assumeTrue(assetsPresent())
        val lane = TestLane()
        val c = asyncController(lane)
        switchTo(c, KeyAction.SWITCH_ALPHA)
        type(c, "ni"); lane.drain()
        assertTrue("precondition: candidates populated", c.candidateWords().isNotEmpty())
        type(c, "h")
        c.reset()
        lane.drain()
        assertTrue("a late decode must not repopulate candidates after reset", c.candidateWords().isEmpty())
    }

    @Test fun backspaceSwipe_clear_drops_an_inflight_decode() {
        assumeTrue(assetsPresent())
        val lane = TestLane()
        val c = asyncController(lane)
        switchTo(c, KeyAction.SWITCH_ALPHA)
        type(c, "ni"); lane.drain()
        assertTrue(c.candidateWords().isNotEmpty())
        type(c, "h")
        assertTrue("up-swipe consumes the gesture and clears the buffer", c.onBackspaceSwipe(true))
        lane.drain()
        assertTrue("a late decode must not survive a backspace-swipe clear", c.candidateWords().isEmpty())
    }


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
        type(async, "nihao")
        sync.onKey(Key("空格", output = " ", action = KeyAction.SPACE))
        async.onKey(Key("空格", output = " ", action = KeyAction.SPACE))
        assertEquals("space commits the same text with a pending async decode as sync", committedSync, committedAsync)
        assertTrue("something was actually committed", committedSync.isNotEmpty())
    }
}
