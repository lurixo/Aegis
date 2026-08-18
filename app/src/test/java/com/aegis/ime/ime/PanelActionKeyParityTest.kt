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
import android.content.res.Configuration
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.aegis.ime.ime.theme.ImePalette
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class PanelActionKeyParityTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density
    private val activities = ArrayList<org.robolectric.android.controller.ActivityController<Activity>>()

    @After fun destroyActivities() {
        activities.asReversed().forEach { it.pause().stop().destroy() }
        activities.clear()
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun layout(view: View, widthDp: Int, heightDp: Int = 320) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controller.get().setContentView(view)
        activities += controller
        relayout(view, widthDp, heightDp)
    }

    private fun relayout(view: View, widthDp: Int, heightDp: Int = 320) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(heightDp), View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun event(action: Int, x: Float, y: Float, time: Long = 0L): MotionEvent =
        MotionEvent.obtain(0, time, action, x, y, 0)

    private fun assertSharedPanelGeometry(
        testContext: android.content.Context,
        widthDp: Int,
        palette: ImePalette,
    ) {
        val emoji = EmojiView(testContext).apply {
            recentProvider = { listOf("🙂") }
            applyPalette(palette)
            refresh()
        }
        val symbols = SymbolsView(testContext).apply {
            recentProvider = { listOf("+") }
            applyPalette(palette)
            refresh()
        }
        layout(emoji, widthDp)
        layout(symbols, widthDp)
        relayout(emoji, widthDp)
        relayout(symbols, widthDp)
        val emojiCell = requireNotNull(emoji.gridCellForTest(0))
        val symbolCell = requireNotNull(symbols.gridCellForTest("+"))

        assertEquals("panels choose the same column count", emoji.gridColumnCountForTest(), symbols.gridColumnCountForTest())
        assertEquals("panels choose the same outer cell width", emojiCell.width, symbolCell.width)
    }

    @Test fun emoji_and_symbol_grids_adapt_columns_to_keep_full_48dp_input_targets() {
        for (width in listOf(280, 320, 360, 411, 480)) {
            val emoji = EmojiView(context).apply {
                recentProvider = { (1..14).map { "emoji-$it" } }
                refresh()
            }
            layout(emoji, width)
            val emojiCell = requireNotNull(emoji.gridCellForTest(0))
            assertTrue("emoji $width dp cell width", emojiCell.width >= dp(48))
            assertTrue("emoji $width dp cell height", emojiCell.height >= dp(48))
            assertTrue(emoji.gridColumnCountForTest() in 1..7)

            val symbols = SymbolsView(context).apply {
                recentProvider = { (1..14).map { "symbol-$it" } }
                refresh()
            }
            layout(symbols, width)
            val symbolCell = requireNotNull(symbols.gridCellForTest("symbol-1"))
            assertTrue("symbol $width dp cell width", symbolCell.width >= dp(48))
            assertTrue("symbol $width dp cell height", symbolCell.height >= dp(48))
            assertTrue(symbols.gridColumnCountForTest() in 1..7)
        }
    }

    @Test fun sparse_emoji_and_symbol_rows_keep_the_same_cell_width_as_full_grids() {
        for (width in listOf(280, 320, 360, 411, 480)) {
            val fullEmoji = EmojiView(context).apply {
                recentProvider = { (1..14).map { "emoji-$it" } }
                refresh()
            }
            layout(fullEmoji, width)
            val emojiWidth = requireNotNull(fullEmoji.gridCellForTest(0)).width
            val emojiColumns = fullEmoji.gridColumnCountForTest()

            for (count in listOf(1, (emojiColumns - 1).coerceAtLeast(1))) {
                val sparse = EmojiView(context).apply {
                    recentProvider = { (1..count).map { "emoji-$it" } }
                    refresh()
                }
                layout(sparse, width)
                assertEquals("emoji $width dp count=$count", emojiWidth, requireNotNull(sparse.gridCellForTest(0)).width)
            }
            val fullSymbols = SymbolsView(context).apply {
                recentProvider = { (1..14).map { "symbol-$it" } }
                refresh()
            }
            layout(fullSymbols, width)
            val symbolWidth = requireNotNull(fullSymbols.gridCellForTest("symbol-1")).width
            val symbolColumns = fullSymbols.gridColumnCountForTest()

            for (count in listOf(1, (symbolColumns - 1).coerceAtLeast(1))) {
                val sparse = SymbolsView(context).apply {
                    recentProvider = { (1..count).map { "symbol-$it" } }
                    refresh()
                }
                layout(sparse, width)
                assertEquals(
                    "symbol $width dp count=$count",
                    symbolWidth,
                    requireNotNull(sparse.gridCellForTest("symbol-1")).width,
                )
            }
        }
    }

    @Test fun emoji_and_symbol_surfaces_share_exact_geometry_across_widths_palettes_and_font_scale() {
        for (fontScale in listOf(0.85f, 1f, 1.35f)) {
            val configuration = Configuration(context.resources.configuration).apply {
                this.fontScale = fontScale
            }
            val scaledContext = context.createConfigurationContext(configuration)
            for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
                for (width in listOf(320, 360, 411, 480)) {
                    assertSharedPanelGeometry(scaledContext, width, palette)
                }
            }
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "xxhdpi")
    fun emoji_and_symbol_surfaces_share_exact_geometry_at_high_density() {
        assertSharedPanelGeometry(context, 360, ImePalette.STATIC_LIGHT)
        assertSharedPanelGeometry(context, 411, ImePalette.STATIC_DARK)
    }

    @Test fun emoji_and_symbol_input_cells_use_static_key_faces_haptics_and_edge_hit_cells() {
        var emojiCommits = 0
        val emoji = EmojiView(context).apply {
            recentProvider = { listOf("🙂") }
            onEmoji = { emojiCommits++ }
            hapticEnabled = true
            refresh()
        }
        layout(emoji, 360)
        val emojiCell = requireNotNull(emoji.gridCellForTest(0))
        assertNotNull(emojiCell.background)
        assertFalse(emojiCell.background is RippleDrawable)
        emojiCell.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0.5f, emojiCell.height / 2f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
        assertEquals(1f, emoji.gridCellFeedbackLevelForTest(0), 0f)
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(emojiCell).lastHapticFeedbackPerformed())
        emojiCell.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 0.5f, emojiCell.height / 2f, 20L))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, emojiCommits)

        var symbolCommits = 0
        val symbols = SymbolsView(context).apply {
            recentProvider = { listOf("+") }
            onSymbol = { _, _ -> symbolCommits++ }
            hapticEnabled = true
            refresh()
        }
        layout(symbols, 360)
        val symbolCell = requireNotNull(symbols.gridCellForTest("+"))
        val margins = symbolCell.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(0, margins.leftMargin)
        assertEquals(0, margins.topMargin)
        assertEquals(0, margins.rightMargin)
        assertEquals(0, margins.bottomMargin)
        assertNotNull(symbolCell.background)
        assertFalse(symbolCell.background is RippleDrawable)
        symbolCell.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, symbolCell.width - 0.5f, symbolCell.height / 2f, 30L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
        assertEquals(1f, symbols.gridCellFeedbackLevelForTest("+"), 0f)
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(symbolCell).lastHapticFeedbackPerformed())
        symbolCell.dispatchTouchEvent(event(MotionEvent.ACTION_UP, symbolCell.width - 0.5f, symbolCell.height / 2f, 50L))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, symbolCommits)
    }

    @Test fun emoji_and_symbol_backspace_controls_share_repeat_swipe_and_haptic_behavior() {
        val emojiRepeats = ArrayList<Unit>()
        val emojiSwipes = ArrayList<Boolean>()
        val emoji = EmojiView(context).apply {
            hapticEnabled = true
            onBackspace = { emojiRepeats += Unit }
            onBackspaceSwipe = { emojiSwipes += it }
        }
        layout(emoji, 360)
        exerciseBackspace(emoji.backspaceBtnForTest(), emojiRepeats, emojiSwipes)

        val symbolRepeats = ArrayList<Unit>()
        val symbolSwipes = ArrayList<Boolean>()
        val symbols = SymbolsView(context).apply {
            hapticEnabled = true
            onBackspace = { symbolRepeats += Unit }
            onBackspaceSwipe = { symbolSwipes += it }
        }
        layout(symbols, 360)
        exerciseBackspace(symbols.backspaceBtnForTest(), symbolRepeats, symbolSwipes)
    }

    @Test fun expanded_candidate_actions_use_the_same_static_face_and_haptic_policy() {
        var closes = 0
        val panel = CandidateGridView(context).apply {
            hapticEnabled = true
            onClose = { closes++ }
        }
        layout(panel, 360)
        for (button in listOf(panel.returnButtonForTest(), panel.backspaceButtonForTest(), panel.clearButtonForTest())) {
            assertNotNull(button.background)
            assertFalse(button.background is RippleDrawable)
        }
        val button = panel.returnButtonForTest()
        button.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, button.width / 2f, button.height / 2f))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(button).lastHapticFeedbackPerformed())
        button.dispatchTouchEvent(event(MotionEvent.ACTION_UP, button.width / 2f, button.height / 2f, 20L))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, closes)
    }

    private fun exerciseBackspace(button: View, repeats: MutableList<Unit>, swipes: MutableList<Boolean>) {
        val x = button.width / 2f
        val y = button.height / 2f
        button.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(button).lastHapticFeedbackPerformed())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BackspaceGesture.REPEAT_DELAY_MS))
        assertEquals(1, repeats.size)
        button.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x, y, 450L))
        assertEquals(1, repeats.size)

        button.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, button.height * 0.8f, 500L))
        button.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x, button.height * 0.1f, 520L))
        assertEquals(listOf(true), swipes)
        assertEquals(1, repeats.size)
    }
}
