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

package com.aegis.ime.dict

object DecodeCancellation {
    private val superseded = ThreadLocal<() -> Boolean>()

    private object Cancelled : RuntimeException(null, null, false, false)

    fun checkpoint() {
        val cancelled = superseded.get() ?: return
        if (cancelled()) throw Cancelled
    }

    fun <R> attempt(cancelled: () -> Boolean, block: () -> R): Result<R>? {
        val outer = superseded.get()
        superseded.set(cancelled)
        return try {
            Result.success(block())
        } catch (t: Throwable) {
            if (t === Cancelled) null else Result.failure(t)
        } finally {
            if (outer == null) superseded.remove() else superseded.set(outer)
        }
    }
}
