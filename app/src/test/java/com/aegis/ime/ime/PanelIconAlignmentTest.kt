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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Layouts
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private fun View.dragVertically(from: Float, to: Float) {
        val x = width / 2f
        dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, height * from, 0))
        dispatchTouchEvent(MotionEvent.obtain(0, 16, MotionEvent.ACTION_MOVE, x, height * to, 0))
        dispatchTouchEvent(MotionEvent.obtain(0, 32, MotionEvent.ACTION_UP, x, height * to, 0))
    }

    @Test fun edit_panel_header_matches_shared_control_geometry_and_both_clipboard_tabs() {
        val v = EditPanelView(ctx)
        val title = requireNotNull(v.actionViewForTest(EditAction.BACK)) as PanelHeaderBackControl
        val reference = PanelBackButton.control(ctx, ctx.getString(com.aegis.ime.R.string.edit_title), ImePalette.STATIC_LIGHT.keyLabel) {}
        reference.measure(
            View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((PanelBackButton.HIT_DP * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        reference.layout(0, 0, reference.measuredWidth, reference.measuredHeight)
        val layoutPanel = LayoutPanelView(ctx)
        layout(layoutPanel, width = (411 * density).roundToInt(), height = (324 * density).roundToInt())
        val layoutBack = layoutPanel.titleButtonForTest()
        val layoutBounds = Rect(0, 0, layoutBack.width, layoutBack.height).also { layoutPanel.offsetDescendantRectToMyCoords(layoutBack, it) }
        val clipboardBounds = listOf(false, true).map { phrases ->
            val clipboard = ClipboardView(ctx)
            if (phrases) clipboard.showPhraseTab("") else clipboard.refresh()
            layout(clipboard, width = (411 * density).roundToInt(), height = (324 * density).roundToInt())
            val back = textViews(clipboard).filterIsInstance<PanelHeaderBackControl>().single()
            Rect(0, 0, back.width, back.height).also { clipboard.offsetDescendantRectToMyCoords(back, it) }
        }
        for ((widthDp, heightDp) in listOf(411 to 324, 320 to 200, 640 to 220, 411 to 324)) {
            layout(v, width = (widthDp * density).roundToInt(), height = (heightDp * density).roundToInt())
            val hit = Rect(0, 0, title.width, title.height).also { v.offsetDescendantRectToMyCoords(title, it) }
            assertEquals((56 * density).toInt(), v.titleBarForTest().height)
            assertEquals(v.titleBarForTest().height, v.actionViewportForTest().top)
            assertTrue("the header target has an outer top inset", hit.top > 0)
            assertEquals((56 * density).toInt(), (layoutBack.parent as View).height)
            for (back in clipboardBounds + layoutBounds) {
                assertEquals("the left edge matches layout and both clipboard tabs", back.left, hit.left)
                assertEquals("the top edge matches layout and both clipboard tabs", back.top, hit.top)
                assertEquals("the bottom edge matches layout and both clipboard tabs", back.bottom, hit.bottom)
            }
            assertEquals("the whole title is one natural-width target", reference.width, title.width)
            assertEquals("the full target height matches keyboard layout", layoutBack.height, title.height)
            val icon = requireNotNull(title.compoundDrawables[0])
            assertTrue("the header draws the shared back glyph", icon === title.glyphForTest())
            assertEquals((16 * density).toInt(), icon.intrinsicHeight)
            for (back in listOf(reference, layoutBack)) {
                assertEquals(back.compoundPaddingLeft, title.compoundPaddingLeft)
                assertEquals(back.compoundPaddingRight, title.compoundPaddingRight)
                assertEquals(back.compoundPaddingTop, title.compoundPaddingTop)
                assertEquals(back.compoundPaddingBottom, title.compoundPaddingBottom)
                assertEquals(back.compoundDrawablePadding, title.compoundDrawablePadding)
                assertEquals(back.typeface, title.typeface)
                assertEquals(back.includeFontPadding, title.includeFontPadding)
                assertEquals(back.textSize, title.textSize, 0f)
                assertEquals("the baseline uses the same full-height control", back.baseline, title.baseline)
            }
            assertEquals(reference.text.toString(), title.text.toString())
            assertTrue("the header fits its label", requireNotNull(title.layout).height <= title.height - title.compoundPaddingTop - title.compoundPaddingBottom)
        }
    }

    @Test fun edit_and_layout_back_actions_use_the_complete_target_and_exclude_their_outer_insets() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val root = requireNotNull(controller.get().findViewById<ViewGroup>(android.R.id.content))
            var backCount = 0
            val edit = EditPanelView(controller.get()).apply { onAction = { if (it == EditAction.BACK) backCount++ } }
            val keyboardLayout = LayoutPanelView(controller.get()).apply { onBack = { backCount++ } }
            for ((v, back) in listOf(edit to requireNotNull(edit.actionViewForTest(EditAction.BACK)), keyboardLayout to keyboardLayout.titleButtonForTest())) {
                root.addView(v)
                shadowOf(Looper.getMainLooper()).idle()
                for ((widthDp, heightDp) in listOf(411 to 324, 320 to 200, 640 to 220)) {
                    layout(root, width = (widthDp * density).roundToInt(), height = (heightDp * density).roundToInt())
                    val topRow = back.parent as View
                    val hit = Rect(0, 0, back.width, back.height).also { root.offsetDescendantRectToMyCoords(back, it) }
                    assertTrue(back.hasOnClickListeners())
                    assertTrue("the natural-width target leaves the rest of the header free", back.right < topRow.width)
                    assertFalse(topRow.hasOnClickListeners())
                    assertEquals((56 * density).toInt(), topRow.height)
                    assertEquals((PanelBackButton.HIT_DP * density).toInt(), hit.height())
                    assertEquals((topRow.height - back.height) / 2, hit.top)
                    assertTrue("the clickable target excludes a real outer top inset", hit.top > 0)
                    for (point in listOf(
                        hit.left + 1f to hit.top + 1f,
                        hit.right - 1f to hit.top + 1f,
                        hit.left + 1f to hit.bottom - 1f,
                        hit.right - 1f to hit.bottom - 1f,
                    )) {
                        backCount = 0
                        root.tap(point.first, point.second)
                        shadowOf(Looper.getMainLooper()).idle()
                        assertEquals("every corner of the full target returns once", 1, backCount)
                    }
                    for (point in listOf(
                        hit.exactCenterX() to hit.top - 1f,
                        hit.exactCenterX() to hit.bottom + 1f,
                        hit.right + 1f to hit.exactCenterY(),
                        root.width - 1f to hit.exactCenterY(),
                    )) {
                        backCount = 0
                        root.tap(point.first, point.second)
                        shadowOf(Looper.getMainLooper()).idle()
                        assertEquals("the outer inset and remaining header do not return", 0, backCount)
                    }
                }
                root.removeView(v)
            }
        } finally {
            controller.pause().stop().destroy()
        }
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
        val actionWidth = (Layouts.CANDIDATE_ACTION_WIDTH_DP * density).toInt()
        assertEquals("$name: lock hit target should fill its slot width", ViewGroup.LayoutParams.MATCH_PARENT, lp.width)
        assertEquals("$name: lock hit target should fill its slot height", ViewGroup.LayoutParams.MATCH_PARENT, lp.height)
        assertEquals("$name: lock sits centred in its slot", Gravity.CENTER, lp.gravity)
        assertEquals("$name: lock hit target should take the whole slot width", slot.width, lock.width)
        assertEquals("$name: lock hit target should take the whole slot height", slot.height, lock.height)
        assertTrue("$name: the action column is at least one action wide", lock.width >= actionWidth)
        assertEquals("$name: lock hit target should match Return width", back.width, lock.width)
        assertEquals("$name: lock hit target should match Return height", back.height, lock.height)
        assertEquals(Gravity.CENTER, lock.gravity)
        assertTrue("$name: lock remains independently clickable", lock.hasOnClickListeners())

        assertEquals("$name: lock key face is icon-only", "", lock.text.toString())
        val icon = requireNotNull(lock.compoundDrawables[0])
        assertTrue("$name: lock glyph keeps a drawable box", icon.bounds.width() > 0 && icon.bounds.height() > 0)
        assertTrue("$name: lock glyph stays within the key face", icon.bounds.width() <= lock.width && icon.bounds.height() <= lock.height)
        assertEquals(
            "$name: back key face spells out its name",
            back.context.getString(com.aegis.ime.R.string.panel_back),
            back.text.toString(),
        )
        assertNull("$name: back key face carries no glyph", back.compoundDrawables.firstOrNull { it != null })
        assertEquals("$name: back label is centred", Gravity.CENTER, back.gravity)
    }
}
