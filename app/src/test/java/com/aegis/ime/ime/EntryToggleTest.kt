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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntryToggleTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun isPanelShowing_is_true_only_for_the_panel_currently_on_screen() {
        val iv = InputView(ctx)
        val a = View(ctx)
        val b = View(ctx)
        assertFalse("nothing open", iv.isPanelShowing(a))
        iv.showPanel(a)
        assertTrue("a is on screen", iv.isPanelShowing(a))
        assertFalse("a different panel is not 'showing'", iv.isPanelShowing(b))
        assertFalse("null is never 'showing'", iv.isPanelShowing(null))
        iv.showPanel(null)
        assertFalse("closed", iv.isPanelShowing(a))
    }

    @Test fun re_tapping_the_entry_icon_toggles_closed_then_open() {
        val iv = InputView(ctx)
        var panel: View? = null
        fun onEntryIconTap() {
            if (iv.isPanelShowing(panel)) { iv.showPanel(null); return }
            iv.showPanel(panel ?: View(ctx).also { panel = it })
        }
        onEntryIconTap(); assertTrue("first tap opens", iv.panelShown)
        onEntryIconTap(); assertFalse("re-tap closes (P4)", iv.panelShown)
        onEntryIconTap(); assertTrue("tap again reopens", iv.panelShown)
    }

    @Test fun tapping_a_different_entry_icon_switches_panels_rather_than_closing() {
        val iv = InputView(ctx)
        val emoji = View(ctx)
        val clipboard = View(ctx)
        iv.showPanel(emoji)
        assertFalse(iv.isPanelShowing(clipboard))
        iv.showPanel(clipboard)
        assertTrue(iv.panelShown)
        assertTrue(iv.isPanelShowing(clipboard))
    }
}
