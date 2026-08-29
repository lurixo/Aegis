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
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
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
class BottomBarSymmetryTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun layout(view: View, width: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun centerX(root: View, descendant: View): Float {
        var current = descendant
        var x = current.width / 2f
        while (current !== root) {
            x += current.left
            current = current.parent as View
        }
        return x
    }

    private fun bounds(root: ViewGroup, descendant: View): Rect = Rect(0, 0, descendant.width, descendant.height).also {
        root.offsetDescendantRectToMyCoords(descendant, it)
    }

    private fun inkCenter(drawable: Drawable): Pair<Float, Float> {
        val bitmap = Bitmap.createBitmap(drawable.bounds.width(), drawable.bounds.height(), Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) ushr 24 != 0) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        bitmap.recycle()
        assertTrue(right >= left && bottom >= top)
        return (left + right + 1) / 2f to (top + bottom + 1) / 2f
    }

    private fun opaqueBounds(width: Int, height: Int, draw: (Canvas) -> Unit): Rect {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (bitmap.getPixel(x, y) ushr 24 != 0) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        bitmap.recycle()
        assertTrue(right >= left && bottom >= top)
        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun assertClickTargetMatchesFaceAndCentersGlyph(control: TextView, name: String) {
        val background = requireNotNull(control.background)
        background.setBounds(0, 0, control.width, control.height)
        val face = (background as ImeKeySurface).faceBoundsForTest(control.width, control.height)

        control.background = null
        val glyph = opaqueBounds(control.width, control.height) { control.draw(it) }
        control.background = background

        assertEquals("$name face width matches its click target", control.width.toFloat(), face.width(), 0f)
        assertEquals("$name face height matches its click target", control.height.toFloat(), face.height(), 0f)
        assertEquals("$name glyph has equal left and right face clearance", face.centerX(), glyph.exactCenterX(), 0.6f)
    }

    private fun tap(root: View, x: Float, y: Float) {
        root.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        root.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tapControl(root: ViewGroup, control: View) {
        val target = bounds(root, control)
        tap(root, target.exactCenterX(), target.exactCenterY())
    }

    private fun assertActionsTileTheColumn(root: ViewGroup, controls: List<View>) {
        val ordered = controls.sortedBy { bounds(root, it).top }
        val column = bounds(root, ordered.first())
        assertTrue("the column leaves the grid room to its left", column.left > 0)
        for (control in ordered) {
            val target = bounds(root, control)
            assertEquals("every action shares the column's left edge", column.left, target.left)
            assertEquals("every action shares the column's right edge", column.right, target.right)
        }
        for ((above, below) in ordered.zipWithNext()) {
            assertEquals(
                "the actions tile the column with no dead space between them",
                bounds(root, above).bottom,
                bounds(root, below).top,
            )
        }
        assertTrue("the column stops above the category bar", bounds(root, ordered.last()).bottom < root.height)
    }

    private fun assertControlsUseKeySurfaces(
        controls: List<TextView>,
        name: String,
    ) {
        var radius: Float? = null
        for (control in controls) {
            assertNotNull("$name control keeps a resting key surface", control.background)
            assertFalse("$name control does not fall back to a platform ripple", control.background is android.graphics.drawable.RippleDrawable)
            assertNull("$name control uses its shared animated surface instead of a foreground ripple", control.foreground)
            val surface = control.background as ImeKeySurface
            val face = surface.faceBoundsForTest(control.width, control.height)
            assertEquals("$name control resting face is transparent", Color.TRANSPARENT, surface.faceColor)
            assertEquals("$name control face left", 0f, face.left, 0f)
            assertEquals("$name control face top", 0f, face.top, 0f)
            assertEquals("$name control face right", control.width.toFloat(), face.right, 0f)
            assertEquals("$name control face bottom", control.height.toFloat(), face.bottom, 0f)
            radius?.let { assertEquals("$name controls share one corner radius", it, surface.cornerRadiusPx, 0f) }
                ?: run { radius = surface.cornerRadiusPx }
        }
    }

    private fun assertPressStateLayer(control: TextView, name: String) {
        val x = control.width / 2f
        val y = control.height / 2f
        control.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
        val background = requireNotNull(control.background)
        background.setBounds(0, 0, control.width, control.height)
        val pressed = opaqueBounds(control.width, control.height) { background.draw(it) }
        assertEquals("$name pressed layer keeps the full hit width", control.width, pressed.width())
        assertEquals("$name pressed layer keeps the full hit height", control.height, pressed.height())
        control.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_CANCEL, x, y, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
    }

    private fun assertActionColumn(
        view: ViewGroup,
        back: TextView,
        clear: TextView,
        lock: TextView,
        backspace: TextView,
        rowHeight: Int,
        gridViewport: View,
        name: String,
    ) {
        val controls = listOf(back, clear, lock, backspace)
        assertEquals("$name actions read top to bottom", controls, controls.sortedBy { bounds(view, it).top })

        val backBounds = bounds(view, back)
        val clearBounds = bounds(view, clear)
        val lockBounds = bounds(view, lock)
        val backspaceBounds = bounds(view, backspace)
        assertTrue("$name column is at least a fifth of the panel", backBounds.width() >= view.width / 5)
        assertEquals(backBounds.width(), clearBounds.width())
        assertEquals(backBounds.width(), lockBounds.width())
        assertEquals(backBounds.width(), backspaceBounds.width())
        assertEquals(backBounds.height(), clearBounds.height())
        assertEquals(backBounds.height(), lockBounds.height())
        assertEquals(backBounds.height(), backspaceBounds.height())
        assertEquals("$name action is exactly one grid row tall", rowHeight, backBounds.height())
        val grid = bounds(view, gridViewport)
        listOf(backBounds, clearBounds, lockBounds, backspaceBounds).forEachIndexed { index, target ->
            assertEquals("$name action $index starts where the grid ends", grid.right, target.left)
            assertEquals("$name action $index sits on grid row $index", grid.top + index * rowHeight, target.top)
        }
        assertEquals("$name column runs to the panel edge", view.width, backspaceBounds.right)
        controls.forEachIndexed { index, control ->
            assertClickTargetMatchesFaceAndCentersGlyph(control, "$name control $index")
        }
        controls.forEach {
            val bitmap = Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888)
            it.draw(Canvas(bitmap))
            bitmap.recycle()
        }
        assertTrue("$name controls keep independent click targets", controls.all { it.hasOnClickListeners() })
        assertTrue("$name controls keep centered content", controls.all { it.gravity == Gravity.CENTER })
        assertNotNull("$name clear keeps its delete glyph", clear.compoundDrawables[0])
        assertNull("$name clear has no right-anchored glyph", clear.compoundDrawables[2])
        assertNotNull("$name delete keeps its glyph", backspace.compoundDrawables[0])
        assertNull("$name delete has no right-anchored glyph", backspace.compoundDrawables[2])
        for (control in listOf(clear, backspace)) {
            val glyph = requireNotNull(control.compoundDrawables[0])
            val center = inkCenter(glyph)
            assertEquals(glyph.bounds.exactCenterX(), center.first, 0.6f)
            assertEquals(glyph.bounds.exactCenterY(), center.second, 0.6f)
        }
    }

    @Test fun symbols_actions_stack_beside_the_grid_rows_in_ltr_and_rtl() {
        for (layoutDirection in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            for (width in listOf(360, 480)) {
                val view = SymbolsView(ctx).apply {
                    this.layoutDirection = layoutDirection
                    recentProvider = { (1..7).map(Int::toString) }
                    applyPalette(ImePalette.STATIC_LIGHT)
                    refresh()
                }
                layout(view, width)
                val back = view.backBtnForTest()
                val clear = view.clearBtnForTest()
                val backspace = view.backspaceBtnForTest()
                val controls = listOf(back, clear, view.lockBtnForTest(), backspace)
                assertActionColumn(
                    view,
                    back,
                    clear,
                    view.lockBtnForTest(),
                    backspace,
                    view.cellHeightForTest(),
                    view.gridViewportForTest(),
                    "SymbolsView",
                )
                assertControlsUseKeySurfaces(controls, "SymbolsView")
                controls.forEachIndexed { index, control -> assertPressStateLayer(control, "SymbolsView control $index") }
                view.applyPalette(ImePalette.STATIC_DARK)
                assertControlsUseKeySurfaces(controls, "SymbolsView")
                view.toggleLockForTest()
                shadowOf(Looper.getMainLooper()).idle()
                assertControlsUseKeySurfaces(controls, "SymbolsView locked")
                view.applyPalette(ImePalette.STATIC_LIGHT)
                assertControlsUseKeySurfaces(controls, "SymbolsView locked light")
                view.toggleLockForTest()
                shadowOf(Looper.getMainLooper()).idle()
                assertControlsUseKeySurfaces(controls, "SymbolsView unlocked light")
            }
        }
    }

    @Test fun emoji_actions_stack_beside_the_grid_rows_in_ltr_and_rtl() {
        for (layoutDirection in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            for (width in listOf(360, 480)) {
                val view = EmojiView(ctx).apply {
                    this.layoutDirection = layoutDirection
                    recentProvider = { (1..7).map(Int::toString) }
                    applyPalette(ImePalette.STATIC_LIGHT)
                    refresh()
                }
                layout(view, width)
                val back = view.backBtnForTest()
                val clear = view.clearBtnForTest()
                val backspace = view.backspaceBtnForTest()
                val controls = listOf(back, clear, view.lockBtnForTest(), backspace)
                assertActionColumn(
                    view,
                    back,
                    clear,
                    view.lockBtnForTest(),
                    backspace,
                    view.cellHeightForTest(),
                    view.gridViewportForTest(),
                    "EmojiView",
                )
                assertControlsUseKeySurfaces(controls, "EmojiView")
                controls.forEachIndexed { index, control -> assertPressStateLayer(control, "EmojiView control $index") }
                view.applyPalette(ImePalette.STATIC_DARK)
                assertControlsUseKeySurfaces(controls, "EmojiView")
                view.toggleLockForTest()
                shadowOf(Looper.getMainLooper()).idle()
                assertControlsUseKeySurfaces(controls, "EmojiView locked")
                view.applyPalette(ImePalette.STATIC_LIGHT)
                assertControlsUseKeySurfaces(controls, "EmojiView locked light")
                view.toggleLockForTest()
                shadowOf(Looper.getMainLooper()).idle()
                assertControlsUseKeySurfaces(controls, "EmojiView unlocked light")
            }
        }
    }

    @Test fun the_symbol_and_emoji_actions_tile_their_column_and_each_stays_clickable() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var symbolBack = 0
        var symbolBackspace = 0
        val symbols = SymbolsView(ctx).apply {
            onBack = { symbolBack++ }
            onBackspace = { symbolBackspace++ }
        }
        controller.get().setContentView(symbols)
        layout(symbols, 360)
        val symbolControls = listOf(
            symbols.backBtnForTest(),
            symbols.clearBtnForTest(),
            symbols.lockBtnForTest(),
            symbols.backspaceBtnForTest(),
        )
        assertActionsTileTheColumn(symbols, symbolControls)
        assertEquals(0, symbolBack)
        assertEquals(0, symbolBackspace)
        assertFalse(symbols.clearDialogVisibleForTest())
        assertFalse(symbols.lockedForTest())
        tapControl(symbols, symbolControls[0])
        tapControl(symbols, symbolControls[1])
        assertTrue(symbols.clearDialogVisibleForTest())
        assertTrue(symbols.dismissClearForTest())
        tapControl(symbols, symbolControls[2])
        tapControl(symbols, symbolControls[3])
        assertEquals(1, symbolBack)
        assertEquals(1, symbolBackspace)
        assertTrue(symbols.lockedForTest())

        var emojiBack = 0
        var emojiBackspace = 0
        val emoji = EmojiView(ctx).apply {
            onBack = { emojiBack++ }
            onBackspace = { emojiBackspace++ }
        }
        controller.get().setContentView(emoji)
        layout(emoji, 360)
        val emojiControls = listOf(
            emoji.backBtnForTest(),
            emoji.clearBtnForTest(),
            emoji.lockBtnForTest(),
            emoji.backspaceBtnForTest(),
        )
        assertActionsTileTheColumn(emoji, emojiControls)
        assertEquals(0, emojiBack)
        assertEquals(0, emojiBackspace)
        assertFalse(emoji.clearDialogVisibleForTest())
        assertFalse(emoji.lockedForTest())
        tapControl(emoji, emojiControls[0])
        tapControl(emoji, emojiControls[1])
        assertTrue(emoji.clearDialogVisibleForTest())
        assertTrue(emoji.dismissClearForTest())
        tapControl(emoji, emojiControls[2])
        tapControl(emoji, emojiControls[3])
        assertEquals(1, emojiBack)
        assertEquals(1, emojiBackspace)
        assertTrue(emoji.lockedForTest())
    }
}
