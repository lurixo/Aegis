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

import android.content.ClipData
import android.content.Context
import android.net.Uri
import org.junit.After
import org.junit.Before
import androidx.core.content.FileProvider
import org.junit.Assert.*
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
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardImageStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context = RuntimeEnvironment.getApplication()
    private val stores = ArrayList<ClipboardStore>()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=")

    @Before fun resetProviderCache() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @After fun close() {
        LiveUserData.restoreInProgress = false
        stores.forEach { it.flushPendingWrites(); it.stopSaving() }
    }

    private fun store(dir: File = temp.newFolder()): ClipboardStore =
        ClipboardStore(dir).apply { load(); stores.add(this) }

    private fun source(bytes: ByteArray = png): Uri {
        val uri = Uri.parse("content://clipboard.test/${System.nanoTime()}")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private fun record(store: ClipboardStore, uri: Uri = source()): Result<ClipEntry> {
        val done = CountDownLatch(1)
        var result: Result<ClipEntry>? = null
        store.recordImage(context.contentResolver, uri, "image/png") { result = it; done.countDown() }
        assertTrue("image capture finished", done.await(10, TimeUnit.SECONDS))
        return result!!
    }

    @Test fun image_survives_source_loss_reload_and_deduplicates_by_bytes() {
        val dir = temp.newFolder()
        val store = store(dir)
        store.record("text")
        val first = record(store).getOrThrow()
        assertTrue(first.isImage)
        assertEquals("image/png", first.mimeType)
        assertNull(first.body())
        assertEquals(1, ClipboardImages.thumbnail(first, 128)!!.width)
        assertArrayEquals(png, first.imageFile()!!.readBytes())
        val second = record(store).getOrThrow()
        assertEquals(first.key, second.key)
        assertEquals(2, store.history().size)
        assertEquals(first.key, store.latestEntry()!!.key)
        val reloaded = store(dir).latestEntry()!!
        assertTrue(reloaded.isImage)
        assertNull(reloaded.body())
        assertArrayEquals(png, reloaded.imageFile()!!.readBytes())
        assertEquals(1, File(dir, "clips/images").listFiles()!!.size)
    }

    @Test fun the_image_already_on_top_captured_again_does_not_write_the_history_again() {
        val dir = temp.newFolder()
        val store = store(dir)
        store.record("text")
        val first = record(store).getOrThrow()
        store.flushPendingWrites()
        val index = Files.readAttributes(File(dir, "clipboard.txt").toPath(), BasicFileAttributes::class.java)

        val again = record(store).getOrThrow()
        store.flushPendingWrites()

        val after = Files.readAttributes(File(dir, "clipboard.txt").toPath(), BasicFileAttributes::class.java)
        assertEquals(first.key, again.key)
        assertEquals(first.key, store.latestEntry()!!.key)
        assertEquals(listOf(first.key, "text"), store.history().map { it.key })
        assertEquals("the history file must not be written again", index.fileKey(), after.fileKey())
        assertEquals(index.lastModifiedTime(), after.lastModifiedTime())
        assertEquals(listOf(first.key, "text"), store(dir).history().map { it.key })
    }

    @Test fun the_image_on_top_captured_again_after_a_history_write_failed_still_writes_the_history() {
        val dir = temp.newFolder()
        val store = store(dir)
        store.record("要删的")
        val image = record(store).getOrThrow()
        store.flushPendingWrites()
        val blocker = store.tempFileFor(File(dir, "clipboard.txt"))
        assertTrue("precondition: the history write is blocked", blocker.mkdirs())
        assertTrue(File(blocker, "occupied").createNewFile())
        assertTrue(store.delete("要删的"))
        store.flushPendingWrites()
        assertEquals("precondition: the delete never reached the file", listOf(image.key, "要删的"), store(dir).history().map { it.key })
        assertTrue(File(blocker, "occupied").delete())
        assertTrue(blocker.delete())

        assertEquals(image.key, record(store).getOrThrow().key)
        store.flushPendingWrites()

        assertEquals(listOf(image.key), store(dir).history().map { it.key })
    }

    @Test fun image_reference_looking_text_stays_text_and_images_cannot_be_edited_as_text() {
        val dir = temp.newFolder()
        val store = store(dir)
        val image = record(store).getOrThrow()
        assertFalse(store.editClip(image.key, "replacement"))
        store.record(image.key)
        store.flushPendingWrites()
        val reloaded = store(dir).history()
        assertFalse(reloaded.first().isImage)
        assertEquals(image.key, reloaded.first().body())
        assertTrue(reloaded.last().isImage)
    }

    @Test fun deleting_and_clearing_history_remove_image_files_only_after_index_write() {
        val store = store()
        val image = record(store).getOrThrow()
        val file = image.imageFile()!!
        assertTrue(store.delete(image.key))
        store.flushPendingWrites()
        assertFalse(file.exists())
        val second = record(store).getOrThrow().imageFile()!!
        assertTrue(store.clearHistory())
        store.flushPendingWrites()
        assertFalse(second.exists())
    }

    @Test fun failed_index_write_preserves_existing_history_and_removes_new_image() {
        val dir = temp.newFolder()
        val store = store(dir)
        store.record("keep")
        store.flushPendingWrites()
        assertTrue(store.tempFileFor(File(dir, "clipboard.txt")).mkdir())
        val result = record(store)
        assertTrue(result.isFailure)
        assertEquals(listOf("keep"), store.history().mapNotNull { it.body() })
        assertTrue(File(dir, "clips/images").listFiles().orEmpty().isEmpty())
        assertEquals("keep", File(dir, "clipboard.txt").readText().trim())
    }

    @Test fun invalid_and_oversized_images_leave_no_history_or_temporary_file() {
        val dir = temp.newFolder()
        val store = store(dir)
        assertTrue(record(store, source("not an image".toByteArray())).isFailure)
        val uri = Uri.parse("content://clipboard.test/large")
        shadowOf(context.contentResolver).registerInputStream(uri, object : InputStream() {
            var remaining = ClipboardImages.MAX_IMAGE_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 1 else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                bytes.fill(1, offset, offset + count)
                remaining -= count
                return count
            }
        })
        assertTrue(record(store, uri).exceptionOrNull() is ClipboardImageTooLargeException)
        assertTrue(store.history().isEmpty())
        assertTrue(File(dir, "clips/images").listFiles().orEmpty().isEmpty())
    }

    @Test fun clearing_history_cancels_an_image_still_being_read() {
        val dir = temp.newFolder()
        val store = store(dir)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val done = CountDownLatch(1)
        val uri = Uri.parse("content://clipboard.test/delayed")
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(png) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                assertTrue(proceed.await(10, TimeUnit.SECONDS))
                return super.read(bytes, offset, length)
            }
        })
        var result: Result<ClipEntry>? = null
        store.recordImage(context.contentResolver, uri, "image/png") { result = it; done.countDown() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        store.clearHistory()
        proceed.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(result!!.isFailure)
        assertTrue(store.history().isEmpty())
        assertTrue(File(dir, "clips/images").listFiles().orEmpty().isEmpty())
    }


    @Test fun failed_history_delete_keeps_the_persisted_image_available_after_reload() {
        val dir = temp.newFolder()
        val store = store(dir)
        val image = record(store).getOrThrow()
        val file = image.imageFile()!!
        assertTrue(store.tempFileFor(File(dir, "clipboard.txt")).mkdir())
        assertTrue(store.delete(image.key))
        store.flushPendingWrites()
        assertTrue(file.isFile)
        val restored = store(dir).latestEntry()!!
        assertTrue(restored.isImage)
        assertArrayEquals(png, restored.imageFile()!!.readBytes())
    }


    @Test fun slow_image_keeps_its_capture_order_behind_newer_text() {
        val dir = temp.newFolder()
        val store = store(dir)
        store.record("old text")
        store.flushPendingWrites()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val done = CountDownLatch(1)
        val uri = Uri.parse("content://clipboard.test/ordered")
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(png) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                assertTrue(proceed.await(10, TimeUnit.SECONDS))
                return super.read(bytes, offset, length)
            }
        })
        var result: Result<ClipEntry>? = null
        store.recordImage(context.contentResolver, uri, "image/png") { result = it; done.countDown() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        store.record("newer text")
        proceed.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        store.flushPendingWrites()
        val image = result!!.getOrThrow()
        assertEquals(listOf("newer text", image.key, "old text"), store.history().map { it.key })
        assertEquals("newer text", store.latestEntry()!!.body())
        assertEquals(listOf("newer text", image.key, "old text"), store(dir).history().map { it.key })
    }

    @Test fun pending_text_write_is_preserved_when_image_capture_fails() {
        val dir = temp.newFolder()
        val store = store(dir)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val done = CountDownLatch(1)
        val uri = Uri.parse("content://clipboard.test/failing-delayed")
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(png) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                assertTrue(proceed.await(10, TimeUnit.SECONDS))
                return super.read(bytes, offset, length)
            }
        })
        val blocker = store.tempFileFor(File(dir, "clipboard.txt"))
        assertTrue(blocker.mkdir())
        var result: Result<ClipEntry>? = null
        store.recordImage(context.contentResolver, uri, "image/png") {
            result = it
            blocker.delete()
            done.countDown()
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        store.record("keep concurrent text")
        proceed.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        store.flushPendingWrites()
        assertTrue(result!!.isFailure)
        assertEquals(listOf("keep concurrent text"), store(dir).history().mapNotNull { it.body() })
        assertTrue(File(dir, "clips/images").listFiles().orEmpty().isEmpty())
    }

    @Test fun image_mime_is_detected_without_coercing_uri_to_text() {
        val clip = ClipData("image", arrayOf("image/png"), ClipData.Item(source()))
        assertEquals("image/png", ClipboardImages.imageMimeType(context.contentResolver, clip, clip.getItemAt(0)))
        val file = ClipData("file", arrayOf("image/png"), ClipData.Item(Uri.parse("file:///private.png")))
        assertNull(ClipboardImages.imageMimeType(context.contentResolver, file, file.getItemAt(0)))
    }

    @Test fun capturing_an_image_from_further_down_again_keeps_its_row_when_the_index_write_fails() {
        val dir = temp.newFolder()
        val store = store(dir)
        val image = record(store).getOrThrow()
        store.record("text")
        store.flushPendingWrites()
        val blocker = store.tempFileFor(File(dir, "clipboard.txt"))
        assertTrue(blocker.mkdir())
        assertTrue(File(blocker, "occupied").createNewFile())
        assertTrue(record(store).isFailure)
        store.flushPendingWrites()
        assertEquals(listOf("text", image.key), store.history().map { it.key })
        assertTrue(File(blocker, "occupied").delete())
        assertTrue(blocker.delete())
        store.record("after")
        store.flushPendingWrites()
        assertEquals(listOf("after", "text", image.key), store(dir).history().map { it.key })
        assertArrayEquals(png, image.imageFile()!!.readBytes())
    }
}
