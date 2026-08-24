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

import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.layout.SymbolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentClearConfirmationTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun layout(view: View, width: Int = 480, height: Int = 320) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun bounds(root: ViewGroup, descendant: View): Rect = Rect(0, 0, descendant.width, descendant.height).also {
        root.offsetDescendantRectToMyCoords(descendant, it)
    }

    private fun event(action: Int, x: Float, y: Float, time: Long): MotionEvent =
        MotionEvent.obtain(0, time, action, x, y, 0)

    private fun pointerEvent(
        actionMasked: Int,
        actionIndex: Int,
        ids: IntArray,
        points: List<Pair<Float, Float>>,
        time: Long,
    ): MotionEvent {
        val properties = Array(ids.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = ids[index]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(ids.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = points[index].first
                y = points[index].second
                pressure = 1f
                size = 1f
            }
        }
        val action = actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return MotionEvent.obtain(
            0,
            time,
            action,
            ids.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    @Test fun confirmation_backdrop_is_clickable_only_while_visible() {
        val overlay = PanelConfirmationOverlay(ctx)
        var confirmations = 0

        assertFalse(overlay.hasOnClickListeners())
        assertFalse(overlay.isClickable)

        overlay.show("Clear recent items?", "Clear", "Cancel", ImePalette.STATIC_LIGHT) { confirmations++ }
        assertTrue(overlay.hasOnClickListeners())
        assertTrue(overlay.isClickable)
        assertTrue(overlay.performClick())

        assertFalse(overlay.hasOnClickListeners())
        assertFalse(overlay.isClickable)
        assertEquals(0, confirmations)
    }

    @Test fun confirmation_actions_share_one_compact_row_in_destructive_then_cancel_order() {
        val overlay = PanelConfirmationOverlay(ctx)
        overlay.show("Delete this item?", "Delete", "Cancel", ImePalette.STATIC_LIGHT) {}
        layout(overlay)

        val confirm = requireNotNull(overlay.confirmActionForTest())
        val cancel = requireNotNull(overlay.cancelActionForTest())
        val card = requireNotNull(overlay.cardForTest())
        val confirmBounds = bounds(overlay, confirm)
        val cancelBounds = bounds(overlay, cancel)
        val cardBounds = bounds(overlay, card)
        val expectedGap = (8 * ctx.resources.displayMetrics.density).toInt()

        assertEquals(confirmBounds.top, cancelBounds.top)
        assertEquals(confirmBounds.bottom, cancelBounds.bottom)
        assertTrue(confirmBounds.centerX() < cancelBounds.centerX())
        assertEquals("only the normal button gap separates the actions", expectedGap, cancelBounds.left - confirmBounds.right)
        assertTrue("the compact actions do not spread to the card edges", confirmBounds.left > cardBounds.left)
        assertTrue("the compact actions do not spread to the card edges", cancelBounds.right < cardBounds.right)
    }

    @Test fun confirmation_backdrop_resets_outside_gestures_after_move_up_and_cancel() {
        val overlay = PanelConfirmationOverlay(ctx)
        overlay.show("Delete this item?", "Delete", "Cancel", ImePalette.STATIC_LIGHT) {}
        layout(overlay)
        val card = requireNotNull(overlay.cardForTest())
        val cardBounds = bounds(overlay, card)
        val insideX = cardBounds.exactCenterX()
        val insideY = cardBounds.exactCenterY()

        assertTrue(overlay.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 2f, 2f, 0)))
        assertTrue(overlay.outsideGestureActiveForTest())
        assertTrue(overlay.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, 2f, 2f, 10)))
        assertFalse(overlay.outsideGestureActiveForTest())
        assertEquals(View.VISIBLE, overlay.visibility)

        assertTrue(overlay.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 2f, 2f, 20)))
        assertTrue(overlay.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, insideX, insideY, 30)))
        assertTrue(overlay.dispatchTouchEvent(event(MotionEvent.ACTION_UP, insideX, insideY, 40)))
        assertFalse(overlay.outsideGestureActiveForTest())
        assertEquals("an outside gesture dragged into the card is not a tap", View.VISIBLE, overlay.visibility)

        assertTrue(overlay.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 2f, 2f, 50)))
        assertTrue(overlay.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 3f, 3f, 60)))
        assertTrue(overlay.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 3f, 3f, 70)))
        assertFalse(overlay.outsideGestureActiveForTest())
        assertEquals(View.GONE, overlay.visibility)
    }

    @Test fun confirmation_backdrop_cancels_a_multitouch_tap_and_hands_off_the_tracked_pointer() {
        val overlay = PanelConfirmationOverlay(ctx)
        overlay.show("Delete this item?", "Delete", "Cancel", ImePalette.STATIC_LIGHT) {}
        layout(overlay)

        assertTrue(overlay.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 2f, 2f, 0)))
        assertTrue(overlay.dispatchTouchEvent(pointerEvent(
            MotionEvent.ACTION_POINTER_DOWN,
            1,
            intArrayOf(0, 7),
            listOf(2f to 2f, 4f to 4f),
            10,
        )))
        assertTrue(overlay.dispatchTouchEvent(pointerEvent(
            MotionEvent.ACTION_POINTER_UP,
            0,
            intArrayOf(0, 7),
            listOf(2f to 2f, 4f to 4f),
            20,
        )))
        assertTrue("the remaining pointer stays owned until its terminal event", overlay.outsideGestureActiveForTest())
        assertTrue(overlay.dispatchTouchEvent(pointerEvent(
            MotionEvent.ACTION_UP,
            0,
            intArrayOf(7),
            listOf(4f to 4f),
            30,
        )))
        assertFalse(overlay.outsideGestureActiveForTest())
        assertEquals("multi-touch is not mistaken for an outside tap", View.VISIBLE, overlay.visibility)

        assertTrue(overlay.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 2f, 2f, 40)))
        assertTrue(overlay.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 2f, 2f, 50)))
        assertEquals(View.GONE, overlay.visibility)
    }

    @Test fun confirmation_backdrop_fast_reopen_drops_the_old_gesture_and_actions() {
        val overlay = PanelConfirmationOverlay(ctx)
        var first = 0
        var second = 0
        overlay.show("First?", "Delete", "Cancel", ImePalette.STATIC_LIGHT) { first++ }
        layout(overlay)
        val staleConfirm = requireNotNull(overlay.confirmActionForTest())
        assertTrue(overlay.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 2f, 2f, 0)))
        assertTrue(overlay.outsideGestureActiveForTest())

        overlay.show("Second?", "Delete", "Cancel", ImePalette.STATIC_LIGHT) { second++ }
        layout(overlay)

        assertFalse(overlay.outsideGestureActiveForTest())
        assertFalse("the detached first action cannot fire", staleConfirm.performClick())
        assertEquals(0, first)
        assertTrue(overlay.confirmForTest())
        assertEquals(1, second)
    }

    @Test fun symbol_recents_clear_only_after_confirmation() {
        val recents = mutableListOf("★", "→")
        var clears = 0
        val view = SymbolsView(ctx).apply {
            recentProvider = { recents.toList() }
            onClearRecents = {
                clears++
                recents.clear()
            }
            applyPalette(ImePalette.STATIC_LIGHT)
            resetToDefault()
        }

        assertEquals(listOf("★", "→"), view.gridCellTextsForTest())
        assertTrue(view.clearBtnForTest().performClick())
        assertTrue(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("★", "→"), recents)

        assertTrue(view.cancelClearForTest())
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("★", "→"), recents)

        assertTrue(view.clearBtnForTest().performClick())
        assertTrue(view.dismissClearForTest())
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("★", "→"), recents)

        assertTrue(view.clearBtnForTest().performClick())
        view.resetToDefault()
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("★", "→"), recents)

        assertTrue(view.clearBtnForTest().performClick())
        assertTrue(view.confirmClearForTest())
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(1, clears)
        assertTrue(recents.isEmpty())
        assertTrue(view.gridCellTextsForTest().isEmpty())

        view.openCategoryForTest(1)
        assertEquals(SymbolCatalog.categories.first().symbols, view.gridCellTextsForTest())
    }

    @Test fun emoji_recents_clear_only_after_confirmation() {
        val recents = mutableListOf("👋", "😀")
        var clears = 0
        val view = EmojiView(ctx).apply {
            recentProvider = { recents.toList() }
            onClearRecents = {
                clears++
                recents.clear()
            }
            applyPalette(ImePalette.STATIC_LIGHT)
            resetToDefault()
        }

        assertEquals(listOf("👋", "😀"), view.gridCellTextsForTest())
        assertTrue(view.clearBtnForTest().performClick())
        assertTrue(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("👋", "😀"), recents)

        assertTrue(view.cancelClearForTest())
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("👋", "😀"), recents)

        assertTrue(view.clearBtnForTest().performClick())
        assertTrue(view.dismissClearForTest())
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("👋", "😀"), recents)

        assertTrue(view.clearBtnForTest().performClick())
        view.resetToDefault()
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(0, clears)
        assertEquals(listOf("👋", "😀"), recents)

        assertTrue(view.clearBtnForTest().performClick())
        assertTrue(view.confirmClearForTest())
        assertFalse(view.clearDialogVisibleForTest())
        assertEquals(1, clears)
        assertTrue(recents.isEmpty())
        assertEquals(listOf(ctx.getString(R.string.emoji_empty_hint)), view.gridCellTextsForTest())

        view.openCategoryForTest(1)
        assertEquals(EmojiCatalog.supported.first().emoji, view.gridCellTextsForTest())
    }

    @Test fun symbol_recent_item_delete_requires_confirmation_and_removes_only_that_item() {
        val recents = mutableListOf("★", "→")
        val origins = mutableMapOf("★" to "math", "→" to "zh")
        val removed = mutableListOf<String>()
        val view = SymbolsView(ctx).apply {
            recentProvider = { recents.toList() }
            recentOriginOf = { origins[it] }
            onDeleteRecent = { symbol ->
                removed += symbol
                recents.remove(symbol)
                origins.remove(symbol)
            }
            applyPalette(ImePalette.STATIC_LIGHT)
            resetToDefault()
        }

        assertTrue(view.longPressCellForTest("★"))
        assertTrue(view.clearDialogVisibleForTest())
        assertTrue(removed.isEmpty())
        assertTrue(view.cancelClearForTest())
        assertEquals(listOf("★", "→"), view.gridCellTextsForTest())

        assertTrue(view.longPressCellForTest("★"))
        assertTrue(view.confirmClearForTest())
        assertEquals(listOf("★"), removed)
        assertEquals(listOf("→"), view.gridCellTextsForTest())
        assertEquals("zh", origins["→"])

        view.openCategoryForTest(1)
        assertFalse("regular symbol categories do not route long-press to recent deletion", view.longPressCellForTest(SymbolCatalog.categories.first().symbols.first()))
    }
}
