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

class CopyBarController(
    private val commit: (String) -> Unit,
    private val selectionChanged: (String) -> Unit,
    private val selectionFinished: () -> Unit,
    private val dismiss: () -> Unit,
    private val addWord: (String) -> Unit = {},
) {
    var content: String? = null
        private set
    var splitMode: Boolean = false
        private set
    private var selection: SplitSelectionModel? = null
    private var selectionSessionActive = false

    val blocks: List<String> get() = selection?.blocks.orEmpty()

    val active: Boolean get() = content != null

    val addableWord: String? get() = content?.let(::addableWord)

    fun show(text: String) {
        if (text.isBlank()) return
        finishSelection()
        content = text
        splitMode = false
        selection = null
    }

    fun toggleSplit() {
        val c = content ?: return
        if (splitMode) {
            finishSelection()
            splitMode = false
            selection = null
        } else {
            selection = SplitSelectionModel.from(c)
            splitMode = true
        }
    }

    fun tapContent() {
        finishSelection()
        content?.let { commit(it) }
        clear()
        dismiss()
    }

    fun tapAddWord() {
        addableWord?.let(addWord)
    }

    fun tapBlock(index: Int): Boolean? {
        val current = selection ?: return null
        if (index !in current.blocks.indices) return null
        val selected = current.toggle(index)
        selectionSessionActive = true
        selectionChanged(current.projection())
        return selected
    }

    fun close() {
        finishSelection()
        clear()
        dismiss()
    }

    fun finishSelection() {
        if (!selectionSessionActive) return
        selectionSessionActive = false
        selectionFinished()
    }

    fun selectedIndices(): Set<Int> = selection?.selectedIndices().orEmpty()

    private fun clear() {
        content = null
        splitMode = false
        selection = null
    }

    companion object {
        private const val MIN_WORD_CHARS = 2
        private const val MAX_WORD_CHARS = 32

        fun addableWord(text: String): String? {
            val word = text.trim()
            val chars = word.codePoints().toArray()
            if (chars.size !in MIN_WORD_CHARS..MAX_WORD_CHARS) return null
            return word.takeIf { chars.all(Character::isIdeographic) }
        }
    }
}
