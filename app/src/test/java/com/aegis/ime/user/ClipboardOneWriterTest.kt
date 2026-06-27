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

    @Test(timeout = 120_000) fun a_clip_delete_the_writer_never_answers_lets_the_caller_go_and_reports_later() {
        val dir = newDir()
        val s = store(dir)
        s.record("要删的一条")
        s.flushPendingWrites()
        val blocker = s.tempFileFor(File(dir, "clipboard.txt"))
        assertTrue("precondition: the write can never reach the disk", blocker.mkdirs())
        assertTrue(File(blocker, "occupied").createNewFile())
        val reported = ArrayBlockingQueue<Boolean>(8)
        s.reportClipWritesTo({ it.run() }) { reported.add(it) }
        val gate = occupy(s)

        val startedAt = System.nanoTime()
        val taken = s.deleteAll(listOf("要删的一条"))
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("the store must take a deletion it has not written yet", taken)
        assertTrue(
            "the caller waited ${waitedMillis}ms rather than being let go at once",
            waitedMillis < 2_000,
        )
        assertNull(
            "nothing may be reported while the write is still stuck behind the occupied writer",
            reported.poll(2, TimeUnit.SECONDS),
        )

        gate.countDown()

        val landed = reported.poll(30, TimeUnit.SECONDS)
        assertNotNull("the write the caller walked away from must still report back", landed)
        assertFalse("a write that never reached the file must not be reported as one that landed", landed!!)
    }
}
