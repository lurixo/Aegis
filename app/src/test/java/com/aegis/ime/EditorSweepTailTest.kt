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

import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import com.aegis.ime.ime.EditorSweep
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorSweepTailTest {

    private class Placeholder(target: View, val opens: Int) : BaseInputConnection(target, true) {
        private var emptied = false
        private var offered = false

        fun hold(text: CharSequence) {
            val content = requireNotNull(editable)
            content.replace(0, content.length, text)
            Selection.setSelection(content, 0)
        }

        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence {
            val real = super.getTextAfterCursor(length, flags) ?: ""
            if (real.isNotEmpty()) return real
            if (emptied && !offered) {
                offered = true
                return "\n".repeat(opens)
            }
            return ""
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val done = super.deleteSurroundingText(beforeLength, afterLength)
            if (requireNotNull(editable).isEmpty()) emptied = true
            return done
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
            ExtractedText().apply {
                val content = requireNotNull(editable)
                startOffset = 0
                text = content.subSequence(0, content.length)
                selectionStart = Selection.getSelectionStart(content)
                selectionEnd = Selection.getSelectionEnd(content)
            }
    }

    private fun editorHolding(text: CharSequence, opens: Int): Placeholder =
        Placeholder(FrameLayout(RuntimeEnvironment.getApplication()), opens).apply { hold(text) }

    @Test fun the_lines_an_editor_opens_while_being_emptied_are_not_kept() {
        val editor = editorHolding("AAA\nBBB", opens = 4)

        val swept = EditorSweep.clearCapturing(editor)

        assertEquals("the snapshot must end where the field ended", "AAA\nBBB", swept.toString())
    }

    @Test fun blank_lines_the_field_really_ended_with_are_kept() {
        val editor = editorHolding("AAA\nBBB\n\n", opens = 4)

        val swept = EditorSweep.clearCapturing(editor)

        assertEquals("what the field really ended with must survive", "AAA\nBBB\n\n", swept.toString())
    }

    @Test fun a_field_whose_end_is_out_of_reach_is_left_as_it_was_walked_out() {
        val editor = editorHolding("A".repeat(EditorSweep.CHUNK * 2), opens = 4)

        val swept = EditorSweep.clearCapturing(editor)

        assertEquals(
            "with the end too far to look at, the walk stands on its own",
            "A".repeat(EditorSweep.CHUNK * 2) + "\n".repeat(4),
            swept.toString(),
        )
    }
}
