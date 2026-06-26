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
import android.graphics.Color
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.layout.Layouts
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandidateBarChevronTest {

    @org.junit.Before fun usePlatformHapticFallback() {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.os.Vibrator::class.java))
            .setHasVibrator(false)
    }

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

    @Test fun candidate_and_expand_press_highlights_stop_at_the_shared_edge_inset() {
        val inset = ImeShapes.edgeInsetDp * density
        fun capture(view: CandidateView) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val view = barView()
        val resting = capture(view)
        val y = view.height / 2
        val first = requireNotNull(view.centerOfCandidateForTest(0))
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, first.first, first.second, 0))
        val candidatePressed = capture(view)
        for (x in 0 until inset.toInt()) {
            assertEquals("candidate highlight stays out of x=$x", resting.getPixel(x, y), candidatePressed.getPixel(x, y))
        }
        assertTrue(resting.getPixel((inset + density).toInt(), y) != candidatePressed.getPixel((inset + density).toInt(), y))
        val cornerX = inset.toInt() + 1
        val cornerY = (4f * density + ImeShapes.keyRadiusDp * density / 4f).toInt()
        assertEquals(
            "the first highlight keeps its rounded corner at the inset",
            resting.getPixel(cornerX, cornerY),
            candidatePressed.getPixel(cornerX, cornerY),
        )
        view.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_CANCEL, first.first, first.second, 0))

        val expand = view.expandControlBoundsForTest()
        view.dispatchTouchEvent(MotionEvent.obtain(20, 20, MotionEvent.ACTION_DOWN, expand.centerX(), expand.centerY(), 0))
        val expandPressed = capture(view)
        for (x in (view.width - inset.toInt()) until view.width) {
            assertEquals("expand highlight stays out of x=$x", resting.getPixel(x, y), expandPressed.getPixel(x, y))
        }
        val inside = (view.width - inset - density).toInt()
        assertTrue(resting.getPixel(inside, y) != expandPressed.getPixel(inside, y))
        view.dispatchTouchEvent(MotionEvent.obtain(20, 30, MotionEvent.ACTION_CANCEL, expand.centerX(), expand.centerY(), 0))
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

    @Test fun the_expand_touch_press_and_chevron_share_one_box_inside_the_edge_inset() {
        val inset = ImeShapes.edgeInsetDp * density
        fun capture(view: CandidateView) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val view = barView()
        val expand = view.expandControlBoundsForTest()
        val y = expand.centerY()
        assertEquals("the control ends at the edge inset", view.width - inset, expand.right, 0.01f)
        fun targetAt(x: Float): String? {
            view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
            val target = view.pressedTargetForTest()
            view.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_CANCEL, x, y, 0))
            return target
        }
        assertEquals("EXPAND", targetAt(expand.left))
        assertEquals("EXPAND", targetAt(expand.right - 1f))
        assertNull("the edge inset takes no touch", targetAt(expand.right))
        assertNull("the edge inset takes no touch", targetAt(view.width - 1f))

        val resting = capture(view)
        view.dispatchTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_DOWN, expand.centerX(), y, 0))
        val pressed = capture(view)
        view.dispatchTouchEvent(MotionEvent.obtain(0, 30, MotionEvent.ACTION_CANCEL, expand.centerX(), y, 0))
        val row = (4f * density + ImeShapes.keyRadiusDp * density).toInt()
        assertTrue("the press fills the left end of the control", resting.getPixel((expand.left + density).toInt(), row) != pressed.getPixel((expand.left + density).toInt(), row))
        assertTrue("the press fills the right end of the control", resting.getPixel((expand.right - density).toInt(), row) != pressed.getPixel((expand.right - density).toInt(), row))
        assertEquals("the press stays out of the edge inset", resting.getPixel(expand.right.toInt() + 1, row), pressed.getPixel(expand.right.toInt() + 1, row))
        val background = resting.getPixel((expand.left + density).toInt(), row)
        val ink = (expand.left.toInt() until expand.right.toInt()).filter { x ->
            (y.toInt() - (4 * density).toInt()..y.toInt() + (4 * density).toInt()).any { resting.getPixel(x, it) != background }
        }
        assertTrue("precondition: the chevron is drawn", ink.isNotEmpty())
        assertEquals("the drawn chevron sits in the middle of the press", expand.centerX(), (ink.first() + ink.last() + 1) / 2f, 1f)
    }

    @Test fun candidates_take_no_tap_in_the_edge_inset_but_the_strip_still_scrolls_from_it() {
        val inset = ImeShapes.edgeInsetDp * density
        val view = barView()
        var picks = 0
        view.onPick = { picks++ }
        val y = view.height / 2f
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, inset - 1f, y, 0))
        assertNull("the edge inset presses no candidate", view.pressedTargetForTest())
        view.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, inset - 1f, y, 0))
        assertEquals("a tap in the edge inset picks nothing", 0, picks)
        view.dispatchTouchEvent(MotionEvent.obtain(20, 20, MotionEvent.ACTION_DOWN, inset, y, 0))
        assertEquals("the first candidate starts at the edge inset", "CANDIDATE", view.pressedTargetForTest())
        view.dispatchTouchEvent(MotionEvent.obtain(20, 30, MotionEvent.ACTION_UP, inset, y, 0))
        assertEquals(1, picks)

        view.setContent(List(40) { "候选$it" }, "shi")
        assertTrue("precondition: the strip can scroll", view.maxScrollForTest() > 0f)
        view.dispatchTouchEvent(MotionEvent.obtain(40, 40, MotionEvent.ACTION_DOWN, view.width / 2f, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(40, 60, MotionEvent.ACTION_MOVE, view.width / 4f, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(40, 80, MotionEvent.ACTION_MOVE, inset * 2f, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(40, 100, MotionEvent.ACTION_CANCEL, inset * 2f, y, 0))
        val scrolled = view.scrollXForTest()
        assertTrue("precondition: the strip is scrolled", scrolled > 0f)
        view.dispatchTouchEvent(MotionEvent.obtain(120, 120, MotionEvent.ACTION_DOWN, inset / 2f, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(120, 140, MotionEvent.ACTION_MOVE, view.width / 4f, y, 0))
        assertTrue("a drag from the edge inset still scrolls the strip", view.scrollXForTest() < scrolled)
        view.dispatchTouchEvent(MotionEvent.obtain(120, 160, MotionEvent.ACTION_CANCEL, view.width / 4f, y, 0))
        assertEquals("the drags picked nothing", 1, picks)
    }

    @Test fun candidate_and_taskbar_chevrons_share_centered_function_icon_geometry() {
        val candidate = barView()
        val candidateHit = candidate.expandControlBoundsForTest()
        val candidateGlyph = candidate.candidateChevronBoundsForTest()
        assertEquals(candidateHit.centerX(), candidateGlyph.centerX(), 0.01f)
        assertEquals(candidateHit.centerY(), candidateGlyph.centerY(), 0.01f)
        assertEquals(
            minOf(
                KeyboardView.nineColumnBoundary(candidate.width, density).roundToInt().toFloat(),
                Layouts.CANDIDATE_ACTION_WIDTH_DP * density,
            ),
            candidateHit.width(),
            0.01f,
        )
        val narrow = CandidateView(ctx).apply {
            setContent(listOf("你好"), "ni'hao")
            measure(
                View.MeasureSpec.makeMeasureSpec((240 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
        assertEquals(
            "a bar too narrow for the full action width follows the nine-key column boundary",
            KeyboardView.nineColumnBoundary(narrow.width, density).roundToInt().toFloat(),
            narrow.expandControlBoundsForTest().width(),
            0.01f,
        )

        candidate.setExpanded(true)
        assertEquals(candidateGlyph, candidate.candidateChevronBoundsForTest())

        val taskbar = idleBar(360)
        val taskbarHit = taskbar.toolbarControlBoundsForTest().last()
        val taskbarGlyph = taskbar.toolbarChevronBoundsForTest()
        assertEquals(taskbar.toolbarIconCentersForTest().last(), taskbarGlyph.centerX(), 0.01f)
        assertEquals(taskbarHit.centerY(), taskbarGlyph.centerY(), 0.01f)
        assertEquals(candidateGlyph.width(), taskbarGlyph.width(), 0.01f)
        assertEquals(candidateGlyph.height(), taskbarGlyph.height(), 0.01f)
        assertEquals("chevron width fills the common icon box", 1.64f * 9f * density, taskbarGlyph.width(), 0.02f * density)
        assertEquals("chevron height follows its aspect inside the box", taskbarGlyph.width() * (0.76f / 1.4f), taskbarGlyph.height(), 0.02f * density)
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
        val back = android.graphics.Rect(0, 0, grid.returnButtonForTest().width, grid.returnButtonForTest().height)
            .also { grid.offsetDescendantRectToMyCoords(grid.returnButtonForTest(), it) }
        assertEquals("the collapsed control sits where the open grid's return button does", back.left.toFloat(), bounds.left, 1f)
        assertEquals("the collapsed control ends where the open grid's return button does", back.right.toFloat(), bounds.right, 1f)
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

    @Test fun the_idle_toolbar_capsule_keeps_the_shared_edge_inset() {
        for (widthDp in listOf(250, 320, 411, 480)) {
            val view = idleBar(widthDp)
            val capsule = view.toolbarCapsuleBoundsForTest()
            val inset = ImeShapes.edgeInsetDp * density
            assertEquals("$widthDp dp left", inset, capsule.left, 0.01f)
            assertEquals("$widthDp dp right", view.width - inset, capsule.right, 0.01f)
        }
    }

    @Test fun seven_idle_toolbar_controls_have_contiguous_bounds_and_actions() {
        for (widthDp in listOf(250, 320, 480)) {
            val view = idleBar(widthDp)
            val controls = view.toolbarControlBoundsForTest()
            val capsule = view.toolbarCapsuleBoundsForTest()
            val centers = view.toolbarIconCentersForTest()
            assertEquals(7, controls.size)
            assertEquals(7, centers.size)
            assertEquals(capsule.left, controls.first().left, 0.01f)
            assertEquals(capsule.right, controls.last().right, 0.01f)
            assertTrue(controls.all { it.top == capsule.top && it.bottom == capsule.bottom })
            controls.zipWithNext().forEach { (left, right) -> assertEquals(left.right, right.left, 0.01f) }
            val spacing = centers.toList().zipWithNext { a, b -> b - a }
            assertTrue("the seven icons are evenly spaced", spacing.all { abs(it - spacing.first()) <= 0.01f })
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
        assertEquals(listOf("BRAND", "EMOJI", "LAYOUT", "EDIT", "CLIPBOARD", "TRANSLATE", "COLLAPSE"), actions)
    }

    @Test fun idle_toolbar_end_targets_follow_the_visible_capsule_shape() {
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
        assertEquals(
            "visible end centers register while transparent and outer pixels stay dead",
            listOf("BRAND", "COLLAPSE"),
            actions,
        )
    }

    @Test fun toolbar_press_highlights_match_their_complete_hit_cells() {
        val view = idleBar(360)
        val highlights = view.toolbarPressHighlightBoundsForTest()
        val hits = view.toolbarControlBoundsForTest()
        assertEquals(7, highlights.size)
        for (i in highlights.indices) {
            assertEquals("highlight $i left", hits[i].left, highlights[i].left, 0.01f)
            assertEquals("highlight $i top", hits[i].top, highlights[i].top, 0.01f)
            assertEquals("highlight $i right", hits[i].right, highlights[i].right, 0.01f)
            assertEquals("highlight $i bottom", hits[i].bottom, highlights[i].bottom, 0.01f)
        }
    }

    @Test fun seven_toolbar_glyphs_share_one_optical_icon_box() {
        val view = idleBar(360)
        val s = 9f * density
        val box = 1.64f * s
        data class IconSpec(val f: BarFunction, val w: Float, val h: Float, val optical: Float)
        val specs = listOf(
            IconSpec(BarFunction.BRAND, 1.28f, 1.59f, 1.06f),
            IconSpec(BarFunction.LAYOUT, 1.40f, 1.40f, 0.96f),
            IconSpec(BarFunction.EMOJI, 1.64f, 1.64f, 1.00f),
            IconSpec(BarFunction.EDIT, 1.00f, 1.64f, 1.02f),
            IconSpec(BarFunction.CLIPBOARD, 1.16f, 1.58f, 1.06f),
            IconSpec(BarFunction.TRANSLATE, 1.64f, 1.64f, 0.98f),
        )
        for (spec in specs) {
            val scale = view.toolbarIconScaleForTest(spec.f)
            assertEquals(
                "${spec.f} matches its optical fit",
                minOf(1.64f / spec.w, 1.64f / spec.h) * spec.optical,
                scale,
                0.001f,
            )
            assertEquals(
                "${spec.f} fills the box at its optical factor",
                box * spec.optical,
                maxOf(spec.w, spec.h) * s * scale,
                0.02f * density,
            )
        }
        val chevron = view.toolbarChevronBoundsForTest()
        assertEquals("the collapse chevron is width-bounded to the box", box, chevron.width(), 0.02f * density)
        assertTrue("the collapse chevron is no longer over-wide", chevron.width() < 1.64f * 9f * density * 1.2f)
    }

    @Test fun idle_toolbar_press_highlight_fills_the_visible_end_cell_and_is_clipped() {
        val view = idleBar(320)
        val capsule = view.toolbarCapsuleBoundsForTest()
        val resting = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(resting))
        view.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, capsule.left + density, capsule.centerY(), 0))
        val pressed = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(pressed))
        val edgeX = (capsule.left + density).toInt()
        assertEquals(
            "the rounded corner stays clipped out of the feedback",
            resting.getPixel(edgeX, (capsule.top + density).toInt()),
            pressed.getPixel(edgeX, (capsule.top + density).toInt()),
        )
        assertTrue(
            "the visible extreme capsule edge receives feedback",
            resting.getPixel(edgeX, capsule.centerY().toInt()) !=
                pressed.getPixel(edgeX, capsule.centerY().toInt()),
        )
        view.dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_CANCEL, capsule.left + density, capsule.centerY(), 0))
    }

    @Test fun idle_toolbar_has_semicircular_ends_and_no_chevron_divider() {
        val view = idleBar(360)
        val controls = view.toolbarControlBoundsForTest()
        assertEquals(controls.first().height() / 2f, view.toolbarOuterRadiusForTest(), 0.01f)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dividerX = (controls.last().left + density * 0.5f).toInt()
        assertEquals(ImePalette.STATIC_LIGHT.keySurface, bitmap.getPixel(dividerX, controls.last().centerY().toInt()))
    }

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

    @Test fun idle_toolbar_translate_slot_renders_the_two_letter_glyph() {
        val view = idleBar(320)
        val slot = view.toolbarControlBoundsForTest()[5]
        val cx = view.toolbarIconCentersForTest()[5]
        val cy = slot.centerY()
        val s = 9f * density * view.toolbarIconScaleForTest(BarFunction.TRANSLATE)
        val effectiveBounds = RectF(cx - s * 0.84f, cy - s * 0.84f, cx + s * 0.84f, cy + s * 0.84f)
        assertTrue(slot.contains(effectiveBounds))
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        fun hasIconInkNear(x: Float, y: Float): Boolean {
            val radius = (density * 1.5f).toInt().coerceAtLeast(1)
            for (py in y.toInt() - radius..y.toInt() + radius) {
                for (px in x.toInt() - radius..x.toInt() + radius) {
                    if (bitmap.getPixel(px, py) == ImePalette.STATIC_LIGHT.icon) return true
                }
            }
            return false
        }

        assertTrue("the front tile keeps its left edge", hasIconInkNear(cx - s * 0.84f, cy + s * 0.30f))
        assertTrue("the back tile keeps its top edge", hasIconInkNear(cx + s * 0.30f, cy - s * 0.84f))
        fun hasBlendedInkNear(x: Float, y: Float): Boolean {
            val bg = ImePalette.STATIC_LIGHT.keySurface
            val radius = (density * 1.5f).toInt().coerceAtLeast(1)
            for (py in y.toInt() - radius..y.toInt() + radius) {
                for (px in x.toInt() - radius..x.toInt() + radius) {
                    val p = bitmap.getPixel(px, py)
                    val d = abs(Color.red(p) - Color.red(bg)) + abs(Color.green(p) - Color.green(bg)) + abs(Color.blue(p) - Color.blue(bg))
                    if (d > 60) return true
                }
            }
            return false
        }
        assertTrue("the 文 keeps its bar", hasBlendedInkNear(cx + s * 0.35f, cy - s * 0.53f))
        assertTrue("the A keeps its apex", hasBlendedInkNear(cx - s * 0.30f, cy - s * 0.02f))
        assertTrue("the A keeps its crossbar", hasBlendedInkNear(cx - s * 0.30f, cy + s * 0.41f))
        assertFalse(
            "the front tile punches the back tile bottom edge clear",
            hasIconInkNear(cx + s * 0.08f, cy + s * 0.26f),
        )
        val ink = RectF(effectiveBounds).apply { inset(-(0.9f * density + 1f), -(0.9f * density + 1f)) }
        for (y in slot.top.toInt() until slot.bottom.toInt()) {
            for (x in slot.left.toInt() until slot.right.toInt()) {
                if (bitmap.getPixel(x, y) != ImePalette.STATIC_LIGHT.icon) continue
                assertTrue("ink at ($x,$y) stays inside the glyph box", ink.contains(x.toFloat(), y.toFloat()))
            }
        }
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
        val startX = v.expandControlBoundsForTest().left - 1f
        var t = 0L
        fun send(action: Int, x: Float) { v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0)); t += 16 }
        send(MotionEvent.ACTION_DOWN, startX)
        send(MotionEvent.ACTION_MOVE, startX - 16f); send(MotionEvent.ACTION_MOVE, startX - 32f)
        send(MotionEvent.ACTION_MOVE, startX - 48f); send(MotionEvent.ACTION_MOVE, startX - 64f)
        send(MotionEvent.ACTION_MOVE, startX - 80f)
        v.dispatchTouchEvent(MotionEvent.obtain(0, t, MotionEvent.ACTION_UP, startX - 80f, y, 0))
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
        val startX = v.expandControlBoundsForTest().left - 1f
        var t = 0L
        fun send(action: Int, x: Float) { v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0)); t += 16 }
        send(MotionEvent.ACTION_DOWN, startX)
        send(MotionEvent.ACTION_MOVE, startX - 16f); send(MotionEvent.ACTION_MOVE, startX - 32f)
        send(MotionEvent.ACTION_MOVE, startX - 48f); send(MotionEvent.ACTION_MOVE, startX - 64f)
        send(MotionEvent.ACTION_MOVE, startX - 80f)
        v.dispatchTouchEvent(MotionEvent.obtain(0, t, MotionEvent.ACTION_UP, startX - 80f, y, 0))
        assertTrue("precondition: a horizontal fling is running", v.isFlingingForTest())
        assertTrue("precondition: it scrolled away from the left edge", v.scrollXForTest() > 0f)

        v.setContent(List(40) { "新候选$it" }, "hao")
        assertFalse("new content cancels the fling", v.isFlingingForTest())
        assertEquals("the offset is reset to 0", 0f, v.scrollXForTest(), 0f)
        v.computeScroll()
        assertEquals("the next frame does NOT restore the stale fling offset", 0f, v.scrollXForTest(), 0f)
    }
}
