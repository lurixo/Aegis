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

class PanelTextInput {

    private var buf: StringBuilder? = null
    var onChange: (String) -> Unit = {}

    val active: Boolean get() = buf != null

    fun text(): String = buf?.toString() ?: ""

    fun begin(initial: String) { buf = StringBuilder(initial); emit() }

    fun end() { buf = null }

    fun commit(text: CharSequence): Boolean {
        val b = buf ?: return false
        b.append(text); emit(); return true
    }

    fun backspace(): Boolean {
        val b = buf ?: return false
        if (b.isNotEmpty()) { b.delete(b.offsetByCodePoints(b.length, -1), b.length); emit() }
        return true
    }

    fun textBefore(n: Int): String? = buf?.let { it.substring(maxOf(0, it.length - n)) }

    fun replaceBefore(length: Int, text: CharSequence): Boolean {
        val b = buf ?: return false
        b.delete(maxOf(0, b.length - length), b.length); b.append(text); emit(); return true
    }

    private fun emit() { onChange(text()) }
}
