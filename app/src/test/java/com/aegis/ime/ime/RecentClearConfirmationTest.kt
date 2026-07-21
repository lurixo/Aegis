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

import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.layout.SymbolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentClearConfirmationTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun confirmation_backdrop_is_clickable_only_while_visible() {
        val overlay = PanelConfirmationOverlay(ctx)
        var confirmations = 0

        assertFalse(overlay.hasOnClickListeners())
        assertFalse(overlay.isClickable)

        overlay.show("Clear recent items?", "Clear", "Cancel", ImePalette.STATIC_LIGHT) { confirmations++ }
        assertTrue(overlay.hasOnClickListeners())
        assertTrue(overlay.isClickable)
        assertTrue(overlay.performClick())

        assertFalse(overlay.hasOnClickListeners())
        assertFalse(overlay.isClickable)
        assertEquals(0, confirmations)
    }

    @Test fun symbol_recents_clear_only_after_confirmation() {
        val recents = mutableListOf("★", "→")
        var clears = 0
        val view = SymbolsView(ctx).apply {
            recentProvider = { recents.toList() }
            onClearRecents = {
                clears++
                recents.clear()
            }
            applyPalette(ImePalette.STATIC_LIGHT)
            resetToDefault()
        }

        assertEquals(listOf("★", "→"), view.gridCellTextsForTest())
        assertTrue(view.clearBtnForTest().performClick())
        assertTrue(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("★", "→"), recents)

        assertTrue(view.cancelClearForTest())
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("★", "→"), recents)

        assertTrue(view.clearBtnForTest().performClick())
        assertTrue(view.dismissClearForTest())
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("★", "→"), recents)

        assertTrue(view.clearBtnForTest().performClick())
        view.resetToDefault()
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("★", "→"), recents)

        assertTrue(view.clearBtnForTest().performClick())
        assertTrue(view.confirmClearForTest())
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(1, clears)
        assertTrue(recents.isEmpty())
        assertTrue(view.gridCellTextsForTest().isEmpty())

        view.openCategoryForTest(1)
        assertEquals(SymbolCatalog.categories.first().symbols, view.gridCellTextsForTest())
    }

    @Test fun emoji_recents_clear_only_after_confirmation() {
        val recents = mutableListOf("👋", "😀")
        var clears = 0
        val view = EmojiView(ctx).apply {
            recentProvider = { recents.toList() }
            onClearRecents = {
                clears++
                recents.clear()
            }
            applyPalette(ImePalette.STATIC_LIGHT)
            resetToDefault()
        }

        assertEquals(listOf("👋", "😀"), view.gridCellTextsForTest())
        assertTrue(view.clearBtnForTest().performClick())
        assertTrue(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("👋", "😀"), recents)

        assertTrue(view.cancelClearForTest())
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("👋", "😀"), recents)

        assertTrue(view.clearBtnForTest().performClick())
        assertTrue(view.dismissClearForTest())
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("👋", "😀"), recents)

        assertTrue(view.clearBtnForTest().performClick())
        view.resetToDefault()
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("👋", "😀"), recents)

        assertTrue(view.clearBtnForTest().performClick())
        assertTrue(view.confirmClearForTest())
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(1, clears)
        assertTrue(recents.isEmpty())
        assertEquals(listOf(ctx.getString(R.string.emoji_empty_hint)), view.gridCellTextsForTest())

        view.openCategoryForTest(1)
        assertEquals(EmojiCatalog.categories.first().emoji, view.gridCellTextsForTest())
    }
}
