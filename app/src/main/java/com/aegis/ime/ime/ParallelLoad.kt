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

object ParallelLoad {

    private const val THREAD_NAME = "aegis-load"

    fun <A, B> both(first: () -> A, second: () -> B): Pair<A, B> {
        val slot = Slot<A>()
        val worker = spawn { slot.hold(runCatching(first)) }
        val secondResult = runCatching(second)
        if (worker == null) slot.hold(runCatching(first)) else joinQuietly(worker)
        return slot.taken().getOrThrow() to secondResult.getOrThrow()
    }

    fun <T> results(tasks: List<() -> T>): List<T> {
        if (tasks.size <= 1) return tasks.map { it() }
        val slots = List(tasks.size) { Slot<T>() }
        val workers = ArrayList<Thread?>(tasks.size - 1)
        for (i in 1 until tasks.size) {
            val slot = slots[i]
            val task = tasks[i]
            workers.add(spawn { slot.hold(runCatching(task)) })
        }
        slots[0].hold(runCatching(tasks[0]))
        for (i in 1 until tasks.size) {
            val worker = workers[i - 1]
            if (worker == null) slots[i].hold(runCatching(tasks[i])) else joinQuietly(worker)
        }
        return slots.map { it.taken().getOrThrow() }
    }

    private class Slot<T> {

        @Volatile
        private var result: Result<T>? = null

        fun hold(value: Result<T>) { result = value }

        fun taken(): Result<T> = result ?: Result.failure(IllegalStateException("parallel load produced no result"))
    }

    private fun spawn(body: () -> Unit): Thread? = runCatching {
        Thread(body, THREAD_NAME).apply { isDaemon = true }.also { it.start() }
    }.getOrNull()

    private fun joinQuietly(worker: Thread) {
        var interrupted = false
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
