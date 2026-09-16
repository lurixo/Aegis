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
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Locale
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditPanelLayoutTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val sizes = listOf(320 to 200, 320 to 230, 320 to 275, 320 to 276, 320 to 290, 411 to 276, 411 to 284, 411 to 286, 411 to 288, 411 to 324, 520 to 220, 640 to 220, 720 to 230)
    private val actions = EditAction.entries.filter { it != EditAction.BACK }

    private fun localizedContext(language: String): Context = ctx.createConfigurationContext(
        Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) },
    )

    private fun layout(view: View, widthDp: Int, heightDp: Int) {
        val density = view.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun bounds(root: ViewGroup, target: View): Rect = Rect().also {
        target.getHitRect(it)
        root.offsetDescendantRectToMyCoords(target.parent as View, it)
    }

    @Test fun regular_function_labels_keep_the_reference_size_and_weight_across_header_height_boundaries() {
        for (language in listOf("en", "zh")) {
            val context = localizedContext(language)
            val reference = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
            val panel = EditPanelView(context)
            for (height in listOf(324, 288, 286, 284, 276, 324)) {
                layout(panel, 411, height)
                for (action in actions) {
                    val target = requireNotNull(panel.actionViewForTest(action)) as TextView
                    if (target.text.isEmpty() || action == EditAction.START_SELECT) continue
                    val message = "$language 411 x $height $action"
                    assertEquals("$message retains the reference 14 sp label", reference.textSize, target.textSize, 0.01f)
                    assertEquals("$message retains the ordinary font", reference.typeface, target.typeface)
                    assertEquals("$message retains the original synthetic weight", reference.paint.isFakeBoldText, target.paint.isFakeBoldText)
                }
            }
        }
    }

    @Test fun compact_selection_keeps_the_reference_proportions_without_shrinking_other_chinese_labels() {
        val context = localizedContext("zh")
        val reference = TextView(context)
        for ((width, height) in sizes) {
            val panel = EditPanelView(context)
            layout(panel, width, height)
            for (action in actions) {
                val target = requireNotNull(panel.actionViewForTest(action)) as TextView
                if (target.text.isEmpty()) continue
                val compactSelection = action == EditAction.START_SELECT && target.height < 52f * target.resources.displayMetrics.density
                reference.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compactSelection) 12f else 14f)
                assertEquals("$width x $height $action keeps its reference type size", reference.textSize, target.textSize, 0.01f)
            }
        }
    }

    @Test fun regular_portrait_icons_and_compact_selection_match_the_reference_proportions() {
        val panel = EditPanelView(localizedContext("zh"))
        layout(panel, 411, 284)
        val density = panel.resources.displayMetrics.density
        for (action in listOf(EditAction.TAB, EditAction.FORWARD_DELETE, EditAction.SELECT_ALL, EditAction.START_SELECT)) {
            val target = requireNotNull(panel.actionViewForTest(action)) as TextView
            val icon = requireNotNull(target.compoundDrawables[1])
            val expectedDp = if (action == EditAction.START_SELECT) 18 else 24
            assertEquals("$action icon width", expectedDp * density, icon.bounds.width().toFloat(), 0.5f)
            assertEquals("$action icon height", expectedDp * density, icon.bounds.height().toFloat(), 0.5f)
        }
    }

    @Test fun labels_remain_complete_on_one_line_in_both_languages_and_all_panel_shapes() {
        for (language in listOf("en", "zh")) {
            val context = localizedContext(language)
            for ((panelWidth, panelHeight) in sizes) {
                val panel = EditPanelView(context)
                layout(panel, panelWidth, panelHeight)
                val expected = mapOf(
                    EditAction.START_SELECT to if (language == "zh") "选择" else "Select",
                    EditAction.TAB to "Tab",
                    EditAction.FORWARD_DELETE to "Delete",
                    EditAction.UNDO to if (language == "zh") "撤销" else "Undo",
                    EditAction.DELETE to if (language == "zh") "退格" else "Backspace",
                    EditAction.HOME to if (language == "zh") "开头" else "Start",
                    EditAction.END to if (language == "zh") "末尾" else "End",
                    EditAction.SELECT_ALL to if (language == "zh") "全选" else "Select all",
                    EditAction.COPY to if (language == "zh") "复制" else "Copy",
                    EditAction.CUT to if (language == "zh") "剪切" else "Cut",
                    EditAction.PASTE to if (language == "zh") "粘贴" else "Paste",
                )
                for ((action, label) in expected) {
                    val target = requireNotNull(panel.actionViewForTest(action)) as TextView
                    val message = "$language $panelWidth x $panelHeight $action"
                    assertEquals("$message label", label, target.text.toString())
                    val textLayout = requireNotNull(target.layout)
                    assertEquals("$message single line", 1, textLayout.lineCount)
                    val width = target.width - target.compoundPaddingLeft - target.compoundPaddingRight
                    val height = target.height - target.compoundPaddingTop - target.compoundPaddingBottom
                    assertTrue(
                        "$message text height: layout=${textLayout.height} available=$height " +
                            "key=${target.height} top=${target.compoundPaddingTop} bottom=${target.compoundPaddingBottom}",
                        textLayout.height <= height,
                    )
                    assertTrue("$message text width", textLayout.getLineWidth(0) <= width + 1)
                    assertEquals("$message no ellipsis", 0, textLayout.getEllipsisCount(0))
                }
            }
        }
    }

    @Test fun every_action_is_visible_and_has_a_distinct_touch_target_without_scrolling() {
        for ((width, height) in sizes) {
            val panel = EditPanelView(ctx)
            layout(panel, width, height)
            val viewport = bounds(panel, panel.actionViewportForTest())
            val minimum = ((if (height >= 290) 48 else 36) * panel.resources.displayMetrics.density).toInt()
            val rectangles = actions.map { action ->
                val rect = bounds(panel, requireNotNull(panel.actionViewForTest(action)))
                assertTrue("$width x $height $action stays in the viewport: $rect / $viewport", viewport.contains(rect))
                assertTrue("$width x $height $action usable width", rect.width() >= minimum)
                assertTrue("$width x $height $action usable height", rect.height() >= minimum)
                rect
            }
            for (left in actions.indices) {
                for (right in left + 1 until actions.size) {
                    assertFalse("$width x $height ${actions[left]} overlaps ${actions[right]}", Rect.intersects(rectangles[left], rectangles[right]))
                }
            }
            assertFalse(panel.actionContentCanScrollForTest())
            assertFalse(panel.actionViewportForTest().canScrollVertically(-1))
            assertFalse(panel.actionViewportForTest().canScrollVertically(1))
        }
    }

    @Test fun portrait_groups_the_dpad_text_jumps_and_editing_clusters() {
        val panel = EditPanelView(ctx)
        layout(panel, 411, 324)
        fun rect(action: EditAction) = bounds(panel, requireNotNull(panel.actionViewForTest(action)))
        val tab = rect(EditAction.TAB)
        val forward = rect(EditAction.FORWARD_DELETE)
        val backspace = rect(EditAction.DELETE)
        val undo = rect(EditAction.UNDO)
        val home = rect(EditAction.HOME)
        val end = rect(EditAction.END)
        assertTrue(rect(EditAction.RIGHT).right < tab.left)
        assertEquals(tab.top, backspace.top)
        assertEquals(tab.left, undo.left)
        assertEquals(forward.left, backspace.left)
        assertEquals(forward.right, backspace.right)
        assertEquals(undo.top, forward.top)
        assertEquals(undo.bottom, forward.bottom)
        assertTrue(undo.right < forward.left)
        assertTrue(forward.top >= backspace.bottom)
        assertTrue(rect(EditAction.SELECT_ALL).top >= forward.bottom)
        assertEquals(rect(EditAction.SELECT_ALL).top, rect(EditAction.CUT).top)
        assertEquals(rect(EditAction.COPY).top, rect(EditAction.PASTE).top)
        assertTrue(rect(EditAction.COPY).top >= rect(EditAction.SELECT_ALL).bottom)
        assertEquals(rect(EditAction.SELECT_ALL).left, rect(EditAction.COPY).left)
        assertEquals(forward.left, rect(EditAction.CUT).left)
        assertTrue(home.top >= rect(EditAction.DOWN).bottom)
        assertEquals(home.top, end.top)
        assertTrue(home.right < end.left)
        assertTrue(end.right < rect(EditAction.PASTE).left)
    }

    @Test fun landscape_keeps_text_jumps_in_the_dpad_and_backspace_above_forward_delete() {
        val panel = EditPanelView(ctx)
        layout(panel, 640, 220)
        fun rect(action: EditAction) = bounds(panel, requireNotNull(panel.actionViewForTest(action)))
        val home = rect(EditAction.HOME)
        val down = rect(EditAction.DOWN)
        val end = rect(EditAction.END)
        val backspace = rect(EditAction.DELETE)
        val forward = rect(EditAction.FORWARD_DELETE)
        val undo = rect(EditAction.UNDO)
        assertEquals(home.top, down.top)
        assertEquals(down.top, end.top)
        assertTrue(home.right <= down.left)
        assertTrue(down.right <= end.left)
        assertEquals(rect(EditAction.TAB).left, undo.left)
        assertEquals(rect(EditAction.TAB).top, backspace.top)
        assertEquals(forward.left, backspace.left)
        assertEquals(undo.top, forward.top)
        assertEquals(undo.bottom, forward.bottom)
        assertTrue(undo.right < forward.left)
        assertEquals(rect(EditAction.TAB).height(), backspace.height())
        assertEquals(forward.width(), backspace.width())
        assertTrue(forward.top > backspace.bottom)
        assertTrue(rect(EditAction.SELECT_ALL).top > forward.bottom)
        assertEquals(rect(EditAction.SELECT_ALL).top, rect(EditAction.CUT).top)
        assertEquals(rect(EditAction.COPY).top, rect(EditAction.PASTE).top)
        assertTrue(rect(EditAction.COPY).top > rect(EditAction.SELECT_ALL).bottom)
        assertEquals(rect(EditAction.SELECT_ALL).left, rect(EditAction.COPY).left)
        assertEquals(forward.left, rect(EditAction.CUT).left)
    }

    @Test fun all_actions_dispatch_from_their_visible_targets_in_both_languages_and_all_panel_shapes() {
        for (language in listOf("en", "zh")) {
            for ((width, height) in sizes) {
                val controller = Robolectric.buildActivity(Activity::class.java).setup()
                try {
                    val root = requireNotNull(controller.get().findViewById<ViewGroup>(android.R.id.content))
                    val panel = EditPanelView(localizedContext(language)).apply {
                        setHasSelection(true)
                        setUndoAvailable(true)
                    }
                    root.addView(panel)
                    shadowOf(Looper.getMainLooper()).idle()
                    layout(root, width, height)
                    val dispatched = mutableListOf<EditAction>()
                    panel.onAction = dispatched::add
                    val expected = actions + EditAction.BACK
                    for ((index, action) in expected.withIndex()) {
                        val target = requireNotNull(panel.actionViewForTest(action))
                        val hit = bounds(root, target)
                        assertTrue("$language $width x $height $action is on screen", Rect(0, 0, root.width, root.height).contains(hit))
                        val downTime = index * 100L
                        for ((eventAction, time) in listOf(MotionEvent.ACTION_DOWN to downTime, MotionEvent.ACTION_UP to downTime + 10)) {
                            val event = MotionEvent.obtain(downTime, time, eventAction, hit.exactCenterX(), hit.exactCenterY(), 0)
                            try {
                                assertTrue(root.dispatchTouchEvent(event))
                            } finally {
                                event.recycle()
                            }
                        }
                        shadowOf(Looper.getMainLooper()).idle()
                        assertEquals("$language $width x $height $action routes once", expected.take(index + 1), dispatched)
                    }
                } finally {
                    controller.pause().stop().destroy()
                }
            }
        }
    }
}
