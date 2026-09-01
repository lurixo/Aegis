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
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
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

    @Test fun short_column_keeps_full_size_primary_controls_and_hides_the_redundant_clear_action() {
        val h = (120 * density).toInt()
        val v = CandidateGridView(ctx)
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val back = v.returnButtonForTest().layoutParams as FrameLayout.LayoutParams
        val delete = v.backspaceButtonForTest().layoutParams as FrameLayout.LayoutParams

        assertEquals(rowPx(), back.height)
        assertEquals(rowPx(), delete.height)
        assertEquals(0, back.topMargin)
        assertEquals(h, delete.topMargin + delete.height)
        assertEquals(View.GONE, v.clearButtonForTest().visibility)
        assertTrue("the two 48dp controls do not overlap", back.topMargin + back.height <= delete.topMargin)
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

    @Test fun selected_reading_uses_text_state_with_a_transparent_shared_press_surface() {
        val v = CandidateGridView(ctx)
        v.setReadings(listOf("ni", "hao"), selected = 0)
        val surface = v.selectedReadingBackgroundForTest(0) as? ImeKeySurface
        assertTrue("selected reading uses the same stateful surface as other IME keys", surface != null)
        assertEquals("the shared surface has no idle rectangular face", Color.TRANSPARENT, requireNotNull(surface).faceColor)
        assertNull("the shared surface replaces the old platform ripple", v.readingTileForTest(0)?.foreground)
    }

    @Test fun selected_reading_uses_accent_and_unselected_uses_default_text_color() {
        val pal = com.aegis.ime.ime.theme.ImePalette.STATIC_LIGHT
        val v = CandidateGridView(ctx).apply {
            applyPalette(pal)
            setReadings(listOf("zhang", "xiang", "xia"), selected = 1)
        }

        assertEquals("unselected reading uses the default candidate text color", pal.candidateText, v.readingTextColorForTest(0))
        assertEquals("selected reading uses the first-candidate color", pal.candidateFirst, v.readingTextColorForTest(1))
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
        assertTrue("backspace keeps a resting action-key surface", b.background != null)
        assertNull("backspace no longer uses a platform foreground ripple", b.foreground)
        val x = b.width / 2f
        b.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, b.height / 2f, 0))
        assertTrue("touch-down puts backspace into the pressed state", b.isPressed)
        assertEquals(1f, v.backspaceFeedbackLevelForTest(), 0f)
        b.dispatchTouchEvent(MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, x, b.height / 2f, 0))
        assertFalse("release clears the pressed state", b.isPressed)
        assertEquals(0f, v.backspaceFeedbackLevelForTest(), 0f)
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

    @Test fun single_grapheme_candidates_pack_four_columns_per_row() {
        val v = measured()
        v.setCandidates(listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十"))
        assertEquals(listOf(4, 4, 2), v.rowColumnCountsForTest())
        assertEquals(listOf(4, 4, 2), v.rowTextsForTest().map { it.size })
    }

    @Test fun multi_grapheme_candidates_take_the_same_four_columns_per_row() {
        val v = measured()
        v.setCandidates(listOf("你好", "再见", "谢谢", "不用", "可以", "没有", "什么", "怎么"))
        assertEquals(listOf(4, 4), v.rowColumnCountsForTest())
        assertEquals(listOf(4, 4), v.rowTextsForTest().map { it.size })
    }

    @Test fun a_wider_word_no_longer_closes_the_row_before_the_fourth_column() {
        val v = measured()
        v.setCandidates(listOf("一", "二", "三", "四", "五", "六六"))
        assertEquals(listOf(4, 2), v.rowColumnCountsForTest())
        assertEquals(listOf(listOf("一", "二", "三", "四"), listOf("五", "六六")), v.rowTextsForTest())
        v.setCandidates(listOf("你好吗", "一", "二二", "四个字啦", "五"))
        assertEquals(listOf(4, 1), v.rowColumnCountsForTest())
        assertEquals(listOf(listOf("你好吗", "一", "二二", "四个字啦"), listOf("五")), v.rowTextsForTest())
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

    @Test fun pinyin_projection_limits_phrases_to_three_rows_and_keeps_every_single_item() {
        val phrases = List(22) { "词${('A'.code + it).toChar()}" }
        val singles = List(18) { (0x4e00 + it).toChar().toString() } + listOf("𰻞", "❤️", "👨‍👩‍👧")
        val source = ArrayList<String>().apply {
            for (i in phrases.indices) {
                add(phrases[i])
                if (i in singles.indices) add(singles[i])
            }
        }
        val v = measured()
        val policy = CandidateProjectionPolicy.PINYIN

        v.setCandidates(source, projection = policy)

        val rows = v.rowTextsForTest()
        val rendered = v.renderedCandidateTextsForTest()
        val shownPhrases = rendered.filter { GraphemeText.clusterCount(it) > 1 }
        assertEquals("the Pinyin product policy is three phrase rows", 3, policy.maxPhraseRows)
        assertEquals(
            "three rows of the fixed four columns cap the phrases at twelve",
            policy.maxPhraseRows * CandidateGridView.COLUMNS,
            shownPhrases.size,
        )
        assertTrue("the source has enough content to exercise later rows", rows.size > policy.maxPhraseRows)
        assertTrue(
            "no phrase may appear after the policy boundary",
            rows.drop(policy.maxPhraseRows).flatten().all { GraphemeText.clusterCount(it) == 1 },
        )
        assertEquals("every single item stays reachable and in engine order", singles, rendered.filter { GraphemeText.clusterCount(it) == 1 })
        assertEquals("visible phrases keep their engine ranking", phrases.take(shownPhrases.size), shownPhrases)
        assertTrue("excess phrases are removed from the expanded projection", shownPhrases.size < phrases.size)
        assertTrue(
            "phrases precede all single items in the projection",
            rendered.dropWhile { GraphemeText.clusterCount(it) > 1 }.all { GraphemeText.clusterCount(it) == 1 },
        )
        assertEquals(rendered, v.renderedSourceIndicesForTest().map(source::get))
    }

    @Test fun pinyin_projection_places_single_items_immediately_after_underfilled_phrase_rows() {
        val phrases = List(5) { "词${('A'.code + it).toChar()}" }
        val singles = listOf("你", "泥", "拟", "𰻞", "❤️")
        val v = measured()

        v.setCandidates(singles.take(2) + phrases + singles.drop(2), projection = CandidateProjectionPolicy.PINYIN)

        assertEquals(phrases.take(4), v.rowTextsForTest()[0])
        assertEquals(phrases.drop(4) + singles.take(3), v.rowTextsForTest()[1])
        assertEquals(singles, v.renderedCandidateTextsForTest().filter { GraphemeText.clusterCount(it) == 1 })
    }

    @Test fun pinyin_projection_picks_original_controller_indices_after_reordering() {
        val source = listOf("你", "词A", "泥", "词B", "拟", "词C", "𰻞", "词D", "❤️")
        val v = measured()
        val picked = ArrayList<Int>()
        v.onPick = picked::add

        v.setCandidates(source, projection = CandidateProjectionPolicy.PINYIN)
        for (i in v.renderedCandidateTextsForTest().indices) assertTrue(v.tapCandidateForTest(i))

        assertEquals(v.renderedSourceIndicesForTest(), picked)
        assertEquals(v.renderedCandidateTextsForTest(), picked.map(source::get))
    }

    @Test fun pinyin_projection_shows_the_same_phrases_at_every_panel_width() {
        val phrases = List(24) { "候选词组${('A'.code + it).toChar()}" }
        val singles = List(16) { (0x4e20 + it).toChar().toString() }
        val source = phrases + singles
        val v = CandidateGridView(ctx)
        val shown = ArrayList<List<String>>()
        for (widthDp in listOf(240, 360, 800)) {
            v.measure(
                View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(250), View.MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            v.setCandidates(source, projection = CandidateProjectionPolicy.PINYIN)
            shown.add(v.renderedCandidateTextsForTest().filter { GraphemeText.clusterCount(it) > 1 })
            assertEquals(singles, v.renderedCandidateTextsForTest().filter { GraphemeText.clusterCount(it) == 1 })
            assertTrue(
                v.rowTextsForTest().drop(CandidateProjectionPolicy.PINYIN.maxPhraseRows).flatten()
                    .all { GraphemeText.clusterCount(it) == 1 },
            )
        }

        assertEquals("a fixed column count makes the phrase cut width-independent", 1, shown.distinct().size)
        assertEquals(phrases.take(CandidateProjectionPolicy.PINYIN.maxPhraseRows * CandidateGridView.COLUMNS), shown.first())
    }

    @Test fun removing_the_projection_restores_the_complete_engine_order() {
        val source = listOf("你", "词A", "泥") + List(20) { "词${('B'.code + it).toChar()}" } + listOf("拟", "𰻞")
        val v = measured()

        v.setCandidates(source)
        assertEquals(source, v.renderedCandidateTextsForTest())
        v.setCandidates(source, projection = CandidateProjectionPolicy.PINYIN)
        assertTrue(v.renderedCandidateTextsForTest() != source)
        v.setCandidates(source, projection = null)

        assertEquals(source, v.renderedCandidateTextsForTest())
        assertEquals(source.indices.toList(), v.renderedSourceIndicesForTest())
    }

    @Test fun a_part_filled_row_keeps_its_four_cells_and_only_the_filled_ones_answer_taps() {
        val v = measured()
        var picked = -1
        v.onPick = { picked = it }
        v.setCandidates(listOf("一", "二", "三", "四", "五", "六六"))
        assertTrue(v.tapCandidateForTest(5))
        assertEquals(5, picked)
        assertFalse("there is no tappable cell past the last candidate", v.tapCandidateForTest(6))
        assertEquals("the trailing row still owns four cells", listOf("五", "六六", "", ""), rowCellTexts(v, 1))
        assertEquals(
            "the cells past the last candidate take no clicks",
            listOf(true, true, false, false),
            rowCells(v, 1).map { it.isClickable },
        )
        val tableW = tableWidth()
        assertEquals("the four cells spread edge to edge", tableW, (0..3).sumOf { v.chipCellWidthForTest(it) })
        assertEquals("a part-filled row keeps the same four cell widths", tableW, (4..7).sumOf { v.chipCellWidthForTest(it) })
    }

    private fun rowCells(v: CandidateGridView, row: Int): List<TextView> {
        val view = v.candidateRowViewForTest(row)
        return (0 until view.childCount).map { view.getChildAt(it) as TextView }
    }

    private fun rowCellTexts(v: CandidateGridView, row: Int): List<String> =
        rowCells(v, row).map { it.text.toString() }

    @Test fun column_capacity_counts_grapheme_clusters_not_chars() {
        assertEquals(1, GraphemeText.clusterCount("👨‍👩‍👧"))
        assertEquals(2, GraphemeText.clusterCount("你好"))
        assertEquals(0, GraphemeText.clusterCount(""))
        val v = measured()
        v.setCandidates(listOf("👨‍👩‍👧", "一", "二", "三", "四", "五"))
        assertEquals(listOf(4, 2), v.rowColumnCountsForTest())
        assertEquals(listOf(4, 2), v.rowTextsForTest().map { it.size })
    }

    @Test fun cells_span_the_table_in_equal_widths() {
        val v = measured()
        v.setCandidates(listOf("一", "二", "三", "四", "五"))
        val tableW = tableWidth()
        val widths = (0 until CandidateGridView.COLUMNS).map { v.chipCellWidthForTest(it) }
        assertEquals(tableW, widths.sum())
        assertTrue("cell widths differ by at most a rounding pixel", widths.max() - widths.min() <= 1)
    }

    @Test fun wide_mixed_grid_keeps_four_single_character_columns_at_the_first_row_text_size() {
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

        assertEquals(listOf(4, 4, 4, 2), v.rowColumnCountsForTest())
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

    @Test fun a_row_of_mixed_lengths_shares_one_size_that_fits_its_widest_member() {
        val v = measured()
        v.setCandidates(listOf("一", "二二", "三三三"))
        assertEquals("three candidates take one row of the fixed four", listOf(3), v.rowColumnCountsForTest())
        val shared = v.chipTextSizeSpForTest(0)
        assertTrue("a three-grapheme word cannot hold base size in a quarter-width cell", shared < 19f)
        for (i in 1..2) assertEquals("every cell in the row shares that size", shared, v.chipTextSizeSpForTest(i), 0.01f)
    }

    @Test fun an_under_filled_row_keeps_the_base_size_and_still_fills_the_width() {
        val v = measured()
        v.setCandidates(listOf("优", "沃", "卧", "奏", "窝"))
        assertEquals("four single-grapheme candidates fill a row and the fifth stands alone", listOf(4, 1), v.rowColumnCountsForTest())
        for (i in 0..4) assertEquals("under-filled candidate $i stays at base 19sp, never enlarged", 19f, v.chipTextSizeSpForTest(i), 0.01f)
        val tableW = tableWidth()
        assertEquals("the sparse row still spreads edge to edge with no trailing empty cell", tableW, (0..3).sumOf { v.chipCellWidthForTest(it) })
    }

    @Test fun a_long_candidate_shares_the_row_with_a_short_one_at_the_shrunken_size() {
        val v = measured()
        v.setCandidates(listOf("你让我说什么说", "你"))
        assertEquals("both candidates sit in the same fixed four-column row", listOf(2), v.rowColumnCountsForTest())
        assertEquals(listOf(listOf("你让我说什么说", "你")), v.rowTextsForTest())
        assertEquals("the row size is driven by its widest member", 10f, v.chipTextSizeSpForTest(0), 0.01f)
        assertEquals("its row-mate is drawn at the same size", 10f, v.chipTextSizeSpForTest(1), 0.01f)
    }

    @Test fun overlong_candidates_shrink_to_the_floor_and_ellipsize() {
        val v = measured()
        v.setCandidates(listOf("超".repeat(80)))
        assertEquals(10f, v.chipTextSizeSpForTest(0), 0.01f)
        assertEquals(TextUtils.TruncateAt.END, v.chipEllipsizeForTest(0))
    }

    @Test fun five_grapheme_candidates_share_one_row_at_one_shrunken_size() {
        val packed = measured()
        packed.setCandidates(listOf("一二三四五", "六七八九十", "上中下左右"))
        assertEquals("all three fit the fixed four columns of one row", listOf(3), packed.rowColumnCountsForTest())
        for (i in 0..2) assertEquals("cell $i is drawn at the floor size", 10f, packed.chipTextSizeSpForTest(i), 0.01f)

        val alone = measured()
        alone.setCandidates(listOf("一二三四五"))
        assertEquals("a lone five-grapheme candidate still gets only a quarter of the width", 10f, alone.chipTextSizeSpForTest(0), 0.01f)
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
        assertEquals(19f, v.readingTextSizeSpForTest(0), 0.01f)
        assertEquals(19f, v.readingTextSizeSpForTest(1), 0.01f)
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
