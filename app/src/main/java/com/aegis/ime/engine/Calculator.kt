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

package com.aegis.ime.engine

object Calculator {

    fun evaluate(expr: String): Double? = try {
        val p = Parser(expr.replace('×', '*').replace('÷', '/'))
        val v = p.parseExpression()
        p.skipSpace()
        if (!p.atEnd()) null else if (v.isNaN() || v.isInfinite()) null else v
    } catch (e: ArithmeticException) {
        null
    } catch (e: IllegalStateException) {
        null
    }

    private class Parser(private val s: String) {
        private var i = 0
        private var percentOperand = false

        fun atEnd(): Boolean = i >= s.length
        fun skipSpace() { while (i < s.length && s[i] == ' ') i++ }

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) {
                skipSpace()
                val op = peek() ?: break
                if (op != '+' && op != '-') break
                i++
                val rhs = parseTerm()
                val delta = if (percentOperand) v * rhs else rhs
                v = if (op == '+') v + delta else v - delta
            }
            return v
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                skipSpace()
                val op = peek() ?: break
                if (op != '*' && op != '/') break
                i++
                val rhs = parseFactor()
                if (op == '*') v *= rhs else {
                    if (rhs == 0.0) throw ArithmeticException("÷0")
                    v /= rhs
                }
                percentOperand = false
            }
            return v
        }

        private fun parseFactor(): Double {
            skipSpace()
            val c = peek() ?: throw IllegalStateException("unexpected end")
            if (c == '+') { i++; return parseFactor() }
            if (c == '-') { i++; return -parseFactor() }
            var v = parsePrimary()
            skipSpace()
            var pct = false
            while (peek() == '%') { i++; v /= 100.0; pct = true; skipSpace() }
            percentOperand = pct
            return v
        }

        private fun parsePrimary(): Double {
            skipSpace()
            if (peek() == '(') {
                i++
                val v = parseExpression()
                skipSpace()
                if (peek() != ')') throw IllegalStateException("expected )")
                i++
                return v
            }
            return parseNumber()
        }

        private fun parseNumber(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i == start) throw IllegalStateException("expected number")
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalStateException("bad number")
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null
    }
}
