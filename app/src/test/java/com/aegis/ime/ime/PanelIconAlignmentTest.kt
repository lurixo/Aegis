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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelIconAlignmentTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun textViews(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(v: View) {
            if (v is TextView) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    private fun layout(v: View, width: Int = 480, height: Int = 320) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun textSizePx(tv: TextView): Int = tv.textSize.roundToInt()

    private fun View.tap(x: Float, y: Float) {
        dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test fun edit_panel_back_icon_matches_right_action_label_height() {
        val v = EditPanelView(ctx)
        val labels = textViews(v)
        val title = labels.first { it.text.toString() == ctx.getString(com.aegis.ime.R.string.edit_title) }
        val delete = labels.first { it.text.toString() == ctx.getString(com.aegis.ime.R.string.clip_delete) }
        val backIcon = title.compoundDrawables[0]

        assertNotNull("edit panel title must keep a leading back drawable", backIcon)
        val maxDelta = (3f * density).roundToInt().coerceAtLeast(3)
        val deleteTextSize = textSizePx(delete)
        assertTrue(
            "edit panel back icon box should stay close to the right action label text size: icon=${backIcon.intrinsicHeight}, text=$deleteTextSize",
            abs(backIcon.intrinsicHeight - deleteTextSize) <= maxDelta,
        )
    }

    @Test fun edit_panel_back_action_is_limited_to_the_back_button() {
        val v = EditPanelView(ctx)
        val actions = mutableListOf<EditAction>()
        v.onAction = { actions += it }
        layout(v, width = 600, height = 320)

        val topRow = v.titleBarForTest()
        val back = v.actionViewForTest(EditAction.BACK)
            ?: throw AssertionError("back button must be exposed as its own hit target")
        assertTrue("back button remains clickable", back.hasOnClickListeners())
        assertTrue("back button should only occupy the top-left part of the row", back.right < topRow.width / 2)
        assertTrue("top row itself must not be a click target", !topRow.hasOnClickListeners())

        v.tap(v.width - 4f, topRow.height / 2f)
        assertTrue("tapping empty top-row space must not go back", actions.isEmpty())

        assertTrue("the back button itself must still fire", back.performClick())
        assertEquals(listOf(EditAction.BACK), actions)
    }

    @Test fun edit_panel_arrow_glyphs_are_centered_in_their_feedback_boxes() {
        val v = EditPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        layout(v, width = 600, height = 320)

        for (action in listOf(EditAction.UP, EditAction.DOWN, EditAction.LEFT, EditAction.RIGHT)) {
            val button = v.actionViewForTest(action)
                ?: throw AssertionError("$action arrow button must exist")
            assertTrue("$action arrow keeps rounded tap feedback", button.foreground is RippleDrawable)

            val bitmap = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
            button.draw(Canvas(bitmap))
            val center = v.arrowLastDrawCenterForTest(action)
                ?: throw AssertionError("$action arrow glyph must draw during button rendering")
            assertEquals("$action glyph x center", button.width / 2f, center.first, 0.5f)
            assertEquals("$action glyph y center", button.height / 2f, center.second, 0.5f)
        }
    }

    @Test fun edit_left_group_moves_together_and_right_actions_use_leading_icons() {
        val v = EditPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        layout(v, width = 600, height = 320)

        fun viewFor(item: EditAction): View = requireNotNull(v.actionViewForTest(item))
        fun centerX(item: EditAction): Float {
            val target = viewFor(item)
            val bounds = Rect(0, 0, target.width, target.height)
            v.offsetDescendantRectToMyCoords(target, bounds)
            return bounds.exactCenterX()
        }

        assertEquals(centerX(EditAction.LEFT), centerX(EditAction.HOME), 0.5f)
        assertEquals(centerX(EditAction.UP), centerX(EditAction.SELECT_ALL), 0.5f)
        assertEquals(centerX(EditAction.DOWN), centerX(EditAction.SELECT_ALL), 0.5f)
        assertEquals(centerX(EditAction.START_SELECT), centerX(EditAction.SELECT_ALL), 0.5f)
        assertEquals(centerX(EditAction.RIGHT), centerX(EditAction.END), 0.5f)
        assertTrue(centerX(EditAction.LEFT) > v.width / 10f)

        val leftWidths = listOf(
            EditAction.LEFT,
            EditAction.UP,
            EditAction.RIGHT,
            EditAction.DOWN,
            EditAction.HOME,
            EditAction.SELECT_ALL,
            EditAction.END,
        ).map { viewFor(it).width }
        assertTrue(leftWidths.max() - leftWidths.min() <= 1)
        assertTrue(leftWidths.all { kotlin.math.abs(it - v.width / 5) <= 1 })

        for (item in listOf(EditAction.DELETE, EditAction.COPY, EditAction.CUT, EditAction.PASTE)) {
            val button = viewFor(item) as TextView
            assertNotNull("$item keeps its icon", button.compoundDrawables[0])
            assertTrue("$item does not stack its icon above the label", button.compoundDrawables[1] == null)
        }
        for (item in listOf(EditAction.HOME, EditAction.SELECT_ALL, EditAction.END)) {
            val button = viewFor(item) as TextView
            assertTrue("$item keeps no leading icon", button.compoundDrawables[0] == null)
            assertNotNull("$item keeps its top symbol", button.compoundDrawables[1])
        }
    }

    @Test fun edit_navigation_and_back_are_only_non_focusable_actions() {
        val v = EditPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        val actions = mutableListOf<EditAction>()
        v.onAction = actions::add
        layout(v, width = 600, height = 320)
        val navigation = listOf(
            EditAction.UP,
            EditAction.DOWN,
            EditAction.LEFT,
            EditAction.RIGHT,
            EditAction.HOME,
            EditAction.END,
        )
        val back = requireNotNull(v.actionViewForTest(EditAction.BACK))
        assertFalse(back.isFocusable)
        assertFalse(back.requestFocus())
        for (action in navigation) {
            val button = requireNotNull(v.actionViewForTest(action))
            assertFalse(button.isFocusable)
            assertFalse(button.requestFocus())
            assertTrue(button.performClick())
            assertFalse(back.isFocused)
        }
        for (action in listOf(
            EditAction.START_SELECT,
            EditAction.DELETE,
            EditAction.COPY,
            EditAction.CUT,
            EditAction.SELECT_ALL,
            EditAction.PASTE,
        )) {
            assertTrue(requireNotNull(v.actionViewForTest(action)).isFocusable)
        }
        assertEquals(navigation, actions)
        assertTrue(back.performClick())
        assertEquals(navigation + EditAction.BACK, actions)
    }

    @Test fun symbols_lock_control_is_centered_and_sized_like_text() {
        val v = SymbolsView(ctx)
        assertCenteredLockControl(v, v.lockSlotForTest(), v.lockBtnForTest(), "SymbolsView")

        v.toggleLockForTest()
        assertCenteredLockControl(v, v.lockSlotForTest(), v.lockBtnForTest(), "SymbolsView locked")
    }

    @Test fun emoji_lock_control_is_centered_and_sized_like_text() {
        val v = EmojiView(ctx)
        assertCenteredLockControl(v, v.lockSlotForTest(), v.lockBtnForTest(), "EmojiView")

        v.toggleLockForTest()
        assertCenteredLockControl(v, v.lockSlotForTest(), v.lockBtnForTest(), "EmojiView locked")
    }

    private fun assertCenteredLockControl(root: View, slot: View, lock: TextView, name: String) {
        layout(root)
        assertTrue("$name: lock label must live inside the centered slot", lock.parent === slot)
        val lp = lock.layoutParams as FrameLayout.LayoutParams
        assertEquals("$name: lock label should measure as a cohesive wrap-content control", ViewGroup.LayoutParams.WRAP_CONTENT, lp.width)
        assertEquals("$name: lock label should fill the bar height for vertical centering", ViewGroup.LayoutParams.MATCH_PARENT, lp.height)
        assertEquals("$name: lock label should be centered inside the middle slot", Gravity.CENTER, lp.gravity)

        val rootCenter = root.width / 2
        val lockCenter = slot.left + (lock.left + lock.right) / 2
        assertTrue("$name: lock control must sit on the panel center", abs(lockCenter - rootCenter) <= 1)
        assertTrue("$name: lock control should not spread across the whole middle slot", lock.width < slot.width / 2)

        val maxPadding = (2f * density).roundToInt() + 1
        assertTrue("$name: lock icon and text should sit close together", lock.compoundDrawablePadding <= maxPadding)

        val icon = lock.compoundDrawables[0]
        assertNotNull("$name: lock control must keep a leading icon", icon)
        val maxDelta = (2f * density).roundToInt().coerceAtLeast(2)
        val labelTextSize = textSizePx(lock)
        assertTrue(
            "$name: lock icon box should match the label text size: icon=${icon.intrinsicHeight}, text=$labelTextSize",
            abs(icon.intrinsicHeight - labelTextSize) <= maxDelta,
        )
    }
}
