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
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.layout.SymbolCatalog
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

    private fun surface(view: View): ImeKeySurface = view.background as ImeKeySurface

    private fun faceBounds(root: ViewGroup, view: View): RectF {
        val outer = Rect(0, 0, view.width, view.height)
        root.offsetDescendantRectToMyCoords(view, outer)
        return surface(view).faceBoundsForTest(view.width, view.height).apply {
            offset(outer.left.toFloat(), outer.top.toFloat())
        }
    }

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
        val metrics = ImePanelSurfaceMetrics.resolve(emoji.resources.displayMetrics.density)
        val emojiTab = emoji.railTabForTest(0)
        val symbolTab = symbols.railTabForTest(0)
        val emojiCell = requireNotNull(emoji.gridCellForTest(0))
        val symbolCell = requireNotNull(symbols.gridCellForTest("+"))
        val emojiTabFace = faceBounds(emoji, emojiTab)
        val symbolTabFace = faceBounds(symbols, symbolTab)
        val emojiCellFace = faceBounds(emoji, emojiCell)
        val symbolCellFace = faceBounds(symbols, symbolCell)

        assertEquals("panels choose the same column count", emoji.gridColumnCountForTest(), symbols.gridColumnCountForTest())
        assertEquals("panels choose the same outer cell width", emojiCell.width, symbolCell.width)
        assertEquals(metrics.railWidthPx, emojiTab.width)
        assertEquals(metrics.railWidthPx, symbolTab.width)
        assertEquals(metrics.faceHeightPx, emojiTab.height)
        assertEquals(metrics.faceHeightPx, symbolTab.height)
        assertEquals(metrics.topFaceOffsetPx.toFloat(), emojiTabFace.top, 0f)
        assertEquals(metrics.topFaceOffsetPx.toFloat(), symbolTabFace.top, 0f)
        assertEquals(metrics.topFaceOffsetPx.toFloat(), emojiCellFace.top, 0f)
        assertEquals(metrics.topFaceOffsetPx.toFloat(), symbolCellFace.top, 0f)
        assertEquals(emojiTabFace.top, emojiCellFace.top, 0f)
        assertEquals(symbolTabFace.top, symbolCellFace.top, 0f)
        assertEquals(emojiTabFace.bottom, emojiCellFace.bottom, 0f)
        assertEquals(symbolTabFace.bottom, symbolCellFace.bottom, 0f)
        for ((name, face) in listOf(
            "emoji category" to emojiTabFace,
            "symbol category" to symbolTabFace,
            "emoji cell" to emojiCellFace,
            "symbol cell" to symbolCellFace,
        )) {
            assertEquals("$name face height", metrics.faceHeightPx.toFloat(), face.height(), 0f)
        }
        assertEquals(emojiCellFace.width(), symbolCellFace.width(), 0f)
        assertEquals(surface(emojiTab).cornerRadiusPx, surface(symbolTab).cornerRadiusPx, 0f)
        assertEquals(surface(emojiTab).cornerRadiusPx, surface(emojiCell).cornerRadiusPx, 0f)
        assertEquals(surface(symbolTab).cornerRadiusPx, surface(symbolCell).cornerRadiusPx, 0f)
        assertEquals(palette.keySurface, surface(emojiTab).faceColor)
        assertEquals(palette.keySurface, surface(symbolTab).faceColor)
        assertEquals(palette.keySurface, surface(emojiCell).faceColor)
        assertEquals(palette.keySurface, surface(symbolCell).faceColor)

        val emojiUnselected = emoji.railTabForTest(1)
        val symbolUnselected = symbols.railTabForTest(1)
        assertEquals(metrics.railWidthPx, emojiUnselected.width)
        assertEquals(metrics.railWidthPx, symbolUnselected.width)
        assertEquals(metrics.faceHeightPx, emojiUnselected.height)
        assertEquals(metrics.faceHeightPx, symbolUnselected.height)
        assertEquals(Color.TRANSPARENT, surface(emojiUnselected).faceColor)
        assertEquals(Color.TRANSPARENT, surface(symbolUnselected).faceColor)
    }

    private data class TabGeometry(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val leftMargin: Int,
        val topMargin: Int,
        val rightMargin: Int,
        val bottomMargin: Int,
    )

    private fun railTabs(first: TextView): List<TextView> {
        val rail = first.parent as ViewGroup
        return (0 until rail.childCount).map { rail.getChildAt(it) as TextView }
    }

    private fun geometry(tab: TextView): TabGeometry {
        val margins = tab.layoutParams as ViewGroup.MarginLayoutParams
        return TabGeometry(
            tab.left,
            tab.top,
            tab.right,
            tab.bottom,
            tab.paddingLeft,
            tab.paddingTop,
            tab.paddingRight,
            tab.paddingBottom,
            margins.leftMargin,
            margins.topMargin,
            margins.rightMargin,
            margins.bottomMargin,
        )
    }

    private fun assertRailState(
        name: String,
        tabs: List<TextView>,
        selected: Int,
        palette: ImePalette,
        bottomActions: List<View>,
    ) {
        assertEquals("$name keeps exactly one selected category", 1, tabs.count { it.isSelected })
        tabs.forEachIndexed { index, tab ->
            assertEquals("$name category $index selected state", index == selected, tab.isSelected)
            val surface = tab.background as? ImeKeySurface
            assertNotNull("$name category $index uses the shared key surface", surface)
            assertEquals(
                "$name category $index resting face",
                if (index == selected) palette.keySurface else Color.TRANSPARENT,
                requireNotNull(surface).faceColor,
            )
            assertFalse("$name category $index has no platform ripple", tab.foreground is RippleDrawable)
        }
        for ((index, action) in bottomActions.withIndex()) {
            assertTrue("$name bottom action $index keeps the shared key surface", action.background is ImeKeySurface)
            assertFalse("$name bottom action $index has no platform ripple", action.foreground is RippleDrawable)
        }
    }

    private fun assertEveryRailSelection(
        name: String,
        panel: View,
        tabs: List<TextView>,
        palette: ImePalette,
        bottomActions: List<View>,
        select: (Int) -> Unit,
    ) {
        val initialGeometry = tabs.map(::geometry)
        assertRailState(name, tabs, selected = 0, palette, bottomActions)
        for (selected in (1 until tabs.size).toList() + 0) {
            select(selected)
            shadowOf(Looper.getMainLooper()).idle()
            relayout(panel, 360)
            assertRailState("$name selected $selected", tabs, selected, palette, bottomActions)
            assertEquals(
                "$name category geometry stays fixed when selecting $selected",
                initialGeometry,
                tabs.map(::geometry),
            )
        }
    }

    private fun exercisePressLifecycle(
        name: String,
        tab: TextView,
        level: () -> Float,
    ) {
        val x = tab.width / 2f
        val y = tab.height / 2f
        tab.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
        assertEquals("$name press reaches the shared level", 1f, level(), 0f)
        assertTrue("$name is pressed while the pointer stays inside", tab.isPressed)

        tab.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, -tab.width.toFloat(), y, 10L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
        assertEquals("$name moving outside releases the shared level", 0f, level(), 0f)
        assertFalse("$name moving outside clears pressed state", tab.isPressed)

        tab.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, -tab.width.toFloat(), y, 20L))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("$name move-out cancellation stays released", 0f, level(), 0f)

        tab.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y, 30L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_IN))
        assertEquals("$name second press reaches the shared level", 1f, level(), 0f)
        tab.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, x, y, 40L))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Motion.PRESS_OUT))
        assertEquals("$name cancellation releases the shared level", 0f, level(), 0f)
        assertFalse("$name cancellation clears pressed state", tab.isPressed)
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

    @Test fun every_emoji_and_symbol_category_starts_on_the_shared_face_geometry() {
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val emoji = EmojiView(context).apply {
                recentProvider = { listOf("🙂") }
                applyPalette(palette)
                refresh()
            }
            layout(emoji, 360)
            val metrics = ImePanelSurfaceMetrics.resolve(emoji.resources.displayMetrics.density)
            var emojiFaceWidth: Float? = null
            for (index in 0..EmojiCatalog.categories.size) {
                emoji.openCategoryForTest(index)
                shadowOf(Looper.getMainLooper()).idle()
                relayout(emoji, 360)
                assertEquals("emoji category $index starts at the top", 0, emoji.gridScrollYForTest())
                val tabFace = faceBounds(emoji, emoji.railTabForTest(0))
                val cellFace = faceBounds(emoji, requireNotNull(emoji.gridCellForTest(0)))
                assertEquals(metrics.topFaceOffsetPx.toFloat(), tabFace.top, 0f)
                assertEquals(tabFace.top, cellFace.top, 0f)
                assertEquals(tabFace.bottom, cellFace.bottom, 0f)
                emojiFaceWidth?.let { assertEquals("emoji category $index cell width", it, cellFace.width(), 0f) }
                    ?: run { emojiFaceWidth = cellFace.width() }
            }

            val symbols = SymbolsView(context).apply {
                recentProvider = { listOf("+") }
                applyPalette(palette)
                refresh()
            }
            layout(symbols, 360)
            var symbolFaceWidth: Float? = null
            for (index in 0..SymbolCatalog.categories.size) {
                symbols.openCategoryForTest(index)
                shadowOf(Looper.getMainLooper()).idle()
                relayout(symbols, 360)
                assertEquals("symbol category $index starts at the top", 0, symbols.gridScrollYForTest())
                val symbol = if (index == 0) "+" else SymbolCatalog.categories[index - 1].symbols.first { it.length == 1 }
                val tabFace = faceBounds(symbols, symbols.railTabForTest(0))
                val cellFace = faceBounds(symbols, requireNotNull(symbols.gridCellForTest(symbol)))
                assertEquals(metrics.topFaceOffsetPx.toFloat(), tabFace.top, 0f)
                assertEquals(tabFace.top, cellFace.top, 0f)
                assertEquals(tabFace.bottom, cellFace.bottom, 0f)
                symbolFaceWidth?.let { assertEquals("symbol category $index cell width", it, cellFace.width(), 0f) }
                    ?: run { symbolFaceWidth = cellFace.width() }
            }
            assertEquals(emojiFaceWidth, symbolFaceWidth)
        }
    }

    @Test fun multi_span_symbols_use_the_shared_base_face_width_and_gap() {
        val symbols = SymbolsView(context).apply {
            applyPalette(ImePalette.STATIC_LIGHT)
            openCategoryForTest(SymbolCatalog.categories.indexOfFirst { it.id == "net" } + 1)
        }
        layout(symbols, 360)
        val single = requireNotNull(symbols.gridCellForTest("."))
        val wide = requireNotNull(symbols.gridCellForTest("https://"))
        val metrics = ImePanelSurfaceMetrics.resolve(symbols.resources.displayMetrics.density)
        val singleFace = surface(single).faceBoundsForTest(single.width, single.height)
        val wideFace = surface(wide).faceBoundsForTest(wide.width, wide.height)
        assertEquals(metrics.faceWidth(single.width), singleFace.width().toInt())
        assertEquals(metrics.faceWidth(single.width, span = 2), wideFace.width().toInt())
        assertEquals(singleFace.width() * 2 + metrics.faceInsetPx * 2, wideFace.width(), 0f)
        assertEquals(metrics.faceHeightPx.toFloat(), singleFace.height(), 0f)
        assertEquals(metrics.faceHeightPx.toFloat(), wideFace.height(), 0f)
    }

    @Test fun emoji_and_symbol_category_tabs_share_action_surfaces_selection_and_stable_geometry() {
        for (palette in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val emoji = EmojiView(context).apply { applyPalette(palette) }
            layout(emoji, 360)
            val emojiTabs = railTabs(emoji.railTabForTest(0))
            assertEveryRailSelection(
                "emoji",
                emoji,
                emojiTabs,
                palette,
                listOf(
                    emoji.backBtnForTest(),
                    emoji.clearBtnForTest(),
                    emoji.lockBtnForTest(),
                    emoji.backspaceBtnForTest(),
                ),
                emoji::openCategoryForTest,
            )

            val symbols = SymbolsView(context).apply { applyPalette(palette) }
            layout(symbols, 360)
            val symbolTabs = railTabs(symbols.railTabForTest(0))
            assertEveryRailSelection(
                "symbols",
                symbols,
                symbolTabs,
                palette,
                listOf(
                    symbols.backBtnForTest(),
                    symbols.clearBtnForTest(),
                    symbols.lockBtnForTest(),
                    symbols.backspaceBtnForTest(),
                ),
                symbols::openCategoryForTest,
            )
        }
    }

    @Test fun emoji_and_symbol_category_tabs_share_press_haptics_and_single_click_dispatch() {
        var emojiRecentReads = 0
        val emoji = EmojiView(context).apply {
            hapticEnabled = true
            recentProvider = {
                emojiRecentReads++
                listOf("🙂")
            }
        }
        layout(emoji, 360)
        val emojiTab = emoji.railTabForTest(1)
        exercisePressLifecycle("emoji category", emojiTab) { emoji.railTabFeedbackLevelForTest(1) }
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(emojiTab).lastHapticFeedbackPerformed())
        assertEquals("cancelled emoji category gestures do not switch", 0, emoji.selectedCategoryForTest())

        emoji.openCategoryForTest(1)
        shadowOf(Looper.getMainLooper()).idle()
        emojiRecentReads = 0
        val emojiRecent = emoji.railTabForTest(0)
        emojiRecent.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, emojiRecent.width / 2f, emojiRecent.height / 2f, 50L))
        emojiRecent.dispatchTouchEvent(event(MotionEvent.ACTION_UP, emojiRecent.width / 2f, emojiRecent.height / 2f, 60L))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("emoji recent category is selected by one real click", 0, emoji.selectedCategoryForTest())
        assertEquals("emoji click dispatches its category action once", 1, emojiRecentReads)

        var symbolRecentReads = 0
        val symbols = SymbolsView(context).apply {
            hapticEnabled = true
            recentProvider = {
                symbolRecentReads++
                listOf("+")
            }
        }
        layout(symbols, 360)
        val symbolTab = symbols.railTabForTest(1)
        exercisePressLifecycle("symbol category", symbolTab) { symbols.railTabFeedbackLevelForTest(1) }
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, shadowOf(symbolTab).lastHapticFeedbackPerformed())
        assertEquals("cancelled symbol category gestures do not switch", 0, symbols.selectedCategoryForTest())

        symbols.openCategoryForTest(1)
        shadowOf(Looper.getMainLooper()).idle()
        symbolRecentReads = 0
        val symbolRecent = symbols.railTabForTest(0)
        symbolRecent.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, symbolRecent.width / 2f, symbolRecent.height / 2f, 70L))
        symbolRecent.dispatchTouchEvent(event(MotionEvent.ACTION_UP, symbolRecent.width / 2f, symbolRecent.height / 2f, 80L))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("symbol recent category is selected by one real click", 0, symbols.selectedCategoryForTest())
        assertEquals("symbol click dispatches its category action once", 1, symbolRecentReads)
    }

    @Test fun emoji_and_symbol_category_haptics_follow_the_panel_toggle() {
        val emoji = EmojiView(context).apply { hapticEnabled = false }
        layout(emoji, 360)
        val emojiTab = emoji.railTabForTest(1)
        emojiTab.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, emojiTab.width / 2f, emojiTab.height / 2f))
        assertEquals(-1, shadowOf(emojiTab).lastHapticFeedbackPerformed())
        emojiTab.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, emojiTab.width / 2f, emojiTab.height / 2f, 10L))

        val symbols = SymbolsView(context).apply { hapticEnabled = false }
        layout(symbols, 360)
        val symbolTab = symbols.railTabForTest(1)
        symbolTab.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, symbolTab.width / 2f, symbolTab.height / 2f, 20L))
        assertEquals(-1, shadowOf(symbolTab).lastHapticFeedbackPerformed())
        symbolTab.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, symbolTab.width / 2f, symbolTab.height / 2f, 30L))
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
