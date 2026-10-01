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

object NeuralCpu {
    private val REQUIRED = setOf("fp", "asimd", "atomics", "asimdrdm", "crc32", "fphp", "asimdhp", "asimddp")

    val supported: Boolean by lazy {
        supports(System.getProperty("os.arch").orEmpty(), runCatching { File("/proc/cpuinfo").readText() }.getOrDefault(""))
    }

    internal fun supports(arch: String, cpuinfo: String): Boolean {
        if (arch != "aarch64" && arch != "arm64") return true
        val cores = cpuinfo.lineSequence()
            .filter { it.substringBefore(':').trim() == "Features" }
            .map { it.substringAfter(':').trim().split(WHITESPACE).toSet() }
            .toList()
        return cores.isNotEmpty() && cores.all { it.containsAll(REQUIRED) }
    }

    private val WHITESPACE = Regex("\\s+")
}
