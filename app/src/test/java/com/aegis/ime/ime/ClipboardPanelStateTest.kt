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

import com.aegis.ime.ime.ClipboardPanelState.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPanelStateTest {

    @Test fun switching_tab_clears_selection_and_expansion() {
        val s = ClipboardPanelState()
        s.enterSelect(); s.toggleSelect("a"); s.toggleExpand("b")
        assertTrue(s.switchTab(Tab.PHRASE))
        assertEquals(Tab.PHRASE, s.tab)
        assertTrue("selection cleared on tab switch", s.selected.isEmpty())
        assertEquals(null, s.expanded)
        assertFalse("same tab is a no-op", s.switchTab(Tab.PHRASE))
    }

    @Test fun enter_exit_select_clears_selection() {
        val s = ClipboardPanelState()
        s.enterSelect(); s.toggleSelect("a"); assertTrue(s.hasSelection())
        s.exitSelect()
        assertFalse(s.selectMode)
        assertFalse(s.hasSelection())
    }

    @Test fun toggle_select_adds_then_removes() {
        val s = ClipboardPanelState()
        assertTrue(s.toggleSelect("a"))
        assertFalse(s.toggleSelect("a"))
        assertFalse("a" in s.selected)
    }

    @Test fun toggle_expand_is_idempotent_pair() {
        val s = ClipboardPanelState()
        s.toggleExpand("x"); assertEquals("x", s.expanded)
        s.toggleExpand("x"); assertEquals(null, s.expanded)
        s.toggleExpand("x"); s.toggleExpand("y"); assertEquals("y", s.expanded)
    }

    @Test fun select_all_toggles_against_the_full_list() {
        val s = ClipboardPanelState()
        val all = listOf("a", "b", "c")
        assertFalse(s.isAllSelected(all))
        s.selectAll(all)
        assertTrue(s.isAllSelected(all))
        assertEquals(setOf("a", "b", "c"), s.selected.toSet())
        s.selectAll(all)
        assertTrue(s.selected.isEmpty())
        assertFalse("empty list is never 'all selected'", s.isAllSelected(emptyList()))
    }

    @Test fun collapse_if_expanded_only_clears_the_matching_card() {
        val s = ClipboardPanelState()
        s.toggleExpand("x")
        s.collapseIfExpanded("y"); assertEquals("x", s.expanded)
        s.collapseIfExpanded("x"); assertEquals(null, s.expanded)
    }

    @Test fun reset_returns_to_defaults() {
        val s = ClipboardPanelState()
        s.switchTab(Tab.PHRASE); s.enterSelect(); s.toggleSelect("a"); s.toggleExpand("b")
        s.reset()
        assertEquals(Tab.CLIPBOARD, s.tab)
        assertFalse(s.selectMode)
        assertEquals(null, s.expanded)
        assertTrue(s.selected.isEmpty())
    }
}
