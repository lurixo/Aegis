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

object ProcessStat {
    fun majorFaults(): Long = runCatching { majorFaults(File("/proc/self/stat").readText()) }.getOrDefault(0L)

    fun threadMajorFaults(): Long = runCatching { majorFaults(File("/proc/thread-self/stat").readText()) }.getOrDefault(0L)

    internal fun majorFaults(stat: String): Long =
        stat.substringAfterLast(')', "").trim().split(' ').getOrNull(MAJOR_FAULTS_FIELD)?.toLongOrNull() ?: 0L

    private const val MAJOR_FAULTS_FIELD = 9
}
