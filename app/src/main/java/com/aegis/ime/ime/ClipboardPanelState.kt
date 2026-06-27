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

/**
 * Pure UI state for the clipboard panel (C7): which tab is active, whether multi-select (编辑剪贴板) is on,
 * the selected entries, and which card is expanded. Extracted from [ClipboardView] so the state-machine
 * rules (switching tab clears the selection, select-all toggles, greyed-when-empty) are unit-testable on
 * the JVM with plain junit — no Android / Robolectric.
 */
class ClipboardPanelState {

    enum class Tab { CLIPBOARD, PHRASE }

    var tab: Tab = Tab.CLIPBOARD
        private set
    var selectMode: Boolean = false
        private set
    var expanded: String? = null
        private set
    val selected = LinkedHashSet<String>()

    fun reset() { tab = Tab.CLIPBOARD; selectMode = false; expanded = null; selected.clear() }

    /** Switch tab; a selection / expansion does not carry across tabs. Returns true if the tab changed. */
    fun switchTab(t: Tab): Boolean {
        if (t == tab) return false
        tab = t; selected.clear(); expanded = null
        return true
    }

    fun enterSelect() { selectMode = true; selected.clear() }
    fun exitSelect() { selectMode = false; selected.clear() }

    /** Toggle a row's membership. Returns true if it is now selected. */
    fun toggleSelect(item: String): Boolean =
        if (selected.add(item)) true else { selected.remove(item); false }

    fun toggleExpand(item: String) { expanded = if (expanded == item) null else item }
    fun collapseIfExpanded(item: String) { if (expanded == item) expanded = null }

    fun isAllSelected(all: List<String>): Boolean = all.isNotEmpty() && selected.containsAll(all)

    /** Select-all toggle: if every entry is already selected, clear; otherwise select all of [all]. */
    fun selectAll(all: List<String>) {
        if (isAllSelected(all)) selected.clear() else { selected.clear(); selected.addAll(all) }
    }

    fun hasSelection(): Boolean = selected.isNotEmpty()
}
