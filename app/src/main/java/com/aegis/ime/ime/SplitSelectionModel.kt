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

import com.aegis.ime.user.ClipSplitter

internal class SplitSelectionModel private constructor(
    val blocks: List<String>,
) {
    private val selected = mutableSetOf<Int>()

    fun toggle(index: Int): Boolean {
        require(index in blocks.indices)
        return if (selected.add(index)) true else {
            selected.remove(index)
            false
        }
    }

    fun isSelected(index: Int): Boolean = index in selected

    fun selectedIndices(): Set<Int> = selected.toSet()

    fun projection(): String =
        blocks.indices
            .filter { it in selected }
            .joinToString(separator = "") { blocks[it] }

    companion object {
        fun from(text: String): SplitSelectionModel =
            SplitSelectionModel(ClipSplitter.copyBlocks(text))
    }
}
