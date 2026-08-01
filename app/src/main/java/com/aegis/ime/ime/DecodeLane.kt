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

import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DecodeLane(
    private val worker: Executor,
    private val main: Executor,
    private val logError: (Throwable) -> Unit = {},
) {
    private val seq = AtomicLong(0L)
    private val running = AtomicReference<Future<*>?>(null)
    @Volatile private var lastRequested = 0L
    @Volatile private var lastApplied = 0L

    val pending: Boolean get() = lastApplied < lastRequested

    fun <R> submit(compute: () -> R, apply: (R) -> Unit, onError: () -> Unit = {}) {
        val gen = seq.incrementAndGet()
        lastRequested = gen
        val task = Runnable {
            if (gen < lastRequested) return@Runnable
            val result = runCatching { compute() }
            result.exceptionOrNull()?.takeUnless { it is CancellationException }?.let(logError)
            main.execute {
                if (gen == lastRequested && gen > lastApplied) {
                    lastApplied = gen
                    result.fold(onSuccess = apply, onFailure = { onError() })
                }
            }
        }
        if (worker is ExecutorService) {
            val future = worker.submit(task)
            running.getAndSet(future)?.cancel(true)
        } else {
            worker.execute(task)
        }
    }

    fun markSatisfiedSynchronously() {
        lastApplied = lastRequested
        running.getAndSet(null)?.cancel(true)
    }
}
