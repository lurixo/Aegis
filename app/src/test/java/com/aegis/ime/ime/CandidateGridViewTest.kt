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
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CandidateGridViewTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun rowPx() = (46 * density).toInt()

    private fun dp(v: Int) = (v * density).toInt()

    private fun measured(v: CandidateGridView = CandidateGridView(ctx)): CandidateGridView = v.apply {
        measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    @Test fun right_controls_distribute_evenly_and_bottom_anchor_clear_in_the_tall_column() {
        val h = (290 * density).toInt()
        val v = CandidateGridView(ctx)
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val back = v.returnButtonForTest().layoutParams as FrameLayout.LayoutParams
        val delete = v.backspaceButtonForTest().layoutParams as FrameLayout.LayoutParams
        val clear = v.clearButtonForTest().layoutParams as FrameLayout.LayoutParams

        assertEquals(rowPx(), back.height)
        assertEquals(rowPx(), delete.height)
        assertEquals(rowPx(), clear.height)
        assertEquals("返回 anchors to the top of the column", 0, back.topMargin)
        assertEquals("重输 bottom-anchors to the real column bottom, leaving no dead space", h, clear.topMargin + clear.height)

        val backCenter = back.topMargin + back.height / 2f
        val deleteCenter = delete.topMargin + delete.height / 2f
        val clearCenter = clear.topMargin + clear.height / 2f
        assertEquals("退格 sits at the column centre", h / 2f, deleteCenter, 1f)
        assertEquals("the three controls are evenly spaced", deleteCenter - backCenter, clearCenter - deleteCenter, 1f)
    }

    @Test fun short_column_still_squeezes_and_bottom_anchors_the_three_controls() {
        val h = (120 * density).toInt()
        val v = CandidateGridView(ctx)
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val back = v.returnButtonForTest().layoutParams as FrameLayout.LayoutParams
        val delete = v.backspaceButtonForTest().layoutParams as FrameLayout.LayoutParams
        val clear = v.clearButtonForTest().layoutParams as FrameLayout.LayoutParams

        assertTrue("controls shrink below the preferred row height when the column is short", back.height < rowPx())
        assertEquals(0, back.topMargin)
        assertEquals(h, clear.topMargin + clear.height)
        val backCenter = back.topMargin + back.height / 2f
        val deleteCenter = delete.topMargin + delete.height / 2f
        val clearCenter = clear.topMargin + clear.height / 2f
        assertEquals(deleteCenter - backCenter, clearCenter - deleteCenter, 1f)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun grid_collapse_glyph_reads_at_the_backspace_size_family() {
        val v = CandidateGridView(ctx)
        val collapse = glyphInkBounds(v.collapseGlyphForTest())
        val backspace = glyphInkBounds(v.backspaceGlyphForTest())
        assertTrue(
            "collapse chevron must rise out of the flat ~7dp mark into the backspace family: " +
                "collapse=${collapse.height()} backspace=${backspace.height()}",
            collapse.height() >= backspace.height() * 0.8f,
        )
        assertTrue(
            "collapse chevron must not overshoot the backspace height: " +
                "collapse=${collapse.height()} backspace=${backspace.height()}",
            collapse.height() <= backspace.height() * 1.1f,
        )
        assertTrue(
            "collapse chevron width stays in the backspace width family: " +
                "collapse=${collapse.width()} backspace=${backspace.width()}",
            collapse.width() <= backspace.width() * 1.2f,
        )
    }

    private fun glyphInkBounds(d: Drawable): RectF {
        val w = d.intrinsicWidth
        val h = d.intrinsicHeight
        d.setBounds(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.draw(Canvas(bmp))
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (Color.alpha(bmp.getPixel(x, y)) == 0) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return RectF(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
    }

    @Test fun selected_reading_uses_text_state_without_a_rectangular_background() {
        val v = CandidateGridView(ctx)
        v.setReadings(listOf("ni", "hao"), selected = 0)
        assertNull("selected reading should not paint a mismatched rectangle", v.selectedReadingBackgroundForTest(0))
    }

    @Test fun selected_reading_uses_accent_and_unselected_uses_default_text_color() {
        val pal = com.aegis.ime.ime.theme.ImePalette.STATIC_LIGHT
        val v = CandidateGridView(ctx).apply {
            applyPalette(pal)
            setReadings(listOf("zhang", "xiang", "xia"), selected = 1)
        }

        assertEquals("unselected reading uses the default candidate text color", pal.candidateText, v.readingTextColorForTest(0))
        assertEquals("selected reading uses the theme accent color", pal.accentBottom, v.readingTextColorForTest(1))
        assertEquals("other unselected readings also use the default candidate text color", pal.candidateText, v.readingTextColorForTest(2))
    }

    @Test fun repeated_content_does_not_rebuild_the_grid_or_reading_column() {
        val v = CandidateGridView(ctx)
        v.setCandidates(listOf("你", "好"))
        v.setReadings(listOf("ni", "hao"), selected = 0)
        val candidateRebuilds = v.candidateRebuildsForTest()
        val readingRebuilds = v.readingRebuildsForTest()

        v.setCandidates(listOf("你", "好"))
        v.setReadings(listOf("ni", "hao"), selected = 0)

        assertEquals(candidateRebuilds, v.candidateRebuildsForTest())
        assertEquals(readingRebuilds, v.readingRebuildsForTest())

        v.setCandidates(listOf("你", "好", "吗"))
        v.setReadings(listOf("ni", "ma"), selected = 1)
        assertEquals(candidateRebuilds + 1, v.candidateRebuildsForTest())
        assertEquals(readingRebuilds + 1, v.readingRebuildsForTest())
    }

    @Test fun reset_to_default_scrolls_candidate_and_reading_columns_to_top() {
        val v = CandidateGridView(ctx)
        v.setReadings((1..30).map { "r$it" })
        v.setCandidates((1..120).map { "候选$it" })
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        v.scrollForTest(gridY = 180, readingY = 96)
        assertTrue("precondition: candidate grid was scrolled", v.gridScrollYForTest() > 0)
        assertTrue("precondition: reading column was scrolled", v.readingScrollYForTest() > 0)

        v.resetToDefault()

        assertEquals("candidate grid scroll resets to top", 0, v.gridScrollYForTest())
        assertEquals("reading column scroll resets to top", 0, v.readingScrollYForTest())
    }

    @Test fun up_swipe_on_grid_backspace_clears_instead_of_deleting_one_unit() {
        var cleared = false
        var deleted = false
        val v = CandidateGridView(ctx).apply {
            onClear = { cleared = true }
            onBackspace = { deleted = true }
        }
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)

        val b = v.backspaceButtonForTest()
        val x = b.width / 2f
        b.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, b.height * 0.75f, 0))
        b.dispatchTouchEvent(MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, x, b.height * 0.15f, 0))

        assertTrue("up-swipe on expanded-grid backspace must clear the preedit", cleared)
        assertFalse("up-swipe must not also fire one-unit backspace", deleted)
    }

    @Test fun single_grapheme_candidates_pack_six_columns_per_row() {
        val v = measured()
        v.setCandidates(listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "百", "千"))
        assertEquals(listOf(6, 6), v.rowColumnCountsForTest())
        assertEquals(listOf(6, 6), v.rowTextsForTest().map { it.size })
    }

    @Test fun two_grapheme_candidates_pack_four_columns_per_row() {
        val v = measured()
        v.setCandidates(listOf("你好", "再见", "谢谢", "不用", "可以", "没有", "什么", "怎么"))
        assertEquals(listOf(4, 4), v.rowColumnCountsForTest())
        assertEquals(listOf(4, 4), v.rowTextsForTest().map { it.size })
    }

    @Test fun row_closes_early_when_a_wider_word_would_shrink_its_cap() {
        val v = measured()
        v.setCandidates(listOf("一", "二", "三", "四", "五", "六六"))
        assertEquals(listOf(6, 4), v.rowColumnCountsForTest())
        assertEquals(listOf(listOf("一", "二", "三", "四", "五"), listOf("六六")), v.rowTextsForTest())
    }

    @Test fun rows_holding_a_word_of_three_or_more_graphemes_use_three_columns() {
        val v = measured()
        v.setCandidates(listOf("你好吗", "一", "二二"))
        assertEquals(listOf(3), v.rowColumnCountsForTest())
        assertEquals(listOf(listOf("你好吗", "一", "二二")), v.rowTextsForTest())
        v.setCandidates(listOf("一", "四个字啦"))
        assertEquals(listOf(3), v.rowColumnCountsForTest())
        assertEquals(listOf(listOf("一", "四个字啦")), v.rowTextsForTest())
    }

    @Test fun engine_order_is_preserved_and_picks_stay_global_across_rows() {
        val v = measured()
        val words = listOf("你", "你好", "尼", "拟", "泥", "逆", "妮", "倪", "你好吗", "腻")
        v.setCandidates(words)
        assertEquals(words, v.renderedCandidateTextsForTest())
        val picked = ArrayList<Int>()
        v.onPick = { picked.add(it) }
        for (i in words.indices) assertTrue(v.tapCandidateForTest(i))
        assertEquals(words.indices.toList(), picked)
    }

    @Test fun trailing_empty_cells_are_not_clickable_ghosts() {
        val v = measured()
        var picked = -1
        v.onPick = { picked = it }
        v.setCandidates(listOf("一", "二", "三", "四", "五", "六六"))
        assertTrue(v.tapCandidateForTest(5))
        assertEquals(5, picked)
        assertFalse("there is no tappable cell past the last candidate", v.tapCandidateForTest(6))
        assertEquals(listOf(5, 1), v.rowTextsForTest().map { it.size })
    }

    @Test fun column_capacity_counts_grapheme_clusters_not_chars() {
        assertEquals(1, GraphemeText.clusterCount("👨‍👩‍👧"))
        assertEquals(2, GraphemeText.clusterCount("你好"))
        assertEquals(0, GraphemeText.clusterCount(""))
        val v = measured()
        v.setCandidates(listOf("👨‍👩‍👧", "一", "二", "三", "四", "五"))
        assertEquals(listOf(6), v.rowColumnCountsForTest())
        assertEquals(listOf(6), v.rowTextsForTest().map { it.size })
    }

    @Test fun cells_span_the_table_in_equal_widths() {
        val v = measured()
        v.setCandidates(listOf("一", "二", "三", "四", "五", "六"))
        val tableW = (360 * density).toInt() - dp(60 + 64) - dp(4 + 4)
        val widths = (0..5).map { v.chipCellWidthForTest(it) }
        assertEquals(tableW, widths.sum())
        assertTrue("cell widths differ by at most a rounding pixel", widths.max() - widths.min() <= 1)
    }

    @Test fun candidate_text_sizes_step_down_with_grapheme_length() {
        val v = measured()
        v.setCandidates(listOf("一", "二二", "三三三", "四四四四"))
        assertEquals(18f, v.chipTextSizeSpForTest(0), 0.01f)
        assertEquals(18f, v.chipTextSizeSpForTest(1), 0.01f)
        assertEquals(16f, v.chipTextSizeSpForTest(2), 0.01f)
        assertEquals(14f, v.chipTextSizeSpForTest(3), 0.01f)
    }

    @Test fun overlong_candidates_shrink_to_the_floor_and_ellipsize() {
        val v = measured()
        v.setCandidates(listOf("超".repeat(20)))
        assertEquals(10f, v.chipTextSizeSpForTest(0), 0.01f)
        assertEquals(TextUtils.TruncateAt.END, v.chipEllipsizeForTest(0))
    }

    @Test fun reading_rail_is_an_inset_card_matching_the_scroll_column_style() {
        val v = measured()
        assertEquals(listOf(dp(51), dp(6), dp(3), dp(8), dp(8)), v.railLayoutForTest().toList())
        assertEquals(8f * density, v.railCornerRadiusForTest(), 0.001f)
        assertFalse("platform scrollbar must stay off in favour of the custom thumb", v.railScrollbarEnabledForTest())
        assertEquals(listOf(dp(4), dp(4), dp(8), dp(8)), v.tableLayoutForTest().toList())
        assertEquals(8f * density, v.tableCornerRadiusForTest(), 0.001f)
        assertNull("the table paints no fill of its own", v.tableBackgroundForTest())
    }

    @Test fun rail_thumb_is_absent_without_overflow() {
        val v = measured(CandidateGridView(ctx).apply { setReadings(listOf("ni", "hao")) })
        assertNull(v.railThumbRectForTest())
    }

    @Test fun rail_thumb_follows_the_scroll_column_math() {
        val v = measured(CandidateGridView(ctx).apply { setReadings((1..30).map { "r$it" }) })
        val (trackH, contentH) = v.railTrackAndContentForTest()
        assertTrue("precondition: reading content overflows the rail", contentH > trackH)
        val rect = requireNotNull(v.railThumbRectForTest())
        val expectedH = maxOf(18f * density, trackH.toFloat() * trackH / contentH)
        assertEquals(expectedH, rect.height(), 0.01f)
        assertEquals(2.5f * density, rect.width(), 0.01f)
        assertEquals(dp(51) - 2f * density, rect.right, 0.01f)
        assertEquals(0f, rect.top, 0.01f)

        v.scrollForTest(gridY = 0, readingY = 120)
        val scrolled = requireNotNull(v.railThumbRectForTest())
        val expectedTop = 120f + 120f / (contentH - trackH) * (trackH - expectedH)
        assertEquals(expectedTop, scrolled.top, 0.01f)
    }

    @Test fun readings_shrink_to_fit_the_rail_and_short_readings_keep_title_size() {
        val v = CandidateGridView(ctx)
        v.setReadings(listOf("ni", "zhuang"))
        assertEquals(18f, v.readingTextSizeSpForTest(0), 0.01f)
        val fitted = v.readingTextSizeSpForTest(1)
        assertTrue("zhuang must shrink below the title size, got $fitted", fitted < 18f)
        assertTrue("zhuang must not shrink past the floor, got $fitted", fitted >= 11f)
    }

    @Test fun palette_flows_to_rail_and_table_in_static_light_and_dark() {
        for (pal in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val v = CandidateGridView(ctx).apply { applyPalette(pal) }
            assertEquals(Triple(pal.railBg, pal.separator, Motion.withAlpha(pal.icon, 0x55)), v.railColorsForTest())
            assertEquals(pal.separator, v.tableSeparatorColorForTest())
        }
    }
}
