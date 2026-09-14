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

package com.aegis.ime.backup

import android.content.Context
import android.net.Uri
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.ClipEntry
import com.aegis.ime.user.LiveUserData
import com.aegis.ime.user.UserDictHot
import org.junit.After
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardImageBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val context: Context = RuntimeEnvironment.getApplication()
    private val stores = ArrayList<ClipboardStore>()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=")

    @After fun close() {
        LiveUserData.restoreInProgress = false
        stores.forEach { it.flushPendingWrites(); it.stopSaving() }
    }

    private fun store(dir: File): ClipboardStore = ClipboardStore(dir).apply { load(); stores.add(this) }

    private fun image(store: ClipboardStore): ClipEntry {
        val uri = Uri.parse("content://clipboard.test/backup")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(png))
        val done = CountDownLatch(1)
        var result: Result<ClipEntry>? = null
        store.recordImage(context.contentResolver, uri, "image/png") { result = it; done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result!!.getOrThrow()
    }

    @Test fun encrypted_backup_restores_image_bytes_and_mime() {
        UserDictHot.host = null
        LiveUserData.onBeforeExport = null
        LiveUserData.onBeforeRestore = null
        LiveUserData.onRestored = null
        val source = temp.newFolder()
        val original = image(store(source))
        val prefs = context.getSharedPreferences("image-backup", Context.MODE_PRIVATE)
        val archive = ByteArrayOutputStream()
        BackupManager.export(source, prefs, "password".toCharArray(), archive)
        val destination = temp.newFolder()
        BackupManager.restore(destination, prefs, "password".toCharArray(), ByteArrayInputStream(archive.toByteArray()), BackupManager.Mode.OVERWRITE)
        val restored = store(destination).latestEntry()!!
        assertTrue(restored.isImage)
        assertEquals(original.key, restored.key)
        assertEquals(original.mimeType, restored.mimeType)
        assertArrayEquals(png, restored.imageFile()!!.readBytes())
        assertNull(restored.body())
    }

    @Test fun restore_merge_preserves_images_and_overwrite_removes_unreferenced_images() {
        val source = temp.newFolder()
        val sourceStore = store(source)
        val original = image(sourceStore)
        val destination = temp.newFolder()
        val destStore = store(destination)
        destStore.record("keep")
        destStore.importHistory(sourceStore.history(), merge = true)
        val saved = destStore.history().first { it.isImage }.imageFile()!!
        assertArrayEquals(png, saved.readBytes())
        assertEquals(listOf("keep"), destStore.history().mapNotNull { it.body() })
        destStore.importHistory(listOf(ClipEntry.of("replacement")), merge = false)
        assertFalse(saved.exists())
        assertTrue(original.imageFile()!!.exists())
    }

    @Test fun archive_accepts_only_hashed_image_names_in_the_image_directory() {
        val hash = "a".repeat(64)
        val valid = "clips/images/$hash.png"
        assertEquals(valid, BackupArchive.sanitizedRelativePath(valid))
        for (path in listOf("clips/images/x.png", "clips/images/$hash.exe", "clips/images/../$hash.png", "clips/images/sub/$hash.png")) {
            assertNull(path, BackupArchive.sanitizedRelativePath(path))
        }
    }
}
