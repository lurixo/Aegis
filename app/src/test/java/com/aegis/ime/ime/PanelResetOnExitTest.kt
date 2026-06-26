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
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelResetOnExitTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT

    private class SpyPanel(ctx: Context) : View(ctx), ResettablePanel {
        var resets = 0
        override fun resetToDefault() { resets++ }
    }

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun <T> hosted(body: (Activity) -> T): T {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            return body(controller.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun dismissing_a_panel_resets_it() {
        val iv = InputView(ctx)
        val spy = SpyPanel(ctx)
        iv.showPanel(spy)
        assertEquals("opening must not reset", 0, spy.resets)
        iv.showPanel(null)
        assertEquals(1, spy.resets)
    }

    @Test fun switching_directly_to_another_panel_resets_the_outgoing_one() {
        val iv = InputView(ctx)
        val a = SpyPanel(ctx)
        val b = SpyPanel(ctx)
        iv.showPanel(a)
        iv.showPanel(b)
        assertEquals("outgoing panel reset", 1, a.resets)
        assertEquals("incoming panel untouched", 0, b.resets)
    }

    @Test fun re_showing_the_same_panel_does_not_reset_it() {
        val iv = InputView(ctx)
        val spy = SpyPanel(ctx)
        iv.showPanel(spy)
        iv.showPanel(spy)
        assertEquals(0, spy.resets)
    }

    @Test fun edit_panel_resets_selection_mode() {
        val ep = EditPanelView(ctx)
        ep.applyPalette(light)
        ep.setSelecting(true)
        val select = requireNotNull(ep.actionViewForTest(EditAction.START_SELECT))
        assertEquals(ctx.getString(com.aegis.ime.R.string.edit_select), ep.selectingLabelForTest())
        assertEquals(ctx.getString(com.aegis.ime.R.string.edit_end_select), select.contentDescription)
        assertTrue(select.isSelected)

        ep.resetToDefault()

        assertEquals(ctx.getString(com.aegis.ime.R.string.edit_select), ep.selectingLabelForTest())
        assertEquals(ctx.getString(com.aegis.ime.R.string.edit_start_select), select.contentDescription)
        assertFalse(select.isSelected)
    }

    private fun host(activity: Activity, panel: View, width: Int, height: Int) {
        val root = android.widget.FrameLayout(ctx)
        activity.setContentView(root)
        root.addView(panel, android.widget.FrameLayout.LayoutParams(width, height))
    }

    @Test fun dragging_the_edit_panel_leaves_no_motion_after_its_dismissal() = hosted { activity ->
        val ep = EditPanelView(ctx).apply { applyPalette(light); setSelecting(true) }
        host(activity, ep, 480, 160)
        layout(ep, 480, 160)
        val viewport = ep.actionViewportForTest()
        fun bounds() = EditAction.entries.associateWith { action ->
            val target = requireNotNull(ep.actionViewForTest(action))
            Rect().also { target.getHitRect(it); ep.offsetDescendantRectToMyCoords(target.parent as View, it) }
        }
        val before = bounds()
        for ((action, time, fraction) in listOf(
            Triple(MotionEvent.ACTION_DOWN, 0L, 0.8f),
            Triple(MotionEvent.ACTION_MOVE, 16L, 0.2f),
            Triple(MotionEvent.ACTION_UP, 32L, 0.2f),
        )) {
            val event = MotionEvent.obtain(0, time, action, viewport.width / 2f, viewport.height * fraction, 0)
            try { viewport.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        ep.resetToDefault()
        ep.applyPalette(light)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(480))
        layout(ep, 480, 160)
        assertEquals("the action positions survive dismissal", before, bounds())
        assertEquals(0, viewport.scrollY)
        assertTrue("the emergency viewport keeps full-size actions scrollable", ep.actionContentCanScrollForTest())
        assertFalse(requireNotNull(ep.actionViewForTest(EditAction.START_SELECT)).isSelected)
    }
}
