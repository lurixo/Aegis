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

import android.widget.FrameLayout
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PredictiveBackTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun src(path: String) = File(path).readText()

    @Test fun back_dismisses_a_panel_confirmation_before_the_panel_that_raised_it() {
        val iv = InputView(ctx)
        val confirmation = PanelConfirmationOverlay(ctx)
        val panel = FrameLayout(ctx).apply { addView(FrameLayout(ctx).apply { addView(confirmation) }) }
        var confirmed = 0
        iv.showPanel(panel)
        confirmation.show("Clear recent items?", "Clear", "Cancel", ImePalette.STATIC_LIGHT) { confirmed++ }

        assertEquals("a confirmation raised over a panel is the top of the stack", "PANEL_CONFIRMATION", iv.backTargetKindForTest())
        assertTrue(iv.hasOverlay())
        assertTrue("Back reports it closed the top overlay", iv.closeTopOverlay())
        assertEquals("Back never takes the confirm path", 0, confirmed)
        assertTrue("the panel that raised the confirmation stays open", iv.panelShown)
        assertEquals("with the confirmation gone the panel is the top again", "PANEL", iv.backTargetKindForTest())

        assertTrue(iv.closeTopOverlay())
        assertFalse("a second Back closes the panel itself", iv.panelShown)
        assertFalse(iv.hasOverlay())
    }

    @Test fun a_dismissed_panel_confirmation_stops_claiming_back() {
        val iv = InputView(ctx)
        val confirmation = PanelConfirmationOverlay(ctx)
        iv.showPanel(FrameLayout(ctx).apply { addView(confirmation) })
        assertEquals("a confirmation that was never shown does not claim Back", "PANEL", iv.backTargetKindForTest())
        confirmation.show("Delete this item?", "Delete", "Cancel", ImePalette.STATIC_LIGHT) {}
        assertEquals("PANEL_CONFIRMATION", iv.backTargetKindForTest())
        confirmation.dismiss()
        assertEquals("dismissing it by any route hands Back back to the panel", "PANEL", iv.backTargetKindForTest())
    }
}
