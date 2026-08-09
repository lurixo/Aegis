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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
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
class CandidateGridViewTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun rowPx() = (48 * density).toInt()

    private fun dp(v: Int) = (v * density).toInt()
    private fun sideWidth() = ((360 * density) * Layouts.NINE_SIDE_FRACTION).roundToInt()
    private fun actionWidth() = minOf(sideWidth(), dp(Layouts.CANDIDATE_ACTION_WIDTH_DP))
    private fun tableWidth() = (360 * density).toInt() - sideWidth() - actionWidth() - dp(4 + 4)

    private fun measured(v: CandidateGridView = CandidateGridView(ctx)): CandidateGridView = v.apply {
        measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    @Test fun right_controls_take_row_one_and_the_seams_under_rows_three_and_five() {
        val h = (320 * density).toInt()
        val v = CandidateGridView(ctx)
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val back = v.returnButtonForTest().layoutParams as FrameLayout.LayoutParams
        val delete = v.backspaceButtonForTest().layoutParams as FrameLayout.LayoutParams
        val clear = v.clearButtonForTest().layoutParams as FrameLayout.LayoutParams
        val separator = v.tableDividerHeightForTest()
        val stride = v.candidateRowStrideForTest()

        assertTrue("the candidate rows keep a separator between them", separator >= 1)
        assertEquals("the row pitch carries the separator", rowPx() + separator, stride)
        assertEquals(rowPx(), back.height)
        assertEquals(rowPx(), delete.height)
        assertEquals(rowPx(), clear.height)
        assertEquals("收起 sits on candidate row 1", dp(8), back.topMargin)
        assertEquals(
            "退格 centres on the seam under candidate row 3",
            dp(8) + stride * 2 + rowPx() + separator / 2,
            delete.topMargin + delete.height / 2,
        )
        assertEquals(
            "重输 centres on the seam under candidate row 5",
            dp(8) + stride * 4 + rowPx() + separator / 2,
            clear.topMargin + clear.height / 2,
        )
        assertEquals("candidate row 2 stays clear", 0, listOf(back, delete, clear).count { it.topMargin < dp(8) + stride + rowPx() && dp(8) + stride < it.topMargin + it.height })
        assertTrue("the three controls stay inside the tall column", clear.topMargin + clear.height <= h)
    }

    @Test fun a_column_that_only_fits_five_rows_falls_back_to_the_even_split() {
        val probe = CandidateGridView(ctx)
        probe.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        probe.layout(0, 0, probe.measuredWidth, probe.measuredHeight)
        val stride = probe.candidateRowStrideForTest()
        val h = dp(8) + stride * 4 + rowPx() + dp(8)

        val v = CandidateGridView(ctx)
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val back = v.returnButtonForTest().layoutParams as FrameLayout.LayoutParams
        val clear = v.clearButtonForTest().layoutParams as FrameLayout.LayoutParams

        assertEquals("candidate row 5 still fits, so a row-aligned rule would take the seam branch", 0, back.topMargin)
        assertTrue("the retype must stay inside the column, got ${clear.topMargin + clear.height} of $h", clear.topMargin + clear.height <= h)
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

    @Test fun right_controls_share_one_vertical_center_line() {
        val v = measured()
        val columnCenter = actionWidth() / 2
        val backCenter = v.returnButtonForTest().paddingLeft + v.collapseGlyphForTest().intrinsicWidth / 2
        val deleteCenter = v.backspaceButtonForTest().paddingLeft + v.backspaceGlyphForTest().intrinsicWidth / 2
        assertTrue("collapse glyph centers on the column center line, got $backCenter vs $columnCenter", kotlin.math.abs(columnCenter - backCenter) <= 1)
        assertTrue("backspace glyph centers on the same line, got $deleteCenter vs $columnCenter", kotlin.math.abs(columnCenter - deleteCenter) <= 1)
        assertEquals("collapse and backspace shift right by the same padding", v.returnButtonForTest().paddingLeft, v.backspaceButtonForTest().paddingLeft)
        assertEquals("redo keeps its label centered so the three share one line", Gravity.CENTER, v.clearButtonForTest().gravity)
    }

    @Test fun grid_collapse_glyph_matches_the_toolbar_collapse_chevron_box() {
        val v = CandidateGridView(ctx)
        val collapse = glyphInkBounds(v.collapseGlyphForTest())
        val backspace = glyphInkBounds(v.backspaceGlyphForTest())
        val boxWidth = 1.64f * 9f * density
        val stroke = 2f * density
        assertEquals(
            "collapse chevron ink fills the toolbar collapse icon box width: collapse=${collapse.width()}",
            boxWidth + stroke,
            collapse.width(),
            3f * density,
        )
        assertEquals(
            "collapse chevron keeps the toolbar chevron aspect: collapse=${collapse.height()}",
            boxWidth * (0.76f / 1.40f) + stroke,
            collapse.height(),
            3f * density,
        )
        assertTrue(
            "resized collapse chevron is shorter than the backspace glyph: " +
                "collapse=${collapse.height()} backspace=${backspace.height()}",
            collapse.height() < backspace.height(),
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

    @Test fun grid_backspace_shows_press_feedback_on_touch_down_like_the_other_controls() {
        val v = measured()
        val b = v.backspaceButtonForTest()
        assertTrue("backspace carries a ripple foreground like collapse and redo", b.foreground is RippleDrawable)
        val x = b.width / 2f
        b.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, b.height / 2f, 0))
        assertTrue("touch-down puts backspace into the pressed state so its ripple fires", b.isPressed)
        b.dispatchTouchEvent(MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, x, b.height / 2f, 0))
        assertFalse("release clears the pressed state", b.isPressed)
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

    @Test fun single_grapheme_candidates_pack_five_columns_per_row() {
        val v = measured()
        v.setCandidates(listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十"))
        assertEquals(listOf(5, 5), v.rowColumnCountsForTest())
        assertEquals(listOf(5, 5), v.rowTextsForTest().map { it.size })
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
        assertEquals("columns equal the candidates actually placed, never a padded cap", listOf(5, 1), v.rowColumnCountsForTest())
        assertEquals(listOf(listOf("一", "二", "三", "四", "五"), listOf("六六")), v.rowTextsForTest())
    }

    @Test fun longer_words_take_wider_cells_with_fewer_columns_to_keep_base_size() {
        val v = measured()
        v.setCandidates(listOf("你好吗", "一", "二二"))
        assertEquals(listOf(3), v.rowColumnCountsForTest())
        assertEquals(listOf(listOf("你好吗", "一", "二二")), v.rowTextsForTest())
        v.setCandidates(listOf("一", "四个字啦"))
        assertEquals(listOf(2), v.rowColumnCountsForTest())
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

    @Test fun under_filled_rows_fill_the_width_with_no_trailing_empty_cells() {
        val v = measured()
        var picked = -1
        v.onPick = { picked = it }
        v.setCandidates(listOf("一", "二", "三", "四", "五", "六六"))
        assertTrue(v.tapCandidateForTest(5))
        assertEquals(5, picked)
        assertFalse("there is no tappable cell past the last candidate", v.tapCandidateForTest(6))
        assertEquals("each row holds exactly its candidates, with no padded-out empty cells", listOf(5, 1), v.rowTextsForTest().map { it.size })
        assertEquals("columns match the candidate count, so every cell is real", listOf(5, 1), v.rowColumnCountsForTest())
        val tableW = tableWidth()
        assertEquals("the five-candidate row spreads edge to edge", tableW, (0..4).sumOf { v.chipCellWidthForTest(it) })
        assertEquals("the lone candidate fills the whole row width", tableW, v.chipCellWidthForTest(5))
    }

    @Test fun column_capacity_counts_grapheme_clusters_not_chars() {
        assertEquals(1, GraphemeText.clusterCount("👨‍👩‍👧"))
        assertEquals(2, GraphemeText.clusterCount("你好"))
        assertEquals(0, GraphemeText.clusterCount(""))
        val v = measured()
        v.setCandidates(listOf("👨‍👩‍👧", "一", "二", "三", "四", "五"))
        assertEquals(listOf(5, 1), v.rowColumnCountsForTest())
        assertEquals(listOf(5, 1), v.rowTextsForTest().map { it.size })
    }

    @Test fun cells_span_the_table_in_equal_widths() {
        val v = measured()
        v.setCandidates(listOf("一", "二", "三", "四", "五"))
        val tableW = tableWidth()
        val widths = (0..4).map { v.chipCellWidthForTest(it) }
        assertEquals(tableW, widths.sum())
        assertTrue("cell widths differ by at most a rounding pixel", widths.max() - widths.min() <= 1)
    }

    @Test fun wide_mixed_grid_keeps_five_single_character_columns_at_the_first_row_text_size() {
        val width = dp(411)
        val v = CandidateGridView(ctx)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        v.setCandidates(
            listOf(
                "目前", "没有", "内容", "美国",
                "你", "泥", "拟", "逆", "妮", "倪", "腻", "匿", "昵", "旎",
            ),
        )

        assertEquals(listOf(4, 5, 5), v.rowColumnCountsForTest())
        for (i in 4..13) {
            assertEquals(v.chipTextSizeSpForTest(0), v.chipTextSizeSpForTest(i), 0.01f)
        }
    }

    @Test fun candidate_table_and_action_hit_columns_use_the_narrower_right_boundary() {
        val v = measured()
        val actionColumn = v.returnButtonForTest().parent as View
        val controls = listOf(v.returnButtonForTest(), v.backspaceButtonForTest(), v.clearButtonForTest())

        assertEquals(actionWidth(), actionColumn.width)
        assertEquals(v.width - actionWidth(), actionColumn.left)
        assertTrue(actionWidth() < sideWidth())
        controls.forEach {
            assertEquals(actionWidth(), it.width)
            assertTrue(it.isClickable)
        }
    }

    @Test fun a_full_row_keeps_the_base_size_regardless_of_grapheme_length() {
        val v = measured()
        v.setCandidates(listOf("一", "二二", "三三三"))
        assertEquals("a filled row is one row", 1, v.rowColumnCountsForTest().size)
        assertEquals("the row is filled to its cap", 3, v.rowColumnCountsForTest().first())
        for (i in 0..2) assertEquals("candidate $i in a filled row stays at base 18sp", 18f, v.chipTextSizeSpForTest(i), 0.01f)
    }

    @Test fun an_under_filled_row_keeps_the_base_size_and_still_fills_the_width() {
        val v = measured()
        v.setCandidates(listOf("优", "沃", "卧", "奏", "窝"))
        assertEquals("five single-grapheme candidates share one row", listOf(5), v.rowColumnCountsForTest())
        for (i in 0..4) assertEquals("under-filled candidate $i stays at base 18sp, never enlarged", 18f, v.chipTextSizeSpForTest(i), 0.01f)
        val tableW = tableWidth()
        assertEquals("the sparse row still spreads edge to edge with no trailing empty cell", tableW, (0..4).sumOf { v.chipCellWidthForTest(it) })
    }

    @Test fun a_long_row_and_a_lone_short_candidate_both_keep_the_base_size() {
        val v = measured()
        v.setCandidates(listOf("你让我说什么说", "你"))
        assertEquals("each candidate lands on its own full-width row", listOf(1, 1), v.rowColumnCountsForTest())
        assertEquals(listOf(listOf("你让我说什么说"), listOf("你")), v.rowTextsForTest())
        assertEquals("a long candidate stays at base size", 18f, v.chipTextSizeSpForTest(0), 0.01f)
        assertEquals("a lone short candidate stays at base size too, never enlarged", 18f, v.chipTextSizeSpForTest(1), 0.01f)
    }

    @Test fun overlong_candidates_shrink_to_the_floor_and_ellipsize() {
        val v = measured()
        v.setCandidates(listOf("超".repeat(80)))
        assertEquals(10f, v.chipTextSizeSpForTest(0), 0.01f)
        assertEquals(TextUtils.TruncateAt.END, v.chipEllipsizeForTest(0))
    }

    @Test fun sharing_a_full_row_and_a_lone_candidate_all_keep_the_base_size() {
        val packed = measured()
        packed.setCandidates(listOf("一二三四五", "六七八九十", "上中下左右"))
        assertEquals("two five-grapheme candidates fill the first row; the third takes its own", listOf(2, 1), packed.rowColumnCountsForTest())
        assertEquals("a candidate sharing a filled row keeps base size", 18f, packed.chipTextSizeSpForTest(0), 0.01f)
        assertEquals("its row-mate also keeps base size", 18f, packed.chipTextSizeSpForTest(1), 0.01f)
        assertEquals("the lone third candidate keeps base size too, never enlarged", 18f, packed.chipTextSizeSpForTest(2), 0.01f)

        val alone = measured()
        alone.setCandidates(listOf("一二三四五"))
        assertEquals("a five-grapheme candidate alone on a wide row keeps base size", 18f, alone.chipTextSizeSpForTest(0), 0.01f)
    }

    @Test fun reading_rail_is_an_inset_card_matching_the_scroll_column_style() {
        val v = measured()
        val side = sideWidth()
        assertEquals(listOf(side - dp(6), dp(3), dp(3), dp(8), dp(8)), v.railLayoutForTest().toList())
        assertEquals(actionWidth(), v.rightColumnWidthForTest())
        assertEquals(8f * density, v.railCornerRadiusForTest(), 0.001f)
        assertFalse("platform scrollbar must stay off in favour of the custom thumb", v.railScrollbarEnabledForTest())
        assertEquals(listOf(dp(4), dp(4), dp(8), dp(8)), v.tableLayoutForTest().toList())
        assertEquals(8f * density, v.tableCornerRadiusForTest(), 0.001f)
        assertNull("the table paints no fill of its own", v.tableBackgroundForTest())
    }

    @Test fun expanded_reading_rail_matches_the_unexpanded_nine_key_scroll_card_width() {
        val expanded = measured()
        val keyboard = KeyboardView(ctx).apply {
            setLayout(Layouts.forId(LayoutId.NINE, Lang.CN), false, false, Lang.CN)
            measure(
                View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }

        assertEquals(keyboard.scrollRegionForTest().width(), expanded.railLayoutForTest()[0].toFloat(), 1f)
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
        assertEquals(v.railLayoutForTest()[0] - 2f * density, rect.right, 0.01f)
        assertEquals(0f, rect.top, 0.01f)

        v.scrollForTest(gridY = 0, readingY = 120)
        val scrolled = requireNotNull(v.railThumbRectForTest())
        val expectedTop = 120f + 120f / (contentH - trackH) * (trackH - expectedH)
        assertEquals(expectedTop, scrolled.top, 0.01f)
    }

    @Test fun readings_keep_title_size_without_scaling() {
        val v = CandidateGridView(ctx)
        v.setReadings(listOf("ni", "zhuang"))
        assertEquals(18f, v.readingTextSizeSpForTest(0), 0.01f)
        assertEquals(18f, v.readingTextSizeSpForTest(1), 0.01f)
    }

    @Test fun palette_flows_to_rail_and_table_in_static_light_and_dark() {
        for (pal in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val v = CandidateGridView(ctx).apply { applyPalette(pal) }
            assertEquals(Triple(pal.railBg, pal.separator, Motion.withAlpha(pal.icon, 0x55)), v.railColorsForTest())
            assertEquals(pal.separator, v.tableSeparatorColorForTest())
            assertEquals(dp(1), v.tableDividerHeightForTest())
            assertEquals(
                "the candidate row separator paints the palette separator colour",
                pal.separator,
                (v.tableDividerForTest() as ColorDrawable).color,
            )
        }
    }
}
