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

import com.aegis.ime.user.asClipEntries
import android.view.View
import android.view.ViewGroup
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardLazyPagingTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT
    private val farPastTheOldCap = 2400
    private val drainGuard = 20_000

    private fun layout(v: View, w: Int = 480, h: Int = 220) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun drain(v: ClipboardView, relayout: Boolean) {
        var rounds = 0
        while (v.runPendingListAppendForTest()) {
            assertTrue("the deferred append never settles", rounds++ < drainGuard)
            if (relayout) layout(v)
        }
    }

    private fun open(v: ClipboardView): ClipboardView {
        layout(v)
        drain(v, relayout = true)
        return v
    }

    private fun clipView(history: List<String>): ClipboardView = ClipboardView(ctx).apply {
        historyProvider = { history.asClipEntries() }
        applyPalette(pal)
        refresh()
    }

    private fun phraseView(phrases: List<String>): ClipboardView = ClipboardView(ctx).apply {
        categoriesProvider = { listOf("默认") }
        phrasesInProvider = { c -> if (c == "默认") phrases else emptyList() }
        applyPalette(pal)
        forcePhrasesStateForTest("默认")
        refresh()
    }

    private fun viewportOf(v: ClipboardView): ViewGroup = v.listViewportForTest() as ViewGroup

    private fun maxScrollOf(v: ClipboardView): Int {
        val viewport = viewportOf(v)
        return (viewport.getChildAt(0).height - viewport.height).coerceAtLeast(0)
    }

    private fun scrollToBottom(v: ClipboardView) {
        viewportOf(v).scrollTo(0, maxScrollOf(v))
    }

    private fun assertReachesTheEndByScrolling(label: String, entries: List<String>, v: ClipboardView) {
        assertTrue("$label overflows the viewport", maxScrollOf(v) > 0)
        val opened = v.listRowCountForTest()
        assertTrue("$label opens with a bounded page, not $opened of ${entries.size} rows", opened < entries.size)
        assertFalse("$label does not materialise the tail on open", entries.last() in v.listRowTextsForTest())

        var loaded = opened
        var rounds = 0
        while (loaded < entries.size) {
            assertTrue("$label needs more rounds than entries", rounds++ < entries.size)
            scrollToBottom(v)
            drain(v, relayout = false)
            layout(v)
            val grown = v.listRowCountForTest()
            assertTrue("$label paging stalled at $loaded of ${entries.size} rows", grown > loaded)
            loaded = grown
        }
        assertEquals("$label reaches entry ${entries.size} with nothing dropped", entries, v.listRowTextsForTest())
    }

    @Test fun a_clipboard_history_far_past_the_old_display_cap_opens_with_a_bounded_page() {
        val history = (1..5000).map { "clip-$it" }
        val v = open(clipView(history))
        val loaded = v.listRowCountForTest()
        assertTrue("open builds $loaded rows, not all ${history.size}", loaded in 1..200)
        assertEquals("the loaded rows are the head of the history, in order", history.take(loaded), v.listRowTextsForTest())
    }

    @Test fun scrolling_reaches_the_last_clipboard_entry_far_past_the_old_display_cap() {
        val history = (1..farPastTheOldCap).map { "clip-$it" }
        assertReachesTheEndByScrolling("clipboard", history, open(clipView(history)))
    }

    @Test fun scrolling_reaches_the_last_common_phrase_far_past_the_old_display_cap() {
        val phrases = (1..farPastTheOldCap).map { "phrase-$it" }
        assertReachesTheEndByScrolling("phrases", phrases, open(phraseView(phrases)))
    }

    @Test fun scrolling_reaches_the_last_entry_in_batch_management_too() {
        val history = (1..farPastTheOldCap).map { "clip-$it" }
        val v = clipView(history)
        v.enterSelectForTest()
        assertReachesTheEndByScrolling("select mode", history, open(v))
    }

    @Test fun every_scroll_to_the_bottom_grows_the_loaded_prefix_without_gaps() {
        val history = (1..300).map { "clip-$it" }
        val v = open(clipView(history))
        var loaded = v.listRowCountForTest()
        assertTrue("open builds $loaded rows, not all ${history.size}", loaded < history.size)
        var rounds = 0
        while (loaded < history.size) {
            assertTrue("paging stalled at $loaded rows", rounds++ < history.size)
            scrollToBottom(v)
            drain(v, relayout = true)
            val grown = v.listRowCountForTest()
            assertTrue("a scroll to the bottom must load more than $loaded rows", grown > loaded)
            assertEquals("round $rounds keeps the loaded rows a gap-free head", history.take(grown), v.listRowTextsForTest())
            loaded = grown
        }
        assertEquals(history, v.listRowTextsForTest())
    }

    @Test fun a_new_clip_arriving_while_paged_keeps_the_already_loaded_entries() {
        val history = (1..farPastTheOldCap).map { "clip-$it" }.toMutableList()
        val v = open(clipView(history))
        val before = v.listRowTextsForTest()
        assertTrue("the panel is paged, holding ${before.size} of ${history.size}", before.size < history.size)
        history.add(0, "just copied")
        v.refresh()
        layout(v)
        val after = v.listRowTextsForTest()
        assertEquals("the new clip is prepended", "just copied", after.first())
        assertTrue("nothing already on screen is lost", after.containsAll(before))
        assertEquals("the loaded rows stay a gap-free head", history.take(after.size), after)
    }
}
