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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** U25: the pure arithmetic engine — precedence, parens, decimals, unary minus, and conservative detection. */
class CalculatorTest {

    @Test fun precedence_is_respected() {
        assertEquals(80.0, Calculator.evaluate("12+34*2")!!, 1e-9)
        assertEquals(14.0, Calculator.evaluate("2+3*4")!!, 1e-9)
    }

    @Test fun parens_and_unary_minus() {
        assertEquals(9.0, Calculator.evaluate("(1+2)*3")!!, 1e-9)
        assertEquals(-3.0, Calculator.evaluate("-5+2")!!, 1e-9)
        assertEquals(7.0, Calculator.evaluate("1-(-6)")!!, 1e-9)
    }

    @Test fun left_associative_and_decimals_and_unicode_ops() {
        assertEquals(1.0, Calculator.evaluate("3-1-1")!!, 1e-9)
        assertEquals(2.5, Calculator.evaluate("10/4")!!, 1e-9)
        assertEquals(6.0, Calculator.evaluate("2×3")!!, 1e-9)
        assertEquals(3.0, Calculator.evaluate("6÷2")!!, 1e-9)
    }

    @Test fun division_by_zero_and_garbage_yield_null() {
        assertNull(Calculator.evaluate("1/0"))
        assertNull(Calculator.evaluate("1+"))
        assertNull(Calculator.evaluate("abc"))
        assertNull(Calculator.evaluate(""))
        assertNull(Calculator.evaluate("(1+2"))
    }

    @Test fun detect_only_fires_on_a_real_trailing_expression() {
        assertNull("plain number is not a calculation", Calculator.detect("12345"))
        assertNull("empty", Calculator.detect(""))
        assertNull("leading unary only is not a calculation", Calculator.detect("-5"))

        val m = Calculator.detect("12+34*2")!!
        assertEquals("12+34*2", m.expr)
        assertEquals("80", m.result)
        assertEquals(7, m.length)
    }

    @Test fun detect_keeps_a_preceding_space_or_label_out_of_the_replaced_run() {
        val m = Calculator.detect("price: 12+3")!!
        assertEquals("12+3", m.expr)
        assertEquals("15", m.result)
        assertEquals("replaces only '12+3', not the space/label", 4, m.length)
    }

    @Test fun whole_number_results_drop_the_decimal() {
        assertEquals("3", Calculator.detect("1.5+1.5")!!.result)
        assertEquals("2.5", Calculator.detect("5/2")!!.result)
    }
}
