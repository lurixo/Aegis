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
import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.Syllable
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val assets = FullDictTestAssets.directory
    private fun assetsPresent() = FullDictTestAssets.available(
        File(assets, FullDictTestAssets.DICT),
        File(assets, FullDictTestAssets.T9),
        File(assets, FullDictTestAssets.LM),
        File(assets, FullDictTestAssets.JIANPIN),
    )

    private open class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = ""
    }

    private val engine: CandidateEngine by lazy {
        DictEngine(
            BinaryDict.fromFile(File(assets, FullDictTestAssets.DICT)),
            BinaryDict.fromFile(File(assets, FullDictTestAssets.T9)),
            CharBigramLM.fromFile(File(assets, FullDictTestAssets.LM)),
            initialsDict = BinaryDict.fromFile(File(assets, FullDictTestAssets.JIANPIN)),
        )
    }

    private class TestLane {
        val workerQ = ArrayDeque<Runnable>()
        val mainQ = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor { workerQ.add(it) }, Executor { mainQ.add(it) })
        fun runNextWorker() = workerQ.removeFirst().run()
        fun runNextMain() = mainQ.removeFirst().run()
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
    private fun switchTo(c: KeyboardController, nine: Boolean) = c.switchTextLayoutForTest(nine)
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
            val sync = syncController().also { switchTo(it, false); type(it, s) }
            val lane = TestLane()
            val async = asyncController(lane).also { switchTo(it, false); type(it, s); lane.drain() }
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
            val sync = syncController().also { switchTo(it, true); type(it, digits) }
            val lane = TestLane()
            val async = asyncController(lane).also { switchTo(it, true); type(it, digits); lane.drain() }
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
            for (layout in listOf(false, true)) {
                val is9 = layout == true
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
        val sync = syncController().also { switchTo(it, true) }
        val lane = TestLane()
        val async = asyncController(lane).also { switchTo(it, true) }
        type(sync, "64"); type(async, "64"); lane.drain()
        pick(sync, "ni"); pick(async, "ni"); lane.drain()
        assertEquals("after lock", sync.decodeStateForTest(), async.decodeStateForTest())
        type(sync, "426"); type(async, "426"); lane.drain()
        assertEquals("after lock + more digits", sync.decodeStateForTest(), async.decodeStateForTest())
    }


    @Test fun drill_path_syncEqualsAsync() {
        assumeTrue(assetsPresent())
        val sync = syncController().also { switchTo(it, false); type(it, "nihao") }
        val lane = TestLane()
        val async = asyncController(lane).also { switchTo(it, false); type(it, "nihao"); lane.drain() }
        sync.onPickReadingIndex(0)
        async.onPickReadingIndex(0); lane.drain()
        sync.onPickReadingIndex(sync.expandedReadings().indexOf("ni"))
        async.onPickReadingIndex(async.expandedReadings().indexOf("ni")); lane.drain()
        assertEquals("drilled homophone grid matches", sync.decodeStateForTest(), async.decodeStateForTest())
    }

    @Test fun a_reading_tap_lands_and_locks_while_the_remainder_decode_is_in_flight() {
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) =
                candidatesCovered(composing, t9).map { it.word }

            override fun candidatesCovered(
                composing: String,
                t9: Boolean,
                cuts: Set<Int>,
                context: CharSequence,
            ): List<Cand> = when (composing) {
                "nihao" -> listOf(Cand("你好", 5), Cand("你", 2))
                "hao" -> listOf(Cand("好", 3), Cand("号", 3))
                else -> emptyList()
            }

            override fun candidatesForLockedReadingCovered(
                letters: String,
                cuts: Set<Int>,
                context: CharSequence,
            ): List<Cand> = if (letters == "hao") listOf(Cand("好", 3), Cand("号", 3)) else emptyList()

            override fun syllablesForReading(letters: String) = when (letters) {
                "nihao" -> listOf(
                    Syllable("ni", 0, 2),
                    Syllable("hao", 2, 5),
                )
                "hao" -> listOf(Syllable("hao", 0, 3))
                else -> emptyList()
            }

            override fun homophonesForReadingAt(letters: String, index: Int): List<String> =
                if (letters == "nihao" && index == 0) listOf("你", "尼", "泥") else emptyList()
        }
        val host = object : Host() {
            val commits = mutableListOf<String>()
            override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        }
        val lane = TestLane()
        val view = InputView(ctx)
        val controller = KeyboardController(host, engine, lane.lane)
        view.onPickCandidate = { controller.onPickCandidate(it) }
        view.onPickReading = { controller.onPickReadingIndex(it) }
        controller.attachView(view)
        switchTo(controller, false)
        type(controller, "nihao")
        lane.drain()
        controller.onPickReadingIndex(0)
        lane.drain()
        controller.onPickReadingIndex(controller.expandedReadings().indexOf("ni"))
        lane.drain()
        view.showExpandedCandidates()
        val grid = view.expandedGridForTest()
        assertTrue(grid.tapCandidateForTest(controller.candidateWords().indexOf("你")))
        assertTrue("the remainder decode is in flight", lane.lane.pending)
        val queued = lane.workerQ.size

        assertTrue(grid.tapReadingForTest(grid.renderedReadingTextsForTest().indexOf("hao")))

        assertEquals("the tap is not dropped: it queues its own decode", queued + 1, lane.workerQ.size)
        assertEquals("你hao", controller.preeditForTest())
        assertEquals("你", controller.composingPrefix())

        lane.drain()

        assertEquals("the locked reading serves its candidates", listOf("好", "👍", "号"), controller.candidateWords())
        assertEquals(controller.candidateWords(), grid.renderedCandidateTextsForTest())
        assertTrue(host.commits.isEmpty())
    }

    @Test fun partial_homophone_pick_keeps_the_expanded_candidates_stable() {
        var remainderDecodes = 0
        var restoredDrillDecodes = 0
        val candidateEngine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) =
                candidatesCovered(composing, t9).map { it.word }

            override fun candidatesCovered(
                composing: String,
                t9: Boolean,
                cuts: Set<Int>,
                context: CharSequence,
            ): List<Cand> {
                if (composing == "hao") remainderDecodes++
                return when (composing) {
                    "nihao" -> listOf(Cand("你好", 5), Cand("你", 2))
                    "hao" -> listOf(Cand("好", 3), Cand("号", 3))
                    else -> emptyList()
                }
            }

            override fun syllablesForReading(letters: String) = when (letters) {
                "nihao" -> listOf(
                    Syllable("ni", 0, 2),
                    Syllable("hao", 2, 5),
                )
                "hao" -> listOf(Syllable("hao", 0, 3))
                else -> emptyList()
            }

            override fun homophonesForReadingAt(letters: String, index: Int): List<String> {
                if (letters != "nihao" || index != 0) return emptyList()
                restoredDrillDecodes++
                return listOf("你", "尼", "泥", "拟", "妮")
            }
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
        view.onPanelBackspace = { controller.onPanelBackspace() }
        var panelChanges = 0
        view.onPanelChanged = { panelChanges++ }
        controller.attachView(view)
        switchTo(controller, false)
        type(controller, "nihao")
        lane.drain()
        controller.onPickReadingIndex(0)
        lane.drain()
        controller.onPickReadingIndex(controller.expandedReadings().indexOf("ni"))
        lane.drain()
        view.showExpandedCandidates()
        val grid = view.expandedGridForTest()
        val rebuildsBefore = grid.candidateRebuildsForTest()
        val allocationsBefore = grid.chipsAllocatedForTest()
        val panelChangesBefore = panelChanges
        val candidatesBefore = grid.renderedCandidateTextsForTest()

        assertTrue(grid.tapCandidateForTest(controller.candidateWords().indexOf("你")))
        val pendingWorkersAfterPick = lane.workerQ.size

        assertTrue(lane.lane.pending)
        assertTrue(grid.tapCandidateForTest(candidatesBefore.indexOf("尼")))
        assertTrue(lane.lane.pending)

        val readingsAfterInFlightPicks = controller.expandedReadings()
        assertEquals("hao", readingsAfterInFlightPicks.first())
        assertEquals(readingsAfterInFlightPicks, grid.renderedReadingTextsForTest())
        assertEquals(-1, controller.drilledSyllableForTest())

        assertEquals("你hao", controller.preeditForTest())
        assertEquals("你", controller.composingPrefix())
        assertTrue(host.commits.isEmpty())
        assertEquals(0, remainderDecodes)
        assertTrue(controller.candidateWords().isNotEmpty())
        assertEquals(candidatesBefore, controller.candidateWords())
        assertEquals(candidatesBefore, grid.renderedCandidateTextsForTest())
        assertTrue(view.shownCandidateCount() > 0)
        assertTrue(grid.selectionContentVisibleForTest())
        assertTrue(view.isPanelShowing(grid))
        assertEquals("⌃", view.barChevronGlyph())
        assertEquals(panelChangesBefore, panelChanges)
        assertEquals(rebuildsBefore, grid.candidateRebuildsForTest())
        assertEquals(allocationsBefore, grid.chipsAllocatedForTest())
        assertEquals(pendingWorkersAfterPick, lane.workerQ.size)

        lane.drain()

        assertEquals(1, remainderDecodes)
        assertEquals("你hao", controller.preeditForTest())
        assertEquals("你", controller.composingPrefix())
        assertEquals(readingsAfterInFlightPicks, controller.expandedReadings())
        assertEquals("好", controller.candidateWords().first())
        assertEquals(controller.candidateWords(), grid.renderedCandidateTextsForTest())
        assertTrue(view.shownCandidateCount() > 0)
        assertTrue(grid.selectionContentVisibleForTest())
        assertTrue(view.isPanelShowing(grid))
        assertEquals(panelChangesBefore, panelChanges)
        assertEquals(rebuildsBefore + 1, grid.candidateRebuildsForTest())
        assertEquals(allocationsBefore, grid.chipsAllocatedForTest())

        val rebuildsBeforeUndo = grid.candidateRebuildsForTest()
        val allocationsBeforeUndo = grid.chipsAllocatedForTest()
        val panelChangesBeforeUndo = panelChanges
        val candidatesBeforeUndo = grid.renderedCandidateTextsForTest()
        val restoredDrillDecodesBeforeUndo = restoredDrillDecodes

        assertTrue(grid.backspaceButtonForTest().performClick())
        val pendingWorkersAfterUndo = lane.workerQ.size

        assertTrue(lane.lane.pending)
        assertTrue(grid.tapCandidateForTest(candidatesBeforeUndo.indexOf("好")))
        assertTrue(lane.lane.pending)

        assertEquals("ni'hao", controller.preeditForTest())
        assertEquals("", controller.composingPrefix())
        assertEquals("ni", controller.expandedReadings().first())
        assertTrue(controller.expandedReadings().contains("hao"))
        assertTrue(host.commits.isEmpty())
        assertEquals(restoredDrillDecodesBeforeUndo, restoredDrillDecodes)
        assertTrue(controller.candidateWords().isNotEmpty())
        assertEquals(candidatesBeforeUndo, controller.candidateWords())
        assertEquals(candidatesBeforeUndo, grid.renderedCandidateTextsForTest())
        assertTrue(view.shownCandidateCount() > 0)
        assertTrue(grid.selectionContentVisibleForTest())
        assertTrue(view.isPanelShowing(grid))
        assertEquals("⌃", view.barChevronGlyph())
        assertEquals(panelChangesBeforeUndo, panelChanges)
        assertEquals(rebuildsBeforeUndo, grid.candidateRebuildsForTest())
        assertEquals(allocationsBeforeUndo, grid.chipsAllocatedForTest())
        assertEquals(pendingWorkersAfterUndo, lane.workerQ.size)

        lane.drain()

        assertEquals(1, remainderDecodes)
        assertEquals(restoredDrillDecodesBeforeUndo + 1, restoredDrillDecodes)
        assertEquals("ni'hao", controller.preeditForTest())
        assertEquals("", controller.composingPrefix())
        assertEquals("ni", controller.expandedReadings().first())
        assertTrue(controller.expandedReadings().contains("hao"))
        assertEquals("你", controller.candidateWords().first())
        assertEquals(candidatesBefore, controller.candidateWords())
        assertEquals(controller.candidateWords(), grid.renderedCandidateTextsForTest())
        assertTrue(view.shownCandidateCount() > 0)
        assertTrue(grid.selectionContentVisibleForTest())
        assertTrue(view.isPanelShowing(grid))
        assertEquals("⌃", view.barChevronGlyph())
        assertEquals(panelChangesBeforeUndo, panelChanges)
        assertEquals(rebuildsBeforeUndo + 1, grid.candidateRebuildsForTest())
        assertEquals(allocationsBeforeUndo, grid.chipsAllocatedForTest())
    }

    @Test fun computed_stale_main_is_dropped_before_apply() {
        val lane = TestLane()
        val grid = CandidateGridView(ctx)
        grid.setCandidates(listOf("visible"))
        val rebuildsBefore = grid.candidateRebuildsForTest()
        var decodes = 0
        var applies = 0

        lane.lane.submit(
            compute = { decodes++; "old" },
            apply = { applies++; grid.setCandidates(listOf(it)) },
        )
        lane.runNextWorker()
        assertEquals(1, decodes)
        assertEquals(1, lane.mainQ.size)

        lane.lane.submit(
            compute = { decodes++; "current" },
            apply = { applies++; grid.setCandidates(listOf(it)) },
        )
        lane.runNextMain()

        assertEquals(1, decodes)
        assertEquals(0, applies)
        assertEquals(listOf("visible"), grid.renderedCandidateTextsForTest())
        assertEquals(rebuildsBefore, grid.candidateRebuildsForTest())
        assertTrue(lane.lane.pending)

        lane.runNextWorker()

        assertEquals(2, decodes)
        assertEquals(0, applies)
        assertEquals(listOf("visible"), grid.renderedCandidateTextsForTest())
        assertEquals(rebuildsBefore, grid.candidateRebuildsForTest())

        lane.runNextMain()

        assertEquals(2, decodes)
        assertEquals(1, applies)
        assertEquals(listOf("current"), grid.renderedCandidateTextsForTest())
        assertEquals(rebuildsBefore + 1, grid.candidateRebuildsForTest())
        assertFalse(lane.lane.pending)
    }


    @Test fun fastBurst_coalesces_to_the_same_final_decode() {
        assumeTrue(assetsPresent())
        val syls = runtimeSyllables()
        val phrases = listOf("nihaoshijie", "beijingdaxue", "zhongguoren") + syls.take(30)
        val mismatches = ArrayList<String>()
        for (p in phrases) {
            val input = p.filter { it in 'a'..'z' }
            if (input.isEmpty()) continue
            val sync = syncController().also { switchTo(it, false); type(it, input) }
            val lane = TestLane()
            val async = asyncController(lane).also { switchTo(it, false); type(it, input) }
            lane.drain()
            if (sync.decodeStateForTest() != async.decodeStateForTest()) mismatches.add(p)
        }
        assertTrue("burst-coalesced async≠sync: ${mismatches.take(20)} (${mismatches.size})", mismatches.isEmpty())
    }


    @Test fun reset_drops_an_inflight_decode_so_no_stale_candidates_survive() {
        assumeTrue(assetsPresent())
        val lane = TestLane()
        val c = asyncController(lane)
        switchTo(c, false)
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
        switchTo(c, false)
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
        switchTo(sync, false); switchTo(async, false)
        type(sync, "nihao")
        type(async, "nihao")
        sync.onKey(Key("空格", output = " ", action = KeyAction.SPACE))
        async.onKey(Key("空格", output = " ", action = KeyAction.SPACE))
        assertEquals("space commits the same text with a pending async decode as sync", committedSync, committedAsync)
        assertTrue("something was actually committed", committedSync.isNotEmpty())
    }
}
