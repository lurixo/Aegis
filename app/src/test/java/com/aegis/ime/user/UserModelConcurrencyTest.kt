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

package com.aegis.ime.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * M-2: UserModel is touched by the background dict-load thread and the main thread (onStartInput reload /
 * commit record) at once. Without synchronization, reload()'s clear() + a concurrent record()/save()
 * iteration corrupts the HashMaps (ConcurrentModificationException / lost updates / a damaged userdb.txt).
 * These run real threads and would fail (CME) on the un-synchronized version.
 */
class UserModelConcurrencyTest {

    @Test fun concurrent_record_reload_save_do_not_corrupt() {
        val seed = File.createTempFile("userdb-seed", ".txt").also { it.deleteOnExit() }
        UserModel().apply { repeat(60) { record(null, "w$it", it.toLong()) }; save(seed) }

        val m = UserModel().apply { load(seed) }
        val errors = ConcurrentLinkedQueue<Throwable>()
        fun spin(body: () -> Unit) = Thread {
            try { body() } catch (t: Throwable) { errors.add(t) }
        }

        val saveOut = File.createTempFile("userdb-save", ".txt").also { it.deleteOnExit() }
        val threads = listOf(
            spin { repeat(3000) { m.record("p", "x$it", it.toLong()) } },          // main-thread commits
            spin { repeat(800) { m.reload(seed) } },                               // onStartInput reload (clear+load)
            spin { repeat(800) { m.load(seed) } },                                 // background initial load
            spin { repeat(400) { m.wordBoost("w1"); m.successors("p", 5) } },      // decoder reads
            spin { repeat(300) { m.save(saveOut) } },                              // onFinishInput save (iterates)
        )
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue("no concurrency exception expected, got: ${errors.toList()}", errors.isEmpty())
    }

    @Test fun save_load_round_trip_is_complete() {
        val tmp = File.createTempFile("userdb-rt", ".txt").also { it.deleteOnExit() }
        UserModel().apply {
            record(null, "你好", 100); record("你好", "世界", 200); record(null, "你好", 300)
            save(tmp)
        }
        val b = UserModel().apply { load(tmp) }
        assertEquals("你好 count survives the round trip", UserModel().apply {
            record(null, "你好", 1); record(null, "你好", 2)
        }.wordBoost("你好"), b.wordBoost("你好"), 1e-9)
        assertEquals(listOf("世界"), b.successors("你好", 5))
    }
}
