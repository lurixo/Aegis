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

package com.aegis.ime

import android.os.Looper
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import com.aegis.ime.ime.EditorUndoHistory
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorUndoReadBudgetTest {
    private val document = "长草稿里的一段正文，包含标点、English words 和数字 12345。\n".repeat(500).take(20_000)
    private val caret = 12_345

    private class WebHost(view: View) : BaseInputConnection(view, true) {
        var deferred = false
        var extractions = 0
        var largestCursorRead = 0
        private val queued = ArrayDeque<() -> Unit>()

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            extractions++
            val content = editable!!
            return ExtractedText().also {
                it.text = content.toString()
                it.startOffset = 0
                it.partialStartOffset = -1
                it.selectionStart = Selection.getSelectionStart(content)
                it.selectionEnd = Selection.getSelectionEnd(content)
            }
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            largestCursorRead = maxOf(largestCursorRead, n)
            return super.getTextBeforeCursor(n, flags)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            largestCursorRead = maxOf(largestCursorRead, n)
            return super.getTextAfterCursor(n, flags)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!deferred) return super.commitText(text, newCursorPosition)
            queued.addLast { super.commitText(text, newCursorPosition) }
            return true
        }

        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    @Test fun web_edits_take_one_snapshot_and_confirm_on_the_selection_report_instead_of_fast_polling() {
        val host = WebHost(View(RuntimeEnvironment.getApplication()))
        host.editable!!.append(document)
        Selection.setSelection(host.editable, caret)
        val history = EditorUndoHistory().apply { preferNativeUndo = true }
        val connection = history.wrap(host)
        host.deferred = true
        assertTrue(connection.commitText("你", 1))
        assertEquals("one snapshot before the edit", 1, host.extractions)
        assertTrue("consistency reads stay local: ${host.largestCursorRead}", host.largestCursorRead <= 256)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertEquals("no 32ms polling while an ordinary edit waits for the host", 1, host.extractions)
        host.deferred = false
        host.flush()
        history.selectionUpdated(caret + 1, caret + 1)
        assertEquals(2, host.extractions)
        assertTrue(history.hasUndo)
        assertTrue(history.undo(connection))
        assertEquals(document, host.editable.toString())
    }
}
