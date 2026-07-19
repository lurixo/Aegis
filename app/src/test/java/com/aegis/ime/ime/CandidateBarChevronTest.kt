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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.theme.ImePalette
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandidateBarChevronTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun barView(context: Context = ctx): CandidateView {
        val viewDensity = context.resources.displayMetrics.density
        val v = CandidateView(context)
        v.setContent(listOf("你好", "你", "拟"), "ni'hao")
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * viewDensity).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * viewDensity).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun idleBar(widthDp: Int, context: Context = ctx): CandidateView {
        val viewDensity = context.resources.displayMetrics.density
        val view = CandidateView(context)
        view.setContent(emptyList(), "")
        view.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * viewDensity).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * viewDensity).toInt(), View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        return view
    }

    private fun CandidateView.tapChevron() {
        val bounds = expandControlBoundsForTest()
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, cx, cy, 0))
        dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, cx, cy, 0))
    }

    @Test fun collapsed_state_chevron_points_down_and_expands() {
        var expanded = false
        var collapsed = false
        val v = barView().apply { onExpand = { expanded = true }; onCollapseExpanded = { collapsed = true } }
        assertEquals("⌄", v.chevronGlyph())
        v.tapChevron()
        assertTrue("a tap on ⌄ expands the grid", expanded)
        assertFalse(collapsed)
    }

    @Test fun expanded_state_chevron_reverses_and_collapses() {
        var expanded = false
        var collapsed = false
        val v = barView().apply { onExpand = { expanded = true }; onCollapseExpanded = { collapsed = true } }
        v.setExpanded(true)
        assertEquals("the arrow direction reverses once expanded", "⌃", v.chevronGlyph())
        v.tapChevron()
        assertTrue("a tap on ⌃ collapses the grid", collapsed)
        assertFalse("it must NOT re-expand", expanded)
    }

    @Test fun candidate_and_taskbar_chevrons_share_centered_function_icon_geometry() {
        val candidate = barView()
        val candidateHit = candidate.expandControlBoundsForTest()
        val candidateGlyph = candidate.candidateChevronBoundsForTest()
        assertEquals(candidateHit.centerX(), candidateGlyph.centerX(), 0.01f)
        assertEquals(candidateHit.centerY(), candidateGlyph.centerY(), 0.01f)
        assertEquals(64f * density, candidateHit.width(), 0.01f)

        candidate.setExpanded(true)
        assertEquals(candidateGlyph, candidate.candidateChevronBoundsForTest())

        val taskbar = idleBar(360)
        val taskbarHit = taskbar.toolbarControlBoundsForTest().last()
        val taskbarGlyph = taskbar.toolbarChevronBoundsForTest()
        assertEquals(taskbar.toolbarIconCentersForTest().last(), taskbarGlyph.centerX(), 0.01f)
        assertEquals(taskbarHit.centerY(), taskbarGlyph.centerY(), 0.01f)
        assertEquals(candidateGlyph.width(), taskbarGlyph.width(), 0.01f)
        assertEquals(candidateGlyph.height(), taskbarGlyph.height(), 0.01f)
        assertEquals("chevron rises to the EDIT family height", 1.64f * 9f * density, taskbarGlyph.height(), 0.02f * density)
        assertEquals("chevron width follows its aspect", taskbarGlyph.height() * (1.4f / 0.76f), taskbarGlyph.width(), 0.02f * density)
        assertTrue(candidateHit.contains(candidateGlyph))
        assertTrue(taskbarHit.contains(taskbarGlyph))
    }

    @Test fun collapsed_expand_hit_column_matches_back_and_requires_bounded_down_and_up() {
        val context = ctx.createConfigurationContext(
            Configuration(ctx.resources.configuration).apply { densityDpi = 411 },
        )
        val viewDensity = context.resources.displayMetrics.density
        val bar = barView(context)
        val grid = CandidateGridView(context)
        grid.measure(
            View.MeasureSpec.makeMeasureSpec(bar.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((230 * viewDensity).toInt(), View.MeasureSpec.EXACTLY),
        )
        grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
        bar.setContent(List(40) { "候选$it" }, "shi")
        val bounds = bar.expandControlBoundsForTest()
        assertEquals(grid.returnButtonForTest().width.toFloat(), bounds.width(), 0.01f)
        var expansions = 0
        var picks = 0
        var time = 0L
        bar.onExpand = { expansions++ }
        bar.onPick = { picks++ }
        fun gesture(downX: Float, downY: Float, upX: Float, upY: Float, move: Boolean = false) {
            val downTime = time
            bar.dispatchTouchEvent(MotionEvent.obtain(downTime, time, MotionEvent.ACTION_DOWN, downX, downY, 0))
            time += 10
            if (move) {
                bar.dispatchTouchEvent(MotionEvent.obtain(downTime, time, MotionEvent.ACTION_MOVE, upX, upY, 0))
                time += 10
            }
            bar.dispatchTouchEvent(MotionEvent.obtain(downTime, time, MotionEvent.ACTION_UP, upX, upY, 0))
            time += 10
        }
        gesture(bounds.left + 1f, bounds.centerY(), bounds.left + 1f, bounds.centerY())
        assertEquals(1, expansions)
        gesture(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.bottom + 1f, move = true)
        gesture(bounds.right - 1f, bounds.centerY(), bounds.right + 1f, bounds.centerY())
        gesture(bounds.left - 1f, bounds.centerY(), bounds.left + 1f, bounds.centerY())
        assertEquals(1, expansions)
        assertEquals(0, picks)
    }


    private fun attached(): InputView {
        val iv = InputView(ctx)
        val host = object : ImeHost {
            override fun commitText(text: CharSequence) {}
            override fun deleteBackward() {}
            override fun performEnter() {}
        }
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }
        KeyboardController(host, engine).attachView(iv)
        return iv
    }

    private fun activityInput(): Pair<FrameLayout, InputView> {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val iv = InputView(activity)
        root.addView(iv)
        activity.setContentView(root)
        layoutRoot(root)
        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
        return root to iv
    }

    private fun layoutRoot(root: FrameLayout) {
        val viewDensity = root.resources.displayMetrics.density
        val width = (360 * viewDensity).toInt()
        val height = (500 * viewDensity).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
    }

    @Test fun inputview_flips_chevron_when_the_grid_opens_and_closes() {
        val iv = attached()
        iv.showCandidates(listOf("你好", "你"), "ni'hao", listOf("ni"))
        assertEquals("⌄", iv.barChevronGlyph())
        iv.showExpandedCandidates()
        assertTrue(iv.panelShown)
        assertEquals("grid open → chevron flips up", "⌃", iv.barChevronGlyph())
        iv.showPanel(null)
        assertEquals("grid closed → chevron back to down", "⌄", iv.barChevronGlyph())
    }

    @Test fun expanded_grid_covers_the_toolbar_row_and_keeps_a_reachable_collapse_chevron() {
        val (root, iv) = activityInput()
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        iv.showCandidates(List(30) { "候选$it" }, "shi", listOf("shi"), 0)
        layoutRoot(root)
        assertTrue("the bar is visible while composing", iv.toolbarShownForTest())
        val barTop = iv.toolbarVisualTopPx()

        iv.showExpandedCandidates()
        layoutRoot(root)
        mainLooper.runToEndOfTasks()
        assertTrue(iv.panelShown)
        assertFalse("the expanded surface covers the bar row", iv.toolbarShownForTest())
        assertEquals("expanded surface top == former bar top", barTop, iv.panelVisualTopPx())

        val collapse = iv.expandedGridForTest().returnButtonForTest()
        assertEquals("the in-surface collapse control is icon-only", "", collapse.text.toString())
        assertTrue("the collapse control shows a chevron glyph", collapse.compoundDrawables[1] != null)

        assertTrue("collapse is reachable inside the expanded surface", collapse.performClick())
        layoutRoot(root)
        mainLooper.runToEndOfTasks()
        assertFalse("collapsing returns to the keyboard", iv.panelShown)
        assertTrue("collapsing restores the bar", iv.toolbarShownForTest())
        assertEquals("the bar returns to its former position", barTop, iv.toolbarVisualTopPx())
    }

    @Test fun pending_expand_coalesces_rapid_updates_and_repeated_open_requests() {
        val (root, iv) = activityInput()
        val candidates = List(54) { "候选$it" }
        val readings = listOf("shi")
        iv.showCandidates(candidates, "shi", readings, 0)
        val grid = iv.expandedGridForTest()
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        var firstPreDrawPools: Pair<Int, Int>? = null
        root.viewTreeObserver.addOnPreDrawListener {
            if (firstPreDrawPools == null) {
                firstPreDrawPools = grid.chipsAllocatedForTest() to grid.readingsAllocatedForTest()
            }
            true
        }
        assertEquals(0, grid.chipsAllocatedForTest())
        assertTrue(iv.tapExpandCandidatesForTest())
        assertTrue(iv.panelShown)
        assertEquals(0, grid.chipsAllocatedForTest())
        assertFalse(grid.selectionContentVisibleForTest())
        assertEquals(View.VISIBLE, grid.returnButtonForTest().visibility)
        root.postOnAnimation { root.viewTreeObserver.dispatchOnPreDraw() }
        mainLooper.runToEndOfTasks()
        assertEquals(0 to 0, firstPreDrawPools)
        assertEquals(candidates.size, grid.chipsAllocatedForTest())
        assertEquals(candidates, grid.renderedCandidateTextsForTest())
        assertEquals(readings, grid.renderedReadingTextsForTest())
        assertEquals(ImePalette.STATIC_LIGHT.accentBottom, grid.readingTextColorForTest(0))
        assertTrue(grid.selectionContentVisibleForTest())

        iv.showPanel(null)
        mainLooper.runToEndOfTasks()
        iv.showCandidates(List(60) { "打开$it" }, "shi", listOf("shi", "si"), 0)
        val candidateRebuilds = grid.candidateRebuildsForTest()
        val readingRebuilds = grid.readingRebuildsForTest()
        var growthPreDrawPools: Pair<Int, Int>? = null
        root.viewTreeObserver.addOnPreDrawListener {
            if (growthPreDrawPools == null) {
                growthPreDrawPools = grid.chipsAllocatedForTest() to grid.readingsAllocatedForTest()
            }
            true
        }
        assertTrue(iv.tapExpandCandidatesForTest())
        iv.showExpandedCandidates()
        iv.showCandidates(List(68) { "中间$it" }, "shi", listOf("shi", "si", "chi", "zhi"), 3)
        iv.showExpandedCandidates()
        val latestCandidates = List(63) { "最新$it" }
        val latestReadings = listOf("chi", "shi", "si")
        iv.showCandidates(latestCandidates, "shi", latestReadings, 1)
        assertEquals(candidates.size, grid.chipsAllocatedForTest())
        assertEquals(1, grid.readingsAllocatedForTest())
        assertEquals(candidateRebuilds, grid.candidateRebuildsForTest())
        assertEquals(readingRebuilds, grid.readingRebuildsForTest())
        assertEquals(candidates, grid.renderedCandidateTextsForTest())
        assertEquals(readings, grid.renderedReadingTextsForTest())
        assertFalse(grid.selectionContentVisibleForTest())
        assertEquals(View.VISIBLE, grid.returnButtonForTest().visibility)
        assertTrue(grid.returnButtonForTest().isClickable)
        root.postOnAnimation { root.viewTreeObserver.dispatchOnPreDraw() }
        mainLooper.runToEndOfTasks()
        assertEquals(candidates.size to 1, growthPreDrawPools)
        assertEquals(latestCandidates.size, grid.chipsAllocatedForTest())
        assertEquals(latestReadings.size, grid.readingsAllocatedForTest())
        assertEquals(latestCandidates, grid.renderedCandidateTextsForTest())
        assertEquals(latestReadings, grid.renderedReadingTextsForTest())
        assertEquals(ImePalette.STATIC_LIGHT.candidateText, grid.readingTextColorForTest(0))
        assertEquals(ImePalette.STATIC_LIGHT.accentBottom, grid.readingTextColorForTest(1))
        assertEquals(ImePalette.STATIC_LIGHT.candidateText, grid.readingTextColorForTest(2))
        assertEquals(candidateRebuilds + 1, grid.candidateRebuildsForTest())
        assertEquals(readingRebuilds + 1, grid.readingRebuildsForTest())
        assertTrue(grid.selectionContentVisibleForTest())
    }

    @LooperMode(LooperMode.Mode.PAUSED)
    @Test fun close_then_reopen_rejects_the_old_nested_bind() {
        val (root, iv) = activityInput()
        val grid = iv.expandedGridForTest()
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        iv.showCandidates(List(70) { "旧候选$it" }, "shi", listOf("shi", "si", "chi", "zhi"), 2)
        assertTrue(iv.tapExpandCandidatesForTest())
        var checkpointPools: Pair<Int, Int>? = null
        grid.postOnAnimation {
            assertTrue(grid.returnButtonForTest().performClick())
            val latestCandidates = List(12) { "重开$it" }
            val latestReadings = listOf("si", "shi")
            iv.showCandidates(latestCandidates, "shi", latestReadings, 1)
            iv.showExpandedCandidates()
            grid.postOnAnimation {
                checkpointPools = grid.chipsAllocatedForTest() to grid.readingsAllocatedForTest()
                assertFalse(grid.selectionContentVisibleForTest())
                assertEquals(View.VISIBLE, grid.returnButtonForTest().visibility)
            }
        }
        root.postOnAnimation { root.viewTreeObserver.dispatchOnPreDraw() }
        mainLooper.runToEndOfTasks()
        assertEquals(0 to 0, checkpointPools)
        assertEquals(12, grid.chipsAllocatedForTest())
        assertEquals(2, grid.readingsAllocatedForTest())
        assertEquals(List(12) { "重开$it" }, grid.renderedCandidateTextsForTest())
        assertEquals(listOf("si", "shi"), grid.renderedReadingTextsForTest())
        assertEquals(ImePalette.STATIC_LIGHT.candidateText, grid.readingTextColorForTest(0))
        assertEquals(ImePalette.STATIC_LIGHT.accentBottom, grid.readingTextColorForTest(1))
        assertEquals(1, grid.candidateRebuildsForTest())
        assertEquals(1, grid.readingRebuildsForTest())
        assertTrue(grid.selectionContentVisibleForTest())
    }

    @LooperMode(LooperMode.Mode.PAUSED)
    @Test fun pending_open_inter_stage_detach_waits_for_reattach_and_binds_latest_once() {
        val (root, iv) = activityInput()
        val grid = iv.expandedGridForTest()
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        iv.showCandidates(List(54) { "分离前$it" }, "shi", listOf("shi"), 0)
        assertTrue(iv.tapExpandCandidatesForTest())
        grid.postOnAnimation { root.removeView(iv) }
        mainLooper.runToEndOfTasks()
        assertFalse(iv.isAttachedToWindow)
        assertEquals(0, grid.chipsAllocatedForTest())
        assertEquals(0, grid.readingsAllocatedForTest())
        assertEquals(0, grid.candidateRebuildsForTest())
        assertEquals(0, grid.readingRebuildsForTest())
        assertFalse(grid.selectionContentVisibleForTest())
        assertEquals(View.VISIBLE, grid.returnButtonForTest().visibility)
        assertTrue(grid.returnButtonForTest().isClickable)
        val latestCandidates = List(51) { "重连$it" }
        val latestReadings = listOf("si", "shi", "chi")
        iv.showCandidates(latestCandidates, "shi", latestReadings, 2)
        assertEquals(0, grid.chipsAllocatedForTest())
        assertEquals(0, grid.readingsAllocatedForTest())
        root.addView(iv)
        layoutRoot(root)
        assertTrue(iv.isAttachedToWindow)
        assertEquals(0, grid.chipsAllocatedForTest())
        mainLooper.runToEndOfTasks()
        assertEquals(latestCandidates.size, grid.chipsAllocatedForTest())
        assertEquals(latestReadings.size, grid.readingsAllocatedForTest())
        assertEquals(latestCandidates, grid.renderedCandidateTextsForTest())
        assertEquals(latestReadings, grid.renderedReadingTextsForTest())
        assertEquals(ImePalette.STATIC_LIGHT.candidateText, grid.readingTextColorForTest(0))
        assertEquals(ImePalette.STATIC_LIGHT.candidateText, grid.readingTextColorForTest(1))
        assertEquals(ImePalette.STATIC_LIGHT.accentBottom, grid.readingTextColorForTest(2))
        assertEquals(1, grid.candidateRebuildsForTest())
        assertEquals(1, grid.readingRebuildsForTest())
        assertTrue(grid.selectionContentVisibleForTest())
    }

    @Test fun six_idle_toolbar_controls_have_equal_centered_bounds_and_actions() {
        for (widthDp in listOf(250, 320, 480)) {
            val view = idleBar(widthDp)
            val controls = view.toolbarControlBoundsForTest()
            val capsule = view.toolbarCapsuleBoundsForTest()
            val centers = view.toolbarIconCentersForTest()
            assertEquals(6, controls.size)
            assertEquals(6, centers.size)
            assertEquals(capsule.left, controls.first().left, 0.01f)
            assertEquals(capsule.right, controls.last().right, 0.01f)
            assertTrue(controls.all { it.top == capsule.top && it.bottom == capsule.bottom })
            controls.zipWithNext().forEach { (left, right) -> assertEquals(left.right, right.left, 0.01f) }
            assertTrue(controls.all { abs(it.height() - controls.first().height()) <= 0.01f })
            val spacing = centers.toList().zipWithNext { a, b -> b - a }
            assertTrue("the six icons are evenly spaced", spacing.all { abs(it - spacing.first()) <= 0.01f })
            val gap = spacing.first()
            assertEquals("left end margin equals inter-icon spacing", gap, centers.first() - capsule.left, 0.01f)
            assertEquals("right end margin equals inter-icon spacing", gap, capsule.right - centers.last(), 0.01f)
            assertEquals(capsule.centerX(), (controls.first().left + controls.last().right) / 2f, 0.01f)
        }
        val view = idleBar(360)
        val actions = ArrayList<String>()
        view.onFunction = { actions += it.name }
        view.onCollapse = { actions += "COLLAPSE" }
        for ((index, rect) in view.toolbarControlBoundsForTest().withIndex()) {
            view.dispatchTouchEvent(MotionEvent.obtain(0, index * 20L, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0))
            view.dispatchTouchEvent(MotionEvent.obtain(0, index * 20L + 10L, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0))
        }
        assertEquals(listOf("BRAND", "LAYOUT", "EMOJI", "EDIT", "CLIPBOARD", "COLLAPSE"), actions)
    }

    @Test fun idle_toolbar_end_targets_fill_only_the_rounded_capsule() {
        val view = idleBar(320)
        val capsule = view.toolbarCapsuleBoundsForTest()
        val actions = ArrayList<String>()
        view.onFunction = { actions += it.name }
        view.onCollapse = { actions += "COLLAPSE" }
        fun tap(x: Float, y: Float, time: Long) {
            view.dispatchTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
            view.dispatchTouchEvent(MotionEvent.obtain(time, time + 10L, MotionEvent.ACTION_UP, x, y, 0))
        }
        tap(capsule.left + 0.5f * density, capsule.centerY(), 0L)
        tap(capsule.left + density, capsule.top + density, 20L)
        tap(capsule.right - density, capsule.top + density, 40L)
        tap(capsule.right - 0.5f * density, capsule.centerY(), 60L)
        tap(capsule.left - density, capsule.centerY(), 80L)
        tap(capsule.right + density, capsule.centerY(), 100L)
        assertEquals(listOf("BRAND", "COLLAPSE"), actions)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun idle_toolbar_end_feedback_is_clipped_to_the_capsule() {
        val view = idleBar(320)
        val capsule = view.toolbarCapsuleBoundsForTest()
        val resting = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(resting))
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, capsule.left + density, capsule.centerY(), 0))
        val pressed = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(pressed))
        assertEquals(
            resting.getPixel((capsule.left + density).toInt(), (capsule.top + density).toInt()),
            pressed.getPixel((capsule.left + density).toInt(), (capsule.top + density).toInt()),
        )
        assertTrue(
            resting.getPixel((capsule.left + density).toInt(), capsule.centerY().toInt()) !=
                pressed.getPixel((capsule.left + density).toInt(), capsule.centerY().toInt()),
        )
        view.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_CANCEL, capsule.left + density, capsule.centerY(), 0))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun idle_toolbar_has_semicircular_ends_and_no_chevron_divider() {
        val view = idleBar(360)
        val controls = view.toolbarControlBoundsForTest()
        assertEquals(controls.first().height() / 2f, view.toolbarOuterRadiusForTest(), 0.01f)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dividerX = (controls.last().left + density * 0.5f).toInt()
        assertEquals(ImePalette.STATIC_LIGHT.keySurface, bitmap.getPixel(dividerX, controls.last().centerY().toInt()))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun idle_toolbar_brand_slot_renders_the_brand_icon() {
        val view = idleBar(320)
        val slot = view.toolbarControlBoundsForTest().first()
        val cx = view.toolbarIconCentersForTest().first()
        val cy = slot.centerY()
        val s = 9f * density * view.toolbarIconScaleForTest(BarFunction.BRAND)
        val glyph = RectF(cx - s * 0.64f, cy - s * 0.76f, cx + s * 0.64f, cy + s * 0.83f)
        assertTrue(slot.contains(glyph))
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val ink = RectF(glyph).apply { inset(-(0.9f * density + 1f), -(0.9f * density + 1f)) }
        var found = false
        for (y in slot.top.toInt() until slot.bottom.toInt()) {
            for (x in slot.left.toInt() until slot.right.toInt()) {
                if (bitmap.getPixel(x, y) != ImePalette.STATIC_LIGHT.icon) continue
                found = true
                assertTrue(ink.contains(x.toFloat(), y.toFloat()))
            }
        }
        assertTrue(found)
    }


    @Test fun a_horizontal_flick_hands_off_to_a_fling() {
        val v = CandidateView(ctx)
        v.setContent(List(40) { "候选$it" }, "ni")
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        assertTrue("the candidate list overflows so there is room to fling", v.maxScrollForTest() > 0f)
        val y = v.height / 2f
        var t = 0L
        fun send(action: Int, x: Float) { v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0)); t += 16 }
        send(MotionEvent.ACTION_DOWN, 300f)
        send(MotionEvent.ACTION_MOVE, 284f); send(MotionEvent.ACTION_MOVE, 268f); send(MotionEvent.ACTION_MOVE, 252f)
        send(MotionEvent.ACTION_MOVE, 236f); send(MotionEvent.ACTION_MOVE, 220f)
        v.dispatchTouchEvent(MotionEvent.obtain(0, t, MotionEvent.ACTION_UP, 220f, y, 0))
        assertTrue("a flick on the candidate strip starts a horizontal fling", v.isFlingingForTest())
        assertTrue("the windowed velocity reflects the leftward flick", v.flingVelocityForTest() < -300f)
    }


    @Test fun new_content_cancels_a_running_fling_and_renders_from_zero() {
        val v = CandidateView(ctx)
        v.setContent(List(40) { "候选$it" }, "ni")
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val y = v.height / 2f
        var t = 0L
        fun send(action: Int, x: Float) { v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0)); t += 16 }
        send(MotionEvent.ACTION_DOWN, 300f)
        send(MotionEvent.ACTION_MOVE, 284f); send(MotionEvent.ACTION_MOVE, 268f); send(MotionEvent.ACTION_MOVE, 252f)
        send(MotionEvent.ACTION_MOVE, 236f); send(MotionEvent.ACTION_MOVE, 220f)
        v.dispatchTouchEvent(MotionEvent.obtain(0, t, MotionEvent.ACTION_UP, 220f, y, 0))
        assertTrue("precondition: a horizontal fling is running", v.isFlingingForTest())
        assertTrue("precondition: it scrolled away from the left edge", v.scrollXForTest() > 0f)

        v.setContent(List(40) { "新候选$it" }, "hao")
        assertFalse("new content cancels the fling", v.isFlingingForTest())
        assertEquals("the offset is reset to 0", 0f, v.scrollXForTest(), 0f)
        v.computeScroll()
        assertEquals("the next frame does NOT restore the stale fling offset", 0f, v.scrollXForTest(), 0f)
    }
}
