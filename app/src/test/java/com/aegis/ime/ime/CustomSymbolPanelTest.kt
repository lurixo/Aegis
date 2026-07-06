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

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomSymbolPanelTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun textViews(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { textViews(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun click(root: View, text: String): Boolean =
        textViews(root).firstOrNull { it.text == text }?.also { it.performClick() } != null

    @Test fun tapping_palette_adds_then_tapping_added_removes() {
        val backing = mutableListOf<String>()
        val p = CustomSymbolPanel(ctx).apply {
            current = { backing.toList() }
            onAdd = { if (it !in backing) backing.add(it) }
            onRemove = { backing.remove(it) }
        }
        p.refresh()
        assertTrue("palette mark 、 is present and clickable", click(p, "、"))
        assertEquals(listOf("、"), backing)
        p.refresh()
        assertTrue("the added mark shows ✕ and removes on tap", click(p, "、 ✕"))
        assertTrue("removed", backing.isEmpty())
    }

    @Test fun back_button_fires_on_back() {
        var back = false
        val p = CustomSymbolPanel(ctx).apply { onBack = { back = true } }
        p.refresh()
        assertTrue(click(p, ctx.getString(com.aegis.ime.R.string.csp_back_title)))
        assertTrue(back)
    }
}
