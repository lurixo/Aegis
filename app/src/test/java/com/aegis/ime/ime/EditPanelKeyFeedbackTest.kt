// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
// FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.ime

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.aegis.ime.ime.theme.ImePalette
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import android.widget.TextView
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditPanelKeyFeedbackTest {

    private val immediateActions = EditAction.entries.filter { it != EditAction.BACK }

    private fun withPanel(undoAvailable: Boolean = true, block: (EditPanelView) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val root = requireNotNull(activity.findViewById<ViewGroup>(android.R.id.content))
            val panel = EditPanelView(activity).apply {
                applyPalette(ImePalette.STATIC_LIGHT)
                setHasSelection(true)
                if (undoAvailable) setUndoAvailable(true)
            }
            root.addView(InputView(activity).apply { setKeyHapticStyle(KeyHaptic.SYSTEM); addView(panel) })
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(dp(panel, 411), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(panel, 290), View.MeasureSpec.EXACTLY),
            )
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            shadowOf(Looper.getMainLooper()).idle()
            block(panel)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun every_immediate_action_keeps_a_key_face_press_feedback_and_haptic() = withPanel { panel ->
        val keyHaptics: KeyHapticsAware = panel
        keyHaptics.hapticEnabled = true
        val dispatched = ArrayList<EditAction>()
        panel.onAction = { dispatched += it }
        val palette = ImePalette.STATIC_LIGHT

        for ((index, action) in immediateActions.withIndex()) {
            val key = requireNotNull(panel.actionViewForTest(action))
            assertFalse("$action must not use a platform ripple as its key face", key.background is RippleDrawable)
            assertKeyFace("$action idle", key)
            assertEquals(
                "$action starts with no pressed layer",
                0f,
                requireNotNull(panel.actionFeedbackLevelForTest(action)),
                0f,
            )

            val time = index * 200L
            send(key, MotionEvent.ACTION_DOWN, time, time)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))

            assertEquals(
                "$action reaches the normal-key pressed level",
                1f,
                requireNotNull(panel.actionFeedbackLevelForTest(action)),
                0f,
            )
            assertTrue("$action has a visible pressed layer", faceCenter(key) != palette.keySurface)
            assertEquals(
                "$action performs keyboard haptics on press",
                HapticFeedbackConstants.KEYBOARD_TAP,
                shadowOf(key).lastHapticFeedbackPerformed(),
            )

            send(key, MotionEvent.ACTION_UP, time, time + Motion.PRESS_IN)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
            assertEquals(
                "$action releases the pressed layer",
                0f,
                requireNotNull(panel.actionFeedbackLevelForTest(action)),
                0f,
            )
            assertKeyFace("$action released", key)
        }

        assertEquals("each immediate key dispatches exactly once", immediateActions, dispatched)
    }

    @Test fun palette_updates_recolor_every_key_face_and_keep_visible_press_feedback() = withPanel { panel ->
        val palette = ImePalette.STATIC_DARK
        panel.applyPalette(palette)

        for ((index, action) in immediateActions.withIndex()) {
            val key = requireNotNull(panel.actionViewForTest(action))
            assertKeyFace("$action dark idle", key, palette.keySurface)

            val time = index * 200L
            send(key, MotionEvent.ACTION_DOWN, time, time)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
            assertTrue("$action has visible feedback on the dark face", faceCenter(key) != palette.keySurface)
            send(key, MotionEvent.ACTION_UP, time, time + Motion.PRESS_IN)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
            assertKeyFace("$action dark released", key, palette.keySurface)
        }

        val back = requireNotNull(panel.actionViewForTest(EditAction.BACK))
        assertTrue("the title action stays the shared panel header control", back is PanelHeaderBackControl)
        assertNull("the title action must not enter the keyboard feedback inventory", panel.actionFeedbackLevelForTest(EditAction.BACK))
        assertTrue("the title action keeps its panel-control ripple", back.foreground is RippleDrawable)
    }

    @Test fun ordinary_actions_follow_the_key_haptics_toggle() = withPanel { panel ->
        panel.hapticEnabled = false
        val left = requireNotNull(panel.actionViewForTest(EditAction.LEFT))
        send(left, MotionEvent.ACTION_DOWN, 0, 0)
        assertEquals("the toggle disables ordinary-key haptics", -1, shadowOf(left).lastHapticFeedbackPerformed())
        send(left, MotionEvent.ACTION_UP, 0, 10)

        panel.hapticEnabled = true
        val copy = requireNotNull(panel.actionViewForTest(EditAction.COPY))
        send(copy, MotionEvent.ACTION_DOWN, 20, 20)
        assertEquals(
            "the toggle enables ordinary-key haptics",
            HapticFeedbackConstants.KEYBOARD_TAP,
            shadowOf(copy).lastHapticFeedbackPerformed(),
        )
        send(copy, MotionEvent.ACTION_UP, 20, 30)
    }

    @Test fun copy_and_cut_have_no_press_or_haptic_until_a_selection_exists() = withPanel { panel ->
        panel.hapticEnabled = true
        val dispatched = ArrayList<EditAction>()
        panel.onAction = { dispatched += it }

        for ((index, action) in listOf(EditAction.COPY, EditAction.CUT).withIndex()) {
            val key = requireNotNull(panel.actionViewForTest(action))
            panel.setHasSelection(false)
            assertFalse("$action is disabled without a selection", key.isEnabled)
            assertFalse("$action is not clickable without a selection", key.isClickable)
            assertKeyFace("$action disabled", key)

            val time = index * 200L
            send(key, MotionEvent.ACTION_DOWN, time, time)
            send(key, MotionEvent.ACTION_UP, time, time + 16)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN + Motion.PRESS_OUT))
            assertEquals(0f, requireNotNull(panel.actionFeedbackLevelForTest(action)), 0f)
            assertEquals(-1, shadowOf(key).lastHapticFeedbackPerformed())
            assertEquals(ImePalette.STATIC_LIGHT.disabled, (key as TextView).currentTextColor)
            assertKeyFace("$action retains a face while disabled", key)
            assertTrue(dispatched.isEmpty())

            panel.setHasSelection(true)
            assertTrue("$action is enabled when a selection exists", key.isEnabled)
            assertTrue("$action is clickable when a selection exists", key.isClickable)
            assertKeyFace("$action enabled", key)
            send(key, MotionEvent.ACTION_DOWN, time + 50, time + 50)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
            assertEquals(1f, requireNotNull(panel.actionFeedbackLevelForTest(action)), 0f)
            assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(key).lastHapticFeedbackPerformed())
            send(key, MotionEvent.ACTION_UP, time + 50, time + 50 + Motion.PRESS_IN)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
            assertKeyFace("$action released", key)
            assertEquals(listOf(action), dispatched)
            dispatched.clear()
        }
    }

    @Test fun same_selection_and_palette_updates_preserve_an_active_copy_press() = withPanel { panel ->
        val dispatched = ArrayList<EditAction>()
        panel.onAction = { dispatched += it }
        val copy = requireNotNull(panel.actionViewForTest(EditAction.COPY))

        send(copy, MotionEvent.ACTION_DOWN, 0, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
        assertEquals(1f, requireNotNull(panel.actionFeedbackLevelForTest(EditAction.COPY)), 0f)
        assertEquals(ImePalette.STATIC_LIGHT.keySurface, (copy.background as ImeKeySurface).faceColor)

        panel.setHasSelection(true)
        assertEquals(1f, requireNotNull(panel.actionFeedbackLevelForTest(EditAction.COPY)), 0f)
        assertEquals(ImePalette.STATIC_LIGHT.keySurface, (copy.background as ImeKeySurface).faceColor)
        panel.applyPalette(ImePalette.STATIC_DARK)
        assertEquals(1f, requireNotNull(panel.actionFeedbackLevelForTest(EditAction.COPY)), 0f)
        assertEquals(ImePalette.STATIC_DARK.keySurface, (copy.background as ImeKeySurface).faceColor)
        assertTrue("the active Copy press stays visible after a palette update", faceCenter(copy) != ImePalette.STATIC_DARK.keySurface)

        send(copy, MotionEvent.ACTION_UP, 0, Motion.PRESS_IN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
        assertEquals(0f, requireNotNull(panel.actionFeedbackLevelForTest(EditAction.COPY)), 0f)
        assertKeyFace("Copy released after palette update", copy, ImePalette.STATIC_DARK.keySurface)
        assertEquals(listOf(EditAction.COPY), dispatched)
    }

    @Test fun undo_starts_disabled_and_ignores_touch_and_direct_click_until_history_is_available() = withPanel(undoAvailable = false) { panel ->
        panel.hapticEnabled = true
        val dispatched = ArrayList<EditAction>()
        panel.onAction = dispatched::add
        val undo = requireNotNull(panel.actionViewForTest(EditAction.UNDO)) as TextView
        val icon = undo.compoundDrawables.filterIsInstance<EditPanelView.GlyphDrawable>().single()
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            panel.applyPalette(palette)
            assertFalse(undo.isEnabled)
            assertFalse(undo.isClickable)
            assertEquals(palette.disabled, undo.currentTextColor)
            assertEquals(palette.disabled, icon.tintForTest())
            send(undo, MotionEvent.ACTION_DOWN, 0, 0)
            send(undo, MotionEvent.ACTION_UP, 0, 16)
            undo.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN + Motion.PRESS_OUT))
            assertEquals(0f, requireNotNull(panel.actionFeedbackLevelForTest(EditAction.UNDO)), 0f)
            assertEquals(-1, shadowOf(undo).lastHapticFeedbackPerformed())
            assertTrue(dispatched.isEmpty())
            assertKeyFace("Undo disabled", undo, palette.keySurface)
        }
        panel.setUndoAvailable(true)
        assertTrue(undo.isEnabled)
        assertTrue(undo.isClickable)
        assertEquals(ImePalette.STATIC_DARK.keyLabel, undo.currentTextColor)
        assertEquals(ImePalette.STATIC_DARK.keyLabel, icon.tintForTest())
        send(undo, MotionEvent.ACTION_DOWN, 100, 100)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
        assertEquals(1f, requireNotNull(panel.actionFeedbackLevelForTest(EditAction.UNDO)), 0f)
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(undo).lastHapticFeedbackPerformed())
        send(undo, MotionEvent.ACTION_UP, 100, 100 + Motion.PRESS_IN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
        assertEquals(listOf(EditAction.UNDO), dispatched)
    }

    @Test fun undo_availability_updates_keep_an_active_press_and_clear_it_when_history_disappears() = withPanel { panel ->
        val dispatched = ArrayList<EditAction>()
        panel.onAction = dispatched::add
        val undo = requireNotNull(panel.actionViewForTest(EditAction.UNDO))
        send(undo, MotionEvent.ACTION_DOWN, 0, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
        panel.setUndoAvailable(true)
        panel.applyPalette(ImePalette.STATIC_DARK)
        assertEquals(1f, requireNotNull(panel.actionFeedbackLevelForTest(EditAction.UNDO)), 0f)
        panel.setUndoAvailable(false)
        assertEquals(0f, requireNotNull(panel.actionFeedbackLevelForTest(EditAction.UNDO)), 0f)
        send(undo, MotionEvent.ACTION_UP, 0, Motion.PRESS_IN + 16)
        undo.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
        assertTrue(dispatched.isEmpty())
        assertKeyFace("Undo loses its active press", undo, ImePalette.STATIC_DARK.keySurface)
    }

    @Test fun selection_toggle_keeps_its_short_label_and_exposes_the_active_mode() = withPanel { panel ->
        val select = requireNotNull(panel.actionViewForTest(EditAction.START_SELECT)) as TextView
        val arrows = listOf(EditAction.UP, EditAction.DOWN, EditAction.LEFT, EditAction.RIGHT)
        assertEquals("Select", select.text.toString())
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            panel.applyPalette(palette)
            for (selecting in listOf(false, true, false)) {
                panel.setSelecting(selecting)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                assertEquals("the toggle label is stable", "Select", select.text.toString())
                assertEquals(
                    panel.context.getString(if (selecting) com.aegis.ime.R.string.edit_end_select else com.aegis.ime.R.string.edit_start_select),
                    select.contentDescription,
                )
                assertEquals(if (selecting) palette.accentBottom else palette.keySurface, (select.background as ImeKeySurface).faceColor)
                assertEquals(if (selecting) palette.accentLabel else palette.keyLabel, select.currentTextColor)
                val selectIcon = select.compoundDrawables.filterIsInstance<EditPanelView.GlyphDrawable>().single()
                assertEquals(if (selecting) palette.accentLabel else palette.keyLabel, selectIcon.tintForTest())
                for (action in arrows) {
                    val glyph = requireNotNull(panel.actionViewForTest(action)).foreground as EditPanelView.GlyphDrawable
                    assertEquals("$action shows selection mode", if (selecting) palette.accentBottom else palette.keyLabel, glyph.tintForTest())
                }
            }
        }
    }

    private fun send(view: View, action: Int, downTime: Long, eventTime: Long) {
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            view.width / 2f,
            view.height / 2f,
            0,
        )
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun faceCenter(view: View): Int {
        assertTrue("the action must be laid out", view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.background.setBounds(0, 0, view.width, view.height)
            view.background.draw(Canvas(bitmap))
            bitmap.getPixel(view.width / 2, view.height / 2)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertKeyFace(message: String, view: View, color: Int = ImePalette.STATIC_LIGHT.keySurface) {
        val surface = view.background as? ImeKeySurface
        assertTrue("$message keeps the shared press surface", surface != null)
        assertEquals("$message static face color", color, requireNotNull(surface).faceColor)
        assertEquals("$message visible idle face", color, faceCenter(view))
    }

    private fun dp(view: View, value: Int): Int = (value * view.resources.displayMetrics.density).toInt()
}
