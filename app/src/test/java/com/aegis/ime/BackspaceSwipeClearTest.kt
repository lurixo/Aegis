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

package com.aegis.ime

import com.aegis.ime.ime.ClearedTextRestore
import com.aegis.ime.ime.EditorSweep
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackspaceSwipeClearTest {

    @Test fun a_restore_reaches_as_far_as_a_clear_can_capture() {
        assertEquals(
            "a clear that keeps more than a restore can put back would lose the difference",
            EditorSweep.MAX_CHARS,
            ClearedTextRestore.MAX_CHARS,
        )
    }

    @Test fun an_unmeasurable_target_falls_back_to_writing_the_newlines_verbatim() {
        val put = StringBuilder()

        ClearedTextRestore.restore(
            "AAA\n\nBBB",
            measure = { 0 },
            commit = { part, then -> put.append(part); then() },
            done = {},
        )

        assertEquals("with no signal to go on, nothing may be dropped", "AAA\n\nBBB", put.toString())
    }

    @Test fun a_run_of_newlines_never_comes_back_as_none() {
        val put = StringBuilder()
        var reads = 0

        ClearedTextRestore.restore(
            "AAA\n\nBBB",
            measure = { if (reads++ == 0) 0 else 9000 },
            commit = { part, then -> put.append(part); then() },
            done = {},
        )

        assertEquals("an absurd measurement must still leave the lines apart", "AAA\nBBB", put.toString())
    }
}
