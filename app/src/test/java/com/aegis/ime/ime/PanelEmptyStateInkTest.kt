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
import androidx.core.graphics.ColorUtils
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelEmptyStateInkTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun textViews(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun only(root: View, text: String): TextView =
        textViews(root).singleOrNull { it.text?.toString() == text }
            ?: throw AssertionError("expected exactly one view reading \"$text\"")

    private fun assertPanelBodyInk(view: View, text: String, p: ImePalette) {
        val tv = only(view, text)
        assertEquals("\"$text\" is the only line on an empty panel, so it reads as body", p.keyLabel, tv.currentTextColor)
        val contrast = ColorUtils.calculateContrast(tv.currentTextColor, p.panelBg)
        assertTrue("\"$text\" on the panel face: $contrast", contrast >= 4.5)
    }

    @Test fun the_emoji_panel_writes_its_empty_line_in_body_ink() {
        for (p in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val view = EmojiView(ctx).apply {
                recentProvider = { emptyList() }
                applyPalette(p)
                resetToDefault()
            }
            assertPanelBodyInk(view, ctx.getString(R.string.emoji_empty_hint), p)
        }
    }

    @Test fun the_symbol_panel_writes_its_empty_line_in_body_ink() {
        for (p in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val view = SymbolsView(ctx).apply {
                recentProvider = { emptyList() }
                applyPalette(p)
                resetToDefault()
            }
            assertPanelBodyInk(view, ctx.getString(R.string.symbols_empty_hint), p)
        }
    }

    @Test fun a_repainted_empty_panel_keeps_its_body_ink() {
        val view = EmojiView(ctx).apply {
            recentProvider = { emptyList() }
            applyPalette(ImePalette.STATIC_LIGHT)
            resetToDefault()
        }
        view.applyPalette(ImePalette.STATIC_DARK)
        assertPanelBodyInk(view, ctx.getString(R.string.emoji_empty_hint), ImePalette.STATIC_DARK)
    }
}
