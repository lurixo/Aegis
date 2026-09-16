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

    @Test fun publishing_an_image_does_not_hold_the_history_during_the_system_call() {
        val dir = temp.newFolder()
        val store = store(dir)
        val entry = recorded(store)
        store.flushPendingWrites()
        var inside: Throwable? = null

        val published = store.retainPublishedImage(entry) {
            inside = runCatching {
                assertEquals(entry.key, promptly("latestEntry()") { store.latestEntry()?.key })
                promptly("record()") { store.record("系统剪贴板回调里复制的") }
            }.exceptionOrNull()
            inside == null
        }
        inside?.let { throw it }
        store.flushPendingWrites()

        assertTrue(published)
        assertEquals(entry.imageFile()!!.name, File(dir, "clips/published-image.ref").readText())
        assertEquals(listOf("系统剪贴板回调里复制的", entry.key), store(dir).historyKeys())
    }

    @Test fun writing_the_published_image_reference_does_not_hold_the_history() {
        val dir = temp.newFolder()
        val store = store(dir)
        val entry = recorded(store)
        store.flushPendingWrites()
        val pipe = pipeAt(store.tempFileFor(File(dir, "clips/published-image.ref")))
        var called = false
        var published: Boolean? = null
        val publisher = Thread { published = store.retainPublishedImage(entry) { called = true; true } }.apply { isDaemon = true; start() }
        waitFor("the reference write reached the paused file") { opening("atomicWrite", publisher) }

        assertEquals(entry.key, promptly("latestEntry()") { store.latestEntry()?.key })
        promptly("record()") { store.record("写引用时复制的") }

        release(pipe)
        publisher.join(TimeUnit.SECONDS.toMillis(10))
        store.flushPendingWrites()

        assertFalse(publisher.isAlive)
        assertEquals(false, published)
        assertFalse("nothing may be published when its reference never reached the disk", called)
        assertFalse(File(dir, "clips/published-image.ref").exists())
        assertEquals(listOf("写引用时复制的", entry.key), store(dir).historyKeys())
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

    @Test fun an_image_published_while_its_last_lease_closes_and_the_sweep_runs_is_kept() {
        val dir = temp.newFolder()
        val store = store(dir)
        val entry = recorded(store)
        val lease = store.retainImageForInput(entry)!!
        assertTrue(store.delete(entry.key))
        store.flushPendingWrites()
        val file = entry.imageFile()!!
        var inside: Throwable? = null

        val published = store.retainPublishedImage(entry) {
            inside = runCatching {
                lease.close()
                store.record("发布时复制的")
                promptly("flushPendingWrites()") { store.flushPendingWrites() }
                assertTrue("the image being published was deleted under the system call", file.isFile)
            }.exceptionOrNull()
            inside == null
        }
        inside?.let { throw it }
        store.flushPendingWrites()

        assertTrue(published)
        assertArrayEquals(png, file.readBytes())
        assertEquals(file.name, File(dir, "clips/published-image.ref").readText())
    }

    @Test fun a_lease_taken_while_the_sweep_waits_keeps_the_image() {
        val dir = temp.newFolder()
        val store = store(dir)
        val entry = recorded(store)
        store.flushPendingWrites()
        val file = entry.imageFile()!!
        val lease = synchronized(monitor(store, "publication")) {
            assertTrue(store.clearHistory())
            waitFor("the sweep is waiting its turn") { writer(store)?.state == Thread.State.BLOCKED }
            promptly("retainImageForInput()") { store.retainImageForInput(entry) }
        }
        store.flushPendingWrites()

        assertNotNull(lease)
        assertArrayEquals(png, file.readBytes())
        lease!!.close()
        store.flushPendingWrites()
        assertFalse(file.exists())
    }

    @Test fun an_image_the_sweep_has_picked_is_neither_leased_nor_published() {
        val dir = temp.newFolder()
        val store = store(dir)
        val entry = recorded(store)
        store.flushPendingWrites()
        val name = entry.imageFile()!!.name
        val history = monitor(store, "history")
        @Suppress("UNCHECKED_CAST")
        val sweeping = monitor(store, "sweepingImages") as MutableSet<String>
        synchronized(history) { sweeping.add(name) }

        assertNull(store.retainImageForInput(entry))
        assertFalse(store.retainPublishedImage(entry) { fail("published an image being deleted"); true })

        synchronized(history) { sweeping.remove(name) }
        store.retainImageForInput(entry)!!.close()
        store.flushPendingWrites()
        assertArrayEquals(png, entry.imageFile()!!.readBytes())
    }

    @Test fun an_image_deleted_while_a_lease_waits_its_turn_is_refused_and_holds_nothing_back() {
        val dir = temp.newFolder()
        val store = store(dir)
        val entry = recorded(store)
        store.flushPendingWrites()
        val file = entry.imageFile()!!
        var lease: ClipboardStore.InputImageLease? = null
        val taker = Thread { lease = store.retainImageForInput(entry) }
        synchronized(monitor(store, "history")) {
            taker.apply { isDaemon = true; start() }
            waitFor("the lease is waiting for the history") { taker.state == Thread.State.BLOCKED }
            assertTrue(file.delete())
        }
        taker.join(TimeUnit.SECONDS.toMillis(10))

        assertFalse(taker.isAlive)
        assertNull("a lease on a file that is gone must be refused", lease)
        assertEquals(entry.key, recorded(store).key)
        assertTrue(store.clearHistory())
        store.flushPendingWrites()
        assertFalse("a refused lease must not keep the image from being swept", file.exists())
    }
}
