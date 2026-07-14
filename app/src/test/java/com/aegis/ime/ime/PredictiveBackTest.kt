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

import android.app.Activity
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PredictiveBackTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun src(path: String) = File(path).readText()


    @Test fun manifest_enables_the_on_back_invoked_callback() {
        val manifest: Document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val app = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals(
            "predictive back must be opted in on <application> (required on the API-34 floor)",
            "true",
            app.getAttributeNS("http://schemas.android.com/apk/res/android", "enableOnBackInvokedCallback"),
        )
    }


    @Test fun has_overlay_tracks_each_dismissable_overlay() {
        val iv = InputView(ctx)
        assertFalse("bare keyboard → no overlay (Back hides the IME)", iv.hasOverlay())
        iv.showPanel(View(ctx))
        assertTrue("a panel is an overlay", iv.hasOverlay())
        iv.showPanel(null)
        assertFalse("closing the panel clears the overlay", iv.hasOverlay())
        iv.showCopyBar("x")
        assertFalse("the copy bar leaves Back to the framework", iv.hasOverlay())
        iv.hideCopyBar()
        assertFalse(iv.hasOverlay())
        iv.showEditBar(true)
        assertTrue("the edit bar is an overlay", iv.hasOverlay())
    }

    @Test fun back_precedence_is_edit_bar_then_panel() {
        val iv = InputView(ctx)
        iv.showPanel(View(ctx))
        assertEquals("PANEL", iv.backTargetKindForTest())
        iv.showEditBar(true)
        assertEquals("the inline edit bar is the top of the stack", "EDIT_BAR", iv.backTargetKindForTest())
        iv.showEditBar(false)
        assertEquals("closing the edit bar falls back to the panel", "PANEL", iv.backTargetKindForTest())
    }


    @Test fun back_closes_the_panel_back_to_the_keyboard() {
        val iv = InputView(ctx)
        iv.showPanel(View(ctx))
        assertTrue("a panel was open", iv.panelShown)
        assertTrue("Back reports it closed the top overlay", iv.closeTopOverlay())
        assertFalse("Back on an open panel returns to the keyboard (panel closed)", iv.panelShown)
        assertFalse("no overlay remains", iv.hasOverlay())
    }

    @Test fun copy_bar_routes_back_to_framework_without_dismissing_content_or_transient_ui() {
        val iv = InputView(ctx)
        var dismissed = 0
        var editCancelled = 0
        iv.onCopyDismiss = { dismissed++ }
        iv.onEditCancel = { editCancelled++; iv.showEditBar(false) }
        iv.showCopyBar("persistent-copy")

        fun assertCopyContentRemains() {
            val matches = arrayListOf<View>()
            iv.findViewsWithText(matches, "persistent-copy", View.FIND_VIEWS_WITH_TEXT)
            assertTrue("the copied content remains rendered", matches.isNotEmpty())
            assertTrue("the copy bar remains visible", iv.copyBarShown)
            assertEquals("Back never takes the explicit copy dismiss path", 0, dismissed)
        }

        assertFalse(iv.hasOverlay())
        assertEquals("NONE", iv.backTargetKindForTest())
        assertFalse(iv.closeTopOverlay())
        assertCopyContentRemains()

        iv.showPanel(View(ctx))
        assertFalse(iv.hasOverlay())
        assertEquals("NONE", iv.backTargetKindForTest())
        assertFalse(iv.closeTopOverlay())
        assertTrue("the panel remains for the hide lifecycle to clear", iv.panelShown)
        assertCopyContentRemains()

        iv.showPanel(null)
        iv.showEditBar(true)
        assertFalse(iv.hasOverlay())
        assertEquals("NONE", iv.backTargetKindForTest())
        assertFalse(iv.closeTopOverlay())
        assertTrue("the edit bar remains for the hide lifecycle to clear", iv.isEditBarShowing())
        assertEquals("Back does not cancel inline editing before hiding the IME", 0, editCancelled)
        assertCopyContentRemains()
    }

    @Test fun back_on_the_edit_bar_runs_its_cancel_path() {
        val iv = InputView(ctx)
        var cancelled = false
        iv.onEditCancel = { cancelled = true; iv.showEditBar(false) }
        iv.showEditBar(true)
        assertTrue(iv.closeTopOverlay())
        assertTrue("Back on the edit bar runs its normal cancel", cancelled)
        assertFalse(iv.isEditBarShowing())
    }

    @Test fun back_with_no_overlay_reports_nothing_to_close_so_the_framework_hides_the_ime() {
        val iv = InputView(ctx)
        assertFalse("bare keyboard → no overlay", iv.hasOverlay())
        assertFalse(
            "with no overlay, closeTopOverlay does nothing and returns false — the service leaves the " +
                "framework default Back (hide the IME) in charge (it never registered the callback)",
            iv.closeTopOverlay(),
        )
    }


    @Test fun animated_close_of_the_last_overlay_lifts_the_overlay_state_immediately() {
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val iv = InputView(ctx)
        val host = FrameLayout(activity).apply { addView(iv) }
        activity.setContentView(host)
        iv.showEditBar(true)
        assertTrue(iv.hasOverlay())
        iv.showEditBar(false)
        assertFalse("logical overlay state clears at once, before the deferred fade sets the view GONE", iv.hasOverlay())
    }

    @Test fun overlay_open_and_close_notify_the_service() {
        val iv = InputView(ctx)
        var notifications = 0
        iv.onOverlayChanged = { notifications++ }
        iv.showPanel(View(ctx))
        iv.showPanel(null)
        iv.showCopyBar("x")
        iv.hideCopyBar()
        iv.showEditBar(true)
        iv.showEditBar(false)
        assertEquals("each routed state change pings the service so it can resync the back callback", 6, notifications)
    }


    @Test fun no_ime_side_predictive_follow_finger_residue_remains() {
        val svc = src("src/main/java/com/aegis/ime/AegisInputMethodService.kt")
        val inputView = src("src/main/java/com/aegis/ime/ime/InputView.kt")
        for (banned in listOf("OnBackAnimationCallback", "onBackStarted", "onBackProgressed", "onBackCancelled", "BackEvent")) {
            assertFalse("the service must have no follow-finger residue: $banned", svc.contains(banned))
        }
        assertTrue("the service registers a plain OnBackInvokedCallback", svc.contains("OnBackInvokedCallback"))
        assertTrue("the plain callback closes the top overlay", svc.contains("closeTopOverlay()"))
        for (banned in listOf(
            "predictiveBackBegin",
            "predictiveBackProgress",
            "predictiveBackCommit",
            "predictiveBackCancel",
            "PREDICTIVE_FADE",
        )) {
            assertFalse("InputView must not keep the removed follow-finger member: $banned", inputView.contains(banned))
        }
        assertTrue("InputView exposes the plain close-one-layer Back", inputView.contains("fun closeTopOverlay()"))
    }
}
