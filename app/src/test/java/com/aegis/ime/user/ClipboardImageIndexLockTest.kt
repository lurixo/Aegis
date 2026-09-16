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

import android.content.Context
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardImageIndexLockTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context = RuntimeEnvironment.getApplication()
    private val stores = ArrayList<ClipboardStore>()
    private val pipes = ArrayList<File>()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=")

    @After fun close() {
        LiveUserData.restoreInProgress = false
        val releaser = Thread {
            while (!Thread.currentThread().isInterrupted) {
                pipes.forEach { release(it) }
                runCatching { Thread.sleep(20) }.onFailure { return@Thread }
            }
        }.apply { isDaemon = true; start() }
        try {
            stores.forEach { it.flushPendingWrites(); it.stopSaving() }
        } finally {
            releaser.interrupt()
        }
    }

    private fun store(dir: File): ClipboardStore = ClipboardStore(dir).apply { load(); stores.add(this) }

    private fun source(): Uri {
        val uri = Uri.parse("content://clipboard.test/${System.nanoTime()}")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(png))
        return uri
    }

    private fun capture(store: ClipboardStore): ArrayBlockingQueue<Result<ClipEntry>> {
        val done = ArrayBlockingQueue<Result<ClipEntry>>(1)
        store.recordImage(context.contentResolver, source(), "image/png") { done.add(it) }
        return done
    }

    private fun recorded(store: ClipboardStore): ClipEntry = capture(store).poll(10, TimeUnit.SECONDS)!!.getOrThrow()

    private fun pipeAt(file: File): File {
        val made = runCatching { ProcessBuilder("mkfifo", file.path).start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("a named pipe is needed to hold a write in place", made && file.exists() && !file.isFile)
        pipes += file
        return file
    }

    private fun release(pipe: File, text: String = "") {
        if (pipe.exists() && !pipe.isFile) RandomAccessFile(pipe, "rw").use { it.write(text.toByteArray()) }
    }

    private fun writer(store: ClipboardStore): Thread? =
        ClipboardStore::class.java.getDeclaredField("writer").apply { isAccessible = true }.get(store) as Thread?

    private fun monitor(store: ClipboardStore, name: String): Any =
        ClipboardStore::class.java.getDeclaredField(name).apply { isAccessible = true }.get(store)!!

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("precondition: $what")
            Thread.sleep(5)
        }
    }

    private fun opening(caller: String, vararg threads: Thread?): Boolean = threads.any { t ->
        val stack = t?.stackTrace.orEmpty()
        stack.firstOrNull()?.methodName == "open0" && stack.any { it.methodName == caller }
    }

    private fun <T> promptly(what: String, work: () -> T): T {
        var outcome: Result<T>? = null
        val caller = Thread { outcome = runCatching(work) }.apply { isDaemon = true; start() }
        caller.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("$what waited for a file the store was still touching", caller.isAlive)
        return outcome!!.getOrThrow()
    }

    @Test fun loading_does_not_hold_the_history_while_reading_the_index() {
        val dir = temp.newFolder()
        val store = store(dir)
        store.record("旧的")
        store.flushPendingWrites()
        assertTrue(File(dir, "clipboard.txt").delete())
        val pipe = pipeAt(File(dir, "clipboard.txt"))
        val loader = Thread { store.load() }.apply { isDaemon = true; start() }
        waitFor("the index read reached the paused file") { opening("readAllLines", loader, writer(store)) }

        assertEquals("旧的", promptly("latestEntry()") { store.latestEntry()?.body() })
        assertEquals(listOf("旧的"), promptly("history()") { store.historyText() })

        release(pipe, "盘上的\n")
        loader.join(TimeUnit.SECONDS.toMillis(10))

        assertFalse(loader.isAlive)
        assertEquals(listOf("盘上的"), store.historyText())
    }

    @Test fun a_clip_copied_while_the_index_is_being_read_never_writes_over_what_was_read() {
        val dir = temp.newFolder()
        val store = store(dir)
        store.record("旧的")
        store.flushPendingWrites()
        val index = File(dir, "clipboard.txt")
        assertTrue(index.delete())
        val pipe = pipeAt(index)
        val loader = Thread { store.load() }.apply { isDaemon = true; start() }
        waitFor("the index read reached the paused file") { opening("readAllLines", loader, writer(store)) }

        store.record("读盘时复制的")
        release(pipe, "盘上的\n")
        loader.join(TimeUnit.SECONDS.toMillis(10))
        store.flushPendingWrites()

        assertFalse(loader.isAlive)
        assertEquals(listOf("盘上的"), store.historyText())
        assertFalse("a snapshot taken before the load finished must not replace the index it read", index.isFile)
        store.record("之后")
        store.flushPendingWrites()
        assertEquals(listOf("之后", "盘上的"), store(dir).historyText())
    }

}
