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
 * P7 (#19): a panel that returns to its DEFAULT state when it is dismissed, so reopening it always starts
 * fresh — default tab/category, no expanded card or overlay, scrolled to the top, locks cleared. [InputView]
 * calls [resetToDefault] on the panel it is leaving (every close funnels through `showPanel(null)`, including
 * `onStartInputView`), so "退出即重置" is wired in one place for every panel. Mirrors P3's lock reset.
 */
interface ResettablePanel {
    fun resetToDefault()
}
