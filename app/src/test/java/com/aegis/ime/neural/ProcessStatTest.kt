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

package com.aegis.ime.neural

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessStatTest {

    private fun stat(name: String) =
        "4242 ($name) S 1 4242 4242 0 -1 4194560 7001 11 37 13 500 60 0 0 20 0 9 0 123456 1000000 2000 18446744073709551615"

    @Test fun major_faults_are_the_twelfth_field() {
        assertEquals(37L, ProcessStat.majorFaults(stat("com.aegis.ime")))
    }

    @Test fun a_process_name_with_spaces_and_parentheses_does_not_shift_the_fields() {
        assertEquals(37L, ProcessStat.majorFaults(stat("a b")))
        assertEquals(37L, ProcessStat.majorFaults(stat("ime) R 5 6 7 8 9 10 11 12 99 (x")))
        assertEquals(37L, ProcessStat.majorFaults(stat("(nested (name))")))
    }

    @Test fun a_trailing_newline_is_accepted() {
        assertEquals(37L, ProcessStat.majorFaults(stat("com.aegis.ime") + "\n"))
    }

    @Test fun text_that_is_not_a_stat_line_counts_as_zero() {
        assertEquals(0L, ProcessStat.majorFaults(""))
        assertEquals(0L, ProcessStat.majorFaults("4242 com.aegis.ime S 1 4242 4242 0 -1 4194560 7001 11 37 13"))
        assertEquals(0L, ProcessStat.majorFaults("4242 (com.aegis.ime) S 1 4242"))
        assertEquals(0L, ProcessStat.majorFaults("4242 (com.aegis.ime) S 1 4242 4242 0 -1 4194560 7001 11 many 13"))
    }

    @Test fun reading_the_running_process_never_throws() {
        assertTrue(ProcessStat.majorFaults() >= 0L)
    }

    @Test fun reading_the_calling_thread_never_throws() {
        assertTrue(ProcessStat.threadMajorFaults() >= 0L)
    }

    @Test fun the_calling_thread_counts_no_more_than_its_process() {
        val thread = ProcessStat.threadMajorFaults()
        assertTrue(thread <= ProcessStat.majorFaults())
    }
}
