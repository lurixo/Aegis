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

interface PanelEditable {
    fun snapshot(): String
    fun selectionStart(): Int
    fun selectionEnd(): Int
    fun setSelection(start: Int, end: Int)
    fun replace(start: Int, end: Int, text: CharSequence)
}

class PanelTextInput {

    private var target: PanelEditable? = null

    val active: Boolean get() = target != null

    fun begin(editable: PanelEditable) { target = editable }

    fun end() { target = null }

    fun text(): String = target?.snapshot() ?: ""

    fun commit(text: CharSequence): Boolean {
        val t = target ?: return false
        t.replace(start(t), end(t), text)
        return true
    }

    fun newline(): Boolean = commit("\n")

    fun backspace(): Boolean {
        val t = target ?: return false
        val s = start(t)
        if (s != end(t)) { t.replace(s, end(t), ""); return true }
        if (s <= 0) return true
        val cluster = GraphemeText.lastClusterLength(t.snapshot().substring(0, s))
        t.replace(s - cluster, s, "")
        return true
    }

    fun deleteSelection(): Boolean {
        val t = target ?: return false
        val s = start(t)
        val e = end(t)
        if (s == e) return false
        t.replace(s, e, "")
        return true
    }

    fun textBefore(n: Int): String? {
        val t = target ?: return null
        val s = start(t)
        return t.snapshot().substring(maxOf(0, s - n), s)
    }

    fun replaceBefore(length: Int, text: CharSequence): Boolean {
        val t = target ?: return false
        val s = start(t)
        t.replace(maxOf(0, s - length), s, text)
        return true
    }

    fun move(move: SelectionMath.Move, extend: Boolean): Boolean {
        val t = target ?: return false
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
        val t = target ?: return false
        t.setSelection(0, t.snapshot().length)
        return true
    }

    fun selectedText(): String? {
        val t = target ?: return null
        val s = start(t)
        val e = end(t)
        return if (s == e) null else t.snapshot().substring(s, e)
    }

    fun hasSelection(): Boolean {
        val t = target ?: return false
        return start(t) != end(t)
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
