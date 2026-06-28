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

    data class Match(val expr: String, val result: String, val length: Int)

    private val OPS = "+-*/×÷"

    private val DATE_LIKE = Regex("""\d+(-\d+){2,}""")

    fun detect(textBeforeCursor: CharSequence): Match? {
        val s = textBeforeCursor.toString()
        if (s.isEmpty()) return null
        var start = s.length
        while (start > 0 && isExprChar(s[start - 1])) start--
        while (start < s.length && s[start] == ' ') start++
        val expr = s.substring(start)
        if (expr.isBlank()) return null
        if (!hasBinaryOperator(expr)) return null
        if (DATE_LIKE.matches(expr)) return null
        val value = evaluate(expr) ?: return null
        return Match(expr, format(value), s.length - start)
    }

    private fun isExprChar(c: Char): Boolean =
        c.isDigit() || c == '.' || c == '(' || c == ')' || c == ' ' || c in OPS

    private fun hasBinaryOperator(expr: String): Boolean {
        for (i in expr.indices) {
            val c = expr[i]
            if (c in OPS) {
                val prev = expr.take(i).trimEnd()
                if (prev.isNotEmpty() && prev.last() != '(') return true
            }
        }
        return false
    }

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

    private fun format(v: Double): String {
        if (v == v.toLong().toDouble()) return v.toLong().toString()
        return v.toBigDecimal().round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private class Parser(private val s: String) {
        private var i = 0

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
                v = if (op == '+') v + rhs else v - rhs
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
            }
            return v
        }

        private fun parseFactor(): Double {
            skipSpace()
            val c = peek() ?: throw IllegalStateException("unexpected end")
            if (c == '+') { i++; return parseFactor() }
            if (c == '-') { i++; return -parseFactor() }
            if (c == '(') {
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
