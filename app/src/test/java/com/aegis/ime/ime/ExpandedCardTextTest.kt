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

import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpandedCardTextTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun layout(view: View, width: Int = 480, height: Int = 480) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun textViews(root: View): List<TextView> {
        val result = ArrayList<TextView>()
        fun collect(view: View) {
            if (view is TextView) result.add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(root)
        return result
    }

    private fun clipboard(content: String) = ClipboardView(context).apply {
        historyProvider = { listOf(content) }
        refresh()
    }

    private fun phrase(content: String) = ClipboardView(context).apply {
        categoriesProvider = { listOf("默认") }
        phrasesInProvider = { listOf(content) }
        forcePhrasesStateForTest("默认")
        refresh()
    }

    @Test fun collapsed_preview_preserves_the_512_character_boundary_and_two_lines() {
        for (length in listOf(511, 512, 513, 2048)) {
            val content = "x".repeat(length)
            val view = clipboard(content)
            layout(view)
            val expected = if (length > 512) content.substring(0, 512) + "…" else content
            val body = textViews(view).single { it.text?.toString() == expected }
            assertEquals(2, body.maxLines)
            assertEquals(TextUtils.TruncateAt.END, body.ellipsize)
        }

        val phraseContent = "常".repeat(2048)
        val phraseView = phrase(phraseContent)
        layout(phraseView)
        val phraseBody = textViews(phraseView).single { it.text?.toString() == phraseContent.substring(0, 512) + "…" }
        assertEquals(2, phraseBody.maxLines)
    }

    @Test fun expanded_chunks_preserve_unicode_text_without_a_terminal_cap() {
        val family = "👨‍👩‍👧‍👦"
        val content = buildString {
            repeat(1400) { index -> append(index).append(':').append(family).append(" e\u0301\n") }
        }
        val chunks = ClipboardView(context).expandedTextChunksForTest(content)

        assertTrue(chunks.size > 4)
        assertEquals(content, chunks.joinToString(separator = ""))
        for (chunk in chunks) {
            assertTrue(chunk.isNotEmpty())
            assertFalse(chunk.first().isLowSurrogate())
            assertFalse(chunk.last().isHighSurrogate())
            assertFalse(chunk.first() == '\u200d')
            assertFalse(chunk.last() == '\u200d')
        }
    }

    @Test fun clipboard_and_phrase_expansion_use_bounded_internal_scroll_with_full_text() {
        val content = buildString {
            repeat(1200) { index -> append("第").append(index).append("行 👩🏽‍💻 e\u0301\n") }
        }
        for (view in listOf(clipboard(content), phrase(content))) {
            view.expandForTest(content)
            layout(view)
            val viewport = requireNotNull(view.expandedTextViewportForTest())
            val adapter = viewport.adapter
            val rebuilt = (0 until adapter.count).joinToString(separator = "") { adapter.getItem(it) as String }

            assertEquals(content, rebuilt)
            assertTrue(adapter.count > 1)
            assertTrue(viewport.height in 1..view.expandedTextMaxHeightForTest())
            assertTrue(viewport.isVerticalScrollBarEnabled)
            assertTrue(viewport.isNestedScrollingEnabled)
            assertTrue(adapter.count > viewport.childCount || viewport.canScrollVertically(1))

            viewport.setSelection(adapter.count - 1)
            layout(view)
            assertTrue(viewport.firstVisiblePosition > 0)
        }
    }

    @Test fun large_collection_management_appends_only_the_next_page_near_the_bottom() {
        val entries = (0 until 5000).map { "clip-$it" }
        val view = ClipboardView(context).apply {
            historyProvider = { entries }
            refresh()
            enterSelectForTest()
        }

        assertEquals(view.initialSyncRowsForTest(), view.listRowCountForTest())
        layout(view, height = 320)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        layout(view, height = 320)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        val firstWindow = view.listRowCountForTest()
        assertTrue(firstWindow in view.initialSyncRowsForTest()..(view.initialSyncRowsForTest() * 3))
        assertTrue(firstWindow < entries.size)

        val scroll = view.listViewportForTest() as ScrollView
        scroll.scrollTo(0, (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0))
        assertTrue(scroll.scrollY > 0)
        assertTrue(view.requestListAppendIfNeededForTest())
        assertTrue(view.runPendingListAppendForTest())
        layout(view, height = 320)
        val nextWindow = view.listRowCountForTest()
        assertTrue(nextWindow > firstWindow)
        assertTrue(nextWindow - firstWindow <= view.initialSyncRowsForTest())
        assertTrue(nextWindow < entries.size)
    }
}
