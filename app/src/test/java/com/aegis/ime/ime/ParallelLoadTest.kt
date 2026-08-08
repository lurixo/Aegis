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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

class ParallelLoadTest {

    @Test fun results_come_back_in_the_order_the_tasks_were_given() {
        val out = ParallelLoad.results(listOf({ "a" }, { "b" }, { "c" }))
        assertEquals(listOf("a", "b", "c"), out)
    }

    @Test fun a_single_task_still_runs_and_reports_its_value() {
        assertEquals(listOf(7), ParallelLoad.results(listOf({ 7 })))
        assertEquals(emptyList<Int>(), ParallelLoad.results(emptyList<() -> Int>()))
    }

    @Test fun every_task_runs_at_the_same_time_as_the_others() {
        val meeting = CyclicBarrier(3)
        val out = ParallelLoad.results(
            List(3) { index ->
                {
                    meeting.await(5, TimeUnit.SECONDS)
                    index
                }
            },
        )
        assertEquals("three dictionaries must be open at once, not one after another", listOf(0, 1, 2), out)
    }

    @Test fun the_user_data_side_runs_at_the_same_time_as_the_asset_side() {
        val meeting = CyclicBarrier(2)
        val (user, assets) = ParallelLoad.both(
            {
                meeting.await(5, TimeUnit.SECONDS)
                "user"
            },
            {
                meeting.await(5, TimeUnit.SECONDS)
                "assets"
            },
        )
        assertEquals("user", user)
        assertEquals("assets", assets)
    }

    @Test fun the_tasks_do_not_all_land_on_the_caller_thread() {
        val threads = Collections.synchronizedSet(HashSet<Thread>())
        ParallelLoad.results(List(3) { { threads.add(Thread.currentThread()) } })
        assertEquals("each task needs a lane of its own", 3, threads.size)
    }

    @Test fun no_worker_outlives_the_call() {
        val started = Collections.synchronizedList(ArrayList<Thread>())
        ParallelLoad.results(List(4) { { started.add(Thread.currentThread()) } })
        assertEquals(4, started.size)
        for (worker in started) {
            assertFalse("a load worker must be finished before the caller moves on", worker.isAlive && worker !== Thread.currentThread())
        }
    }

    @Test fun a_task_that_throws_is_reported_after_the_others_have_finished() {
        var sibling = false
        val failure = runCatching {
            ParallelLoad.results(
                listOf<() -> Unit>(
                    { sibling = true },
                    { throw IllegalStateException("dict is broken") },
                ),
            )
        }.exceptionOrNull()
        assertTrue("the failing task must reach the caller", failure is IllegalStateException)
        assertTrue("the other task must still have run to completion", sibling)
    }

    @Test fun a_failing_user_data_side_still_lets_the_asset_side_finish() {
        var assetsRan = false
        val failure = runCatching {
            ParallelLoad.both<Unit, Unit>(
                { throw IllegalStateException("userdb is broken") },
                { assetsRan = true },
            )
        }.exceptionOrNull()
        assertTrue("the failing side must reach the caller", failure is IllegalStateException)
        assertTrue("the other side must still have run to completion", assetsRan)
    }
}
