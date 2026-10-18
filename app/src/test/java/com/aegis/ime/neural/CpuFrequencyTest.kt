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

import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuFrequencyTest {

    private class FakeSysfs(val files: MutableMap<String, String>) {
        val reads = ArrayList<String>()

        fun read(path: String): String {
            reads.add(path)
            return files[path] ?: throw FileNotFoundException(path)
        }

        fun count(path: String) = reads.count { it == path }
    }

    private fun cpu(n: Int, file: String) = "/sys/devices/system/cpu/cpu$n/cpufreq/$file"

    private fun phone(): FakeSysfs {
        val files = HashMap<String, String>()
        files["/sys/devices/system/cpu/possible"] = "0-7\n"
        for (n in 0..5) files[cpu(n, "cpuinfo_max_freq")] = "3628800\n"
        for (n in 6..7) files[cpu(n, "cpuinfo_max_freq")] = "4608000\n"
        for (n in 0..7) files[cpu(n, "scaling_cur_freq")] = "1209600\n"
        return FakeSysfs(files)
    }

    @Test fun the_cpu_list_reads_ranges_and_single_cpus() {
        assertEquals((0..7).toList(), CpuFrequency.cpus("0-7\n"))
        assertEquals(listOf(0, 1, 2, 3, 5, 7, 8), CpuFrequency.cpus("0-3,5,7-8"))
        assertEquals(listOf(0), CpuFrequency.cpus("0"))
        assertEquals(emptyList<Int>(), CpuFrequency.cpus(""))
        assertEquals(emptyList<Int>(), CpuFrequency.cpus("0-x"))
        assertEquals(emptyList<Int>(), CpuFrequency.cpus("7-0"))
        assertEquals(emptyList<Int>(), CpuFrequency.cpus("0-100000"))
    }

    @Test fun every_core_max_frequency_is_read_once_per_process_and_cached() {
        val fs = phone()
        val freq = CpuFrequency(fs::read)
        val expected = (0..5).associateWith { 3_628_800L } + (6..7).associateWith { 4_608_000L }
        assertEquals(expected, freq.maxKhz())
        fs.files[cpu(7, "cpuinfo_max_freq")] = "1000\n"
        assertEquals(expected, freq.maxKhz())
        freq.currentKhz(7)
        freq.currentKhz(0)
        assertEquals(1, fs.count("/sys/devices/system/cpu/possible"))
        for (n in 0..7) assertEquals(1, fs.count(cpu(n, "cpuinfo_max_freq")))
    }

    @Test fun the_current_frequency_is_read_on_every_call() {
        val fs = phone()
        val freq = CpuFrequency(fs::read)
        assertEquals(1_209_600L, freq.currentKhz(6))
        fs.files[cpu(6, "scaling_cur_freq")] = "4608000"
        assertEquals(4_608_000L, freq.currentKhz(6))
        assertEquals(2, fs.count(cpu(6, "scaling_cur_freq")))
    }

    @Test fun a_current_frequency_that_cannot_be_read_is_not_tried_again() {
        val fs = phone()
        fs.files.remove(cpu(3, "scaling_cur_freq"))
        fs.files[cpu(4, "scaling_cur_freq")] = "fast"
        fs.files[cpu(5, "scaling_cur_freq")] = "0"
        val freq = CpuFrequency(fs::read)
        repeat(3) {
            assertEquals(0L, freq.currentKhz(3))
            assertEquals(0L, freq.currentKhz(4))
            assertEquals(0L, freq.currentKhz(5))
        }
        for (n in 3..5) assertEquals(1, fs.count(cpu(n, "scaling_cur_freq")))
        assertEquals(1_209_600L, freq.currentKhz(2))
    }

    @Test fun a_core_without_a_readable_max_frequency_is_left_out_and_never_read_for_its_current_one() {
        val fs = phone()
        fs.files.remove(cpu(1, "cpuinfo_max_freq"))
        fs.files[cpu(2, "cpuinfo_max_freq")] = "-5"
        val freq = CpuFrequency(fs::read)
        assertEquals((0..7).toSet() - setOf(1, 2), freq.maxKhz().keys)
        assertEquals(0L, freq.currentKhz(1))
        assertEquals(0L, freq.currentKhz(2))
        assertEquals(0L, freq.currentKhz(42))
        assertEquals(0, fs.count(cpu(1, "scaling_cur_freq")) + fs.count(cpu(2, "scaling_cur_freq")))
    }

    @Test fun an_unreadable_cpu_list_leaves_every_core_unreadable_without_retrying() {
        val fs = phone()
        fs.files.remove("/sys/devices/system/cpu/possible")
        val freq = CpuFrequency(fs::read)
        assertTrue(freq.maxKhz().isEmpty())
        assertEquals(0L, freq.currentKhz(0))
        assertTrue(freq.maxKhz().isEmpty())
        assertEquals(listOf("/sys/devices/system/cpu/possible"), fs.reads)
    }
}
