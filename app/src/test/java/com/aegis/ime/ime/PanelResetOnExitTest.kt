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

import android.content.Context
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P7 (#19): a panel returns to its default state when dismissed, so reopening always starts fresh (default
 * tab/category/scroll, no lock). Two layers are covered: (1) the [InputView.showPanel] chokepoint calls
  * Chinese IME behavior note.
 * onStartInputView's showPanel(null)) funnels through it; (2) each real panel's resetToDefault restores its
 * own defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelResetOnExitTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT

    private class SpyPanel(ctx: Context) : View(ctx), ResettablePanel {
        var resets = 0
        override fun resetToDefault() { resets++ }
    }

    // ---------- chokepoint wiring ----------

    @Test fun dismissing_a_panel_resets_it() {
        val iv = InputView(ctx)
        val spy = SpyPanel(ctx)
        iv.showPanel(spy)
        assertEquals("opening must not reset", 0, spy.resets)
        iv.showPanel(null) // Chinese IME behavior note.
        assertEquals(1, spy.resets)
    }

    @Test fun switching_directly_to_another_panel_resets_the_outgoing_one() {
        val iv = InputView(ctx)
        val a = SpyPanel(ctx)
        val b = SpyPanel(ctx)
        iv.showPanel(a)
        iv.showPanel(b) // leaving a for b
        assertEquals("outgoing panel reset", 1, a.resets)
        assertEquals("incoming panel untouched", 0, b.resets)
    }

    @Test fun re_showing_the_same_panel_does_not_reset_it() {
        val iv = InputView(ctx)
        val spy = SpyPanel(ctx)
        iv.showPanel(spy)
        iv.showPanel(spy) // a trailing render re-showing the SAME panel must not wipe its state
        assertEquals(0, spy.resets)
    }

    // ---------- per-panel defaults ----------

    @Test fun symbols_panel_resets_to_the_common_tab_unlocked_and_scrolled_up() {
        val sv = SymbolsView(ctx)
        sv.applyPalette(light)
        sv.openCategoryForTest(4) // Chinese IME behavior note.
        sv.toggleLockForTest()    // lock it (P3)
        assertEquals(4, sv.selectedCategoryForTest())
        assertTrue(sv.lockedForTest())

        sv.resetToDefault()

        assertEquals("back to 常用 (index 0)", 0, sv.selectedCategoryForTest())
        assertFalse("lock cleared (P3 spirit)", sv.lockedForTest())
        assertEquals("grid scrolled to top", 0, sv.gridScrollYForTest())
    }

    @Test fun emoji_panel_resets_to_the_first_category() {
        val ev = EmojiView(ctx)
        ev.applyPalette(light)
        ev.openCategoryForTest(2)
        assertEquals(2, ev.selectedCategoryForTest())

        ev.resetToDefault()

        assertEquals(0, ev.selectedCategoryForTest())
    }

    @Test fun clipboard_panel_resets_to_the_clipboard_tab_and_clears_the_category_picker() {
        val cv = ClipboardView(ctx)
        cv.applyPalette(light)
        cv.forcePhrasesStateForTest("我的分类")
        assertFalse("on the 常用语 tab", cv.isClipboardTabForTest())
        assertEquals("我的分类", cv.phraseCatForTest())

        cv.resetToDefault()

        assertTrue("back to the 剪贴板 tab", cv.isClipboardTabForTest())
        assertEquals("category picker cleared", "", cv.phraseCatForTest())
    }

    @Test fun reopening_after_an_input_view_recreate_still_starts_default() {
        // H1 guard: panels are service-scoped singletons, so an input-view recreate (config/theme change)
        // discards the old InputView WITHOUT showPanel(null) — onStartInputView's showPanel(null) then has no
        // outgoing panel to reset. The service's on-open resetToDefault() (mirrored here) covers that gap.
        val stale = SymbolsView(ctx).apply { applyPalette(light); openCategoryForTest(3); toggleLockForTest() }
        assertTrue("precondition: stale lock", stale.lockedForTest())
        assertEquals("precondition: stale category", 3, stale.selectedCategoryForTest())

        val freshIv = InputView(ctx)
        freshIv.showPanel(null)   // onStartInputView on the recreated view — nothing to reset (currentPanel == null)
        stale.resetToDefault()    // service open path resets the singleton before showing it
        freshIv.showPanel(stale)

        assertEquals("reopens on 常用", 0, stale.selectedCategoryForTest())
        assertFalse("reopens unlocked", stale.lockedForTest())
    }

    @Test fun edit_panel_resets_selection_mode() {
        val ep = EditPanelView(ctx)
        ep.applyPalette(light)
        ep.setSelecting(true)
        assertEquals("结束选择", ep.selectingLabelForTest())

        ep.resetToDefault()

        assertEquals("开始选择", ep.selectingLabelForTest())
    }
}
