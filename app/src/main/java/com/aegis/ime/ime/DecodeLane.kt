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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DecodeLane(
    private val worker: Executor,
    private val main: Executor,
    private val logError: (Throwable) -> Unit = {},
    private val settleMillis: Long = SETTLE_MILLIS,
    private val workDone: (Long) -> Unit = {},
) {
    private class Delivery(val gen: Long, val run: () -> Unit)

    private val seq = AtomicLong(0L)
    @Volatile private var lastRequested = 0L
    @Volatile private var lastApplied = 0L
    private val finishedLock = ReentrantLock()
    private val finishedChanged = finishedLock.newCondition()
    private var finished: Delivery? = null

    val pending: Boolean get() = lastApplied < lastRequested

    private fun stale(gen: Long): Boolean = gen != lastRequested || gen <= lastApplied

    fun <R> submit(compute: () -> R, apply: (R) -> Unit, onError: () -> Unit = {}) {
        val gen = seq.incrementAndGet()
        lastRequested = gen
        worker.execute {
            if (stale(gen)) return@execute
            val started = System.nanoTime()
            val result = DecodeCancellation.attempt({ stale(gen) }, compute)
            workDone(System.nanoTime() - started)
            if (result == null) return@execute
            result.exceptionOrNull()?.let(logError)
            val delivery = Delivery(gen) {
                if (gen == lastRequested && gen > lastApplied) {
                    lastApplied = gen
                    result.fold(onSuccess = apply, onFailure = { onError() })
                }
            }
            finishedLock.withLock {
                finished = delivery
                finishedChanged.signalAll()
            }
            main.execute { delivery.run() }
        }
    }

    fun execute(task: () -> Unit) {
        worker.execute { runCatching(task).exceptionOrNull()?.let(logError) }
    }

    fun settle(): Boolean {
        val gen = lastRequested
        if (gen <= lastApplied) return true
        var remaining = TimeUnit.MILLISECONDS.toNanos(settleMillis)
        val delivery = finishedLock.withLock {
            while (finished?.gen != gen && remaining > 0L) remaining = finishedChanged.awaitNanos(remaining)
            finished?.takeIf { it.gen == gen }
        } ?: return false
        delivery.run()
        return gen <= lastApplied
    }

    fun markSatisfiedSynchronously() {
        lastApplied = lastRequested
    }

    companion object {
        const val SETTLE_MILLIS = 150L
    }
}
