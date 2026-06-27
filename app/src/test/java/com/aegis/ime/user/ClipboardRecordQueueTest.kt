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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class ClipboardRecordQueueTest {

    private val dirs = ArrayList<File>()
    private var release: CountDownLatch? = null

    @After fun letGo() {
        release?.countDown()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newDir(): File = Files.createTempDirectory("clipqueue").toFile().also { dirs += it }

    private fun store(dir: File) = ClipboardStore(dir).apply { load() }

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

    @Test fun a_stopped_store_still_lands_the_writes_already_queued() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.record("交班前的最后一条")
        s.stopSaving()
        release?.countDown()
        assertTrue(
            "the writer must be allowed to finish what it was given",
            writer(s).awaitTermination(10, TimeUnit.SECONDS),
        )

        assertEquals(listOf("交班前的最后一条"), store(dir).historyText())
    }

    @Test(timeout = 30_000) fun a_clip_whose_write_is_stuck_is_never_left_off_the_panel() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.record("还在排队的")

        assertEquals(
            "the panel must hold a clip whose write has not landed yet",
            listOf("还在排队的"),
            s.history().map { it.body() },
        )
        assertFalse("precondition: the write really was still stuck", File(dir, "clipboard.txt").exists())
    }

    @Test fun copying_the_clip_on_top_again_after_its_write_failed_still_writes_it() {
        val dir = newDir()
        val s = store(dir)
        s.record("原有的")
        s.flushPendingWrites()
        val blocker = s.tempFileFor(File(dir, "clipboard.txt"))
        assertTrue("precondition: the history write is blocked", blocker.mkdirs())
        assertTrue(File(blocker, "occupied").createNewFile())
        s.record("没写进去的")
        s.flushPendingWrites()
        assertEquals("precondition: the write never landed", listOf("原有的"), store(dir).historyText())
        assertTrue(File(blocker, "occupied").delete())
        assertTrue(blocker.delete())

        s.record("没写进去的")
        s.flushPendingWrites()

        assertEquals(listOf("没写进去的", "原有的"), store(dir).historyText())
    }
}
