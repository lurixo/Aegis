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

class InputAssociationsWarmupTest {

    @Test fun the_warmup_lookup_builds_a_queryable_table() {
        InputAssociations.lookup("nihao")
        assertTrue("haode → 👌 resolves after warm-up", "👌" in InputAssociations.lookup("haode"))
        assertTrue("jia → + resolves", "+" in InputAssociations.lookup("jia"))
        assertEquals("an unknown key stays empty", emptyList<String>(), InputAssociations.lookup("zzzzq"))
        assertEquals("an empty query is a no-op", emptyList<String>(), InputAssociations.lookup(""))
    }
}
