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

import com.aegis.ime.user.asClipEntries
import com.aegis.ime.user.clipEntries
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aegis.ime.LandscapeImeWindowPolicy
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w853dp-h388dp-land-hdpi")
class LandscapeHeight388ConstraintTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    @Test fun alpha_edit_candidate_and_enter_are_inside_real_at_most_window_and_clickable() {
        val keys = mutableListOf<KeyAction>()
        var picked = -1
        var confirmed = false
        val iv = InputView(ctx).apply {
            simulateNavInsetForTest(24)
            showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
            showCandidates(listOf("你", "泥", "逆"), "ni", emptyList())
            showEditBar(true)
            onKey = { keys += it.action }
            onPickCandidate = { picked = it }
            onEditConfirm = { confirmed = true }
        }
        val activity = attachToActivity(iv)
        layoutAtMost(iv, dp(853), dp(388))

        assertEquals("root must consume, not exceed, its 582px cap", 582, iv.measuredHeight)
        assertEquals(iv.height, iv.dockSurfaceBottomPx())
        assertEquals(24, iv.dockHeightSpecForTest()!!.navBottom)
        assertEquals("the shorter four-row keyboard affords a partial aesthetic raise", dp(12), iv.dockHeightSpecForTest()!!.bottomExtra)
        assertFalse("h388 remains above the declared emergency minimum", iv.dockHeightSpecForTest()!!.emergency)
        assertVerticalBounds(iv)

        val first = requireNotNull(iv.keyboardLabelBoundsForTest("q"))
        val enter = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.ENTER))
        assertRectInsideSurface(iv, first)
        assertRectInsideSurface(iv, enter)
        assertTrue("first row keeps a usable >=28dp face", first.height() >= dp(28))
        assertTrue("last-row Enter keeps a usable >=28dp face", enter.height() >= dp(28))

        assertTrue(iv.tapKeyboardLabelForTest("q"))
        assertTrue(iv.tapKeyboardActionForTest(KeyAction.ENTER))
        assertEquals(listOf(KeyAction.COMMIT, KeyAction.ENTER), keys)
        assertTrue(iv.tapFirstCandidateForTest())
        assertEquals(0, picked)
        assertTrue(dispatchRootTap(iv, iv.editConfirmBoundsForTest(), iv.editBarForTest().confirmButtonForTest()))
        flushPostedClicks()
        assertTrue("confirm callback missing; bounds=${iv.editConfirmBoundsForTest()}, edit=[${iv.editBarVisualLeftPx()},${iv.editBarVisualTopPx()}..${iv.editBarVisualRightPx()},${iv.editBarVisualBottomPx()}]", confirmed)

        val surface = iv.dockSurfaceBoundsInWindow()
        val insets = LandscapeImeWindowPolicy.resolve(
            compactLandscape = iv.isCompactLandscapeDock(),
            normalTop = iv.barTopInsetPx(),
            windowBottom = iv.height,
            surfaceBounds = surface,
        )
        assertEquals(iv.height, insets.contentTop)
        assertEquals(surface, insets.touchableRegion)
        val region = requireNotNull(insets.touchableRegion)
        assertTrue(region.bottom <= iv.height)
        assertTrue(region.right <= iv.width)
        activity.pause().stop().destroy()
    }

    @Test fun nine_nav_cutout_edit_candidate_first_last_and_enter_are_region_bound_and_clickable_at_most_and_exactly() {
        val config = ctx.resources.configuration
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, config.orientation)
        assertEquals("configuration must remain at the h388 qualifier", 853, config.screenWidthDp)
        assertEquals(388, config.screenHeightDp)
        assertEquals(1.5f, density, 0f)

        val emitted = mutableListOf<Key>()
        val picked = mutableListOf<Int>()
        var confirms = 0
        val iv = InputView(ctx).apply {
            showKeyboard(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = true), false, false, Lang.CN)
            showCandidates(listOf("你", "泥", "逆"), "ni", listOf("ni"))
            showEditBar(true)
            onKey = emitted::add
            onPickCandidate = picked::add
            onEditConfirm = { confirms++ }
        }
        ViewCompat.dispatchApplyWindowInsets(
            iv,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(58, 0, 17, 24))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(9, 0, 9, 0))
                .build(),
        )
        val activity = attachToActivity(iv)
        try {
            fun assertGeometryAndDispatch(pass: String) {
                assertEquals("$pass width", 1280, iv.measuredWidth)
                assertEquals("$pass h388 cap", 582, iv.measuredHeight)
                assertTrue("$pass must be a right dock", iv.isCompactLandscapeDock())
                assertEquals(17, iv.bodyRightPaddingPxForTest())
                assertEquals(24 + iv.dockHeightSpecForTest()!!.bottomExtra, iv.bodyBottomPaddingPx())
                assertVerticalBounds(iv)

                val abc = requireNotNull(iv.keyboardLabelBoundsForTest("ABC"))
                val bottom123 = requireNotNull(iv.keyboardLabelBoundsForTest("123"))
                val enter = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.ENTER))
                val confirm = iv.editConfirmBoundsForTest()
                for ((name, bounds) in listOf("ABC first row" to abc, "123 last row" to bottom123, "NINE Enter" to enter)) {
                    assertRectInsideSurface(iv, bounds)
                    assertTrue("$pass $name must retain a positive touch face: $bounds", bounds.width() > 0f && bounds.height() > 0f)
                    assertRootRectInsideTouchableRegion(iv, name, bounds)
                }
                assertPanelRectInsideBody(iv, "edit confirm", confirm)
                assertRootRectInsideTouchableRegion(iv, "edit confirm", confirm)
                assertRootRectInsideTouchableRegion(
                    iv,
                    "candidate strip",
                    Rect(iv.toolbarVisualLeftPx(), iv.toolbarVisualTopPx(), iv.toolbarVisualRightPx(), iv.toolbarVisualBottomPx()),
                )

                val insets = resolveWindowInsets(iv)
                assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_REGION, insets.touchableInsets)
                val region = requireNotNull(insets.touchableRegion)
                assertEquals(iv.dockTouchableBoundsInWindow(), region)
                assertTrue("$pass composing preedit must be included", region.contains(iv.preeditSurfaceBoundsInWindow()))
                assertTrue("$pass body must be included", region.contains(iv.dockSurfaceBoundsInWindow()))
                assertFalse("$pass adjacent host pixel must pass through", region.contains(region.left - 1, region.top))

                assertTrue("$pass ABC root dispatch", iv.tapKeyboardLabelForTest("ABC"))
                assertTrue("$pass 123 root dispatch", iv.tapKeyboardLabelForTest("123"))
                assertTrue("$pass Enter root dispatch", iv.tapKeyboardActionForTest(KeyAction.ENTER))
                assertTrue("$pass candidate root dispatch", iv.tapFirstCandidateForTest())
                assertTrue("$pass edit confirm root dispatch", iv.tapEditConfirmForTest())
                flushPostedClicks()
            }

            layoutAtMost(iv, 1280, 582)
            assertGeometryAndDispatch("AT_MOST")
            layoutExactly(iv, 1280, 582)
            assertGeometryAndDispatch("EXACTLY")

            assertEquals(
                listOf(
                    KeyAction.COMMIT, KeyAction.SWITCH_NUMPAD, KeyAction.ENTER,
                    KeyAction.COMMIT, KeyAction.SWITCH_NUMPAD, KeyAction.ENTER,
                ),
                emitted.map { it.action },
            )
            assertEquals(listOf("ABC", "123", "↵", "ABC", "123", "↵"), emitted.map { it.label })
            assertEquals("T9 ABC must emit digit 2 in both measure modes", listOf("2", "2"), emitted.filter { it.label == "ABC" }.map { it.output })
            assertEquals(listOf(0, 0), picked)
            assertEquals(2, confirms)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun nine_expanded_edit_clipboard_and_phrases_panels_keep_key_actions_inside_the_live_region() {
        var expandedBackspaces = 0
        var expandedClears = 0
        var candidatePicks = 0
        val editActions = mutableListOf<EditAction>()
        val pickedPanelText = mutableListOf<String>()
        val iv = InputView(ctx).apply {
            showKeyboard(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = true), false, false, Lang.CN)
            showCandidates((1..40).map { "候选$it" }, "nihao", (1..12).map { "reading$it" })
            showEditBar(true)
            onPanelBackspace = { expandedBackspaces++ }
            onPanelClear = { expandedClears++ }
            onPickCandidate = { candidatePicks++ }
        }
        ViewCompat.dispatchApplyWindowInsets(
            iv,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(58, 0, 17, 24))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(9, 0, 9, 0))
                .build(),
        )
        val activity = attachToActivity(iv)
        try {
            layoutAtMost(iv, 1280, 582)
            assertTrue("candidate must dispatch before opening a panel", iv.tapFirstCandidateForTest())
            flushPostedClicks()
            assertEquals(1, candidatePicks)

            iv.showExpandedCandidates()
            layoutAtMost(iv, 1280, 582)
            settleUiAnimations()
            val grid = iv.expandedGridForTest()
            assertTrue(iv.isPanelShowing(grid))
            val expandedSpec = iv.dockHeightSpecForTest()!!
            assertEquals(expandedSpec.keyboardHeight + expandedSpec.barHeight, iv.panelHeightPx())
            assertPanelControlsInside(iv)
            val expandedControls = iv.expandedPanelControlBoundsForTest()
            expandedControls.forEachIndexed { index, bounds ->
                assertRootRectInsideTouchableRegion(iv, "expanded control $index", bounds)
            }
            assertTrue(dispatchRootTap(iv, expandedControls[1], grid.backspaceButtonForTest()))
            assertTrue(dispatchRootTap(iv, expandedControls[2], grid.clearButtonForTest()))
            assertTrue(dispatchRootTap(iv, expandedControls[0], grid.returnButtonForTest()))
            flushPostedClicks()
            settleUiAnimations()
            assertEquals(1, expandedBackspaces)
            assertEquals(1, expandedClears)
            assertFalse(iv.isPanelShowing(grid))

            val edit = EditPanelView(ctx).apply { onAction = editActions::add }
            iv.showPanel(edit)
            layoutAtMost(iv, 1280, 582)
            settleUiAnimations()
            for (action in listOf(EditAction.UP, EditAction.PASTE)) {
                val target = requireNotNull(edit.actionViewForTest(action))
                edit.scrollActionIntoViewForTest(action)
                val bounds = iv.panelDescendantBoundsForTest(target)
                assertPanelRectInside(iv, "edit $action", bounds)
                assertRootRectInsideTouchableRegion(iv, "edit $action", bounds)
                assertTrue(dispatchRootTap(iv, bounds, target))
            }
            flushPostedClicks()
            assertEquals(listOf(EditAction.UP, EditAction.PASTE), editActions)
            iv.showPanel(null)
            settleUiAnimations()

            val clipboard = ClipboardView(ctx).apply {
                historyProvider = { clipEntries("clipboard-entry") }
                categoriesProvider = { listOf("Quick") }
                phrasesInProvider = { listOf("phrase-entry") }
                onPick = pickedPanelText::add
                refresh()
            }
            iv.showPanel(clipboard)
            layoutAtMost(iv, 1280, 582)
            settleUiAnimations()
            val clipTarget = requireNotNull(firstClickableDescendant(requireNotNull(clipboard.listRowViewForTest(0))))
            val clipBounds = iv.panelDescendantBoundsForTest(clipTarget)
            assertPanelRectInside(iv, "clipboard entry", clipBounds)
            assertRootRectInsideTouchableRegion(iv, "clipboard entry", clipBounds)
            assertTrue(dispatchRootTap(iv, clipBounds, clipTarget))
            flushPostedClicks()

            clipboard.showPhraseTab("Quick")
            layoutAtMost(iv, 1280, 582)
            settleUiAnimations()
            val phraseTarget = requireNotNull(firstClickableDescendant(requireNotNull(clipboard.listRowViewForTest(0))))
            val phraseBounds = iv.panelDescendantBoundsForTest(phraseTarget)
            assertPanelRectInside(iv, "phrase entry", phraseBounds)
            assertRootRectInsideTouchableRegion(iv, "phrase entry", phraseBounds)
            assertTrue(dispatchRootTap(iv, phraseBounds, phraseTarget))
            flushPostedClicks()
            assertEquals(listOf("clipboard-entry", "phrase-entry"), pickedPanelText)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun nine_composition_edit_and_expanded_panel_resize_h388_to_h291_to_near_square_and_back_without_stale_geometry() {
        val emitted = mutableListOf<Key>()
        var picked = 0
        var confirmed = 0
        val iv = InputView(ctx).apply {
            showKeyboard(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = true), false, false, Lang.CN)
            showCandidates((1..30).map { "候选$it" }, "nihao", (1..8).map { "reading$it" })
            showEditBar(true)
            onKey = emitted::add
            onPickCandidate = { picked++ }
            onEditConfirm = { confirmed++ }
        }
        ViewCompat.dispatchApplyWindowInsets(
            iv,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(58, 0, 17, 24))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(9, 0, 9, 0))
                .build(),
        )
        val activity = attachToActivity(iv)
        try {
            layoutAtMost(iv, 1280, 582)
            iv.showExpandedCandidates()
            layoutAtMost(iv, 1280, 582)
            settleUiAnimations()
            val grid = iv.expandedGridForTest()
            assertStatefulNinePanel(iv, grid, expectCompact = true)
            val initialPanelHeight = iv.panelHeightPx()

            RuntimeEnvironment.setQualifiers("w640dp-h291dp-land-hdpi")
            assertEquals(640, iv.resources.configuration.screenWidthDp)
            assertEquals(291, iv.resources.configuration.screenHeightDp)
            layoutAtMost(iv, dpRound(640), dpRound(291))
            assertStatefulNinePanel(iv, grid, expectCompact = true)
            val minimumPanelHeight = iv.panelHeightPx()
            assertTrue("h291 must recompute the open panel height", minimumPanelHeight < initialPanelHeight)

            RuntimeEnvironment.setQualifiers("w320dp-h200dp-land-hdpi")
            assertEquals(320, iv.resources.configuration.screenWidthDp)
            assertEquals(200, iv.resources.configuration.screenHeightDp)
            layoutAtMost(iv, dpRound(320), dpRound(200))
            assertStatefulNinePanel(iv, grid, expectCompact = false)
            val nearSquarePanelHeight = iv.panelHeightPx()
            assertTrue("near-square must replace, not retain, the h291 panel height", nearSquarePanelHeight < minimumPanelHeight)

            RuntimeEnvironment.setQualifiers("w853dp-h388dp-land-hdpi")
            layoutAtMost(iv, 1280, 582)
            assertStatefulNinePanel(iv, grid, expectCompact = true)
            assertEquals("restored h388 panel height must match its original live value", initialPanelHeight, iv.panelHeightPx())
            assertTrue("restored panel must not retain near-square height", iv.panelHeightPx() != nearSquarePanelHeight)

            iv.showPanel(null)
            settleUiAnimations()
            layoutAtMost(iv, 1280, 582)
            assertFalse(iv.panelShown)
            assertTrue(iv.keyboardHeightPx() > 0)
            assertEquals(iv.dockHeightSpecForTest()!!.keyboardHeight, iv.keyboardHeightPx())
            assertTrue(iv.tapKeyboardLabelForTest("ABC"))
            assertTrue(iv.tapKeyboardLabelForTest("123"))
            assertTrue(iv.tapKeyboardActionForTest(KeyAction.ENTER))
            assertTrue(iv.tapFirstCandidateForTest())
            assertTrue(iv.tapEditConfirmForTest())
            flushPostedClicks()
            assertEquals(listOf(KeyAction.COMMIT, KeyAction.SWITCH_NUMPAD, KeyAction.ENTER), emitted.map { it.action })
            assertEquals(1, picked)
            assertEquals(1, confirmed)
        } finally {
            RuntimeEnvironment.setQualifiers("w853dp-h388dp-land-hdpi")
            activity.pause().stop().destroy()
        }
    }

    @Test fun nine_stateful_overlays_keep_geometry_across_qualifier_change_and_same_view_detach_reattach() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val host = FrameLayout(controller.get())
        controller.get().setContentView(host)
        val emitted = mutableListOf<Key>()
        var panelBackspaces = 0
        var picked = 0
        var confirmed = 0
        val iv = InputView(controller.get()).apply {
            showKeyboard(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = true), false, false, Lang.CN)
            showCandidates((1..20).map { "候选$it" }, "nihao", listOf("ni", "hao"))
            showEditBar(true)
            onKey = emitted::add
            onPanelBackspace = { panelBackspaces++ }
            onPickCandidate = { picked++ }
            onEditConfirm = { confirmed++ }
        }
        host.addView(iv)
        val systemInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(58, 0, 17, 24))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(9, 0, 9, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(iv, systemInsets)
        try {
            layoutAtMost(iv, 1280, 582)
            iv.showExpandedCandidates()
            layoutAtMost(iv, 1280, 582)
            settleUiAnimations()
            val grid = iv.expandedGridForTest()
            assertStatefulNinePanel(iv, grid, expectCompact = true)

            RuntimeEnvironment.setQualifiers("w388dp-h853dp-port-hdpi")
            assertEquals(Configuration.ORIENTATION_PORTRAIT, iv.resources.configuration.orientation)
            layoutAtMost(iv, dpRound(388), dpRound(853))
            assertStatefulNinePanel(iv, grid, expectCompact = false)

            RuntimeEnvironment.setQualifiers("w853dp-h388dp-land-hdpi")
            layoutAtMost(iv, 1280, 582)
            assertStatefulNinePanel(iv, grid, expectCompact = true)

            host.removeView(iv)
            assertTrue("geometry surrogate must really detach the input view", iv.parent == null)
            assertTrue(iv.isComposing())
            assertTrue(iv.isEditBarShowing())
            assertTrue(iv.isPanelShowing(grid))
            host.addView(iv)
            ViewCompat.dispatchApplyWindowInsets(iv, systemInsets)
            layoutAtMost(iv, 1280, 582)
            settleUiAnimations()
            assertStatefulNinePanel(iv, grid, expectCompact = true)

            val controls = iv.expandedPanelControlBoundsForTest()
            assertTrue(dispatchRootTap(iv, controls[1], grid.backspaceButtonForTest()))
            assertTrue(dispatchRootTap(iv, controls[0], grid.returnButtonForTest()))
            flushPostedClicks()
            settleUiAnimations()
            assertEquals(1, panelBackspaces)
            assertFalse(iv.panelShown)

            layoutAtMost(iv, 1280, 582)
            assertTrue(iv.tapKeyboardLabelForTest("ABC"))
            assertTrue(iv.tapKeyboardLabelForTest("123"))
            assertTrue(iv.tapKeyboardActionForTest(KeyAction.ENTER))
            assertTrue(iv.tapFirstCandidateForTest())
            assertTrue(iv.tapEditConfirmForTest())
            flushPostedClicks()
            assertEquals(listOf(KeyAction.COMMIT, KeyAction.SWITCH_NUMPAD, KeyAction.ENTER), emitted.map { it.action })
            assertEquals(1, picked)
            assertEquals(1, confirmed)
            val restoredInsets = resolveWindowInsets(iv)
            assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_REGION, restoredInsets.touchableInsets)
            assertTrue(requireNotNull(restoredInsets.touchableRegion).contains(iv.preeditSurfaceBoundsInWindow()))
        } finally {
            RuntimeEnvironment.setQualifiers("w853dp-h388dp-land-hdpi")
            controller.pause().stop().destroy()
        }
    }

    @Test fun open_panel_remeasures_with_the_hidden_keyboard_and_compressed_actions_stay_touchable() {
        var backspaces = 0
        var clears = 0
        val candidates = (1..120).map { "候选$it" }
        val readings = (1..40).map { "reading$it" }
        val iv = InputView(ctx).apply {
            simulateNavInsetForTest(24)
            showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
            showCandidates(candidates, "nihao", readings)
            showEditBar(true)
            onPanelBackspace = { backspaces++ }
            onPanelClear = { clears++ }
        }
        val activity = attachToActivity(iv)
        layoutAtMost(iv, dp(853), dp(388))
        iv.showExpandedCandidates()
        layoutAtMost(iv, dp(853), dp(388))
        settleUiAnimations()
        val tallPanelHeight = iv.panelHeightPx()

        val tallSpec = iv.dockHeightSpecForTest()!!
        assertEquals(tallSpec.keyboardHeight + tallSpec.barHeight, tallPanelHeight)
        assertPanelControlsInside(iv)
        assertTrue("long candidate content scrolls in the compressed panel", iv.expandedGridForTest().gridCanScrollForwardForTest())
        assertTrue("long reading content scrolls in the compressed panel", iv.expandedGridForTest().readingCanScrollForwardForTest())

        layoutAtMost(iv, dp(853), dp(291))
        assertTrue(iv.panelHeightPx() < tallPanelHeight)
        val compressedSpec = iv.dockHeightSpecForTest()!!
        assertEquals(compressedSpec.keyboardHeight + compressedSpec.barHeight, iv.panelHeightPx())
        assertEquals(iv.height, iv.dockSurfaceBottomPx())
        assertPanelControlsInside(iv)

        val controls = iv.expandedPanelControlBoundsForTest()
        val grid = iv.expandedGridForTest()
        assertTrue(dispatchRootTap(iv, controls[1]))
        assertTrue(dispatchRootTap(iv, controls[2], grid.clearButtonForTest()))
        assertTrue("return remains reachable at the compressed top rail", dispatchRootTap(iv, controls[0], grid.returnButtonForTest()))
        flushPostedClicks()
        assertEquals(1, backspaces)
        assertEquals("clear callback missing; controls=$controls panel=[${iv.panelVisualLeftPx()},${iv.panelVisualTopPx()}..${iv.panelVisualRightPx()},${iv.panelVisualBottomPx()}]", 1, clears)
        assertFalse(iv.isPanelShowing(grid))
        activity.pause().stop().destroy()
    }

    @Test fun exact_soft_input_window_height_extends_the_surface_floor_without_stretching_keys() {
        val emitted = mutableListOf<KeyAction>()
        val iv = InputView(ctx).apply {
            simulateNavInsetForTest(24)
            showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
            onKey = { emitted += it.action }
        }
        val activity = attachToActivity(iv)
        layoutAtMost(iv, dp(853), 800)
        val naturalKeyboardHeight = iv.dockHeightSpecForTest()!!.keyboardHeight

        iv.measure(
            View.MeasureSpec.makeMeasureSpec(dp(853), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)

        assertEquals(800, iv.measuredHeight)
        assertEquals(800, iv.dockHeightSpecForTest()!!.rootHeight)
        assertEquals("EXACTLY must extend the floor, not distort the key geometry", naturalKeyboardHeight, iv.dockHeightSpecForTest()!!.keyboardHeight)
        assertEquals("the physical surface must cover the framework's exact root", 800, iv.dockSurfaceBottomPx())
        assertEquals(800, iv.dockSurfaceBoundsInWindow().bottom)
        assertTrue(iv.keyboardVisualBottomPx() <= iv.dockSurfaceBottomPx())
        assertTrue(iv.tapKeyboardActionForTest(KeyAction.ENTER))
        assertEquals(listOf(KeyAction.ENTER), emitted)
        assertVerticalBounds(iv)
        activity.pause().stop().destroy()
    }

    @Test fun bottom_display_cutout_joins_the_hard_height_budget_and_keeps_enter_above_it() {
        val emitted = mutableListOf<KeyAction>()
        val iv = InputView(ctx).apply {
            showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
            showEditBar(true)
            onKey = { emitted += it.action }
        }
        ViewCompat.dispatchApplyWindowInsets(
            iv,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 0, 0, 48))
                .build(),
        )
        layoutAtMost(iv, dp(853), dp(388))

        assertEquals("overlapping nav/cutout use the larger hard safe bottom", 48, iv.dockHeightSpecForTest()!!.navBottom)
        assertTrue(iv.bodyBottomPaddingPx() >= 48)
        assertEquals(iv.height, iv.dockSurfaceBottomPx())
        val enter = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.ENTER))
        assertRectInsideSurface(iv, enter)
        assertTrue(enter.bottom <= iv.height - 48)
        assertTrue(iv.tapKeyboardActionForTest(KeyAction.ENTER))
        assertEquals(listOf(KeyAction.ENTER), emitted)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h291dp-land-mdpi")
class MinimumHeight291ConstraintTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun alpha_and_nine_keep_minimum_faces_with_edit_nav_and_real_root_dispatch() {
        val emitted = mutableListOf<String>()
        val iv = InputView(ctx).apply {
            simulateNavInsetForTest(24)
            showEditBar(true)
            onKey = { emitted += it.label.ifEmpty { it.action.name } }
        }

        iv.showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
        layoutAtMost(iv, 640, 291)
        assertEquals(291, iv.measuredHeight)
        assertEquals(291, iv.dockSurfaceBottomPx())
        assertFalse(iv.dockHeightSpecForTest()!!.emergency)
        val alphaFirst = requireNotNull(iv.keyboardLabelBoundsForTest("q"))
        val alphaEnter = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.ENTER))
        assertTrue(alphaFirst.height() >= 28f)
        assertTrue("alpha Enter height=${alphaEnter.height()} spec=${iv.dockHeightSpecForTest()}", alphaEnter.height() >= 27.99f)
        assertRectInsideSurface(iv, alphaFirst)
        assertRectInsideSurface(iv, alphaEnter)
        assertTrue(iv.tapKeyboardLabelForTest("q"))
        assertTrue(iv.tapKeyboardActionForTest(KeyAction.ENTER))

        iv.showKeyboard(Layouts.nine(Lang.CN, Layouts.ninePunctuation()), false, false, Lang.CN)
        layoutAtMost(iv, 640, 291)
        assertEquals(291, iv.measuredHeight)
        assertEquals(291, iv.dockSurfaceBottomPx())
        assertFalse(iv.dockHeightSpecForTest()!!.emergency)
        val nineFirst = requireNotNull(iv.keyboardLabelBoundsForTest("ABC"))
        val nineEnter = requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.ENTER))
        assertTrue("fractional NINE cells retain a 32dp face", nineFirst.height() >= 32f)
        assertTrue(nineEnter.height() >= 32f)
        assertRectInsideSurface(iv, nineFirst)
        assertRectInsideSurface(iv, nineEnter)
        assertTrue(iv.tapKeyboardLabelForTest("ABC"))
        assertTrue(iv.tapKeyboardActionForTest(KeyAction.ENTER))
        assertEquals(4, emitted.size)
        assertVerticalBounds(iv)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w720dp-h360dp-land-xhdpi")
class DensityHeight360ConstraintTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun xhdpi_alpha_and_nine_fit_the_720px_height_cap_with_edit_and_nav() {
        val iv = InputView(ctx).apply {
            simulateNavInsetForTest(32)
            showEditBar(true)
        }
        val width = 1440
        val height = 720

        for (layout in listOf(
            Layouts.forId(LayoutId.ALPHA, Lang.CN),
            Layouts.nine(Lang.CN, Layouts.ninePunctuation()),
        )) {
            iv.showKeyboard(layout, false, false, Lang.CN)
            layoutAtMost(iv, width, height)
            assertEquals(height, iv.measuredHeight)
            assertEquals(height, iv.dockSurfaceBottomPx())
            assertTrue(iv.keyboardVisualBottomPx() <= iv.dockSurfaceBottomPx())
            assertTrue(iv.dockHeightSpecForTest()!!.keyboardHeight > 0)
            assertVerticalBounds(iv)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w853dp-h388dp-land-hdpi")
class WideToNarrowInsetResizeTest {

    @Test fun compact_to_full_width_resize_reapplies_left_inset_before_the_new_measure() {
        val iv = InputView(RuntimeEnvironment.getApplication())
        ViewCompat.dispatchApplyWindowInsets(
            iv,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(24, 0, 16, 24))
                .build(),
        )
        layoutAtMost(iv, 1280, 582)
        assertTrue(iv.isCompactLandscapeDock())
        assertEquals("left inset is outside the old remote dock", 6, iv.bodyLeftPaddingPxForTest())

        try {
            RuntimeEnvironment.setQualifiers("w320dp-h200dp-land-hdpi")
            layoutAtMost(iv, 480, 300)
            assertFalse(iv.isCompactLandscapeDock())
            assertEquals(0, iv.dockSurfaceLeftPx())
            assertEquals(480, iv.dockSurfaceRightPx())
            assertEquals("incoming full-width pass must protect the persistent left system inset", 24, iv.bodyLeftPaddingPxForTest())
            assertEquals(16, iv.bodyRightPaddingPxForTest())
        } finally {
            RuntimeEnvironment.setQualifiers("w853dp-h388dp-land-hdpi")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h200dp-land-mdpi")
class TinyPanelViewportConstraintTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun edit_custom_and_clipboard_important_actions_remain_inside_and_root_clickable() {
        val iv = InputView(ctx).apply {
            simulateNavInsetForTest(12)
            showEditBar(true)
        }
        val activity = attachToActivity(iv)
        layoutAtMost(iv, 320, 200)

        val editActions = mutableListOf<EditAction>()
        val edit = EditPanelView(ctx).apply { onAction = { editActions += it } }
        iv.showPanel(edit)
        layoutAtMost(iv, 320, 200)
        settleUiAnimations()
        assertEquals("real emergency panel target", 118, iv.panelHeightPx())
        assertTrue("edit actions must scroll at h200 instead of collapsing their rows", edit.actionContentCanScrollForTest())
        val actionViewport = iv.panelDescendantBoundsForTest(edit.actionViewportForTest())
        assertPanelRectInside(iv, "edit action viewport", actionViewport)
        assertTrue("at least one complete 44dp action row remains visible", actionViewport.height() >= 44)
        for (action in listOf(EditAction.BACK, EditAction.UP, EditAction.DELETE, EditAction.PASTE)) {
            val target = requireNotNull(edit.actionViewForTest(action))
            if (action != EditAction.BACK) edit.scrollActionIntoViewForTest(action)
            val fullBounds = iv.panelDescendantBoundsForTest(target)
            val visibleBounds = Rect(fullBounds)
            if (action != EditAction.BACK) {
                assertTrue("$action must intersect the real scrolled viewport", visibleBounds.intersect(actionViewport))
                assertTrue("$action must expose a >=44dp touch slice, got $visibleBounds", visibleBounds.height() >= 44)
            }
            assertPanelRectInside(iv, "$action visible bounds", visibleBounds)
            assertTrue(dispatchRootTap(iv, visibleBounds, target))
            flushPostedClicks()
        }
        assertEquals(listOf(EditAction.BACK, EditAction.UP, EditAction.DELETE, EditAction.PASTE), editActions)

        var customBack = 0
        var added = ""
        val symbols = (1..48).map { "S$it" }
        val custom = CustomSymbolPanel(ctx).apply {
            addPalette = symbols
            current = { emptyList() }
            onBack = { customBack++ }
            onAdd = { added = it }
            refresh()
        }
        iv.showPanel(custom)
        layoutAtMost(iv, 320, 200)
        settleUiAnimations()
        assertEquals(iv.panelHeightPx(), custom.height)
        assertTrue("the intact full-height symbol rows scroll in the 74px viewport", custom.contentCanScrollForwardForTest())
        custom.contentScrollForTest(Int.MAX_VALUE)
        assertTrue(custom.contentScrollYForTest() > 0)
        val lastChip = requireNotNull(custom.paletteChipForTest(symbols.last()))
        val chipBounds = iv.panelDescendantBoundsForTest(lastChip)
        assertPanelRectInside(iv, "last custom symbol after scroll", chipBounds)
        val visibleChipBounds = Rect(chipBounds)
        val contentViewport = iv.panelDescendantBoundsForTest(custom.contentViewportForTest())
        assertTrue(
            "scrolled last chip $chipBounds must intersect viewport $contentViewport; " +
                "scrollY=${custom.contentScrollYForTest()} panel=${custom.width}x${custom.height}",
            visibleChipBounds.intersect(contentViewport),
        )
        assertTrue("scrolled last chip must expose a positive touch area", !visibleChipBounds.isEmpty)
        assertTrue(dispatchRootTap(iv, visibleChipBounds, lastChip))
        val customBackButton = custom.backButtonForTest()
        val backBounds = iv.panelDescendantBoundsForTest(customBackButton)
        assertPanelRectInside(iv, "custom back", backBounds)
        assertTrue(dispatchRootTap(iv, backBounds, customBackButton))
        flushPostedClicks()
        assertEquals(symbols.last(), added)
        assertEquals(1, customBack)

        var clipboardBack = 0
        val clipboard = ClipboardView(ctx).apply {
            categoriesProvider = { listOf("A", "B", "C") }
            phrasesInProvider = { (1..20).map { "phrase$it" } }
            onBack = { clipboardBack++ }
            switchTabForTest(toClipboard = false)
        }
        iv.showPanel(clipboard)
        layoutAtMost(iv, 320, 200)
        settleUiAnimations()
        val fixed = clipboard.fixedChromeViewsForTest()
        assertEquals("phrase mode has a top toolbar and bottom category bar", 2, fixed.size)
        for ((index, chrome) in fixed.withIndex()) {
            val bounds = iv.panelDescendantBoundsForTest(chrome)
            assertPanelRectInside(iv, "clipboard fixed chrome $index", bounds)
            assertTrue("clipboard fixed chrome remains non-zero", bounds.height() > 0)
        }
        val listBounds = iv.panelDescendantBoundsForTest(clipboard.listViewportForTest())
        assertPanelRectInside(iv, "clipboard scroll viewport", listBounds)
        assertTrue(listBounds.height() > 0)
        val clipBack = requireNotNull(firstClickableDescendant(fixed.first()))
        val clipBackBounds = iv.panelDescendantBoundsForTest(clipBack)
        assertPanelRectInside(iv, "clipboard back", clipBackBounds)
        assertTrue(dispatchRootTap(iv, clipBackBounds, clipBack))
        flushPostedClicks()
        assertEquals(1, clipboardBack)
        activity.pause().stop().destroy()
    }

    @Test fun clipboard_select_phrase_sort_and_category_sort_keep_readable_root_clickable_actions() {
        val deleted = mutableListOf<List<String>>()
        val clips = (1..20).map { "clip$it" }
        val clipboard = ClipboardView(ctx).apply {
            historyProvider = { clips.asClipEntries() }
            categoriesProvider = { listOf("A", "B", "C") }
            phrasesInProvider = { category -> (1..20).map { "$category phrase$it" } }
            onDeleteClips = { deleted += it; true }
        }
        val iv = InputView(ctx).apply {
            simulateNavInsetForTest(12)
            showEditBar(true)
            showPanel(clipboard)
        }
        val activity = attachToActivity(iv)

        clipboard.enterSelectForTest()
        val authoredTextSizes = clipboardFixedTextSizes(clipboard)
        layoutAtMost(iv, 320, 200)
        settleUiAnimations()
        var cancel = assertClipboardModeReadable(iv, clipboard, ctx.getString(com.aegis.ime.R.string.clip_cancel))
        assertClipboardFixedTextScales(clipboard, authoredTextSizes)
        val stableSelectBounds = clipboardModeBoundsSnapshot(iv, clipboard)
        repeat(2) {
            layoutAtMost(iv, 320, 200)
            cancel = assertClipboardModeReadable(iv, clipboard, ctx.getString(com.aegis.ime.R.string.clip_cancel))
            assertEquals("identical select-mode measures must not alternate compact/overflow states", stableSelectBounds, clipboardModeBoundsSnapshot(iv, clipboard))
        }
        settleUiAnimations()
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertFalse("compressed Clipboard geometry must settle its layout request", clipboard.isLayoutRequested)
        assertTrue(dispatchRootTap(iv, iv.panelDescendantBoundsForTest(cancel), cancel))
        flushPostedClicks()
        assertFalse(clipboard.isSelectModeForTest())

        clipboard.enterSelectForTest(listOf(clips.first()))
        layoutAtMost(iv, 320, 200)
        settleUiAnimations()
        val delete = assertClipboardModeReadable(iv, clipboard, ctx.getString(com.aegis.ime.R.string.clip_delete))
        assertTrue(dispatchRootTap(iv, iv.panelDescendantBoundsForTest(delete), delete))
        flushPostedClicks()
        assertTrue(deleted.isEmpty())
        assertTrue(clipboard.isSelectModeForTest())
        val confirmDelete = visibleTextViews(clipboard).last { it.text?.toString() == ctx.getString(com.aegis.ime.R.string.clip_delete) && it.isClickable }
        assertTrue(confirmDelete.performClick())
        flushPostedClicks()
        assertEquals(listOf(listOf(clips.first())), deleted)
        assertFalse(clipboard.isSelectModeForTest())

        clipboard.showPhraseTab("A")
        clipboard.enterSortModeForTest()
        layoutAtMost(iv, 320, 200)
        settleUiAnimations()
        val sortDone = assertClipboardModeReadable(iv, clipboard, ctx.getString(com.aegis.ime.R.string.clip_done))
        assertTrue(dispatchRootTap(iv, iv.panelDescendantBoundsForTest(sortDone), sortDone))
        flushPostedClicks()
        assertFalse(clipboard.isSortModeForTest())

        clipboard.enterCategorySortModeForTest()
        layoutAtMost(iv, 320, 200)
        settleUiAnimations()
        val categoryDone = assertClipboardModeReadable(iv, clipboard, ctx.getString(com.aegis.ime.R.string.clip_done))
        assertTrue(dispatchRootTap(iv, iv.panelDescendantBoundsForTest(categoryDone), categoryDone))
        flushPostedClicks()
        assertFalse(clipboard.isCategorySortModeForTest())
        activity.pause().stop().destroy()
    }
}

private fun layoutAtMost(iv: InputView, widthPx: Int, heightPx: Int) {
    iv.measure(
        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST),
    )
    iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
}

private fun layoutExactly(iv: InputView, widthPx: Int, heightPx: Int) {
    iv.measure(
        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
    )
    iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
}

private fun dpRound(value: Int): Int =
    (value * RuntimeEnvironment.getApplication().resources.displayMetrics.density).roundToInt()

private fun resolveWindowInsets(iv: InputView) = LandscapeImeWindowPolicy.resolve(
    compactLandscape = iv.isCompactLandscapeDock(),
    normalTop = rootLocationInWindow(iv)[1] + iv.barTopInsetPx(),
    windowBottom = rootLocationInWindow(iv)[1] + iv.height,
    surfaceBounds = iv.dockTouchableBoundsInWindow(),
)

private fun rootLocationInWindow(iv: InputView): IntArray = IntArray(2).also(iv::getLocationInWindow)

private fun rootRectInWindow(iv: InputView, rect: Rect): Rect {
    val location = rootLocationInWindow(iv)
    return Rect(rect).apply { offset(location[0], location[1]) }
}

private fun rootRectInWindow(iv: InputView, rect: RectF): Rect =
    Rect().also(rect::roundOut).let { rootRectInWindow(iv, it) }

private fun assertRootRectInsideTouchableRegion(iv: InputView, name: String, rect: Rect) {
    val windowRect = rootRectInWindow(iv, rect)
    val region = requireNotNull(resolveWindowInsets(iv).touchableRegion) {
        "$name expected a compact touch region"
    }
    assertTrue("$name window bounds $windowRect must be non-empty", !windowRect.isEmpty)
    assertTrue("$name window bounds $windowRect must be inside touch region $region", region.contains(windowRect))
}

private fun assertRootRectInsideTouchableRegion(iv: InputView, name: String, rect: RectF) {
    val windowRect = rootRectInWindow(iv, rect)
    val region = requireNotNull(resolveWindowInsets(iv).touchableRegion) {
        "$name expected a compact touch region"
    }
    assertTrue("$name window bounds $windowRect must be non-empty", !windowRect.isEmpty)
    assertTrue("$name window bounds $windowRect must be inside touch region $region", region.contains(windowRect))
}

private fun assertPanelRectInsideBody(iv: InputView, name: String, rect: Rect) {
    val body = Rect(
        iv.dockSurfaceLeftPx(),
        iv.dockSurfaceTopPx(),
        iv.dockSurfaceRightPx(),
        iv.dockSurfaceBottomPx(),
    )
    assertTrue("$name must be non-empty: $rect", !rect.isEmpty)
    assertTrue("$name $rect must stay inside body $body", body.contains(rect))
}

private fun assertStatefulNinePanel(iv: InputView, grid: CandidateGridView, expectCompact: Boolean) {
    assertTrue("composition must survive the geometry transition", iv.isComposing())
    assertTrue("edit bar must survive the geometry transition", iv.isEditBarShowing())
    assertTrue("expanded panel intent must survive the geometry transition", iv.isPanelShowing(grid))
    assertTrue("active panel must remain visible", iv.panelShown)
    assertTrue("preedit must retain positive height", iv.preeditVisualBottomPx() > iv.preeditVisualTopPx())
    assertFalse("candidate strip is covered by the expanded surface", iv.toolbarShownForTest())
    assertTrue("edit bar must retain positive height", iv.editBarVisualBottomPx() > iv.editBarVisualTopPx())
    assertTrue("panel must retain positive height", iv.panelHeightPx() > 0)
    val heightSpec = iv.dockHeightSpecForTest()!!
    assertEquals("panel slot must cover the keyboard and the toolbar row", heightSpec.keyboardHeight + heightSpec.barHeight, iv.panelHeightPx())
    assertEquals("visible panel child must fill the current slot", iv.panelHeightPx(), grid.height)
    assertEquals("opaque body must end at the current root", iv.height, iv.dockSurfaceBottomPx())
    assertVerticalBounds(iv)
    assertEquals(expectCompact, iv.isCompactLandscapeDock())

    val insets = resolveWindowInsets(iv)
    if (expectCompact) {
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_REGION, insets.touchableInsets)
        val region = requireNotNull(insets.touchableRegion)
        assertEquals(iv.dockTouchableBoundsInWindow(), region)
        assertTrue(region.contains(iv.preeditSurfaceBoundsInWindow()))
        assertTrue(region.contains(iv.dockSurfaceBoundsInWindow()))
        assertRootRectInsideTouchableRegion(
            iv,
            "active panel",
            Rect(iv.panelVisualLeftPx(), iv.panelVisualTopPx(), iv.panelVisualRightPx(), iv.panelVisualBottomPx()),
        )
        iv.expandedPanelControlBoundsForTest().forEachIndexed { index, bounds ->
            assertRootRectInsideTouchableRegion(iv, "restored expanded control $index", bounds)
        }
    } else {
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE, insets.touchableInsets)
        assertTrue("full-width/portrait fallback must not retain a synthetic region", insets.touchableRegion == null)
    }
}

private fun attachToActivity(view: View) = Robolectric.buildActivity(Activity::class.java).setup().also {
    it.get().setContentView(view)
}

private fun settleUiAnimations() {
    shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
}

private fun assertVerticalBounds(iv: InputView) {
    assertTrue(iv.preeditVisualTopPx() >= 0)
    assertTrue(iv.preeditVisualBottomPx() <= iv.height)
    assertTrue(iv.toolbarVisualTopPx() >= 0)
    assertTrue(iv.toolbarVisualBottomPx() <= iv.height)
    if (iv.isEditBarShowing()) {
        assertTrue(iv.editBarVisualTopPx() >= 0)
        assertTrue(iv.editBarVisualBottomPx() <= iv.height)
    }
    if (iv.panelShown) {
        assertTrue(iv.panelVisualTopPx() >= 0)
        assertTrue(iv.panelVisualBottomPx() <= iv.height)
    } else {
        assertTrue(iv.keyboardVisualTopPx() >= 0)
        assertTrue(iv.keyboardVisualBottomPx() <= iv.height)
    }
    assertTrue(iv.dockSurfaceTopPx() >= 0)
    assertTrue(iv.dockSurfaceBottomPx() <= iv.height)
}

private fun assertRectInsideSurface(iv: InputView, rect: RectF) {
    assertTrue(rect.left >= iv.dockSurfaceLeftPx())
    assertTrue(rect.top >= iv.dockSurfaceTopPx())
    assertTrue(rect.right <= iv.dockSurfaceRightPx())
    assertTrue(rect.bottom <= iv.dockSurfaceBottomPx())
}

private fun assertPanelControlsInside(iv: InputView) {
    val panel = Rect(
        iv.panelVisualLeftPx(),
        iv.panelVisualTopPx(),
        iv.panelVisualRightPx(),
        iv.panelVisualBottomPx(),
    )
    for (control in iv.expandedPanelControlBoundsForTest()) {
        assertTrue("panel control $control must be non-empty", !control.isEmpty)
        assertTrue("panel control $control must stay inside $panel", panel.contains(control))
    }
}

private fun assertPanelRectInside(iv: InputView, name: String, rect: Rect) {
    val panel = Rect(
        iv.panelVisualLeftPx(),
        iv.panelVisualTopPx(),
        iv.panelVisualRightPx(),
        iv.panelVisualBottomPx(),
    )
    assertTrue("$name must be non-empty: $rect", !rect.isEmpty)
    assertTrue("$name $rect must stay inside panel $panel", panel.contains(rect))
}

private fun firstClickableDescendant(view: View): View? {
    if (view.isClickable) return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) firstClickableDescendant(view.getChildAt(i))?.let { return it }
    }
    return null
}

private fun assertClipboardModeReadable(iv: InputView, clipboard: ClipboardView, actionText: String): android.widget.TextView {
    val fixed = clipboard.fixedChromeViewsForTest()
    assertTrue("mode must retain at least one fixed action rail", fixed.isNotEmpty())
    for ((index, chrome) in fixed.withIndex()) {
        assertPanelRectInside(iv, "mode fixed chrome $index", iv.panelDescendantBoundsForTest(chrome))
        for (label in visibleTextViews(chrome)) {
            if (label.text.isNullOrEmpty()) continue
            val bounds = iv.panelDescendantBoundsForTest(label)
            assertPanelRectInside(iv, "visible fixed label '${label.text}'", bounds)
            assertTrue(
                "visible fixed label '${label.text}' must retain the declared 20dp compact rail: " +
                    "height=${label.height}, textSize=${label.textSize}",
                label.height >= (20 * label.resources.displayMetrics.density).roundToInt(),
            )
        }
    }
    val viewport = iv.panelDescendantBoundsForTest(clipboard.listViewportForTest())
    assertPanelRectInside(iv, "mode list viewport", viewport)
    assertTrue("mode list must retain a scroll/touch viewport", viewport.height() > 0)
    val action = fixed.asSequence().mapNotNull { textViewWithExactText(it, actionText) }.firstOrNull()
        ?: throw AssertionError("missing fixed action '$actionText'")
    val actionBounds = iv.panelDescendantBoundsForTest(action)
    assertPanelRectInside(iv, "mode action '$actionText'", actionBounds)
    assertTrue(action.isClickable)
    assertTrue(
        "mode action '$actionText' must retain the declared 20dp compact rail",
        action.height >= (20 * action.resources.displayMetrics.density).roundToInt(),
    )
    return action
}

private fun clipboardFixedTextSizes(clipboard: ClipboardView): Map<android.widget.TextView, Float> =
    clipboard.fixedChromeViewsForTest()
        .flatMap(::visibleTextViews)
        .filter { !it.text.isNullOrEmpty() }
        .associateWith { it.textSize }

private fun assertClipboardFixedTextScales(
    clipboard: ClipboardView,
    authoredTextSizes: Map<android.widget.TextView, Float>,
) {
    for (chrome in clipboard.fixedChromeViewsForTest()) {
        for (label in visibleTextViews(chrome).filter { !it.text.isNullOrEmpty() }) {
            val authoredTextSize = requireNotNull(authoredTextSizes[label])
            val railHeight = (40 * label.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val minTextSize = (authoredTextSize * minOf(1f, label.height.toFloat() / railHeight)).coerceAtLeast(1f)
            assertTrue(
                "visible fixed label '${label.text}' must stay readable within its rail — never larger than authored, never below its height ratio (was ${label.textSize})",
                label.textSize in (minTextSize - 0.01f)..(authoredTextSize + 0.01f),
            )
        }
    }
}

private fun visibleTextViews(view: View): List<android.widget.TextView> {
    if (view.visibility != View.VISIBLE) return emptyList()
    if (view is android.widget.TextView) return listOf(view)
    if (view !is ViewGroup) return emptyList()
    return buildList {
        for (i in 0 until view.childCount) addAll(visibleTextViews(view.getChildAt(i)))
    }
}

private fun textViewWithExactText(view: View, text: String): android.widget.TextView? {
    if (view.visibility != View.VISIBLE) return null
    if (view is android.widget.TextView && view.text.toString() == text) return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) textViewWithExactText(view.getChildAt(i), text)?.let { return it }
    }
    return null
}

private fun clipboardModeBoundsSnapshot(iv: InputView, clipboard: ClipboardView): List<Rect> =
    clipboard.fixedChromeViewsForTest().map { Rect(iv.panelDescendantBoundsForTest(it)) } +
        Rect(iv.panelDescendantBoundsForTest(clipboard.listViewportForTest()))

private fun dispatchRootTap(root: View, rect: Rect, expectedTarget: View? = null): Boolean {
    val x = rect.exactCenterX()
    val y = rect.exactCenterY()
    val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
    val up = MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, x, y, 0)
    return try {
        val accepted = root.dispatchTouchEvent(down)
        if (expectedTarget != null) {
            assertTrue("expected target must own a click handler: bounds=$rect", expectedTarget.isClickable)
        }
        root.dispatchTouchEvent(up) && accepted
    } finally {
        down.recycle()
        up.recycle()
    }
}

private fun flushPostedClicks() {

    shadowOf(Looper.getMainLooper()).idle()
}
