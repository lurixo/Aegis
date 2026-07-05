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


    @Test fun back_progress_nudges_the_top_overlay_and_cancel_restores_it() {
        val iv = InputView(ctx)
        val panel = View(ctx)
        iv.showPanel(panel)
        assertTrue(iv.predictiveBackBegin())
        iv.predictiveBackProgress(1f)
        assertTrue("the panel fades as the gesture progresses", panel.alpha < 1f)
        assertTrue("and slides a little toward its dismissal edge (down)", panel.translationY > 0f)
        iv.predictiveBackCancel()
        assertEquals("cancel restores full opacity", 1f, panel.alpha, 0f)
        assertEquals("cancel restores the position", 0f, panel.translationY, 0f)
        assertTrue("and the panel is still open (the gesture was abandoned)", iv.panelShown)
    }

    @Test fun back_commit_closes_the_top_overlay_only() {
        val iv = InputView(ctx)
        iv.showCopyBar("x")
        iv.showPanel(View(ctx))
        assertTrue(iv.predictiveBackBegin())
        iv.predictiveBackCommit()
        assertFalse("commit closed the panel (the top overlay)", iv.panelShown)
        assertTrue("but the copy bar underneath survives — Back peels one layer", iv.copyBarShown)
    }

    @Test fun back_commit_on_the_edit_bar_runs_its_cancel_path() {
        val iv = InputView(ctx)
        var cancelled = false
        iv.onEditCancel = { cancelled = true; iv.showEditBar(false) }
        iv.showEditBar(true)
        assertTrue(iv.predictiveBackBegin())
        iv.predictiveBackCommit()
        assertTrue("committing Back on the edit bar runs its normal cancel", cancelled)
        assertFalse(iv.isEditBarShowing())
    }


    @Test fun animated_close_of_the_last_overlay_lifts_the_overlay_state_immediately() {
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
}
