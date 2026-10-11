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
    private val REQUIRED_ARM64 = setOf("fp", "asimd", "atomics", "asimdrdm", "crc32", "fphp", "asimdhp", "asimddp")
    private val REQUIRED_X86_64 = setOf("sse4_2", "avx", "f16c", "fma", "bmi2", "avx2")

    val supported: Boolean by lazy {
        supports(System.getProperty("os.arch").orEmpty(), runCatching { File("/proc/cpuinfo").readText() }.getOrDefault(""))
    }

    internal fun supports(arch: String, cpuinfo: String): Boolean {
        val (key, required) = when (arch) {
            "aarch64", "arm64" -> "Features" to REQUIRED_ARM64
            "x86_64" -> "flags" to REQUIRED_X86_64
            else -> return true
        }
        val cores = cpuinfo.lineSequence()
            .filter { it.substringBefore(':').trim() == key }
            .map { it.substringAfter(':').trim().split(WHITESPACE).toSet() }
            .toList()
        return cores.isNotEmpty() && cores.all { it.containsAll(required) }
    }

    private val WHITESPACE = Regex("\\s+")
}
