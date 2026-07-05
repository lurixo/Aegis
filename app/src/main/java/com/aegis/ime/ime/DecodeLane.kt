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

/**
 * ① Serial "latest-wins" compute lane: moves an expensive PURE computation (the pinyin decode + the
 * cross-process text-before-cursor read) OFF the key-handling thread while guaranteeing the applied
 * result is always the one for the MOST RECENT request.
 *
 * Threading contract:
 *  - [worker] runs each [submit]'s compute. Use a SINGLE-thread executor so computes never overlap and
 *    input order is preserved. A request already superseded by a newer one when it reaches the worker
 *    skips its compute entirely (coalescing — only the final keystroke of a fast burst pays for a decode).
 *  - [main] delivers the result back to the caller's (main) thread — a `Handler(mainLooper)::post` in the
 *    IME. A result whose generation is not newer than one already applied is DROPPED, so an out-of-order
 *    or post-reset delivery can never overwrite fresher candidates.
 *
 * The computation MUST be pure over an immutable snapshot captured at [submit] time (it runs on the
 * worker with no lock on caller state). Equivalence to a synchronous call is then by construction: the
 * same function over the same inputs. [submit]/[pending]/[markSatisfiedSynchronously] are called only on
 * the main thread; the worker reads only [seq]/[lastRequested] (volatile). See [KeyboardController].
 */
class DecodeLane(
    private val worker: Executor,
    private val main: Executor,
) {
    private val seq = AtomicLong(0L)
    @Volatile private var lastRequested = 0L
    @Volatile private var lastApplied = 0L

    /** True when a submitted decode has not yet been applied (a fresher result is still in flight). */
    val pending: Boolean get() = lastApplied < lastRequested

    fun <R> submit(compute: () -> R, apply: (R) -> Unit) {
        val gen = seq.incrementAndGet()
        lastRequested = gen
        worker.execute {
            // Coalesce: a newer request arrived before this one ran → its compute will produce the result
            // that wins anyway, so skip this (potentially expensive) decode entirely.
            if (gen < lastRequested) return@execute
            val result = compute()
            main.execute {
                // Latest wins on the apply side too: drop a stale / out-of-order / post-reset delivery.
                if (gen > lastApplied) {
                    lastApplied = gen
                    apply(result)
                }
            }
        }
    }

    /**
     * Mark every in-flight request as satisfied WITHOUT applying anything. The caller has just produced an
     * up-to-date result synchronously (e.g. a space-commit forced a live decode), so the pending async
     * result — identical by construction — must not later re-fire a redundant apply/render on top of it.
     */
    fun markSatisfiedSynchronously() {
        lastApplied = lastRequested
    }
}
