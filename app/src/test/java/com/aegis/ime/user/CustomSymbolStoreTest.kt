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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomSymbolStoreTest {

    private var n = 0
    private fun freshStore() =
        CustomSymbolStore(RuntimeEnvironment.getApplication().getSharedPreferences("custom-${n++}", 0))

    @Test fun add_then_list_preserves_order() {
        val s = freshStore()
        assertTrue(s.add("、")); assertTrue(s.add("《")); assertTrue(s.add("%"))
        assertEquals(listOf("、", "《", "%"), s.list())
    }

    @Test fun duplicates_and_blanks_are_rejected() {
        val s = freshStore()
        assertTrue(s.add("、"))
        assertFalse("no duplicate", s.add("、"))
        assertFalse("no blank", s.add("   "))
        assertEquals(listOf("、"), s.list())
    }

    @Test fun remove_drops_only_that_symbol() {
        val s = freshStore()
        s.add("、"); s.add("《"); s.add("%")
        s.remove("《")
        assertEquals(listOf("、", "%"), s.list())
    }

    @Test fun pasted_internal_newline_does_not_split_into_multiple_marks() {
        val s = freshStore()
        assertTrue(s.add("→\n←"))
        assertEquals(listOf("→←"), s.list())
    }

    @Test fun no_capacity_limit_on_either_store_key() {
        for (key in listOf("custom_symbols", "custom_operators")) {
            val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("uncapped-$key", 0)
            val s = CustomSymbolStore(prefs, key)
            val wanted = (0 until 250).map { "x$it" }
            wanted.forEach { assertTrue("$key must accept $it", s.add(it)) }
            assertEquals(250, s.list().size)
            assertTrue("$key keeps adding past the old 200 cap", s.add("x250"))
            assertEquals(wanted + "x250", s.list())
            assertEquals("$key survives a persistence round trip", wanted + "x250", CustomSymbolStore(prefs, key).list())
        }
    }

    @Test fun persists_across_instances_on_the_same_prefs() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("custom-shared", 0)
        CustomSymbolStore(prefs).add("、")
        assertEquals(listOf("、"), CustomSymbolStore(prefs).list())
    }
}
