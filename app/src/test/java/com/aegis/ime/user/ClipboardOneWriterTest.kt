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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class ClipboardOneWriterTest {

    private val dirs = ArrayList<File>()
    private val gates = ArrayList<CountDownLatch>()

    @After fun letGo() {
        gates.forEach { it.countDown() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newDir(): File = Files.createTempDirectory("onewriter").toFile().also { dirs += it }

    private fun store(dir: File) = ClipboardStore(dir).apply { load() }

    private fun writer(store: ClipboardStore): ExecutorService {
        val field = ClipboardStore::class.java.getDeclaredField("io")
        field.isAccessible = true
        return field.get(store) as ExecutorService
    }

    private fun occupy(store: ClipboardStore, work: () -> Unit = {}): CountDownLatch {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1).also { gates += it }
        writer(store).execute {
            entered.countDown()
            gate.await(30, TimeUnit.SECONDS)
            work()
        }
        assertTrue("precondition: the clipboard writer is occupied", entered.await(10, TimeUnit.SECONDS))
        return gate
    }

    private fun parked(t: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val state = t.state
            if (!t.isAlive || state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) return
            Thread.onSpinWait()
        }
    }

    @Test(timeout = 120_000) fun a_deletion_the_writer_never_answers_lets_the_caller_go_and_reports_later() {
        val dir = newDir()
        val s = store(dir)
        assertEquals(
            "precondition: the phrase is there to delete",
            1,
            s.addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("要删的一条")),
        )
        s.flushPendingWrites()
        val blocker = s.tempFileFor(File(dir, "phrases.txt"))
        assertTrue("precondition: the write can never reach the disk", blocker.mkdirs())
        assertTrue(File(blocker, "occupied").createNewFile())
        val reported = ArrayBlockingQueue<PhraseChange>(8)
        s.reportPhraseWritesTo({ it.run() }) { reported.add(it) }
        val gate = occupy(s)

        val startedAt = System.nanoTime()
        val taken = s.deletePhrasesFrom(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("要删的一条"))
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("the store must take a deletion it has not written yet", taken)
        assertTrue(
            "the caller waited ${waitedMillis}ms: a deletion the user made from the panel runs on the" +
                " drawing thread, so it must not wait for the write at all",
            waitedMillis < 2_000,
        )
        assertNull(
            "nothing may be reported while the write is still stuck behind the occupied writer",
            reported.poll(2, TimeUnit.SECONDS),
        )

        gate.countDown()

        val change = reported.poll(30, TimeUnit.SECONDS)
        assertNotNull("the write the caller walked away from must still report back", change)
        assertFalse("a write that never reached the file must not be reported as one that landed", change!!.saved)
    }

    @Test(timeout = 120_000) fun a_phrase_write_the_next_one_queued_behind_still_reports_what_reached_the_file() {
        val dir = newDir()
        val s = store(dir)
        val reported = ArrayBlockingQueue<PhraseChange>(8)
        s.reportPhraseWritesTo({ it.run() }) { reported.add(it) }
        val gate = occupy(s)

        assertTrue("precondition: the category is taken", s.addCategory("甲"))
        assertEquals("precondition: the phrase is taken", 1, s.addPhrasesTo("甲", listOf("存进去的")))

        gate.countDown()
        s.flushPendingWrites()

        assertEquals(
            "two edits made back to back both reached phrases.txt, so neither may be called a failure",
            listOf(true, true),
            listOf(reported.poll(30, TimeUnit.SECONDS)?.saved, reported.poll(30, TimeUnit.SECONDS)?.saved),
        )
        assertEquals(listOf("存进去的"), store(dir).phrasesIn("甲"))
    }

    @Test fun an_imported_history_is_written_by_the_store_writer_alone() {
        val dir = newDir()
        val s = store(dir)
        val firstGate = occupy(s)
        val queuedBehind = CountDownLatch(1)
        val secondGate = CountDownLatch(1).also { gates += it }
        val finished = CountDownLatch(1)

        val importer = Thread {
            s.importHistory(listOf("导入的一条").asClipEntries(), merge = false)
            finished.countDown()
        }.apply { isDaemon = true; start() }
        parked(importer)
        writer(s).execute {
            queuedBehind.countDown()
            secondGate.await(30, TimeUnit.SECONDS)
        }
        firstGate.countDown()
        assertTrue(
            "precondition: the writer reached the task queued behind the import",
            queuedBehind.await(30, TimeUnit.SECONDS),
        )

        assertFalse(
            "an import must wait its turn on the writer that owns the file, not write beside it",
            finished.await(3, TimeUnit.SECONDS),
        )
        assertFalse("and nothing of it may have reached the disk yet", File(dir, "clipboard.txt").exists())

        secondGate.countDown()
        importer.join(TimeUnit.SECONDS.toMillis(30))
        assertFalse("the import must go through once the writer is free", importer.isAlive)
        assertEquals(listOf("导入的一条"), store(dir).historyText())
    }

    @Test fun an_imported_history_still_reports_what_the_writer_could_not_do() {
        val dir = newDir()
        val s = store(dir)
        File(dir, "clipboard.txt").let {
            assertTrue("precondition: the history path is occupied", it.mkdirs())
            File(it, "blocker").writeText("x")
        }

        val failure = runCatching { s.importHistory(listOf("进不去").asClipEntries(), merge = false) }

        assertTrue("the failure the writer hit must come back whole", failure.isFailure)
    }
}
