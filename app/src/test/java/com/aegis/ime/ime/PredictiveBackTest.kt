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

/**
 * IME-SIDE BACK BEHAVIOUR (predictive back is app-only; the IME keeps Back routing, no animation). The IME's own follow-the-finger
 * predictive-back animation was removed — the user ruled predictive back is done only on the app (settings)
 * side; the IME keyboard has no app-behind to peek. What the IME KEEPS is the correct Back ROUTING, without
 * animation: while an overlay (inline edit bar → extras panel → copy bar) is open, Back peels the top layer
 * back to the keyboard; with no overlay, Back is left to the framework default (hide the IME). The service
 * registers a plain [android.window.OnBackInvokedCallback] (not an OnBackAnimationCallback) exactly while an
 * overlay is up. These assert the wiring that IS unit-testable: the manifest flag (the app side needs it),
 * the overlay-open predicate the service (un)registers on, the top-of-stack precedence, the close-one-layer
 * behaviour + the no-overlay fall-through, and that no follow-finger residue survives in the sources. (The
 * actual OnBackInvoked dispatch — and the app-side seekable peek — are emulator concerns.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PredictiveBackTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun src(path: String) = File(path).readText()

    // ---- manifest opt-in (retained: the settings side needs it) ----------------------------------------

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

    // ---- overlay-open predicate + precedence -----------------------------------------------------------

    @Test fun has_overlay_tracks_each_dismissable_overlay() {
        val iv = InputView(ctx)
        assertFalse("bare keyboard → no overlay (Back hides the IME)", iv.hasOverlay())
        iv.showPanel(View(ctx))
        assertTrue("a panel is an overlay", iv.hasOverlay())
        iv.showPanel(null)
        assertFalse("closing the panel clears the overlay", iv.hasOverlay())
        iv.showCopyBar("x")
        assertTrue("the copy bar is an overlay", iv.hasOverlay())
        iv.hideCopyBar()
        assertFalse(iv.hasOverlay())
        iv.showEditBar(true)
        assertTrue("the edit bar is an overlay", iv.hasOverlay())
    }

    @Test fun back_precedence_is_edit_bar_then_panel_then_copy_bar() {
        val iv = InputView(ctx)
        iv.showCopyBar("x")
        assertEquals("COPY_BAR", iv.backTargetKindForTest())
        iv.showPanel(View(ctx))
        assertEquals("a panel outranks the copy bar", "PANEL", iv.backTargetKindForTest())
        iv.showEditBar(true)
        assertEquals("the inline edit bar is the top of the stack", "EDIT_BAR", iv.backTargetKindForTest())
        iv.showEditBar(false)
        assertEquals("closing the edit bar falls back to the panel", "PANEL", iv.backTargetKindForTest())
    }

    // ---- Back closes the top overlay (回键盘), no animation ---------------------------------------------

    @Test fun back_closes_the_panel_back_to_the_keyboard() {
        val iv = InputView(ctx)
        iv.showPanel(View(ctx))
        assertTrue("a panel was open", iv.panelShown)
        assertTrue("Back reports it closed the top overlay", iv.closeTopOverlay())
        assertFalse("Back on an open panel returns to the keyboard (panel closed)", iv.panelShown)
        assertFalse("no overlay remains", iv.hasOverlay())
    }

    @Test fun back_peels_exactly_one_overlay_layer() {
        val iv = InputView(ctx)
        iv.showCopyBar("x")
        iv.showPanel(View(ctx)) // panel on top of the copy bar
        assertTrue(iv.closeTopOverlay())
        assertFalse("Back closed the panel (the top overlay)", iv.panelShown)
        assertTrue("but the copy bar underneath survives — Back peels one layer", iv.copyBarShown)
        assertTrue("a second Back closes the copy bar", iv.closeTopOverlay())
        assertFalse("now nothing is left open", iv.hasOverlay())
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

    // ---- every overlay change notifies the service (its (un)register trigger) --------------------------

    @Test fun animated_close_of_the_last_overlay_lifts_the_overlay_state_immediately() {
        // Attach + animations ON so the copy-bar close DEFERS its GONE (the fade runs). hasOverlay() must still
        // flip to false at once (logical intent, not the deferred visibility) — else the service would keep the
        // back callback registered after the last overlay closed and swallow the next Back for one press.
        Settings.Global.putFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val iv = InputView(ctx)
        val host = FrameLayout(activity).apply { addView(iv) }
        activity.setContentView(host)
        iv.showCopyBar("x")
        assertTrue(iv.hasOverlay())
        iv.hideCopyBar()
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
        assertEquals("each overlay open/close pings the service so it can (un)register the back callback", 6, notifications)
    }

    // ---- no follow-finger residue survives (无 OnBackAnimationCallback 残留) ------------------------------

    @Test fun no_ime_side_predictive_follow_finger_residue_remains() {
        val svc = src("src/main/java/com/aegis/ime/AegisInputMethodService.kt")
        val inputView = src("src/main/java/com/aegis/ime/ime/InputView.kt")
        // The service must not carry any OnBackAnimationCallback (follow-finger) surface any more…
        for (banned in listOf("OnBackAnimationCallback", "onBackStarted", "onBackProgressed", "onBackCancelled", "BackEvent")) {
            assertFalse("the service must have no follow-finger residue: $banned", svc.contains(banned))
        }
        // …it registers a PLAIN OnBackInvokedCallback that closes the top overlay instead.
        assertTrue("the service registers a plain OnBackInvokedCallback", svc.contains("OnBackInvokedCallback"))
        assertTrue("the plain callback closes the top overlay", svc.contains("closeTopOverlay()"))
        // The input view must not carry any of the removed predictive follow-finger methods/fields.
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
