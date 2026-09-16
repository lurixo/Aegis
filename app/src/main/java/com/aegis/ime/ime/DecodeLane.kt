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

import com.aegis.ime.dict.DecodeCancellation
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

class DecodeLane(
    private val worker: Executor,
    private val main: Executor,
    private val logError: (Throwable) -> Unit = {},
) {
    private val seq = AtomicLong(0L)
    @Volatile private var lastRequested = 0L
    @Volatile private var lastApplied = 0L

    val pending: Boolean get() = lastApplied < lastRequested

    private fun stale(gen: Long): Boolean = gen != lastRequested || gen <= lastApplied

    fun <R> submit(compute: () -> R, apply: (R) -> Unit, onError: () -> Unit = {}) {
        val gen = seq.incrementAndGet()
        lastRequested = gen
        worker.execute {
            if (stale(gen)) return@execute
            val result = DecodeCancellation.attempt({ stale(gen) }, compute)
            if (result == null) return@execute
            result.exceptionOrNull()?.let(logError)
            main.execute {
                if (gen == lastRequested && gen > lastApplied) {
                    lastApplied = gen
                    result.fold(onSuccess = apply, onFailure = { onError() })
                }
            }
        }
    }

    fun markSatisfiedSynchronously() {
        lastApplied = lastRequested
    }
}
