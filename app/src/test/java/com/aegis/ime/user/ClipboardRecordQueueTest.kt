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
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
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

    private fun changer(store: ClipboardStore): ExecutorService {
        val field = ClipboardStore::class.java.getDeclaredField("changes")
        field.isAccessible = true
        return field.get(store) as ExecutorService
    }

    private fun occupy(store: ClipboardStore) {
        val lanes = listOf(changer(store), writer(store))
        val entered = CountDownLatch(lanes.size)
        val gate = CountDownLatch(1)
        release = gate
        for (lane in lanes) lane.execute {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
        assertTrue("precondition: both clipboard lanes are occupied", entered.await(2, TimeUnit.SECONDS))
    }

    private fun big(marker: String): String = marker + "x".repeat(ClipboardStore.BIG_THRESHOLD + 1)

    private fun sideFiles(dir: File): List<String> =
        File(dir, "clips").listFiles()?.map { it.name }?.sorted().orEmpty()

    @Test fun the_thread_that_copied_a_big_block_does_not_hash_it() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.record(big("one"))

        assertEquals(
            "recording a big clip must be handed to the writer, hash and all",
            emptyList<ClipEntry>(),
            rowsWithoutWaiting(s),
        )
        release?.countDown()
        assertEquals(1, s.history().size)
        assertTrue("a big clip must still be filed by its digest", s.historyKeys().single().startsWith("B\t"))
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

    @Test fun an_export_flush_lands_a_clip_that_was_still_being_hashed() {
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

    @Test fun a_phrase_edit_does_not_write_on_the_thread_that_made_it() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("排队中的常用语"))

        assertFalse(
            "a phrase edit must be handed to the writer, not written where it was made",
            File(dir, "phrases.txt").exists(),
        )
        release?.countDown()
        s.flushPendingWrites()
        assertEquals(listOf("排队中的常用语"), store(dir).phrases())
    }

    @Test fun an_export_flush_lands_a_phrase_edit_that_was_still_queued() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("待落盘"))
        release?.countDown()
        s.flushPendingWrites()

        assertEquals(listOf("待落盘"), store(dir).phrases())
    }

    @Test fun a_phrase_import_reports_the_failure_it_hit_on_the_writer() {
        val dir = newDir()
        val s = store(dir)
        File(dir, "phrases.txt").let {
            it.deleteRecursively()
            assertTrue("precondition: the phrase file path is occupied", it.mkdirs())
            File(it, "blocker").writeText("x")
        }

        try {
            s.importPhrasesText("C\t甲\nP\t进不去\n", merge = false)
            fail("expected the blocked phrase file to be reported")
        } catch (e: IOException) {
            assertTrue("the failure the writer hit must come back whole", e.message.orEmpty().isNotEmpty())
        }
    }

    @Test fun a_stopped_store_still_lands_the_writes_already_queued() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.record("交班前的最后一条")
        s.stopSaving()
        release?.countDown()
        assertTrue(
            "the change lane must be allowed to finish what it was given",
            changer(s).awaitTermination(10, TimeUnit.SECONDS),
        )
        assertTrue(
            "the writer must be allowed to finish what it was given",
            writer(s).awaitTermination(10, TimeUnit.SECONDS),
        )

        assertEquals(listOf("交班前的最后一条"), store(dir).historyText())
    }

    @Test(timeout = 30_000) fun a_write_that_never_finishes_does_not_hold_up_reading_the_panel() {
        val dir = newDir()
        val s = store(dir)
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        release = gate
        writer(s).execute {
            entered.countDown()
            gate.await(20, TimeUnit.SECONDS)
        }
        assertTrue("precondition: the file writer is occupied", entered.await(2, TimeUnit.SECONDS))

        s.record("刚复制的")
        s.settleChanges()

        val startedAt = System.nanoTime()
        val rows = s.history()
        s.reloadPhrases()
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertEquals("what the panel opens on must already hold the clip", listOf("刚复制的"), rows.map { it.body() })
        assertTrue(
            "opening the panel waited ${waitedMillis}ms behind a write that never finishes",
            waitedMillis < 2_000,
        )
        assertFalse("precondition: the write really was still stuck", File(dir, "clipboard.txt").exists())
    }

    @Test(timeout = 30_000) fun a_clip_that_has_not_been_filed_yet_is_never_left_off_the_panel() {
        val dir = newDir()
        val s = store(dir)
        occupy(s)

        s.record("还在排队的")
        val reader = Thread { s.history() }.apply { isDaemon = true; start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && reader.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)) {
            Thread.onSpinWait()
        }

        assertTrue(
            "reading the history must wait for a clip that was taken but not filed rather than leave it out",
            reader.isAlive,
        )

        release?.countDown()
        reader.join(TimeUnit.SECONDS.toMillis(20))
        assertEquals(listOf("还在排队的"), s.history().map { it.body() })
    }

    @Test fun a_reload_never_throws_away_a_phrase_edit_the_writer_still_holds() {
        val dir = newDir()
        val s = store(dir)
        File(dir, "phrases.txt").writeText("C\t甲\nP\t盘上的\n")
        s.load()
        occupy(s)

        assertEquals(1, s.addPhrasesTo("甲", listOf("刚加的")))
        s.reloadPhrases()

        assertEquals(
            "a reload must not put back a file that is older than the edit still on its way to it",
            listOf("刚加的", "盘上的"),
            s.phrasesIn("甲"),
        )
        release?.countDown()
        s.flushPendingWrites()
        assertEquals(listOf("刚加的", "盘上的"), store(dir).phrasesIn("甲"))
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
}
