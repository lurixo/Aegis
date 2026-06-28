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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** U22: the pure parts of the image store — mime inference + the single-image size cap. */
class ClipImageStoreTest {

    @Test fun mime_inferred_from_extension() {
        assertEquals("image/jpeg", ClipImageStore.mimeOf("/x/1.jpg"))
        assertEquals("image/jpeg", ClipImageStore.mimeOf("/x/1.JPEG"))
        assertEquals("image/gif", ClipImageStore.mimeOf("/x/a.gif"))
        assertEquals("image/webp", ClipImageStore.mimeOf("/x/a.webp"))
        assertEquals("image/png", ClipImageStore.mimeOf("/x/a.png"))
        assertEquals("default png", "image/png", ClipImageStore.mimeOf("/x/noext"))
    }

    @Test fun is_stored_image_validates_a_real_file_under_clipboard_images() {
        // M-1: only a marker backed by a REAL file in clipboard_images counts as an image.
        val dir = Files.createTempDirectory("img").toFile()
        val s = ClipImageStore(dir)
        val imgDir = File(dir, "clipboard_images").apply { mkdirs() }
        val real = File(imgDir, "1.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(s.isStoredImage(real.absolutePath))
        assertFalse("missing file", s.isStoredImage(File(imgDir, "missing.png").absolutePath))
        assertFalse("not a file path at all", s.isStoredImage("https://x"))
        val outside = File(dir, "secret.txt").apply { writeText("x") }
        assertFalse("outside clipboard_images (traversal guard)", s.isStoredImage(outside.absolutePath))
    }

    @Test fun a_text_clip_starting_with_the_marker_is_treated_as_text_not_image() {
        // M-1: the audit case — "img:https://x" text must paste as TEXT, never as a (broken) image.
        val s = ClipImageStore(Files.createTempDirectory("img").toFile())
        val fakeMarker = ClipboardStore.IMG_PREFIX + "https://x" // has the marker but no real file
        assertTrue(ClipboardStore.isImageEntry(fakeMarker))
        assertFalse("validated check rejects it → handled as text", s.isStoredImage(ClipboardStore.imagePath(fakeMarker)))
        assertFalse("plain 'img:…' (no marker control prefix) isn't even a marker", ClipboardStore.isImageEntry("img:https://x"))
    }

    @Test fun within_cap_guards_single_image_size() {
        val s = ClipImageStore(Files.createTempDirectory("img").toFile())
        assertTrue("unknown size allowed (save() guards while copying)", s.withinCap(-1))
        assertTrue(s.withinCap(1024))
        assertTrue(s.withinCap(ClipImageStore.MAX_BYTES))
        assertFalse(s.withinCap(ClipImageStore.MAX_BYTES + 1))
    }
}
