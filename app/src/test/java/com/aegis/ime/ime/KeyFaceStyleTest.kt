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

import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyFaceStyleTest {

    @Test fun no_key_carrying_a_sub_label_sits_on_a_function_face() {
        for (id in LayoutId.entries) {
            val layout = Layouts.forId(id, Lang.CN, composing = true)
            val keys = layout.rows.flatMap { it.keys } +
                layout.cells.orEmpty().map { it.key } +
                layout.scrollColumn?.items.orEmpty()
            for (key in keys.filter { it.sub != null }) {
                assertFalse(
                    "$id: '${key.sub}' would draw on a function face the sub ink is not held to",
                    key.rail,
                )
            }
        }
    }

}
