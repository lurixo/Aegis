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

package com.aegis.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomRaisePersistsTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun a_rebuilt_input_view_keeps_the_bottom_raise_without_an_insets_dispatch() {
        InputView(ctx).simulateNavInsetForTest(120)
        val rebuilt = InputView(ctx)
        assertTrue(
            "bottom raise restored from the cached navbar inset (>= navbar bottom)",
            rebuilt.bodyBottomPaddingPx() >= 120,
        )
    }

    @Test fun the_cached_navbar_inset_drives_the_rebuilt_raise_and_zero_is_harmless() {
        InputView(ctx).simulateNavInsetForTest(0)
        val zero = InputView(ctx).bodyBottomPaddingPx()
        InputView(ctx).simulateNavInsetForTest(120)
        val seeded = InputView(ctx).bodyBottomPaddingPx()

        assertEquals("raise grows by exactly the cached navbar bottom", 120, seeded - zero)
        assertTrue("a rebuilt view always carries the constant raise baseline (never bare 0)", zero > 0)
    }
}
