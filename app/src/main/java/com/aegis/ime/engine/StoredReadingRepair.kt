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

package com.aegis.ime.engine

import com.aegis.ime.user.UserModel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class StoredReadingRepair(private val apply: (List<UserModel.ReadingRepair>) -> Unit) {
    private val engine = AtomicReference<DictEngine?>()
    private val queued = AtomicBoolean(false)
    private val worker = ThreadPoolExecutor(0, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, "aegis-reading-repair").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun request(latest: DictEngine? = null) {
        if (latest != null) engine.set(latest)
        if (engine.get() == null || queued.getAndSet(true)) return
        if (runCatching { worker.execute(::run) }.isFailure) queued.set(false)
    }

    private fun run() {
        queued.set(false)
        val current = engine.get() ?: return
        val repairs = runCatching { current.storedReadingRepairs() }.getOrDefault(emptyList())
        if (repairs.isNotEmpty() && engine.get() === current) apply(repairs)
    }

    private companion object {
        const val IDLE_SECONDS = 30L
    }
}
