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

package com.aegis.ime.user

object ClipSplitter {

    enum class Kind { LINK, EMAIL, HAN, LATIN, DIGIT, SYMBOL }

    data class Block(val text: String, val kind: Kind)

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    private enum class Cls { HAN, LATIN, DIGIT, SYMBOL, SPACE }

    fun split(s: String): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        while (i < s.length) {
            val entity = entityAt(s, i)
            if (entity != null) { out.add(entity); i += entity.text.length; continue }
            val cp = s.codePointAt(i)
            val cls = classOf(cp)
            var j = i + Character.charCount(cp)
            if (cls == Cls.HAN) {
                out.add(Block(s.substring(i, j), Kind.HAN))
                i = j
                continue
            }
            while (j < s.length && classOf(s.codePointAt(j)) == cls && entityAt(s, j) == null) {
                j += Character.charCount(s.codePointAt(j))
            }
            if (cls != Cls.SPACE) out.add(Block(s.substring(i, j), kindOf(cls)))
            i = j
        }
        return out
    }

    fun blocks(s: String): List<String> = split(s).map { it.text }

    private fun entityAt(s: String, i: Int): Block? {
        EMAIL.matchAt(s, i)?.let { return Block(it.value, Kind.EMAIL) }
        return null
    }

    private fun classOf(cp: Int): Cls = when {
        Character.isWhitespace(cp) -> Cls.SPACE
        Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN -> Cls.HAN
        cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code -> Cls.LATIN
        cp in '0'.code..'9'.code -> Cls.DIGIT
        else -> Cls.SYMBOL
    }

    private fun kindOf(c: Cls): Kind = when (c) {
        Cls.HAN -> Kind.HAN
        Cls.LATIN -> Kind.LATIN
        Cls.DIGIT -> Kind.DIGIT
        else -> Kind.SYMBOL
    }
}
