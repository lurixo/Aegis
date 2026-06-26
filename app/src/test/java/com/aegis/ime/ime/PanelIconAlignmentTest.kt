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
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun layout(v: View, width: Int = 480, height: Int = 320) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun View.dragVertically(from: Float, to: Float) {
        val x = width / 2f
        dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, height * from, 0))
        dispatchTouchEvent(MotionEvent.obtain(0, 16, MotionEvent.ACTION_MOVE, x, height * to, 0))
        dispatchTouchEvent(MotionEvent.obtain(0, 32, MotionEvent.ACTION_UP, x, height * to, 0))
    }

    @Test fun the_back_control_press_fills_the_control_that_starts_at_the_shared_edge_inset() {
        val back = PanelBackButton.control(ctx, "返回", ImePalette.STATIC_LIGHT.keyLabel) {}
        back.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec((PanelBackButton.HIT_DP * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        back.layout(0, 0, back.measuredWidth, back.measuredHeight)
        back.draw(Canvas(Bitmap.createBitmap(back.width, back.height, Bitmap.Config.ARGB_8888)))
        val ripple = back.foreground as android.graphics.drawable.RippleDrawable
        val mask = requireNotNull(ripple.findDrawableByLayerId(android.R.id.mask))
        assertEquals("the press starts at the control's own edge", 0, mask.bounds.left)
        assertEquals(back.width, mask.bounds.right)
        assertEquals(
            "the leading padding gives way to the host's edge inset",
            (PanelBackButton.EDGE_DP * density).toInt() - (ImeShapes.edgeInsetDp * density).toInt(),
            back.paddingLeft,
        )
    }

    @Test fun edit_panel_never_pages_at_compact_or_portrait_heights() {
        for ((widthDp, heightDp) in listOf(320 to 200, 411 to 230, 411 to 290, 411 to 340, 640 to 220)) {
            val v = EditPanelView(ctx)
            layout(v, width = (widthDp * density).roundToInt(), height = (heightDp * density).roundToInt())
            val viewport = v.actionViewportForTest()
            viewport.dragVertically(0.75f, 0.25f)
            assertEquals("$widthDp x $heightDp after upward drag", 0, viewport.scrollY)
            viewport.dragVertically(0.25f, 0.75f)
            assertEquals("$widthDp x $heightDp after downward drag", 0, viewport.scrollY)
            assertFalse(viewport.canScrollVertically(-1))
            assertFalse(v.actionContentCanScrollForTest())
        }
    }

    @Test fun edit_panel_direction_arrows_are_compact_centered_open_paths() {
        val v = EditPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        layout(v, width = (411 * density).roundToInt(), height = (324 * density).roundToInt())
        val directions = mapOf(
            EditAction.UP to (0f to -1f), EditAction.DOWN to (0f to 1f),
            EditAction.LEFT to (-1f to 0f), EditAction.RIGHT to (1f to 0f),
        )
        for ((action, direction) in directions) {
            val button = requireNotNull(v.actionViewForTest(action))
            val glyph = button.foreground as EditPanelView.GlyphDrawable
            assertEquals((24 * density).roundToInt(), glyph.intrinsicWidth)
            assertTrue(button.background is ImeKeySurface)
            val bitmap = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
            try {
                glyph.setBounds(0, 0, button.width, button.height)
                glyph.draw(Canvas(bitmap))
                val center = requireNotNull(v.arrowLastDrawCenterForTest(action))
                assertEquals(button.width / 2f, center.first, 0.5f)
                assertEquals(button.height / 2f, center.second, 0.5f)
                val scale = glyph.glyphSizeForTest()
                val (dx, dy) = direction
                val px = -dy
                val py = dx
                assertTrue("$action center shaft", bitmap.hasInkNear(center.first - dx * scale * 0.55f, center.second - dy * scale * 0.55f))
                for (side in listOf(-1f, 1f)) {
                    assertTrue(
                        "$action open arrowhead wing",
                        bitmap.hasInkNear(center.first + dx * scale * 0.18f + px * scale * 0.62f * side, center.second + dy * scale * 0.18f + py * scale * 0.62f * side),
                    )
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun Bitmap.hasInkNear(x: Float, y: Float): Boolean {
        val centerX = x.roundToInt()
        val centerY = y.roundToInt()
        for (pixelY in (centerY - 1).coerceAtLeast(0)..(centerY + 1).coerceAtMost(height - 1)) {
            for (pixelX in (centerX - 1).coerceAtLeast(0)..(centerX + 1).coerceAtMost(width - 1)) {
                if (getPixel(pixelX, pixelY).ushr(24) != 0) return true
            }
        }
        return false
    }

    @Test fun edit_labels_use_stacked_icons_with_visible_text_in_portrait() {
        val v = EditPanelView(ctx)
        layout(v, width = (411 * density).roundToInt(), height = (324 * density).roundToInt())
        for (action in listOf(EditAction.START_SELECT, EditAction.TAB, EditAction.FORWARD_DELETE, EditAction.UNDO, EditAction.DELETE,
            EditAction.HOME, EditAction.END, EditAction.SELECT_ALL, EditAction.COPY, EditAction.CUT, EditAction.PASTE)) {
            val button = requireNotNull(v.actionViewForTest(action)) as TextView
            assertTrue("$action visible label", button.text.isNotEmpty())
            assertNull("$action does not consume label width with a leading icon", button.compoundDrawables[0])
            assertEquals("$action icon box", (24 * density).roundToInt(), requireNotNull(button.compoundDrawables[1]).intrinsicWidth)
            assertEquals("$action keeps the full rendered icon", (24 * density).roundToInt(), requireNotNull(button.compoundDrawables[1]).bounds.height())
            assertEquals("$action single line", 1, requireNotNull(button.layout).lineCount)
        }
    }

    @Test fun every_edit_action_has_a_visible_rounded_key_face_in_both_palettes() {
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val v = EditPanelView(ctx).apply { applyPalette(palette) }
            layout(v, width = (411 * density).roundToInt(), height = (324 * density).roundToInt())
            for (action in EditAction.entries.filter { it != EditAction.BACK }) {
                val target = requireNotNull(v.actionViewForTest(action))
                assertTrue("$action shared key surface", target.background is ImeKeySurface)
                val surface = target.background as ImeKeySurface
                assertEquals("$action persistent key face", palette.keySurface, surface.faceColor)
                assertEquals("$action corner radius", 10f * density, surface.faceCornerRadiusPx, 0.5f)
                assertFalse(target.background is android.graphics.drawable.RippleDrawable)
                assertEquals(0f, requireNotNull(v.actionFeedbackLevelForTest(action)), 0f)
            }
        }
    }

    @Test fun edit_navigation_and_back_do_not_take_focus_from_the_editor() {
        val v = EditPanelView(ctx)
        val dispatched = mutableListOf<EditAction>()
        v.onAction = dispatched::add
        layout(v, width = 600, height = 320)
        val navigation = listOf(EditAction.UP, EditAction.DOWN, EditAction.LEFT, EditAction.RIGHT, EditAction.HOME, EditAction.END, EditAction.BACK)
        for (action in navigation) {
            val button = requireNotNull(v.actionViewForTest(action))
            assertFalse("$action cannot take editor focus", button.isFocusable)
            assertFalse(button.requestFocus())
            assertTrue(button.performClick())
        }
        assertEquals(navigation, dispatched)
    }
}
