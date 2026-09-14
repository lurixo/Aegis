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
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
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

    private fun loadForInput(store: ClipboardStore, uri: Uri = source()): Result<ClipboardStore.InputImageLease> {
        val done = CountDownLatch(1)
        var result: Result<ClipboardStore.InputImageLease>? = null
        store.loadImageForInput(context.contentResolver, uri, "image/png") { result = it; done.countDown() }
        assertTrue("input image loading finished", done.await(10, TimeUnit.SECONDS))
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

    @Test fun image_clipdata_uses_readable_content_uri_and_original_mime_without_text() {
        val store = store(context.filesDir)
        val entry = record(store).getOrThrow()
        val clip = ClipboardImages.clipData(context, entry)!!
        assertEquals("image/png", clip.description.getMimeType(0))
        assertNull(clip.getItemAt(0).text)
        val uri = clip.getItemAt(0).uri!!
        assertEquals("content", uri.scheme)
        assertEquals(context.packageName + ".clipboard.images", uri.authority)
        context.contentResolver.openInputStream(uri).use { assertArrayEquals(png, it!!.readBytes()) }
        assertTrue(runCatching { context.contentResolver.openOutputStream(uri, "w") }.isFailure)
        assertEquals(0, context.contentResolver.delete(uri, null, null))
        assertTrue(entry.imageFile()!!.isFile)
        assertNull(ClipboardImages.uri(context, ClipEntry.of("text")))
        assertTrue(runCatching {
            FileProvider.getUriForFile(context, uri.authority!!, File(context.filesDir, "userdb.txt"))
        }.isFailure)
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

    @Test fun published_image_survives_history_clear_and_store_recreation() {
        val store = store(context.filesDir)
        val entry = record(store).getOrThrow()
        val clip = ClipboardImages.clipData(context, entry)!!
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertTrue(store.retainPublishedImage(entry) { clipboard.setPrimaryClip(clip); true })
        assertTrue(store.clearHistory())
        store.flushPendingWrites()
        assertTrue(store.history().isEmpty())
        context.contentResolver.openInputStream(clipboard.primaryClip!!.getItemAt(0).uri).use {
            assertArrayEquals(png, it!!.readBytes())
        }
        val reloaded = store(context.filesDir)
        reloaded.record("new text")
        reloaded.flushPendingWrites()
        assertEquals(listOf("new text"), reloaded.history().mapNotNull { it.body() })
        assertArrayEquals(png, entry.imageFile()!!.readBytes())
    }

    @Test fun successful_image_publication_replaces_the_single_retained_image() {
        val dir = temp.newFolder()
        val store = store(dir)
        val first = record(store).getOrThrow()
        val firstFile = first.imageFile()!!
        assertTrue(store.retainPublishedImage(first) { true })
        assertTrue(store.clearHistory())
        store.flushPendingWrites()
        val second = record(store, source(png + byteArrayOf(0))).getOrThrow()
        val secondFile = second.imageFile()!!
        assertNotEquals(firstFile.name, secondFile.name)
        assertTrue(store.retainPublishedImage(second) { assertTrue(firstFile.isFile); true })
        assertTrue(store.clearHistory())
        store.flushPendingWrites()
        assertFalse(firstFile.exists())
        assertEquals(listOf(secondFile.name), File(dir, "clips/images").listFiles()!!.map { it.name })
    }

    @Test fun rejected_and_throwing_publications_keep_the_previous_retained_image() {
        val dir = temp.newFolder()
        val store = store(dir)
        val first = record(store).getOrThrow()
        val firstFile = first.imageFile()!!
        assertTrue(store.retainPublishedImage(first) { true })
        assertTrue(store.clearHistory())
        store.flushPendingWrites()
        val second = record(store, source(png + byteArrayOf(0))).getOrThrow()
        val secondFile = second.imageFile()!!
        assertFalse(store.retainPublishedImage(second) { false })
        assertFalse(store.retainPublishedImage(second) { throw IllegalStateException("publication failed") })
        assertTrue(store.clearHistory())
        store.flushPendingWrites()
        val reloaded = store(dir)
        reloaded.record("after restart")
        reloaded.flushPendingWrites()
        assertArrayEquals(png, firstFile.readBytes())
        assertFalse(secondFile.exists())
    }

    @Test fun failed_reference_write_does_not_publish_or_replace_the_previous_pin() {
        val dir = temp.newFolder()
        val store = store(dir)
        val first = record(store).getOrThrow()
        val firstFile = first.imageFile()!!
        assertTrue(store.retainPublishedImage(first) { true })
        store.flushPendingWrites()
        val second = record(store, source(png + byteArrayOf(0))).getOrThrow()
        val blocker = store.tempFileFor(File(dir, "clips/published-image.ref"))
        assertTrue(blocker.mkdir())
        var called = false
        assertFalse(store.retainPublishedImage(second) { called = true; true })
        assertFalse(called)
        blocker.delete()
        assertTrue(store.clearHistory())
        store.flushPendingWrites()
        assertArrayEquals(png, firstFile.readBytes())
        assertNull(second.imageFile())
    }

    @Test fun publication_supports_reentrant_history_updates_without_nested_publication() {
        val store = store()
        val entry = record(store).getOrThrow()
        assertTrue(store.retainPublishedImage(entry) {
            assertFalse(store.retainPublishedImage(entry) { fail("nested publication"); true })
            store.record("listener update")
            store.clearHistory()
            true
        })
        store.flushPendingWrites()
        assertTrue(store.history().isEmpty())
        assertArrayEquals(png, entry.imageFile()!!.readBytes())
    }

    @Test fun history_restore_preserves_published_image_without_readding_it_to_history() {
        val dir = temp.newFolder()
        val store = store(dir)
        val entry = record(store).getOrThrow()
        assertTrue(store.retainPublishedImage(entry) { true })
        store.flushPendingWrites()
        store.stopSaving()
        val restoring = store(dir)
        LiveUserData.restoreInProgress = true
        try {
            restoring.importHistory(listOf(ClipEntry.of("restored")), merge = false)
            assertFalse(restoring.retainPublishedImage(entry) { fail("published during restore"); true })
        } finally {
            LiveUserData.restoreInProgress = false
        }
        assertEquals(listOf("restored"), restoring.history().mapNotNull { it.body() })
        assertArrayEquals(png, entry.imageFile()!!.readBytes())
    }

    @Test fun image_publication_rejects_missing_or_foreign_files_before_callback() {
        val store = store()
        val foreign = record(store()).getOrThrow()
        assertFalse(store.retainPublishedImage(foreign) { fail("foreign publication"); true })
        val missing = record(store).getOrThrow()
        missing.imageFile()!!.delete()
        assertFalse(store.retainPublishedImage(missing) { fail("missing publication"); true })
        assertFalse(store.retainPublishedImage(ClipEntry.of("text")) { fail("text publication"); true })
    }

    @Test fun rejected_first_publication_does_not_retain_an_image() {
        val dir = temp.newFolder()
        val store = store(dir)
        val entry = record(store).getOrThrow()
        val file = entry.imageFile()!!
        assertFalse(store.retainPublishedImage(entry) { false })
        assertTrue(store.clearHistory())
        store.flushPendingWrites()
        assertFalse(file.exists())
        assertFalse(File(dir, "clips/published-image.ref").exists())
    }

    @Test fun failed_publication_reference_rollback_defers_cleanup_until_recovered() {
        val dir = temp.newFolder()
        val store = store(dir)
        val first = record(store).getOrThrow()
        val firstFile = first.imageFile()!!
        assertTrue(store.retainPublishedImage(first) { true })
        store.flushPendingWrites()
        val second = record(store, source(png + byteArrayOf(0))).getOrThrow()
        val secondFile = second.imageFile()!!
        val blocker = store.tempFileFor(File(dir, "clips/published-image.ref"))
        assertFalse(store.retainPublishedImage(second) {
            assertTrue(blocker.mkdir())
            File(blocker, "keep").writeText("keep the staging path blocked")
            false
        })
        assertTrue(store.clearHistory())
        store.flushPendingWrites()
        assertTrue(firstFile.isFile)
        assertTrue(File(blocker, "keep").delete())
        assertTrue(blocker.delete())
        store.record("retry after storage recovery")
        store.flushPendingWrites()
        assertTrue(firstFile.isFile)
        assertFalse(secondFile.exists())
        val reloaded = store(dir)
        reloaded.clearHistory()
        reloaded.flushPendingWrites()
        assertArrayEquals(png, firstFile.readBytes())
    }

    @Test fun paused_history_input_image_load_does_not_modify_history_or_system_clipboard() {
        val dir = temp.newFolder()
        val store = store(dir)
        val prefs = context.getSharedPreferences("aegis", Context.MODE_PRIVATE)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("keep", "unchanged"))
        prefs.edit().putBoolean("clip_history", false).commit()
        val lease = loadForInput(store).getOrThrow()
        val file = lease.entry.imageFile()!!
        try {
            assertArrayEquals(png, file.readBytes())
            assertTrue(store.history().isEmpty())
            assertFalse(File(dir, "clipboard.txt").exists())
            assertEquals("unchanged", clipboard.primaryClip!!.getItemAt(0).text.toString())
        } finally {
            lease.close()
            prefs.edit().putBoolean("clip_history", true).commit()
        }
        store.flushPendingWrites()
        assertFalse(file.exists())
    }

    @Test fun two_input_image_leases_survive_clear_until_the_last_idempotent_close() {
        val store = store()
        val entry = record(store).getOrThrow()
        val file = entry.imageFile()!!
        val first = store.retainImageForInput(entry)!!
        val second = store.retainImageForInput(entry)!!
        store.clearHistory()
        store.flushPendingWrites()
        assertArrayEquals(png, file.readBytes())
        first.close()
        first.close()
        store.flushPendingWrites()
        assertArrayEquals(png, file.readBytes())
        second.close()
        store.flushPendingWrites()
        assertFalse(file.exists())
    }

    @Test fun input_image_lease_survives_history_delete_and_restore_replacement() {
        val store = store()
        val entry = record(store).getOrThrow()
        val file = entry.imageFile()!!
        val lease = store.retainImageForInput(entry)!!
        assertTrue(store.delete(entry.key))
        store.flushPendingWrites()
        assertArrayEquals(png, file.readBytes())
        LiveUserData.restoreInProgress = true
        try {
            store.importHistory(listOf(ClipEntry.of("restored")), merge = false)
            assertNull(store.retainImageForInput(entry))
            assertArrayEquals(png, file.readBytes())
        } finally {
            LiveUserData.restoreInProgress = false
        }
        lease.close()
        store.flushPendingWrites()
        assertEquals(listOf("restored"), store.history().mapNotNull { it.body() })
        assertFalse(file.exists())
    }

    @Test fun input_image_close_keeps_history_and_published_references() {
        val store = store()
        val entry = record(store).getOrThrow()
        val file = entry.imageFile()!!
        store.retainImageForInput(entry)!!.close()
        store.flushPendingWrites()
        assertArrayEquals(png, file.readBytes())
        val lease = store.retainImageForInput(entry)!!
        assertTrue(store.retainPublishedImage(entry) { true })
        store.clearHistory()
        store.flushPendingWrites()
        lease.close()
        store.flushPendingWrites()
        assertArrayEquals(png, file.readBytes())
    }

    @Test fun input_image_close_does_not_delete_a_reference_left_by_failed_history_write() {
        val dir = temp.newFolder()
        val store = store(dir)
        val entry = record(store).getOrThrow()
        val file = entry.imageFile()!!
        val lease = store.retainImageForInput(entry)!!
        assertTrue(store.tempFileFor(File(dir, "clipboard.txt")).mkdir())
        assertTrue(store.clearHistory())
        store.flushPendingWrites()
        lease.close()
        store.flushPendingWrites()
        assertArrayEquals(png, file.readBytes())
        val reloaded = store(dir)
        assertEquals(entry.key, reloaded.latestEntry()!!.key)
        reloaded.clearHistory()
        reloaded.flushPendingWrites()
        assertFalse(file.exists())
    }

    @Test fun clearing_history_does_not_cancel_an_input_image_load() {
        val store = store()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val done = CountDownLatch(1)
        val uri = Uri.parse("content://clipboard.test/input-clear")
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(png) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                assertTrue(proceed.await(10, TimeUnit.SECONDS))
                return super.read(bytes, offset, length)
            }
        })
        var result: Result<ClipboardStore.InputImageLease>? = null
        store.loadImageForInput(context.contentResolver, uri) { result = it; done.countDown() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        store.clearHistory()
        proceed.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        val lease = result!!.getOrThrow()
        val file = lease.entry.imageFile()!!
        assertTrue(store.history().isEmpty())
        assertArrayEquals(png, file.readBytes())
        lease.close()
        store.flushPendingWrites()
        assertFalse(file.exists())
    }

    @Test fun restore_cancels_an_inflight_input_image_load_and_defers_cleanup() {
        val dir = temp.newFolder()
        val store = store(dir)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val done = CountDownLatch(1)
        val uri = Uri.parse("content://clipboard.test/input-restore")
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(png) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                assertTrue(proceed.await(10, TimeUnit.SECONDS))
                return super.read(bytes, offset, length)
            }
        })
        var result: Result<ClipboardStore.InputImageLease>? = null
        store.loadImageForInput(context.contentResolver, uri) { result = it; done.countDown() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        LiveUserData.restoreInProgress = true
        proceed.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(result!!.isFailure)
        assertTrue(store.history().isEmpty())
        assertEquals(1, File(dir, "clips/images").listFiles()!!.size)
        LiveUserData.restoreInProgress = false
        store.record("after restore")
        store.flushPendingWrites()
        assertTrue(File(dir, "clips/images").listFiles().orEmpty().isEmpty())
    }

    @Test fun closing_an_input_image_during_restore_defers_file_cleanup() {
        val store = store()
        val lease = loadForInput(store).getOrThrow()
        val file = lease.entry.imageFile()!!
        LiveUserData.restoreInProgress = true
        lease.close()
        store.flushPendingWrites()
        assertArrayEquals(png, file.readBytes())
        LiveUserData.restoreInProgress = false
        store.record("after restore")
        store.flushPendingWrites()
        assertFalse(file.exists())
    }

    @Test fun stopped_store_rejects_input_image_loads_but_releases_existing_leases() {
        val store = store()
        val lease = loadForInput(store).getOrThrow()
        val file = lease.entry.imageFile()!!
        store.stopSaving()
        assertTrue(loadForInput(store).isFailure)
        assertNull(store.retainImageForInput(lease.entry))
        lease.close()
        lease.close()
        assertFalse(file.exists())
    }

    @Test fun reload_before_callback_delivery_cancels_and_releases_the_input_image() {
        val dir = temp.newFolder()
        val store = store(dir)
        val callbacks = ArrayBlockingQueue<Runnable>(1)
        store.reportClipWritesTo(Executor { callbacks.add(it) }) { }
        var result: Result<ClipboardStore.InputImageLease>? = null
        store.loadImageForInput(context.contentResolver, source()) { result = it }
        val callback = callbacks.poll(10, TimeUnit.SECONDS)!!
        assertEquals(1, File(dir, "clips/images").listFiles()!!.size)
        store.load()
        callback.run()
        store.flushPendingWrites()
        assertTrue(result!!.isFailure)
        assertTrue(File(dir, "clips/images").listFiles().orEmpty().isEmpty())
    }

    @Test fun rejected_input_image_callback_releases_its_undelivered_lease() {
        val dir = temp.newFolder()
        val store = store(dir)
        val rejected = CountDownLatch(1)
        store.reportClipWritesTo(Executor {
            rejected.countDown()
            throw RejectedExecutionException("callback owner stopped")
        }) { }
        store.loadImageForInput(context.contentResolver, source()) { fail("rejected callback delivered") }
        assertTrue(rejected.await(10, TimeUnit.SECONDS))
        store.flushPendingWrites()
        store.flushPendingWrites()
        assertTrue(store.history().isEmpty())
        assertTrue(File(dir, "clips/images").listFiles().orEmpty().isEmpty())
    }

    @Test fun input_image_retention_rejects_foreign_missing_and_non_image_entries() {
        val store = store()
        assertNull(store.retainImageForInput(record(store()).getOrThrow()))
        assertNull(store.retainImageForInput(ClipEntry.of("text")))
        val entry = record(store).getOrThrow()
        entry.imageFile()!!.delete()
        assertNull(store.retainImageForInput(entry))
    }
}
