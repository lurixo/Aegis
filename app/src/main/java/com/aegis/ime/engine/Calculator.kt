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

/**
 * U25 计算器: detect a trailing arithmetic expression in the text before the cursor and evaluate it, so
 * the result can be offered as a candidate. Pure + side-effect free. Operators + − × ÷ (and their ASCII
 * forms * /), parentheses, decimals, and a leading unary minus are supported, with normal precedence.
 *
 * Deliberately conservative — [detect] only fires on a maximal trailing run of expression characters that
 * (a) actually contains a binary operator and (b) parses, so plain numbers / phone numbers / pinyin never
 * produce a result ("仅识别为算式时才算").
 */
object Calculator {

    data class Match(val expr: String, val result: String, val length: Int)

    private val OPS = "+-*/×÷"

    /** Integer groups joined by 2+ single dashes — a date / phone / range shape, not a calculation. */
    private val DATE_LIKE = Regex("""\d+(-\d+){2,}""")

    /** Find a calculable expression ending exactly at the end of [textBeforeCursor]; null when there is none. */
    fun detect(textBeforeCursor: CharSequence): Match? {
        val s = textBeforeCursor.toString()
        if (s.isEmpty()) return null
        // Walk back over expression chars (digits, . , operators, parens, spaces) to the maximal trailing run,
        // then skip any leading spaces so the run we replace starts exactly at the expression (keeps a
        // preceding space like "price: 12+3" intact).
        var start = s.length
        while (start > 0 && isExprChar(s[start - 1])) start--
        while (start < s.length && s[start] == ' ') start++
        val expr = s.substring(start)
        if (expr.isBlank()) return null
        // Must contain a binary operator between operands (a bare "12" or "(5)" is not a calculation).
        if (!hasBinaryOperator(expr)) return null
        // Cheap date/phone/range guard: a run shaped like integer groups joined by 2+ single dashes
        // (2024-01-15, 138-1234-5678, 1-2-3) is far more likely a date/phone/range than a subtraction
        // chain, so suppress the noise. The shape match is precise — anything with + * / × ÷, parentheses,
        // a decimal point, an explicit/leading unary minus, or spaces (1-(-6), -5-3, 1.5-2-3, "1 - 2 - 3")
        // still calculates, and every division is untouched; only a bare digit-dash-digit-dash… run is dropped.
        if (DATE_LIKE.matches(expr)) return null
        val value = evaluate(expr) ?: return null
        return Match(expr, format(value), s.length - start)
    }

    private fun isExprChar(c: Char): Boolean =
        c.isDigit() || c == '.' || c == '(' || c == ')' || c == ' ' || c in OPS

    /** True if [expr] has an operator acting as a binary op (not just a leading unary minus). */
    private fun hasBinaryOperator(expr: String): Boolean {
        for (i in expr.indices) {
            val c = expr[i]
            if (c in OPS) {
                // a '-' or '+' at the very start (after optional spaces/'(') is unary, not a calculation
                val prev = expr.take(i).trimEnd()
                if (prev.isNotEmpty() && prev.last() != '(') return true
            }
        }
        return false
    }

    /** Evaluate [expr]; null on a syntax error or division by zero. */
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

    /** Trim a whole-number double to "12" and keep up to 10 fractional digits otherwise (no trailing zeros). */
    private fun format(v: Double): String {
        if (v == v.toLong().toDouble()) return v.toLong().toString()
        return v.toBigDecimal().round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }

    /** Tiny recursive-descent parser: expr = term (+|- term)*; term = factor (*|/ factor)*; factor = number | (expr) | -factor. */
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
