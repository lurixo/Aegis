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

    @Suppress("UNCHECKED_CAST")
    private fun rowsWithoutWaiting(store: ClipboardStore): List<ClipEntry> {
        val field = ClipboardStore::class.java.getDeclaredField("history")
        field.isAccessible = true
        return ArrayList(field.get(store) as ArrayList<ClipEntry>)
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

    private fun big(marker: String): String = marker + "x".repeat(ClipboardStore.BIG_THRESHOLD + 1)

    private fun sideFiles(dir: File): List<String> =
        File(dir, "clips").listFiles()?.map { it.name }?.sorted().orEmpty()

    @Test fun a_big_block_is_on_the_panel_the_moment_it_is_copied() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.record(big("one"))

        assertEquals(
            "a big clip must be filed by its digest before record returns",
            1,
            rowsWithoutWaiting(s).size,
        )
        assertTrue("a big clip must still be filed by its digest", s.historyKeys().single().startsWith("B\t"))
        assertFalse(
            "and filing it must not write on the thread that copied it",
            File(dir, "clipboard.txt").exists(),
        )
    }

    @Test fun copying_the_same_big_block_twice_leaves_one_row_and_one_side_file() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)
        val body = big("same")

        s.record(body)
        s.record(body)
        release?.countDown()

        assertEquals("the same block copied twice is one row", 1, s.history().size)
        s.flushPendingWrites()
        assertEquals(1, sideFiles(dir).size)
        assertEquals(1, store(dir).history().size)
    }

    @Test fun two_different_big_blocks_both_survive() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.record(big("first"))
        s.record(big("second"))
        release?.countDown()
        s.flushPendingWrites()

        assertEquals(2, s.history().size)
        assertEquals(2, sideFiles(dir).size)
        assertEquals(2, store(dir).history().size)
    }

    @Test fun a_small_clip_copied_after_a_big_one_still_lands_on_top() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.record(big("under"))
        s.record("on top")
        release?.countDown()

        assertEquals("on top", s.history().first().body())
        s.flushPendingWrites()
        assertEquals("on top", store(dir).history().first().body())
    }

    @Test fun an_export_flush_lands_a_clip_whose_write_was_still_queued() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)
        val body = big("queued")

        s.record(body)
        release?.countDown()
        s.flushPendingWrites()

        assertEquals(body, store(dir).history().single().body())
    }

    @Test fun clearing_the_history_also_clears_a_clip_that_was_still_being_filed() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.record(big("doomed"))
        release?.countDown()
        s.clearHistory()
        s.flushPendingWrites()

        assertEquals(emptyList<ClipEntry>(), s.history())
        assertEquals("the side file of a cleared clip must be swept", emptyList<String>(), sideFiles(dir))
        assertEquals(emptyList<ClipEntry>(), store(dir).history())
    }

    @Test fun deleting_a_clip_that_was_still_being_filed_removes_it() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.record(big("goes"))
        s.record("stays")
        release?.countDown()
        val doomed = s.historyKeys().first { it.startsWith("B\t") }
        s.delete(doomed)
        s.flushPendingWrites()

        assertEquals(listOf("stays"), store(dir).historyText())
        assertEquals(emptyList<String>(), sideFiles(dir))
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

    @Test fun a_reload_never_sees_half_of_a_clip_that_was_still_being_filed() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.record(big("late"))
        release?.countDown()
        s.load()

        assertEquals("a reload must not drop a clip the writer had not filed yet", 1, s.history().size)
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

    @Test fun copying_a_big_clip_on_top_again_after_its_side_file_went_missing_writes_it_back() {
        val dir = newDir()
        val s = store(dir)
        val body = big("丢了侧文件")
        s.record(body)
        s.flushPendingWrites()
        val side = File(dir, "clips").listFiles()!!.single()
        assertTrue("precondition: the side file is gone", side.delete())

        s.record(body)
        s.flushPendingWrites()

        assertEquals("the copy must put the side file back", listOf(side.name), sideFiles(dir))
        assertEquals(body, store(dir).history().single().body())
    }
}
