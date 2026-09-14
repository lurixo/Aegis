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
import java.util.Base64

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

    private fun source(bytes: ByteArray = png): Uri {
        val uri = Uri.parse("content://clipboard.test/${System.nanoTime()}")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    @Test fun image_mime_is_detected_without_coercing_uri_to_text() {
        val clip = ClipData("image", arrayOf("image/png"), ClipData.Item(source()))
        assertEquals("image/png", ClipboardImages.imageMimeType(context.contentResolver, clip, clip.getItemAt(0)))
        val file = ClipData("file", arrayOf("image/png"), ClipData.Item(Uri.parse("file:///private.png")))
        assertNull(ClipboardImages.imageMimeType(context.contentResolver, file, file.getItemAt(0)))
    }
}
