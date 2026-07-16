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
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test fun edit_copy_cut_and_paste_match_delete_bounds_and_rounded_feedback() {
        for (height in listOf(246, 296, 299)) {
            val dispatched = mutableListOf<EditAction>()
            val v = EditPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
            v.onAction = dispatched::add
            v.setHasSelection(true)
            layout(v, width = 600, height = height)
            val reference = requireNotNull(v.actionViewForTest(EditAction.DELETE))
            val actions = listOf(EditAction.DELETE, EditAction.COPY, EditAction.CUT, EditAction.PASTE)
            for (action in actions.drop(1)) {
                val target = requireNotNull(v.actionViewForTest(action))
                assertEquals(reference.width, target.width)
                assertEquals(reference.height, target.height)
            }
            val bounds = actions.map { action ->
                val target = requireNotNull(v.actionViewForTest(action))
                Rect(0, 0, target.width, target.height).also { v.offsetDescendantRectToMyCoords(target, it) }
            }
            assertTrue(bounds.all { it.left == bounds.first().left && it.right == bounds.first().right })
            bounds.zipWithNext().forEach { (upper, lower) -> assertTrue(upper.bottom <= lower.top) }
            val titleHeight = (40 * density).toInt()
            val bottomHeight = (56 * density).toInt()
            val contentHeight = maxOf((44 * 3 * density).toInt() + bottomHeight, height - titleHeight)
            val midHeight = contentHeight - bottomHeight
            assertEquals(midHeight / 3, reference.height)
            assertEquals(titleHeight, bounds.first().top)
            val dpadBounds = listOf(EditAction.UP, EditAction.START_SELECT, EditAction.DOWN).map { action ->
                val target = requireNotNull(v.actionViewForTest(action))
                Rect(0, 0, target.width, target.height).also { v.offsetDescendantRectToMyCoords(target, it) }
            }
            val firstDpadHeight = midHeight / 3
            val secondDpadHeight = (midHeight - firstDpadHeight) / 2
            val expectedDpadHeights = listOf(firstDpadHeight, secondDpadHeight, midHeight - firstDpadHeight - secondDpadHeight)
            assertEquals(bounds.first().top, dpadBounds.first().top)
            assertEquals(bounds.first().height(), dpadBounds.first().height())
            assertEquals(expectedDpadHeights, dpadBounds.map { it.height() })
            dpadBounds.zipWithNext().forEach { (upper, lower) -> assertEquals(upper.bottom, lower.top) }
            assertEquals(titleHeight + midHeight, dpadBounds.last().bottom)
            val navigationBounds = listOf(EditAction.HOME, EditAction.SELECT_ALL, EditAction.END).map { action ->
                val target = requireNotNull(v.actionViewForTest(action))
                Rect(0, 0, target.width, target.height).also { v.offsetDescendantRectToMyCoords(target, it) }
            }
            assertTrue(navigationBounds.all { it.top == titleHeight + midHeight })
            assertTrue(navigationBounds.all { it.height() == bottomHeight })
            assertTrue(navigationBounds.all { it.bottom == titleHeight + contentHeight })
            for (action in actions) {
                val target = requireNotNull(v.actionViewForTest(action))
                assertNull(target.background)
                val ripple = target.foreground as android.graphics.drawable.RippleDrawable
                val mask = ripple.findDrawableByLayerId(android.R.id.mask) as GradientDrawable
                assertEquals(ImeShapes.keyRadiusDp * density, mask.cornerRadius, 0f)
                assertTrue(target.hasOnClickListeners())
                assertTrue(target.performClick())
                assertEquals(action, dispatched.last())
            }
            assertEquals(actions, dispatched)
            v.applyPalette(ImePalette.STATIC_DARK)
            for (action in actions) {
                val target = requireNotNull(v.actionViewForTest(action))
                assertNull(target.background)
                assertTrue(target.foreground is android.graphics.drawable.RippleDrawable)
            }
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

    @Test fun symbols_lock_control_fills_a_normal_bar_hit_target() {
        val v = SymbolsView(ctx)
        assertNormalLockControl(v, v.lockSlotForTest(), v.lockBtnForTest(), v.backBtnForTest(), "SymbolsView")

        v.toggleLockForTest()
        assertNormalLockControl(v, v.lockSlotForTest(), v.lockBtnForTest(), v.backBtnForTest(), "SymbolsView locked")
    }

    @Test fun emoji_lock_control_fills_a_normal_bar_hit_target() {
        val v = EmojiView(ctx)
        assertNormalLockControl(v, v.lockSlotForTest(), v.lockBtnForTest(), v.backBtnForTest(), "EmojiView")

        v.toggleLockForTest()
        assertNormalLockControl(v, v.lockSlotForTest(), v.lockBtnForTest(), v.backBtnForTest(), "EmojiView locked")
    }

    private fun assertNormalLockControl(root: View, slot: View, lock: TextView, back: TextView, name: String) {
        layout(root)
        assertTrue("$name: lock control must live inside its slot", lock.parent === slot)
        val lp = lock.layoutParams as FrameLayout.LayoutParams
        assertEquals("$name: lock hit target should fill the slot width", ViewGroup.LayoutParams.MATCH_PARENT, lp.width)
        assertEquals("$name: lock hit target should fill the bar height", ViewGroup.LayoutParams.MATCH_PARENT, lp.height)
        assertEquals("$name: lock label should be centered inside the middle slot", Gravity.CENTER, lp.gravity)
        assertEquals("$name: lock hit target should fill the slot", slot.width, lock.width)
        assertEquals("$name: lock hit target should fill the slot", slot.height, lock.height)
        assertEquals("$name: lock hit target should match Return width", back.width, lock.width)
        assertEquals("$name: lock hit target should match Return height", back.height, lock.height)
        assertEquals(Gravity.CENTER, lock.gravity)
        assertTrue("$name: lock remains independently clickable", lock.hasOnClickListeners())

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
