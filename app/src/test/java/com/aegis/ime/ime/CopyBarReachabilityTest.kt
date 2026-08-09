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

import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
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
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CopyBarReachabilityTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val light = ImePalette.STATIC_LIGHT
    private val wPx = ctx.resources.displayMetrics.widthPixels
    private val hPx = (44 * ctx.resources.displayMetrics.density).toInt()

    private fun marked(length: Int): String {
        val sb = StringBuilder(length + 16)
        var i = 0
        while (sb.length < length) sb.append('|').append(1_000_000 + i++)
        return sb.substring(0, length)
    }

    private fun lay(v: View) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, wPx, hPx)
    }

    private fun bar(source: String): CopyBarView =
        CopyBarView(ctx).apply { applyPalette(light); show(source) }.also { lay(it) }

    private fun scrollerOf(v: CopyBarView): HorizontalScrollView =
        requireNotNull(v.contentScrollerForTest()) { "copied content should sit inside a horizontal scroller" }

    private fun panRight(v: CopyBarView, scroller: HorizontalScrollView) {
        scroller.scrollTo(Int.MAX_VALUE, 0)
        lay(v)
    }

    private fun panLeft(v: CopyBarView, scroller: HorizontalScrollView) {
        scroller.scrollTo(0, 0)
        lay(v)
    }

    @Test fun every_character_of_a_long_copy_is_reachable_by_panning_the_bar() {
        val source = marked(30_000)
        val v = bar(source)
        val scroller = scrollerOf(v)
        val text = scroller.getChildAt(0) as TextView

        val seen = BooleanArray(source.length)
        var steps = 0
        while (true) {
            val start = v.previewStartForTest()
            val shown = text.text.toString()
            assertEquals(
                "the preview window is a verbatim slice of the copied text at $start",
                source.substring(start, start + shown.length),
                shown,
            )
            assertTrue(
                "a single measure never gets more than ${CopyBarView.WINDOW_CHARS} chars, got ${shown.length}",
                shown.length <= CopyBarView.WINDOW_CHARS,
            )
            for (i in start until start + shown.length) seen[i] = true
            if (start + shown.length >= source.length) break
            assertTrue("panning right never reached the tail after $steps pans", steps++ < source.length)
            panRight(v, scroller)
            assertTrue(
                "panning to the right edge must extend the window past char ${start + shown.length}",
                v.previewStartForTest() > start,
            )
        }

        val firstGap = seen.indexOfFirst { !it }
        assertEquals("character $firstGap of the copied text can never be scrolled into view", -1, firstGap)
        assertEquals("the bar still holds the untruncated copy", source, v.contentForTest())
    }

    @Test fun panning_right_pulls_the_scroll_back_by_the_width_of_what_slid_out() {
        val source = marked(30_000)
        val v = bar(source)
        val scroller = scrollerOf(v)
        val text = scroller.getChildAt(0) as TextView

        val viewport = scroller.width - scroller.paddingLeft - scroller.paddingRight
        val edge = text.width - viewport
        assertTrue("precondition: the rendered strip is wider than the viewport", edge > 0)
        val before = v.previewStartForTest()

        scroller.scrollTo(edge, 0)
        lay(v)

        val after = v.previewStartForTest()
        assertTrue("precondition: the window slid", after > before)
        val shift = text.paint.measureText(source, before, after).roundToInt()
        assertTrue("precondition: the pull back is smaller than the strip", shift in 1 until edge)
        assertEquals(
            "the scroll must follow the text that slid out, so the finger stays on the same glyph",
            (edge - shift).toLong(),
            scroller.scrollX.toLong(),
        )
    }

    @Test fun the_character_after_the_window_is_reachable_and_panning_back_returns_to_the_head() {
        val source = marked(CopyBarView.WINDOW_CHARS) + "★"
        val v = bar(source)
        val scroller = scrollerOf(v)
        val text = scroller.getChildAt(0) as TextView

        assertFalse("the tail character starts out of reach", text.text.toString().contains("★"))
        panRight(v, scroller)
        assertTrue(
            "char ${CopyBarView.WINDOW_CHARS + 1} is still unreachable: ${text.text.length} shown from ${v.previewStartForTest()}",
            text.text.toString().endsWith("★"),
        )
        assertEquals(1, v.previewStartForTest())

        panLeft(v, scroller)
        assertEquals("panning back restores the head of the copy", 0, v.previewStartForTest())
        assertEquals(source.substring(0, CopyBarView.WINDOW_CHARS), text.text.toString())
    }

    @Test fun a_two_hundred_thousand_character_copy_is_never_handed_to_one_measure() {
        val source = marked(200_000)
        val v = bar(source)
        val scroller = scrollerOf(v)
        val text = scroller.getChildAt(0) as TextView
        val wholeWidth = text.paint.measureText(source.substring(0, 2_000)) * (source.length / 2_000)

        var widest = 0
        repeat(40) {
            assertEquals(CopyBarView.WINDOW_CHARS, text.text.length)
            widest = maxOf(widest, text.measuredWidth)
            panRight(v, scroller)
        }
        assertTrue(
            "the rendered strip grew to $widest px, near the $wholeWidth px the whole copy would need",
            widest < wholeWidth / 20f,
        )
        assertEquals(
            "each right-edge pan advances the window by one step",
            40 * CopyBarView.STEP_CHARS,
            v.previewStartForTest(),
        )
        assertEquals(source, v.contentForTest())
    }

    @Test fun copies_that_fit_the_window_render_whole_and_never_slide() {
        for (n in listOf(1, 12, 1_999, CopyBarView.WINDOW_CHARS)) {
            val source = marked(n)
            val v = bar(source)
            val scroller = scrollerOf(v)
            val text = scroller.getChildAt(0) as TextView
            assertEquals("length $n renders whole", source, text.text.toString())
            assertEquals("length $n has nothing to slide", 0, v.previewStartForTest())
            assertEquals(1, text.maxLines)
            assertNull(text.ellipsize)
            assertFalse(scroller.isHorizontalScrollBarEnabled)

            panRight(v, scroller)
            assertEquals("length $n must not move under the pan", source, text.text.toString())
            assertEquals(0, v.previewStartForTest())
            panLeft(v, scroller)
            assertEquals(source, text.text.toString())
            assertEquals(0, v.previewStartForTest())
        }
    }

    @Test fun commit_and_split_keep_the_whole_copy_after_the_window_has_slid() {
        val source = marked(6_000)
        val commits = mutableListOf<String>()
        val v = CopyBarView(ctx).apply { applyPalette(light); onCommit = { commits.add(it) }; show(source) }
        lay(v)
        val scroller = scrollerOf(v)
        repeat(4) { panRight(v, scroller) }
        assertEquals(4 * CopyBarView.STEP_CHARS, v.previewStartForTest())

        v.toggleSplitForTest()
        val joined = v.splitBlocksForTest().joinToString("")
        assertTrue(
            "split ran on a truncated copy: it ends with ${joined.takeLast(24)}",
            joined.endsWith(source.takeLast(24)),
        )
        v.toggleSplitForTest()
        lay(v)

        val text = scrollerOf(v).getChildAt(0) as TextView
        assertTrue(text.performClick())
        assertEquals("the tap commits the untruncated copy", listOf(source), commits)
    }

    @Test fun the_window_model_covers_every_index_in_both_directions() {
        for (length in listOf(0, 1, 999, 1_000, 1_001, 1_249, 1_250, 1_251, 3_001, 10_000)) {
            val source = marked(length)
            val window = CopyBarPreview(source, 1_000, 250)
            val forwardSeen = BooleanArray(length)
            var guard = 0
            while (true) {
                val start = window.start
                val shown = window.text()
                assertEquals("length $length window at $start", source.substring(start, start + shown.length), shown)
                assertTrue("length $length window overflowed to ${shown.length}", shown.length <= 1_000)
                for (i in start until start + shown.length) forwardSeen[i] = true
                if (!window.forward()) break
                assertTrue("length $length never stops advancing", guard++ <= length)
            }
            assertEquals("length $length gap going forward", -1, forwardSeen.indexOfFirst { !it })
            assertEquals("length $length stops at the tail", length, window.end)

            val backSeen = BooleanArray(length)
            guard = 0
            while (true) {
                val start = window.start
                for (i in start until window.end) backSeen[i] = true
                if (!window.back()) break
                assertTrue("length $length never stops retreating", guard++ <= length)
            }
            assertEquals("length $length gap going back", -1, backSeen.indexOfFirst { !it })
            assertEquals("length $length returns to the head", 0, window.start)
            assertEquals(length > 1_000, window.slides)
        }
    }
}
