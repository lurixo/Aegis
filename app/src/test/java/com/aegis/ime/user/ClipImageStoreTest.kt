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
import java.nio.file.Files

class ClipImageStoreTest {

    @Test fun mime_inferred_from_extension() {
        assertEquals("image/jpeg", ClipImageStore.mimeOf("/x/1.jpg"))
        assertEquals("image/jpeg", ClipImageStore.mimeOf("/x/1.JPEG"))
        assertEquals("image/gif", ClipImageStore.mimeOf("/x/a.gif"))
        assertEquals("image/webp", ClipImageStore.mimeOf("/x/a.webp"))
        assertEquals("image/png", ClipImageStore.mimeOf("/x/a.png"))
        assertEquals("default png", "image/png", ClipImageStore.mimeOf("/x/noext"))
    }

    @Test fun within_cap_guards_single_image_size() {
        val s = ClipImageStore(Files.createTempDirectory("img").toFile())
        assertTrue("unknown size allowed (save() guards while copying)", s.withinCap(-1))
        assertTrue(s.withinCap(1024))
        assertTrue(s.withinCap(ClipImageStore.MAX_BYTES))
        assertFalse(s.withinCap(ClipImageStore.MAX_BYTES + 1))
    }
}
