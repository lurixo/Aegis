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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class ClipboardWriteOrderTest {

    private val dirs = ArrayList<File>()
    private val stores = ArrayList<ClipboardStore>()
    private var release: CountDownLatch? = null

    @After fun letGo() {
        release?.countDown()
        stores.forEach { it.stopSaving() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newDir(): File = Files.createTempDirectory("clipwriteorder").toFile().also { dirs += it }

    private fun store(dir: File) = ClipboardStore(dir).apply { load() }.also { stores += it }

    private fun writer(store: ClipboardStore): ExecutorService {
        val field = ClipboardStore::class.java.getDeclaredField("io")
        field.isAccessible = true
        return field.get(store) as ExecutorService
    }

    private fun occupy(store: ClipboardStore) {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        release = gate
        writer(store).execute {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
        assertTrue("precondition: the clipboard writer is occupied", entered.await(2, TimeUnit.SECONDS))
    }

    private fun scheduleSave(store: ClipboardStore) {
        val m = ClipboardStore::class.java.getDeclaredMethod("scheduleSave")
        m.isAccessible = true
        m.invoke(store)
    }

    @Test fun the_newest_history_always_wins_the_write_race() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.record("最新一条")
        scheduleSave(s)

        release?.countDown()
        s.flushPendingWrites()

        assertEquals(listOf("最新一条"), s.historyText())
        assertEquals(
            "what the panel shows and what the file holds must be the same list",
            s.historyText(),
            store(dir).historyText(),
        )
    }

    @Test fun a_save_stamped_before_a_record_never_writes_over_it() {
        val dir = newDir()
        val s = store(dir)
        s.record("先有的")
        s.flushPendingWrites()
        occupy(s)

        scheduleSave(s)
        s.record("后来的")

        release?.countDown()
        s.flushPendingWrites()

        assertEquals(listOf("后来的", "先有的"), s.historyText())
        assertEquals(s.historyText(), store(dir).historyText())
    }
}
