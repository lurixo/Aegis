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

package com.aegis.ime.dict

import org.junit.Assert.assertTrue
import org.junit.Test

class DictBuilderCompletenessTest {

    @Test fun source_backed_rare_readings_are_canonical() {
        assertTrue(listOf("cei", "fiao", "tei").all { it in com.aegis.tools.Pinyin.canonicalSyllables })
    }
}
