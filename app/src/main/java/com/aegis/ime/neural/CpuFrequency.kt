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

import java.io.File
import java.util.concurrent.ConcurrentHashMap

class CpuFrequency(private val read: (String) -> String) {
    private val maxKhz: Map<Int, Long> by lazy { readMaxKhz() }
    private val unreadable = ConcurrentHashMap.newKeySet<Int>()

    fun maxKhz(): Map<Int, Long> = maxKhz

    fun currentKhz(cpu: Int): Long {
        if (cpu !in maxKhz || cpu in unreadable) return 0L
        val khz = khz("$CPU_DIR/cpu$cpu/cpufreq/scaling_cur_freq")
        if (khz <= 0L) unreadable.add(cpu)
        return khz
    }

    private fun readMaxKhz(): Map<Int, Long> {
        val possible = runCatching { read("$CPU_DIR/possible") }.getOrNull() ?: return emptyMap()
        return cpus(possible).mapNotNull { cpu ->
            khz("$CPU_DIR/cpu$cpu/cpufreq/cpuinfo_max_freq").takeIf { it > 0L }?.let { cpu to it }
        }.toMap()
    }

    private fun khz(path: String): Long =
        runCatching { read(path).trim().toLongOrNull() }.getOrNull()?.takeIf { it > 0L } ?: 0L

    companion object {
        private const val CPU_DIR = "/sys/devices/system/cpu"
        private const val MAX_CPUS = 1024

        val system = CpuFrequency { File(it).readText() }

        internal fun cpus(list: String): List<Int> =
            list.trim().split(',').filter { it.isNotBlank() }.flatMap { part ->
                val bounds = part.trim().split('-').map {
                    it.trim().toIntOrNull()?.takeIf { cpu -> cpu in 0 until MAX_CPUS } ?: return emptyList()
                }
                when (bounds.size) {
                    1 -> listOf(bounds[0])
                    2 -> if (bounds[0] <= bounds[1]) (bounds[0]..bounds[1]).toList() else return emptyList()
                    else -> return emptyList()
                }
            }.distinct()
    }
}
