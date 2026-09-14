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
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-mdpi")
class PanelTapDispatchTest {

    private enum class Gesture { SHORT, JITTER, SLOW, EDGE, CANCEL, LEAVE }

    private fun withKeyboard(nine: Boolean, block: (Activity, InputView) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val input = InputView(activity).apply {
                showKeyboard(
                    if (nine) Layouts.nine(Layouts.ninePunctuation(), composing = false)
                    else Layouts.forId(LayoutId.ALPHA, Lang.CN),
                    false,
                    false,
                    Lang.CN,
                )
            }
            activity.setContentView(input)
            layout(input)
            block(activity, input)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun layout(input: InputView) {
        shadowOf(Looper.getMainLooper()).idle()
        val density = input.resources.displayMetrics.density
        input.measure(
            View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((891 * density).toInt(), View.MeasureSpec.AT_MOST),
        )
        input.layout(0, 0, input.measuredWidth, input.measuredHeight)
    }

    private fun bounds(input: InputView, target: View): Rect = Rect(0, 0, target.width, target.height).also {
        input.offsetDescendantRectToMyCoords(target, it)
    }

    private fun backControl(panel: View): PanelHeaderBackControl {
        if (panel is PanelHeaderBackControl) return panel
        if (panel is ViewGroup) {
            for (index in 0 until panel.childCount) {
                val child = panel.getChildAt(index)
                if (child is PanelHeaderBackControl) return child
                if (child is ViewGroup) {
                    runCatching { backControl(child) }.getOrNull()?.let { return it }
                }
            }
        }
        error("Panel has no shared back control")
    }

    private fun send(input: InputView, down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            assertTrue("InputView accepts action $action at $x, $y", input.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun gesture(input: InputView, target: View, kind: Gesture) {
        val box = bounds(input, target)
        assertTrue("Target has a nonempty hit rectangle", box.width() > 0 && box.height() > 0)
        val slop = ViewConfiguration.get(input.context).scaledTouchSlop.toFloat()
        val x = if (kind == Gesture.EDGE || kind == Gesture.JITTER) box.right - 1f else box.exactCenterX()
        val y = if (kind == Gesture.EDGE) box.top + 1f else box.exactCenterY()
        val down = SystemClock.uptimeMillis()
        send(input, down, MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        assertTrue("$kind has press feedback before release", target.isPressed)
        val endX = when (kind) {
            Gesture.JITTER -> x + slop / 2f
            Gesture.LEAVE -> box.right + slop + 2f
            else -> x
        }
        if (kind == Gesture.JITTER || kind == Gesture.LEAVE) {
            send(input, down, MotionEvent.ACTION_MOVE, endX, y)
        }
        if (kind == Gesture.SLOW) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        }
        send(input, down, if (kind == Gesture.CANCEL) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, endX, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertFalse("$kind releases press feedback", target.isPressed)
    }

    @Test fun shared_header_backs_close_each_panel_with_short_jitter_slow_and_edge_taps_in_both_layouts() {
        for (nine in listOf(false, true)) withKeyboard(nine) { activity, input ->
            var backs = 0
            val onBack = { backs++; input.showPanel(null) }
            val panels = listOf(
                EditPanelView(activity).apply { onAction = { if (it == EditAction.BACK) onBack() } },
                LayoutPanelView(activity).apply { this.onBack = onBack },
                CustomSymbolPanel(activity).apply { this.onBack = onBack; refresh() },
                ClipboardView(activity).apply { this.onBack = onBack; refresh() },
            )
            for (panel in panels) {
                for (kind in listOf(Gesture.SHORT, Gesture.JITTER, Gesture.SLOW, Gesture.EDGE)) {
                    input.showPanel(panel)
                    layout(input)
                    val before = backs
                    gesture(input, backControl(panel), kind)
                    assertEquals("$nine ${panel.javaClass.simpleName} $kind dispatches once", before + 1, backs)
                    assertFalse("$nine ${panel.javaClass.simpleName} $kind closes the panel", input.isPanelShowing(panel))
                }
            }
        }
    }

    @Test fun canceled_or_outside_header_gestures_do_not_close_and_the_next_tap_still_works() {
        for (nine in listOf(false, true)) withKeyboard(nine) { activity, input ->
            var backs = 0
            val panel = EditPanelView(activity).apply {
                onAction = { if (it == EditAction.BACK) { backs++; input.showPanel(null) } }
            }
            for (kind in listOf(Gesture.CANCEL, Gesture.LEAVE)) {
                input.showPanel(panel)
                layout(input)
                val before = backs
                gesture(input, backControl(panel), kind)
                assertEquals("$nine $kind must not dispatch Back", before, backs)
                assertTrue(input.isPanelShowing(panel))
                gesture(input, backControl(panel), Gesture.SHORT)
                assertEquals(before + 1, backs)
                assertFalse(input.isPanelShowing(panel))
            }
        }
    }

    @Test fun ordinary_edit_keys_preserve_clicks_across_jitter_slow_and_edge_taps_in_both_layouts() {
        for (nine in listOf(false, true)) withKeyboard(nine) { activity, input ->
            val actions = mutableListOf<EditAction>()
            val panel = EditPanelView(activity).apply {
                onAction = actions::add
                setHasSelection(true)
                setUndoAvailable(true)
            }
            input.showPanel(panel)
            layout(input)
            for (action in listOf(EditAction.LEFT, EditAction.COPY, EditAction.UNDO)) {
                for (kind in Gesture.entries) {
                    val before = actions.size
                    gesture(input, requireNotNull(panel.actionViewForTest(action)), kind)
                    val expected = if (kind == Gesture.CANCEL || kind == Gesture.LEAVE) emptyList() else listOf(action)
                    assertEquals("$nine $action $kind", expected, actions.drop(before))
                }
            }
        }
    }
}
