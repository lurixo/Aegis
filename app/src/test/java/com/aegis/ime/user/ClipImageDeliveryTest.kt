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

import androidx.core.content.FileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipImageDeliveryTest {

    private val ctx = RuntimeEnvironment.getApplication()


    @Test fun image_accepted_matches_concrete_and_wildcards() {
        assertFalse("no advertised types → not supported", ClipImageStore.imageAccepted(emptyArray(), "image/png"))
        assertTrue("concrete match", ClipImageStore.imageAccepted(arrayOf("image/png"), "image/png"))
        assertTrue("image/* wildcard", ClipImageStore.imageAccepted(arrayOf("image/*"), "image/png"))
        assertTrue("*/* wildcard", ClipImageStore.imageAccepted(arrayOf("*/*"), "image/png"))
        assertTrue("match among several", ClipImageStore.imageAccepted(arrayOf("text/plain", "image/jpeg"), "image/jpeg"))
        assertFalse("type the field does not accept", ClipImageStore.imageAccepted(arrayOf("image/gif"), "image/png"))
    }


    @Test fun subcase_A_unsupported_field_falls_back_without_running_commit() {
        var commitRan = false
        val committed = ClipImageStore.deliverImage(emptyArray(), "image/png") { commitRan = true; true }
        assertFalse("a field that doesn't advertise images must NOT commit", committed)
        assertFalse("commit is never attempted when unsupported (→ fallback)", commitRan)
    }

    @Test fun subcase_B_commit_returning_false_falls_back() {
        val committed = ClipImageStore.deliverImage(arrayOf("image/*"), "image/png") { false }
        assertFalse("a failed commit must route to the fallback", committed)
    }

    @Test fun supported_and_successful_commit_does_not_fall_back() {
        var commitRan = false
        val committed = ClipImageStore.deliverImage(arrayOf("image/*"), "image/png") { commitRan = true; true }
        assertTrue("a supported field with a successful commit inserts directly", committed)
        assertTrue("commit was attempted", commitRan)
    }


    @Test fun fallback_clip_is_a_content_uri_from_our_grantable_fileprovider() {
        val dir = File(ctx.filesDir, "clipboard_images").apply { mkdirs() }
        val img = File(dir, "fallback.png").apply { writeBytes(ByteArray(16)) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", img)

        val clip = ClipImageStore.imageClip(ctx.contentResolver, uri)
        assertEquals("one item", 1, clip.itemCount)
        assertEquals("system-clipboard image is a content:// URI (not file:///path)", "content", clip.getItemAt(0).uri?.scheme)
        assertTrue("URI is served by our FileProvider", clip.getItemAt(0).uri?.authority?.endsWith(".fileprovider") == true)
        val bytes = ctx.contentResolver.openInputStream(clip.getItemAt(0).uri!!)!!.use { it.readBytes() }
        assertEquals("the content URI serves the saved image bytes", 16, bytes.size)
    }


    @Test fun is_self_write_only_matches_our_own_fallback_uri() {
        val a1 = android.net.Uri.parse("content://com.aegis.ime.fileprovider/clipboard_images/a.png")
        val a2 = android.net.Uri.parse("content://com.aegis.ime.fileprovider/clipboard_images/a.png")
        val b = android.net.Uri.parse("content://com.aegis.ime.fileprovider/clipboard_images/b.png")
        assertTrue("our own write is recognised by value", ClipImageStore.isSelfWrite(a1, a2))
        assertFalse("a different clip is not suppressed", ClipImageStore.isSelfWrite(b, a1))
        assertFalse("null incoming clip → not self", ClipImageStore.isSelfWrite(null, a1))
        assertFalse("nothing marked → not self", ClipImageStore.isSelfWrite(a1, null))
    }
}
