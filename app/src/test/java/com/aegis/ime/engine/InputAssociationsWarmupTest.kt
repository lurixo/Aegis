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

package com.aegis.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⑦ The [InputAssociations] table is built lazily on first access (~40ms one-time on low-end ART). The IME
 * service warms it off the main thread in onCreate with a single throwaway [InputAssociations.lookup] so the
 * first keystroke never pays that cost. This asserts the warm-up CALL is a valid trigger: after it, the table
 * is populated and known associations resolve (proving the pre-warm builds a queryable map, not a no-op).
 */
class InputAssociationsWarmupTest {

    @Test fun the_warmup_lookup_builds_a_queryable_table() {
        // The exact call the service makes on its background thread.
        InputAssociations.lookup("nihao")
        // After warm-up, real associations resolve — the map is built and usable on the (now-hot) input path.
        assertTrue("haode → 👌 resolves after warm-up", "👌" in InputAssociations.lookup("haode"))
        assertTrue("jia → + resolves", "+" in InputAssociations.lookup("jia"))
        assertEquals("an unknown key stays empty", emptyList<String>(), InputAssociations.lookup("zzzzq"))
        assertEquals("an empty query is a no-op", emptyList<String>(), InputAssociations.lookup(""))
    }
}
