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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClearedTextStoreTest {

    @get:Rule val temporary = TemporaryFolder()

    @Test fun a_new_store_has_no_content() {
        val store = ClearedTextStore(temporary.root)
        assertFalse(store.hasContent())
        assertNull(store.held())
    }

    @Test fun cleared_text_survives_reopening_without_losing_unicode_or_whitespace() {
        val text = " 你好\t𠮷\nsecond line\r\n "
        ClearedTextStore(temporary.root).keep(text)
        val reopened = ClearedTextStore(temporary.root)
        assertTrue(reopened.hasContent())
        assertEquals(text, reopened.held())
    }

    @Test fun the_live_store_keeps_the_original_character_sequence() {
        val text = StringBuilder("kept text")
        val store = ClearedTextStore(temporary.root)
        store.keep(text)
        assertSame(text, store.held())
    }

    @Test fun an_empty_keep_preserves_the_last_nonempty_text() {
        val store = ClearedTextStore(temporary.root)
        store.keep("first")
        store.keep("")
        assertEquals("first", store.held())
        assertEquals("first", ClearedTextStore(temporary.root).held())
        store.keep("second")
        assertEquals("second", ClearedTextStore(temporary.root).held())
    }

    @Test fun forgetting_removes_both_the_live_and_persisted_text() {
        val store = ClearedTextStore(temporary.root)
        store.keep("old text")
        store.forget()
        assertFalse(store.hasContent())
        assertNull(store.held())
        assertFalse(ClearedTextStore(temporary.root).hasContent())
        assertNull(ClearedTextStore(temporary.root).held())
    }

    @Test fun a_failed_disk_write_still_keeps_text_in_the_live_store() {
        val parent = File(temporary.root, "not-a-directory").apply { writeText("occupied") }
        val store = ClearedTextStore(parent)
        store.keep("recoverable")
        assertTrue(store.hasContent())
        assertEquals("recoverable", store.held())
    }

    @Test fun an_empty_persisted_file_is_not_a_restore_candidate() {
        File(temporary.root, "cleared_text.txt").writeText("")
        val store = ClearedTextStore(temporary.root)
        assertFalse(store.hasContent())
        assertNull(store.held())
    }
}
