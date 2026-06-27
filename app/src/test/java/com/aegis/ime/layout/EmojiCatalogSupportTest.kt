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

package com.aegis.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojiCatalogSupportTest {

    @Test fun the_supported_catalog_only_ever_removes_and_keeps_order() {
        val full = EmojiCatalog.categories
        val supported = EmojiCatalog.supported
        assertEquals("every category survives", full.map { it.id }, supported.map { it.id })
        for ((c, s) in full.zip(supported)) {
            val kept = s.emoji.toSet()
            assertTrue("nothing is invented in " + c.id, c.emoji.containsAll(s.emoji))
            assertEquals("order is preserved in " + c.id, s.emoji, c.emoji.filter { it in kept })
        }
    }
}
