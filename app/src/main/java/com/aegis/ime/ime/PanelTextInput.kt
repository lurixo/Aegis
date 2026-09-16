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

import com.aegis.ime.layout.SymbolCatalog

interface PanelEditable {
    fun snapshot(): String
    fun selectionStart(): Int
    fun selectionEnd(): Int
    fun setSelection(start: Int, end: Int)
    fun replace(start: Int, end: Int, text: CharSequence)
}

class PanelTextInput {

    private var target: PanelEditable? = null
    private val history = TextUndoHistory()
    private var presented: () -> Boolean = { true }

    var onTargetChanged: () -> Unit = {}

    val active: Boolean get() = live() != null

    fun begin(editable: PanelEditable, presented: () -> Boolean = { true }) {
        val changed = target !== editable
        if (changed) history.clear()
        target = editable
        this.presented = presented
        if (changed) onTargetChanged()
    }

    fun end() {
        val changed = target != null
        history.clear()
        target = null
        presented = { true }
        if (changed) onTargetChanged()
    }

    private fun live(): PanelEditable? {
        val t = target ?: return null
        if (presented()) return t
        end()
        return null
    }

    fun text(): String = live()?.snapshot() ?: ""

    fun commit(text: CharSequence): Boolean {
        val t = live() ?: return false
        edit(t) { t.replace(start(t), end(t), text) }
        return true
    }

    fun newline(): Boolean = commit("\n")

    fun commitSymbol(symbol: CharSequence): Boolean {
        val t = live() ?: return false
        val s = start(t)
        val e = end(t)
        val insertion = SymbolCatalog.insertionFor(symbol.toString(), t.snapshot().substring(e))
        edit(t) {
            t.replace(s, e, insertion.joinToString(""))
            val caret = s + insertion[0].length
            t.setSelection(caret, caret)
        }
        return true
    }

    fun deleteForward(): Boolean {
        val t = live() ?: return false
        val s = start(t)
        val e = end(t)
        val through = if (s != e) e else GraphemeText.nextCluster(t.snapshot(), s)
        if (through > s) edit(t) { t.replace(s, through, "") }
        return true
    }

    fun backspace(): Boolean {
        val t = live() ?: return false
        val s = start(t)
        if (s != end(t)) { edit(t) { t.replace(s, end(t), "") }; return true }
        if (s <= 0) return true
        val cluster = GraphemeText.lastClusterLength(t.snapshot().substring(0, s))
        edit(t) { t.replace(s - cluster, s, "") }
        return true
    }

    fun deleteSelection(): Boolean {
        val t = live() ?: return false
        val s = start(t)
        val e = end(t)
        if (s == e) return false
        edit(t) { t.replace(s, e, "") }
        return true
    }

    fun textBefore(n: Int): String? {
        val t = live() ?: return null
        val s = start(t)
        return t.snapshot().substring(maxOf(0, s - n), s)
    }

    fun replaceBefore(length: Int, text: CharSequence): Boolean {
        val t = live() ?: return false
        val s = start(t)
        edit(t) { t.replace(maxOf(0, s - length), s, text) }
        return true
    }

    fun move(move: SelectionMath.Move, extend: Boolean): Boolean {
        val t = live() ?: return false
        val text = t.snapshot()
        if (extend) {
            t.setSelection(t.selectionStart(), SelectionMath.step(text, t.selectionEnd(), move))
            return true
        }
        val collapsed = if (move == SelectionMath.Move.LEFT || move == SelectionMath.Move.UP ||
            move == SelectionMath.Move.HOME
        ) start(t) else end(t)
        val next = if (start(t) != end(t) &&
            (move == SelectionMath.Move.LEFT || move == SelectionMath.Move.RIGHT)
        ) collapsed else SelectionMath.step(text, collapsed, move)
        t.setSelection(next, next)
        return true
    }

    fun selectAll(): Boolean {
        val t = live() ?: return false
        t.setSelection(0, t.snapshot().length)
        return true
    }

    fun selectedText(): String? {
        val t = live() ?: return null
        val s = start(t)
        val e = end(t)
        return if (s == e) null else t.snapshot().substring(s, e)
    }

    fun hasSelection(): Boolean {
        val t = live() ?: return false
        return start(t) != end(t)
    }

    fun canUndo(): Boolean {
        val t = live() ?: return false
        return history.peek(snapshot(t)) != null
    }

    fun undo(): Boolean {
        val t = live() ?: return false
        val entry = history.peek(snapshot(t)) ?: return false
        val replacement = history.replacement(entry)
        t.replace(replacement.start, replacement.end, replacement.text)
        t.setSelection(entry.before.selectionStart, entry.before.selectionEnd)
        history.pop()
        return true
    }

    private fun edit(t: PanelEditable, action: () -> Unit) {
        val before = snapshot(t)
        action()
        history.record(before, snapshot(t))
    }

    private fun snapshot(t: PanelEditable): EditorTextSnapshot? {
        val text = t.snapshot()
        if (text.length > 65_536) return null
        return EditorTextSnapshot(
            text,
            t.selectionStart().coerceIn(0, text.length),
            t.selectionEnd().coerceIn(0, text.length),
        )
    }

    private fun start(t: PanelEditable): Int {
        val length = t.snapshot().length
        return minOf(t.selectionStart(), t.selectionEnd()).coerceIn(0, length)
    }

    private fun end(t: PanelEditable): Int {
        val length = t.snapshot().length
        return maxOf(t.selectionStart(), t.selectionEnd()).coerceIn(0, length)
    }
}
