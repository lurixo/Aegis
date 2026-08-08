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
import com.aegis.ime.user.clipEntries
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ClipboardOpenPathTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val pal = ImePalette.STATIC_LIGHT

    @Test fun default_clipboard_open_preparation_renders_the_current_tab_once() {
        var historyReads = 0
        var phraseReads = 0
        val v = ClipboardView(ctx).apply {
            historyProvider = { historyReads++; clipEntries("clip-a", "clip-b") }
            phrasesInProvider = { phraseReads++; emptyList() }
        }

        v.resetToDefault()
        v.applyPalette(pal)

        assertTrue("open starts from the clipboard tab", v.isClipboardTabForTest())
        assertEquals("default open should build the current clipboard tab once", 1, historyReads)
        assertEquals("phrase rows are not needed for the default clipboard tab", 0, phraseReads)
        assertEquals(listOf("clip-a", "clip-b"), v.listRowTextsForTest())
        assertFalse("small lists render synchronously", v.runPendingListAppendForTest())
    }

    @Test fun large_clipboard_history_open_defers_rows_after_the_first_batch() {
        val clips = (0 until (ClipboardView(ctx).initialSyncRowsForTest() + 5)).map { "clip-$it" }
        val v = ClipboardView(ctx).apply {
            historyProvider = { clips.asClipEntries() }
            applyPalette(pal)
        }

        assertTrue(v.initialSyncRowsForTest() <= 12)
        assertEquals("first open work is bounded to the initial batch", v.initialSyncRowsForTest(), v.listRowCountForTest())
        assertEquals(clips.take(v.initialSyncRowsForTest()), v.listRowTextsForTest())

        while (v.runPendingListAppendForTest()) {
        }

        assertEquals("deferred rows are still appended for full scroll semantics", clips.size, v.listRowCountForTest())
        assertEquals(clips, v.listRowTextsForTest())
    }

    @Test fun switching_to_common_phrases_snapshots_category_data_once_and_defers_offscreen_rows() {
        val probe = ClipboardView(ctx)
        val phrases = (0 until (probe.initialSyncRowsForTest() + 5)).map { "phrase-$it" }
        var categoryReads = 0
        var phraseReads = 0
        var noteReads = 0
        val v = ClipboardView(ctx).apply {
            historyProvider = { clipEntries("clip") }
            categoriesProvider = { categoryReads++; listOf("默认") }
            phrasesInProvider = { phraseReads++; phrases }
            phraseNoteProvider = { _, _ -> noteReads++; "" }
            applyPalette(pal)
        }

        v.switchTabForTest(toClipboard = false)

        assertFalse(v.isClipboardTabForTest())
        assertEquals(1, categoryReads)
        assertEquals(1, phraseReads)
        assertEquals(v.initialSyncRowsForTest(), noteReads)
        assertEquals(phrases.take(v.initialSyncRowsForTest()), v.listRowTextsForTest())

        while (v.runPendingListAppendForTest()) {
        }

        assertEquals(1, categoryReads)
        assertEquals(1, phraseReads)
        assertEquals(phrases.size, noteReads)
        assertEquals(phrases, v.listRowTextsForTest())
    }

    @Test fun refreshing_cancels_a_stale_deferred_append() {
        var clips = (0 until (ClipboardView(ctx).initialSyncRowsForTest() + 5)).map { "old-$it" }
        val v = ClipboardView(ctx).apply {
            historyProvider = { clips.asClipEntries() }
            applyPalette(pal)
        }
        assertEquals("large first render leaves rows deferred", v.initialSyncRowsForTest(), v.listRowCountForTest())

        clips = listOf("new")
        v.refresh()

        assertEquals(listOf("new"), v.listRowTextsForTest())
        assertFalse("the refresh cancelled the previous deferred append", v.runPendingListAppendForTest())
    }

    @Test fun reset_to_default_cancels_a_stale_deferred_append() {
        val clips = (0 until (ClipboardView(ctx).initialSyncRowsForTest() + 5)).map { "old-$it" }
        val v = ClipboardView(ctx).apply {
            historyProvider = { clips.asClipEntries() }
            applyPalette(pal)
        }
        assertEquals("large first render leaves rows deferred", v.initialSyncRowsForTest(), v.listRowCountForTest())

        v.resetToDefault()

        assertFalse("the reset cancelled the previous deferred append", v.runPendingListAppendForTest())
        assertEquals(v.initialSyncRowsForTest(), v.listRowCountForTest())
    }
}
