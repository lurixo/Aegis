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

    @Test fun dates_phones_and_multi_dash_ranges_are_not_calculations() {
        assertNull("ISO date", Calculator.detect("2024-01-15"))
        assertNull("phone number", Calculator.detect("138-1234-5678"))
        assertNull("date embedded in text", Calculator.detect("会议 2024-01-15"))
    }

    @Test fun the_dash_guard_never_touches_division_or_normal_calculations() {
        assertEquals("2.5", Calculator.detect("10/4")!!.result)
        assertEquals("2", Calculator.detect("5-3")!!.result)
        assertEquals("80", Calculator.detect("12+34*2")!!.result)
        assertEquals("-1", Calculator.detect("1-2")!!.result)
    }

    @Test fun f3_percent_is_a_postfix_divide_by_100() {
        assertEquals(0.15, Calculator.evaluate("15%")!!, 1e-9)
        assertEquals(30.0, Calculator.evaluate("200×15%")!!, 1e-9)
        assertEquals(30.0, Calculator.evaluate("200*15%")!!, 1e-9)
        assertEquals(0.05, Calculator.evaluate("(2+3)%")!!, 1e-9)
        assertEquals(-0.05, Calculator.evaluate("-5%")!!, 1e-9)
    }

    @Test fun f3_detect_fires_on_a_percent_expression_but_not_a_bare_percentage() {
        val pct = Calculator.detect("200×15%")!!
        assertEquals("200×15%", pct.expr)
        assertEquals("30", pct.result)
        assertEquals("=30 is appended when no '=' was typed", "=30", pct.append)
        assertNull("a bare percentage is not a calculation", Calculator.detect("50%"))
        assertNull("a bare percentage is not a calculation", Calculator.detect("100%"))
    }

    @Test fun f3_a_trailing_equals_terminates_the_expression_and_appends_the_bare_result() {
        val m = Calculator.detect("1+1=")!!
        assertEquals("the '=' is not part of the expression", "1+1", m.expr)
        assertEquals("2", m.result)
        assertEquals("only the bare result is appended after a typed '='", "2", m.append)
        assertNull("no result lingers after the equation is complete", Calculator.detect("1+1=2"))
        assertNull(Calculator.detect("5="))
        assertEquals("80", Calculator.detect("12+34*2 =")!!.result)
    }

    @Test fun f3_without_a_typed_equals_the_append_keeps_the_equals_prefix() {
        assertEquals("=2", Calculator.detect("1+1")!!.append)
        assertEquals("=80", Calculator.detect("12+34*2")!!.append)
    }

    @Test fun the_dash_guard_shape_match_spares_unary_paren_and_decimal_negatives() {
        assertEquals("7", Calculator.detect("1-(-6)")!!.result)
        assertEquals("-8", Calculator.detect("-5-3")!!.result)
        assertEquals("8", Calculator.detect("5--3")!!.result)
        assertEquals("-3.5", Calculator.detect("1.5-2-3")!!.result)
    }
}
