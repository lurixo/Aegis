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

package com.aegis.ime.ime

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

class DecodeLane(
    private val worker: Executor,
    private val main: Executor,
) {
    private val seq = AtomicLong(0L)
    @Volatile private var lastRequested = 0L
    @Volatile private var lastApplied = 0L

    val pending: Boolean get() = lastApplied < lastRequested

    fun <R> submit(compute: () -> R, apply: (R) -> Unit) {
        val gen = seq.incrementAndGet()
        lastRequested = gen
        worker.execute {
            if (gen < lastRequested) return@execute
            val result = compute()
            main.execute {
                if (gen > lastApplied) {
                    lastApplied = gen
                    apply(result)
                }
            }
        }
    }

    fun markSatisfiedSynchronously() {
        lastApplied = lastRequested
    }
}
