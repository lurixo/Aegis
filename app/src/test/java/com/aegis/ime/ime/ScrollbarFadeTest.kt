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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollbarFadeTest {

    private val hold = ScrollbarFade.HOLD_MS
    private val fade = ScrollbarFade.FADE_MS

    @Test fun the_timings_are_the_toast_timings() {
        assertEquals(ImeToast.HOLD_MS, ScrollbarFade.HOLD_MS)
        assertEquals(ImeToast.FADE_MS, ScrollbarFade.FADE_MS)
    }

    @Test fun nothing_shows_before_the_first_scroll() {
        val bar = ScrollbarFade()
        assertFalse(bar.isShowing(1_000))
        assertEquals(0f, bar.alphaAt(1_000), 0f)
        assertNull(bar.nextTickDelayMs(1_000))
    }

    @Test fun a_scroll_fades_the_bar_in_holds_it_and_fades_it_out() {
        val bar = ScrollbarFade()
        bar.scrolled(1_000)
        assertEquals("the fade-in starts from nothing", 0f, bar.alphaAt(1_000), 0f)
        assertEquals(0.5f, bar.alphaAt(1_000 + fade / 2), 0.01f)
        assertEquals(1f, bar.alphaAt(1_000 + fade), 0f)
        assertEquals("fully shown for the whole hold", 1f, bar.alphaAt(1_000 + hold), 0f)
        assertEquals(0.5f, bar.alphaAt(1_000 + hold + fade / 2), 0.01f)
        assertEquals(0f, bar.alphaAt(1_000 + hold + fade), 0f)
        assertFalse("gone once the fade-out completes", bar.isShowing(1_000 + hold + fade))
    }

    @Test fun every_scroll_restarts_the_hold_without_restarting_the_fade_in() {
        val bar = ScrollbarFade()
        bar.scrolled(1_000)
        bar.scrolled(2_000)
        assertEquals("already shown, so no second fade-in", 1f, bar.alphaAt(2_000), 0f)
        assertEquals(1f, bar.alphaAt(2_000 + hold), 0f)
        assertEquals(0.5f, bar.alphaAt(2_000 + hold + fade / 2), 0.01f)
    }

    @Test fun a_scroll_during_the_fade_out_brings_the_bar_back_from_its_current_alpha() {
        val bar = ScrollbarFade()
        bar.scrolled(1_000)
        val midFade = 1_000 + hold + fade / 2
        assertEquals(0.5f, bar.alphaAt(midFade), 0.01f)
        bar.scrolled(midFade)
        assertEquals("no jump at the moment of the scroll", 0.5f, bar.alphaAt(midFade), 0.01f)
        assertEquals(1f, bar.alphaAt(midFade + fade / 2), 0.01f)
    }

    @Test fun hide_drops_the_bar_at_once() {
        val bar = ScrollbarFade()
        bar.scrolled(1_000)
        bar.hide()
        assertEquals(0f, bar.alphaAt(1_000 + fade), 0f)
        assertNull(bar.nextTickDelayMs(1_000 + fade))
    }

    @Test fun the_next_tick_is_a_frame_while_fading_and_the_hold_remainder_while_shown() {
        val bar = ScrollbarFade()
        bar.scrolled(1_000)
        assertEquals(0L, bar.nextTickDelayMs(1_000))
        assertEquals(0L, bar.nextTickDelayMs(1_000 + fade / 2))
        assertEquals(hold - fade, bar.nextTickDelayMs(1_000 + fade))
        assertEquals(hold - 700, bar.nextTickDelayMs(1_700))
        assertEquals(0L, bar.nextTickDelayMs(1_000 + hold))
        assertEquals(0L, bar.nextTickDelayMs(1_000 + hold + fade / 2))
        assertNull(bar.nextTickDelayMs(1_000 + hold + fade))
        assertTrue(bar.isShowing(1_000 + hold + fade - 1))
    }
}
