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
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import com.aegis.ime.ime.theme.ImeType
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandidateGridViewTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun dp(v: Int) = (v * density).toInt()
    private fun sideWidth() = ((360 * density) * Layouts.NINE_SIDE_FRACTION).roundToInt()
    private fun actionWidth() = minOf(sideWidth(), dp(Layouts.CANDIDATE_ACTION_WIDTH_DP))
    private fun tableWidth() = (360 * density).toInt() - sideWidth() - actionWidth()

    private fun measured(v: CandidateGridView = CandidateGridView(ctx)): CandidateGridView = v.apply {
        measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    @Test fun four_candidate_rows_fill_the_panel_height() {
        for (heightDp in listOf(250, 320)) {
            val h = (heightDp * density).toInt()
            val v = CandidateGridView(ctx)
            v.setCandidates((1..40).map { "候$it" })
            v.measure(
                View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            val separator = v.tableDividerHeightForTest()
            val rows = v.visibleCandidateRowsForTest()

            val content = h - (CandidateGridView.ROWS - 1) * separator
            assertEquals(
                "${heightDp}dp: the row height is the panel height divided four ways",
                (content + CandidateGridView.ROWS - 1) / CandidateGridView.ROWS,
                v.candidateRowHeightForTest(),
            )
            assertEquals("${heightDp}dp: exactly four rows are laid out", CandidateGridView.ROWS, rows.size)
            assertEquals("${heightDp}dp: the first row starts at the top", 0, rows.first().top)
            assertTrue(
                "${heightDp}dp: the four rows and their separators fill the panel: $rows of $h",
                rows.last().bottom >= h,
            )
            assertEquals(
                "${heightDp}dp: the reading tiles keep the candidate row pitch",
                v.candidateRowStrideForTest(),
                v.candidateRowHeightForTest() + separator,
            )
        }
    }

    @Test fun the_three_controls_split_the_action_column_into_equal_thirds() {
        for (heightDp in listOf(320, 120)) {
            val h = (heightDp * density).toInt()
            val v = CandidateGridView(ctx)
            v.measure(
                View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            val actions = (0..2).map { v.actionBoundsForTest(it) }

            assertEquals("${heightDp}dp: 收起 starts at the top of the column", 0, actions[0].top)
            assertEquals("${heightDp}dp: 重输 ends at the bottom of the column", h, actions[2].bottom)
            actions.zipWithNext().forEach { (upper, lower) ->
                assertEquals("${heightDp}dp: the thirds tile with no gap: $actions", upper.bottom, lower.top)
            }
            assertTrue(
                "${heightDp}dp: the three thirds differ by at most a rounding pixel: $actions",
                actions.maxOf { it.height() } - actions.minOf { it.height() } <= 1,
            )
            assertEquals(
                "${heightDp}dp: every control stays visible however short the column is",
                listOf(View.VISIBLE, View.VISIBLE, View.VISIBLE),
                listOf(v.returnButtonForTest(), v.backspaceButtonForTest(), v.clearButtonForTest()).map { it.visibility },
            )
        }
    }

    @Test fun right_controls_share_one_vertical_center_line() {
        val v = measured()
        val columnCenter = actionWidth() / 2
        val deleteCenter = v.backspaceButtonForTest().paddingLeft + v.backspaceGlyphForTest().intrinsicWidth / 2
        assertTrue("backspace glyph centers on the column center line, got $deleteCenter vs $columnCenter", kotlin.math.abs(columnCenter - deleteCenter) <= 1)
        for (control in listOf(v.returnButtonForTest(), v.backspaceButtonForTest(), v.clearButtonForTest())) {
            assertEquals("every control centres its content", Gravity.CENTER, control.gravity)
        }
    }

    @Test fun the_worded_action_controls_read_at_the_panel_action_size() {
        val v = measured()
        for ((name, control) in listOf(
            ctx.getString(R.string.panel_back) to v.returnButtonForTest(),
            ctx.getString(R.string.kbd_redo) to v.clearButtonForTest(),
        )) {
            assertEquals("the control spells out its name", name, control.text.toString())
            assertNull("no glyph is left beside the words", control.compoundDrawables.firstOrNull { it != null })
            assertEquals("it is set at the panel action size", ImeType.body * density, control.textSize, 0.01f)
        }
    }

    @Test fun the_backspace_glyph_keeps_the_unified_action_icon_size() {
        val v = CandidateGridView(ctx)
        val glyph = v.backspaceGlyphForTest()

        val ink = glyphInkBounds(glyph)
        val target = ImePanelSurfaceMetrics.actionIconPx(com.aegis.ime.ime.theme.ImeType.body, density)
        assertEquals(
            "backspace ink lands its longer edge on the unified icon size",
            target,
            maxOf(ink.width(), ink.height()),
            1f * density + 1f,
        )
        assertTrue(
            "backspace ink stays inside the action column: ${ink.width()} of ${glyph.intrinsicWidth}",
            ink.width() <= glyph.intrinsicWidth,
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

    @Test fun holding_grid_backspace_deletes_once_and_never_repeats() {
        var deleted = 0
        val v = CandidateGridView(ctx).apply { onBackspace = { deleted++ } }
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val b = v.backspaceButtonForTest()
        val x = b.width / 2f
        val y = b.height / 2f

        b.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(BackspaceGesture.REPEAT_DELAY_MS + 10 * BackspaceGesture.REPEAT_INTERVAL_MS))
        assertEquals("a hold on the expanded backspace must not repeat", 0, deleted)

        b.dispatchTouchEvent(MotionEvent.obtain(0, 600, MotionEvent.ACTION_UP, x, y, 0))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("releasing still deletes exactly once", 1, deleted)
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

    @Test fun candidates_that_fit_one_track_take_four_to_a_row() {
        val v = measured()
        v.setCandidates(listOf("你好", "再见", "谢谢", "不用", "可以", "没有", "什么", "怎么"))
        assertEquals(listOf(4, 4), v.rowColumnCountsForTest())
        assertEquals(List(8) { 1 }, (0..7).map { v.chipSpanForTest(it) })
    }

    @Test fun a_candidate_too_wide_for_one_track_merges_the_next_ones_instead_of_shrinking() {
        val v = measured()
        v.setCandidates(listOf("你好吗", "一", "二二", "四个字啦", "五"))

        assertEquals(
            "你好吗 and 四个字啦 each take two tracks, so the first row holds three candidates",
            listOf(listOf("你好吗", "一", "二二"), listOf("四个字啦", "五")),
            v.rowTextsForTest(),
        )
        assertEquals(
            "the spare track of the second row goes to the leftmost candidate",
            listOf(2, 1, 1, 3, 1),
            (0..4).map { v.chipSpanForTest(it) },
        )
        for (i in 0..4) assertEquals("merging keeps candidate $i at base size", 19f, v.chipTextSizeSpForTest(i), 0.01f)
        val tableW = tableWidth()
        assertEquals("a merged row still spans the table exactly", tableW, (0..2).sumOf { v.chipCellWidthForTest(it) })
    }

    @Test fun merging_never_puts_more_than_four_tracks_on_a_row() {
        val v = measured()
        v.setCandidates(listOf("一", "二", "三", "四", "五", "六六"))
        assertEquals(listOf(listOf("一", "二", "三", "四"), listOf("五", "六六")), v.rowTextsForTest())
        v.setCandidates(listOf("一二三四五", "六七八九十", "上中下左右"))
        assertEquals(
            "two two-track candidates fill a row and the third opens the next",
            listOf(listOf("一二三四五", "六七八九十"), listOf("上中下左右")),
            v.rowTextsForTest(),
        )
        assertEquals(
            "the lone candidate on the second row takes the whole row",
            listOf(2, 2, 4),
            (0..2).map { v.chipSpanForTest(it) },
        )
        for (i in 0..2) assertEquals(19f, v.chipTextSizeSpForTest(i), 0.01f)
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
            "these two-grapheme phrases each take one track, so three rows hold twelve",
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

    @Test fun pinyin_projection_recomputes_from_the_complete_source_after_width_changes() {
        val phrases = List(24) { "候选词组${('A'.code + it).toChar()}" }
        val singles = List(16) { (0x4e20 + it).toChar().toString() }
        val source = phrases + singles
        val v = CandidateGridView(ctx)
        v.measure(
            View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(250), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        v.setCandidates(source, projection = CandidateProjectionPolicy.PINYIN)
        val narrowPhraseCount = v.renderedCandidateTextsForTest().count { GraphemeText.clusterCount(it) > 1 }

        v.measure(
            View.MeasureSpec.makeMeasureSpec(dp(800), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(250), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val widePhraseCount = v.renderedCandidateTextsForTest().count { GraphemeText.clusterCount(it) > 1 }

        assertTrue(
            "a wider table needs fewer tracks per phrase, so three rows carry more of them: " +
                "$narrowPhraseCount then $widePhraseCount",
            widePhraseCount > narrowPhraseCount,
        )
        assertEquals(singles, v.renderedCandidateTextsForTest().filter { GraphemeText.clusterCount(it) == 1 })
        assertTrue(
            v.rowTextsForTest().drop(CandidateProjectionPolicy.PINYIN.maxPhraseRows).flatten()
                .all { GraphemeText.clusterCount(it) == 1 },
        )
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

    @Test fun spare_tracks_go_to_the_candidates_already_on_the_row() {
        val v = measured()
        var picked = -1
        v.onPick = { picked = it }
        v.setCandidates(listOf("一", "二", "三", "四", "五", "六六"))
        assertTrue(v.tapCandidateForTest(5))
        assertEquals(5, picked)
        assertFalse("there is no tappable cell past the last candidate", v.tapCandidateForTest(6))
        assertEquals("a row never ends on a blank cell", listOf("五", "六六"), rowCellTexts(v, 1))
        assertTrue("every cell on the row takes clicks", rowCells(v, 1).all { it.isClickable })
        assertEquals(
            "the two spare tracks are handed out one each from the left",
            listOf(2, 2),
            (4..5).map { v.chipSpanForTest(it) },
        )
        val tableW = tableWidth()
        assertEquals("the filled row spreads edge to edge", tableW, (0..3).sumOf { v.chipCellWidthForTest(it) })
        assertEquals("the widened row spans the table too", tableW, (4..5).sumOf { v.chipCellWidthForTest(it) })
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

    @Test fun a_row_of_mixed_lengths_keeps_every_cell_at_base_size() {
        val v = measured()
        v.setCandidates(listOf("一", "二二", "三三三"))
        assertEquals(listOf(listOf("一", "二二", "三三三")), v.rowTextsForTest())
        assertEquals("only the three-grapheme candidate needs a second track", listOf(1, 1, 2), (0..2).map { v.chipSpanForTest(it) })
        for (i in 0..2) assertEquals("cell $i keeps base size, never shrunk", 19f, v.chipTextSizeSpForTest(i), 0.01f)
    }

    @Test fun an_under_filled_row_keeps_the_base_size_and_still_fills_the_width() {
        val v = measured()
        v.setCandidates(listOf("优", "沃", "卧", "奏", "窝"))
        assertEquals("four single-grapheme candidates fill a row and the fifth stands alone", listOf(4, 1), v.rowColumnCountsForTest())
        for (i in 0..4) assertEquals("under-filled candidate $i stays at base 19sp, never enlarged", 19f, v.chipTextSizeSpForTest(i), 0.01f)
        val tableW = tableWidth()
        assertEquals("the sparse row still spreads edge to edge with no trailing empty cell", tableW, (0..3).sumOf { v.chipCellWidthForTest(it) })
    }

    @Test fun a_long_candidate_takes_the_tracks_it_needs_and_keeps_base_size() {
        val v = measured()
        v.setCandidates(listOf("你让我说什么说", "你"))
        assertEquals(listOf(listOf("你让我说什么说", "你")), v.rowTextsForTest())
        assertEquals("the long candidate merges three tracks", listOf(3, 1), (0..1).map { v.chipSpanForTest(it) })
        assertEquals("merging leaves it at base size", 19f, v.chipTextSizeSpForTest(0), 0.01f)
        assertEquals("its row-mate keeps base size too", 19f, v.chipTextSizeSpForTest(1), 0.01f)
    }

    @Test fun overlong_candidates_shrink_to_the_floor_and_ellipsize() {
        val v = measured()
        v.setCandidates(listOf("超".repeat(80)))
        assertEquals(10f, v.chipTextSizeSpForTest(0), 0.01f)
        assertEquals(TextUtils.TruncateAt.END, v.chipEllipsizeForTest(0))
    }

    @Test fun the_reading_column_candidates_and_actions_share_one_ruled_table() {
        val v = measured()
        val side = sideWidth()
        assertEquals(
            "the reading column runs edge to edge with no card inset",
            listOf(side, 0, 0, 0, 0),
            v.railLayoutForTest().toList(),
        )
        assertEquals(actionWidth(), v.rightColumnWidthForTest())
        assertFalse("platform scrollbar must stay off in favour of the custom thumb", v.railScrollbarEnabledForTest())
        assertEquals(
            "one rule closes the reading column and one closes the candidates",
            listOf(side, v.width - actionWidth()),
            v.columnRulesForTest(),
        )
        assertEquals(
            "the action keys are ruled apart at their own boundaries",
            (1..2).map { v.actionBoundsForTest(it).top },
            v.actionRulesForTest(),
        )
        assertEquals("one outline rounds the whole panel", 8f * density, v.panelCornerRadiusForTest(), 0.001f)
    }

    @Test fun the_reading_column_spans_the_nine_key_scroll_column_band() {
        val expanded = measured()
        val keyboard = KeyboardView(ctx).apply {
            setLayout(Layouts.forId(LayoutId.NINE, Lang.CN), false, false, Lang.CN)
            measure(
                View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
        val card = keyboard.scrollRegionForTest()

        assertEquals(
            "the reading column covers the nine-key scroll card and the gaps around it",
            card.left + card.right,
            expanded.railLayoutForTest()[0].toFloat(),
            1f,
        )
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

    private fun idle(ms: Long) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun overflowingRail(): CandidateGridView =
        measured(CandidateGridView(ctx).apply { setReadings((1..30).map { "r$it" }) })

    private fun CandidateGridView.railTouch(action: Int, x: Float, y: Float, t: Long) =
        dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))

    @Test fun rail_thumb_stays_hidden_until_the_user_scrolls() {
        val v = overflowingRail()
        assertEquals("nothing scrolled, so no thumb", 0f, v.railThumbAlphaForTest(), 0f)
        v.scrollForTest(gridY = 0, readingY = 120)
        idle(ScrollbarFade.FADE_MS)
        assertEquals("a programmatic scroll is not a user scroll", 0f, v.railThumbAlphaForTest(), 0f)
    }

    @Test fun dragging_the_readings_shows_the_thumb_and_it_fades_on_the_toast_timing() {
        val v = overflowingRail()
        val x = v.railLayoutForTest()[0] / 2f
        val y0 = v.height * 0.8f
        val step = 40f * density
        v.railTouch(MotionEvent.ACTION_DOWN, x, y0, 0)
        v.railTouch(MotionEvent.ACTION_MOVE, x, y0 - step, 50)
        v.railTouch(MotionEvent.ACTION_MOVE, x, y0 - 2 * step, 100)
        assertTrue("precondition: the drag scrolled the readings", v.readingScrollYForTest() > 0)
        idle(ScrollbarFade.FADE_MS)
        assertEquals("the drag faded the thumb in", 1f, v.railThumbAlphaForTest(), 0f)
        v.railTouch(MotionEvent.ACTION_MOVE, x, y0 - 2 * step, 500)
        v.railTouch(MotionEvent.ACTION_UP, x, y0 - 2 * step, 500)
        val settled = v.readingScrollYForTest()
        idle(ScrollbarFade.HOLD_MS - ScrollbarFade.FADE_MS)
        assertEquals("a paused release does not fling", settled, v.readingScrollYForTest())
        assertEquals("shown for the toast hold after the last movement, not the release", 1f, v.railThumbAlphaForTest(), 0f)
        idle(ScrollbarFade.FADE_MS / 2)
        assertEquals("then it fades on the toast fade", 0.5f, v.railThumbAlphaForTest(), 0.02f)
        idle(ScrollbarFade.FADE_MS / 2)
        assertEquals(0f, v.railThumbAlphaForTest(), 0f)
    }

    @Test fun a_reading_fling_frame_shows_the_thumb() {
        val v = overflowingRail()
        v.flingReadingsForTest(3000)
        idle(16)
        v.stepReadingFlingForTest()
        assertTrue("precondition: the fling moved the readings", v.readingScrollYForTest() > 0)
        idle(ScrollbarFade.FADE_MS)
        assertEquals("each fling frame counts as scrolling", 1f, v.railThumbAlphaForTest(), 0f)
    }

    @Test fun reopening_the_panel_hides_the_rail_thumb() {
        val v = overflowingRail()
        v.flingReadingsForTest(3000)
        idle(16)
        v.stepReadingFlingForTest()
        idle(ScrollbarFade.FADE_MS)
        assertEquals(1f, v.railThumbAlphaForTest(), 0f)
        v.prepareForOpen()
        assertEquals("the viewport reset drops the thumb with it", 0f, v.railThumbAlphaForTest(), 0f)
        assertEquals(0, v.readingScrollYForTest())
    }

    @Test fun readings_keep_title_size_without_scaling() {
        val v = CandidateGridView(ctx)
        v.setReadings(listOf("ni", "zhuang"))
        assertEquals(19f, v.readingTextSizeSpForTest(0), 0.01f)
        assertEquals(19f, v.readingTextSizeSpForTest(1), 0.01f)
    }

    @Test fun the_press_highlight_covers_the_whole_candidate_and_reading_cell() {
        val v = measured()
        v.setCandidates(listOf("你", "好"))
        v.setReadings(listOf("ni", "hao"), selected = 0)
        val cells = listOf(
            "candidate" to (v.candidateRowViewForTest(0).getChildAt(0) as TextView),
            "reading" to requireNotNull(v.readingTileForTest(0)),
        )

        for ((label, cell) in cells) {
            val surface = cell.background as ImeKeySurface
            assertEquals(
                "$label press highlight fills its cell edge to edge",
                RectF(0f, 0f, 40f, 30f),
                surface.faceBoundsForTest(40, 30),
            )
            assertEquals(
                "$label press highlight is square; the panel clip rounds the outer corners",
                0f,
                surface.cornerRadiusPx,
                0f,
            )
        }
    }

    @Test fun the_reading_and_action_columns_stand_apart_from_the_candidate_area() {
        for (pal in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val v = CandidateGridView(ctx).apply { applyPalette(pal) }
            assertEquals(
                "the side columns take the rail surface and the candidates sit on the board",
                Triple(pal.railBg, pal.keyboardBg, pal.railBg),
                v.columnBackgroundsForTest(),
            )
            assertTrue("the two surfaces must actually differ", pal.railBg != pal.keyboardBg)
        }
    }

    @Test fun palette_flows_to_every_rule_of_the_table_in_static_light_and_dark() {
        for (pal in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val v = CandidateGridView(ctx).apply { applyPalette(pal) }
            assertEquals(pal.separator to Motion.withAlpha(pal.icon, 0x55), v.railColorsForTest())
            assertEquals(pal.separator, v.panelRuleColorForTest())
            assertEquals(pal.separator, v.panelOutlineColorForTest())
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
