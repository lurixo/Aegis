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

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SWITCH-FLICKER GUARD. The user reported a severe white/black flash on every surface switch — clipboard↔常用语,
 * emoji/symbol category, keyboard↔panel and keyboard-layout changes. The root cause was that a switch dropped the
 * outgoing content to alpha 0 over a background-less slot, so the transparent IME window (and the host app behind
 * it) showed through for a frame; content swaps also faded the WHOLE surface to a blank trough. These pin the fix:
 *  (1) the panel slot [InputView.panelContainer] and every panel root carry an OPAQUE keyboard-colour floor, so no
 *      transition can ever expose a transparent frame — the deterministic view-tree state that flashes on a device;
 *  (2) an emoji/symbol category switch swaps the recycled cells IN PLACE (content changes, no whole-grid fade).
 * (Verified on-device by holding each transition's trough: before = blank floor, after = content present.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwitchFlickerTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT

    private fun floorColor(bg: android.graphics.drawable.Drawable?): Int? = (bg as? ColorDrawable)?.color

    @Test fun panel_slot_carries_an_opaque_keyboard_floor() {
        val iv = InputView(ctx)
        iv.applyPalette(light)
        val floor = iv.panelFloorColorForTest()
        assertEquals("the panel slot must be painted the keyboard-floor colour", light.keyboardBg, floor)
        assertEquals("…and it must be fully opaque so an alpha-0 panel never reveals the window", 0xFF, Color.alpha(floor!!))
    }

    @Test fun every_panel_root_carries_an_opaque_floor() {
        // A panel's own content can fade during a reveal/swap; its root must be an opaque keyboard-colour floor so
        // the fade reveals the panel colour, never the transparent IME window.
        for ((name, bg) in listOf(
            "emoji" to floorColor(EmojiView(ctx).apply { applyPalette(light) }.background),
            "symbols" to floorColor(SymbolsView(ctx).apply { applyPalette(light) }.background),
        )) {
            assertEquals("$name panel floor colour", light.keyboardBg, bg)
            assertEquals("$name panel floor must be opaque", 0xFF, Color.alpha(bg!!))
        }
    }

    @Test fun emoji_category_switch_swaps_content_in_place() {
        val v = EmojiView(ctx).apply { applyPalette(light) }
        v.openCategoryForTest(1) // a populated category (0 = 最近/recent, empty on a fresh view)
        val first = v.gridCellTextsForTest()
        assertTrue("the opened category grid is populated (never left blank)", first.isNotEmpty())
        v.openCategoryForTest(2)
        assertEquals("the selected category updates", 2, v.selectedCategoryForTest())
        assertNotEquals("switching categories actually changes the grid content in place", first, v.gridCellTextsForTest())
        assertTrue("the switched-to grid is populated in place (never blank)", v.gridCellTextsForTest().isNotEmpty())
    }

    @Test fun symbol_category_switch_swaps_content_in_place() {
        val v = SymbolsView(ctx).apply { applyPalette(light) }
        v.openCategoryForTest(1) // a populated category (0 = 常用/recent, empty on a fresh view)
        val first = v.gridCellTextsForTest()
        assertTrue("the opened category grid is populated (never left blank)", first.isNotEmpty())
        v.openCategoryForTest(2)
        assertEquals("switching categories updates the selection", 2, v.selectedCategoryForTest())
        assertNotEquals("switching categories actually changes the tile content in place", first, v.gridCellTextsForTest())
        assertTrue("the switched-to grid is populated in place (never blank)", v.gridCellTextsForTest().isNotEmpty())
    }
}
