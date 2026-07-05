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

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelBackspaceTest {

    private class RecordingHost(private val selection: Boolean) : ImeHost {
        val calls = mutableListOf<String>()
        override fun commitText(text: CharSequence) { calls.add("commit:'$text'") }
        override fun deleteBackward() { calls.add("deleteBackward") }
        override fun performEnter() {}
        override fun hasSelection(): Boolean = selection
        override fun deleteSelection() { calls.add("deleteSelection") }
        override fun deleteGraphemeBackward() { calls.add("deleteGrapheme") }
    }

    @Test fun panel_backspace_deletes_the_selection_when_one_exists() {
        val h = RecordingHost(selection = true)
        h.panelBackspace()
        assertEquals(listOf("deleteSelection"), h.calls)
    }

    @Test fun panel_backspace_removes_a_grapheme_cluster_when_there_is_no_selection() {
        val h = RecordingHost(selection = false)
        h.panelBackspace()
        assertEquals(listOf("deleteGrapheme"), h.calls)
    }
}
