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

    @Test fun f3_percent_is_a_postfix_divide_by_100() {
        assertEquals(0.15, Calculator.evaluate("15%")!!, 1e-9)
        assertEquals(30.0, Calculator.evaluate("200×15%")!!, 1e-9)
        assertEquals(30.0, Calculator.evaluate("200*15%")!!, 1e-9)
        assertEquals(0.05, Calculator.evaluate("(2+3)%")!!, 1e-9)
        assertEquals(-0.05, Calculator.evaluate("-5%")!!, 1e-9)
    }
}
