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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipboardEditClipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newDir(): File = tmp.newFolder()

    private fun ClipboardStore.keys(): List<String> = history().map { it.key }

    private fun ClipboardStore.bodies(): List<String?> = history().map { it.body() }

    @Test fun an_edited_entry_keeps_its_place_in_the_history() {
        val s = ClipboardStore(newDir()).apply { load() }
        s.record("a"); s.record("b"); s.record("c")
        assertEquals(listOf("c", "b", "a"), s.keys())
        assertTrue(s.editClip("b", "B!"))
        assertEquals("the edited row does not jump to the top", listOf("c", "B!", "a"), s.keys())
    }

    @Test fun editing_to_an_existing_entry_folds_the_duplicate_away() {
        val s = ClipboardStore(newDir()).apply { load() }
        s.record("a"); s.record("b"); s.record("c")
        assertTrue(s.editClip("c", "a"))
        assertEquals("only one copy of the text survives", listOf("a", "b"), s.keys())
    }

    @Test fun folding_a_duplicate_keeps_the_newer_position() {
        val s = ClipboardStore(newDir()).apply { load() }
        s.record("a"); s.record("b"); s.record("c")
        assertEquals(listOf("c", "b", "a"), s.keys())
        assertTrue(s.editClip("a", "c"))
        assertEquals("the survivor sits where the newer copy was", listOf("c", "b"), s.keys())
    }

    @Test fun a_refused_edit_is_reported_so_the_user_is_not_left_guessing() {
        val refusals = ArrayList<Boolean>()
        val s = ClipboardStore(newDir()).apply {
            load()
            reportClipWritesTo({ it.run() }) { refusals.add(it) }
        }
        s.record("a")
        assertFalse(s.editClip("a", "   "))
        assertFalse(s.editClip("gone", "x"))
        assertEquals("both refusals reach the user", listOf(false, false), refusals)
    }

    @Test fun an_unchanged_edit_is_accepted_and_changes_nothing() {
        val s = ClipboardStore(newDir()).apply { load() }
        s.record("a"); s.record("b")
        assertTrue(s.editClip("b", "b"))
        assertEquals(listOf("b", "a"), s.keys())
    }

    @Test fun an_empty_edit_is_refused_and_leaves_the_entry_alone() {
        val s = ClipboardStore(newDir()).apply { load() }
        s.record("a")
        assertFalse(s.editClip("a", "   "))
        assertEquals(listOf("a"), s.keys())
    }

    @Test fun editing_a_missing_entry_is_refused() {
        val s = ClipboardStore(newDir()).apply { load() }
        s.record("a")
        assertFalse(s.editClip("nope", "x"))
        assertEquals(listOf("a"), s.keys())
    }

    @Test fun an_edit_survives_a_persist_roundtrip() {
        val dir = newDir()
        ClipboardStore(dir).apply {
            load(); record("a"); record("b")
            assertTrue(editClip("a", "line1\nline2"))
            flushPendingWrites()
        }
        assertEquals(listOf("b", "line1\nline2"), ClipboardStore(dir).apply { load() }.keys())
    }

    @Test fun a_small_entry_edited_past_the_threshold_moves_to_a_sidecar() {
        val dir = newDir()
        val big = "x".repeat(ClipboardStore.BIG_THRESHOLD + 10)
        val s = ClipboardStore(dir).apply { load(); record("small") }
        assertTrue(s.editClip("small", big))
        s.flushPendingWrites()
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals(listOf(big), reloaded.bodies())
        assertTrue("the body lives in a sidecar file", File(dir, "clips").listFiles().orEmpty().isNotEmpty())
    }

    @Test fun a_big_entry_edited_back_under_the_threshold_drops_its_sidecar() {
        val dir = newDir()
        val big = "y".repeat(ClipboardStore.BIG_THRESHOLD + 10)
        val s = ClipboardStore(dir).apply { load(); record(big) }
        s.flushPendingWrites()
        val key = s.keys().single()
        assertTrue(s.editClip(key, "small again"))
        s.flushPendingWrites()
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("small again"), reloaded.bodies())
        assertTrue(
            "the orphaned sidecar is reclaimed",
            File(dir, "clips").listFiles().orEmpty().isEmpty(),
        )
    }
}
