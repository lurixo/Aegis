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
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelPageGestureTest {

    private val slop = 8f
    private val fling = 400f
    private val width = 300f

    private fun gesture(hasPrevious: Boolean = true, hasNext: Boolean = true) =
        PanelPageGesture(slop, fling).apply { begin(100f, 100f, width, hasPrevious, hasNext) }

    @Test fun a_clearly_sideways_drag_locks_past_the_slop_and_then_follows_the_finger() {
        val g = gesture()
        assertFalse("inside the slop nothing is decided", g.move(100f - slop, 100f))
        assertFalse(g.dragging)
        assertTrue("past the slop and clearly sideways the drag locks", g.move(100f - slop - 2f, 101f))
        assertTrue(g.dragging)
        assertEquals("the lock carries only the overslop so the page does not jump", -2f, g.offset, 0f)
        assertTrue(g.move(40f, 120f))
        assertEquals("a locked drag follows the finger one to one", -52f, g.offset, 0f)
        assertTrue("vertical travel after the lock never releases it", g.move(40f, 300f))
        assertEquals(-52f, g.offset, 0f)
    }

    @Test fun the_sideways_lock_needs_one_and_a_half_times_the_vertical_travel() {
        val g = gesture()
        assertFalse(
            "a sideways move that is not 1.5x the vertical move stays undecided",
            g.move(100f + slop + 4f, 100f + (slop + 4f) / PanelPageGesture.DIRECTION_BIAS),
        )
        assertFalse(g.dragging)
        assertTrue(
            "just past the bias the same distance locks",
            g.move(100f + slop + 4f, 100f + (slop + 4f) / PanelPageGesture.DIRECTION_BIAS - 1f),
        )
    }

    @Test fun a_gesture_decided_vertical_never_turns_into_a_page_drag() {
        val g = gesture()
        assertFalse("a vertical move past the slop decides the gesture vertical", g.move(100f + 6f, 100f + slop + 1f))
        assertFalse("later sideways travel cannot take it over", g.move(0f, 100f + slop + 1f))
        assertFalse(g.move(-200f, 100f))
        assertFalse(g.dragging)
        assertEquals(0f, g.offset, 0f)
        assertEquals("an undecided release selects nothing", 0, g.release(-5000f))
    }

    @Test fun jitter_inside_the_slop_stays_undecided_until_a_clear_sideways_move() {
        val g = gesture()
        assertFalse(g.move(104f, 103f))
        assertFalse(g.move(97f, 96f))
        assertFalse(g.move(100f, 100f))
        assertTrue(g.move(100f + slop + 10f, 102f))
        assertEquals(10f, g.offset, 0f)
    }

    @Test fun the_first_page_does_not_follow_a_drag_toward_a_missing_previous_page() {
        val g = gesture(hasPrevious = false)
        assertTrue("the sideways drag still locks so the pressed cell is not tapped", g.move(100f + slop + 40f, 100f))
        assertEquals("but the content does not follow toward a missing page", 0f, g.offset, 0f)
        assertTrue(g.move(260f, 100f))
        assertEquals(0f, g.offset, 0f)
        assertEquals("releasing a blocked drag stays on the page", 0, g.release(9000f))
    }

    @Test fun reversing_toward_an_existing_page_follows_immediately_from_the_edge() {
        val g = gesture(hasPrevious = false)
        assertTrue(g.move(100f + slop + 40f, 100f))
        assertTrue(g.move(200f, 100f))
        assertEquals(0f, g.offset, 0f)
        assertTrue(g.move(170f, 100f))
        assertEquals("the reversal is not held back by the blocked travel", -30f, g.offset, 0f)
    }

    @Test fun the_last_page_does_not_follow_a_drag_toward_a_missing_next_page() {
        val g = gesture(hasNext = false)
        assertTrue(g.move(100f - slop - 40f, 100f))
        assertEquals(0f, g.offset, 0f)
        assertEquals(0, g.release(-9000f))
    }

    @Test fun the_offset_never_travels_past_one_page() {
        val g = gesture()
        assertTrue(g.move(100f - slop - 1f, 100f))
        assertTrue(g.move(-900f, 100f))
        assertEquals(-width, g.offset, 0f)
        assertTrue(g.move(900f, 100f))
        assertEquals(width, g.offset, 0f)
    }

    @Test fun release_past_half_the_page_selects_the_neighbour_on_that_side() {
        val next = gesture()
        assertTrue(next.move(100f - slop - width / 2f - 1f, 100f))
        assertEquals("past half toward the next page", 1, next.release(0f))

        val previous = gesture()
        assertTrue(previous.move(100f + slop + width / 2f + 1f, 100f))
        assertEquals("past half toward the previous page", -1, previous.release(0f))

        val short = gesture()
        assertTrue(short.move(100f - slop - width / 2f, 100f))
        assertEquals("exactly half snaps back", 0, short.release(0f))
    }

    @Test fun a_fling_toward_the_neighbour_selects_it_from_a_short_drag() {
        val next = gesture()
        assertTrue(next.move(100f - slop - 10f, 100f))
        assertEquals(1, next.release(-fling))

        val previous = gesture()
        assertTrue(previous.move(100f + slop + 10f, 100f))
        assertEquals(-1, previous.release(fling))

        val slow = gesture()
        assertTrue(slow.move(100f - slop - 10f, 100f))
        assertEquals("a release slower than the fling threshold snaps back", 0, slow.release(-fling + 1f))
    }

    @Test fun a_fling_back_toward_the_current_page_snaps_back_even_past_half() {
        val next = gesture()
        assertTrue(next.move(100f - slop - width * 0.8f, 100f))
        assertEquals(0, next.release(fling))

        val previous = gesture()
        assertTrue(previous.move(100f + slop + width * 0.8f, 100f))
        assertEquals(0, previous.release(-fling))
    }

    @Test fun release_and_cancel_end_the_gesture() {
        val released = gesture()
        assertTrue(released.move(100f - slop - width, 100f))
        assertEquals(1, released.release(0f))
        assertFalse(released.dragging)
        assertFalse("a released gesture ignores further moves", released.move(-400f, 100f))
        assertEquals("a second release selects nothing", 0, released.release(-9000f))

        val cancelled = gesture()
        assertTrue(cancelled.move(100f - slop - width, 100f))
        cancelled.cancel()
        assertFalse(cancelled.dragging)
        assertEquals("a cancelled drag selects nothing", 0, cancelled.release(-9000f))
    }

    @Test fun rebasing_after_a_pointer_change_keeps_the_offset_continuous() {
        val g = gesture()
        assertTrue(g.move(100f - slop - 20f, 100f))
        assertEquals(-20f, g.offset, 0f)
        g.rebase(250f)
        assertTrue(g.move(240f, 100f))
        assertEquals("the new pointer only adds its own travel", -30f, g.offset, 0f)
    }

    @Test fun a_zero_width_page_never_locks() {
        val g = PanelPageGesture(slop, fling).apply { begin(100f, 100f, 0f, hasPrevious = true, hasNext = true) }
        assertFalse(g.move(0f, 100f))
        assertFalse(g.dragging)
    }
}
