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

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.decoder.Cand
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.InputAssociations
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.SymbolCatalog
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandidatePagingIntegrationTest {

    private class Host : ImeHost {
        val commits = ArrayList<String>()
        var beforeCursor = ""
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = beforeCursor.takeLast(n)
    }

    private class PagingEngine : CandidateEngine {
        fun words(input: String): List<String> = List(75) { "$input-候选$it" }
        val predictions = List(75) { "预测$it" }

        override fun candidates(composing: String, t9: Boolean): List<String> = words(composing)

        override fun candidatesCovered(
            composing: String,
            t9: Boolean,
            cuts: Set<Int>,
            context: CharSequence,
        ): List<Cand> = words(composing).map { Cand(it, composing.length) }

        override fun predict(prevWord: String?): List<String> = if (prevWord == null) emptyList() else predictions
    }

    private class TestLane {
        val worker = ArrayDeque<Runnable>()
        val main = ArrayDeque<Runnable>()
        val lane = DecodeLane(Executor(worker::addLast), Executor(main::addLast))

        fun drain() {
            while (worker.isNotEmpty() || main.isNotEmpty()) {
                while (worker.isNotEmpty()) worker.removeFirst().run()
                while (main.isNotEmpty()) main.removeFirst().run()
            }
        }
    }

    private fun type(controller: KeyboardController, text: String) {
        text.forEach { controller.onKey(Key(it.toString(), output = it.toString())) }
    }

    private fun controller(
        engine: PagingEngine,
        host: Host = Host(),
        lane: DecodeLane? = null,
        view: InputView? = null,
    ): KeyboardController {
        val controller = KeyboardController(host, engine, lane)
        controller.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        if (view != null) {
            view.onRequestMoreCandidates = controller::requestMoreCandidates
            view.onRequestMoreReadings = controller::requestMoreReadings
            controller.attachView(view)
        }
        return controller
    }

    @Test
    fun normalCandidatesLoadInThirtyItemPagesUntilEveryResultIsReachable() {
        val engine = PagingEngine()
        val controller = controller(engine)
        assertTrue(InputAssociations.lookup("qzx").isEmpty())

        type(controller, "qzx")
        assertEquals(engine.words("qzx").take(30), controller.candidateWords())
        assertTrue(controller.hasMoreCandidatesForTest())

        controller.requestMoreCandidates()
        assertEquals(engine.words("qzx").take(60), controller.candidateWords())
        controller.requestMoreCandidates()
        assertEquals(engine.words("qzx"), controller.candidateWords())
        assertFalse(controller.hasMoreCandidatesForTest())
    }

    @Test
    fun pendingContinuationCannotAppendAfterTheInputEpochChanges() {
        val engine = PagingEngine()
        val lane = TestLane()
        val controller = controller(engine, lane = lane.lane)

        type(controller, "qzx")
        lane.drain()
        assertEquals(engine.words("qzx").take(30), controller.candidateWords())

        controller.requestMoreCandidates()
        type(controller, "v")
        lane.drain()

        assertEquals(engine.words("qzxv").take(30), controller.candidateWords())
        assertTrue(controller.candidateWords().none { it.startsWith("qzx-") })
    }

    @Test
    fun pendingContinuationCannotAppendAfterTheHostContextChanges() {
        val engine = PagingEngine()
        val host = Host()
        val lane = TestLane()
        val controller = controller(engine, host, lane.lane)

        type(controller, "qzx")
        lane.drain()
        controller.requestMoreCandidates()
        host.beforeCursor = "甲"
        controller.onHostContextChanged()
        lane.drain()

        assertEquals(engine.words("qzx").take(30), controller.candidateWords())
        assertTrue(controller.hasMoreCandidatesForTest())
    }

    @Test
    fun everySymbolAndEmojiAssociationRemainsOrderedAheadOfTheCandidateTail() {
        val entry = InputAssociations.entriesForTest().maxBy { it.value.size }
        val associations = ArrayList<String>()
        val folded = HashSet<String>()
        for (glyph in entry.value) {
            if (folded.add(SymbolCatalog.foldFullWidth(glyph))) associations.add(glyph)
        }
        assertTrue(associations.size > 3)
        val engine = PagingEngine()
        val controller = controller(engine)

        type(controller, entry.key)
        controller.requestAllCandidatesForTest()

        val base = engine.words(entry.key)
        assertEquals(base.take(1) + associations + base.drop(1), controller.candidateWords())
    }

    @Test
    fun emptyPreeditPredictionsStayExpandedAndContinueThroughTheGridCallback() {
        val engine = PagingEngine()
        val host = Host()
        val view = InputView(RuntimeEnvironment.getApplication())
        val controller = controller(engine, host, view = view)

        type(controller, "qzx")
        controller.onPickCandidate(0)

        assertEquals("", controller.preeditForTest())
        assertEquals(engine.predictions.take(30), controller.candidateWords())
        view.showExpandedCandidates()
        assertTrue(view.panelShown)
        assertEquals(engine.predictions.take(30), view.expandedGridForTest().renderedCandidateTextsForTest())

        view.expandedGridForTest().requestMoreCandidatesForTest()
        assertEquals(engine.predictions.take(60), view.expandedGridForTest().renderedCandidateTextsForTest())
        controller.requestMoreCandidates()
        assertEquals(engine.predictions, view.expandedGridForTest().renderedCandidateTextsForTest())
        assertTrue(view.panelShown)
    }

    @Test
    fun emptyInitialBatchCanRequestTheNextLazyBatchFromTheExpandControl() {
        val view = InputView(RuntimeEnvironment.getApplication())
        var requests = 0
        view.onRequestMoreCandidates = { requests++ }
        view.showCandidates(emptyList(), "zzzz", emptyList())

        view.showExpandedCandidates()

        assertEquals(1, requests)
        assertFalse(view.panelShown)
    }

    @Test
    fun appendingCandidatePagesPreservesTheExpandedListViewport() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val view = InputView(activity)
        root.addView(view)
        activity.setContentView(root)
        val density = activity.resources.displayMetrics.density
        val width = (360 * density).toInt()
        val height = (500 * density).toInt()
        fun layout() {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
        }
        layout()
        val first = List(30) { "候选-$it" }
        view.showCandidates(first, "qzx", listOf("qzx"))
        view.showExpandedCandidates()
        layout()
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        layout()
        val grid = view.expandedGridForTest()
        grid.scrollForTest(138)
        layout()
        val row = grid.firstVisibleCandidateRowForTest()
        val top = grid.firstVisibleCandidateTopForTest()
        assertTrue(row > 0 || top != 0)

        view.showCandidates(first + List(30) { "候选-${it + 30}" }, "qzx", listOf("qzx"))
        layout()

        assertEquals(row, grid.firstVisibleCandidateRowForTest())
        assertEquals(top, grid.firstVisibleCandidateTopForTest())
        assertEquals(60, grid.renderedCandidateTextsForTest().size)
    }
}
