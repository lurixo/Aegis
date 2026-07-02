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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * debug.17 #66: the SHARED [FlingScroller] (extracted from the keyboard's A3 column, now also used by the
 * candidate strip). Deterministic because the velocity is SELF-computed over a time window of samples — these
 * lock that math + the fling-threshold / stop-a-running-fling behaviour without any view geometry or frame
 * clock (Robolectric's VelocityTracker shadow reports nothing; OverScroller.fling settles its target at once).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlingScrollerTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private fun steadyFlick(f: FlingScroller) { // 16px/16ms toward smaller positions ≈ -1000 px/s over the window
        f.addSample(0, 180f); f.addSample(16, 164f); f.addSample(32, 148f)
        f.addSample(48, 132f); f.addSample(64, 116f); f.addSample(80, 100f)
    }

    @Test fun windowedVelocityMatchesASteadyFlick() {
        val f = FlingScroller(ctx)
        steadyFlick(f)
        assertEquals("a steady ~1000px/s flick measures ≈ -1000px/s", -1000f, f.velocity(), 80f)
    }

    @Test fun fewerThanTwoSamplesHasNoVelocity() {
        val f = FlingScroller(ctx)
        assertEquals(0f, f.velocity(), 0f)
        f.addSample(0, 100f)
        assertEquals(0f, f.velocity(), 0f)
    }

    @Test fun aSoftFinishStillCarriesTheWindowedVelocity() {
        // Chinese IME behavior note.
        val f = FlingScroller(ctx)
        f.addSample(0, 180f); f.addSample(16, 148f); f.addSample(32, 116f) // fast up to here…
        f.addSample(64, 116f)                                              // …then a brief flat finish (0 last delta)
        assertTrue("a real flick with a soft finish keeps momentum", abs(f.velocity()) > 300f)
    }

    @Test fun aFastFlickHandsOffToAFlingTowardTheBottom() {
        val f = FlingScroller(ctx)
        steadyFlick(f)
        assertTrue("a fast flick starts a fling", f.fling(start = 0f, max = 1000f))
        assertTrue("the fling is running", !f.isFinished)
        assertTrue("…and coasts toward the bottom (final > start)", f.finalOffset() > 0f)
    }

    @Test fun aSlowDragDoesNotFling() {
        val f = FlingScroller(ctx)
        f.addSample(0, 100f); f.addSample(100, 98f) // 2px / 100ms ≈ 20 px/s, below the min fling threshold
        assertFalse("a slow drag does not hand off to a fling", f.fling(start = 0f, max = 1000f))
    }

    @Test fun noRoomMeansNoFling() {
        val f = FlingScroller(ctx)
        steadyFlick(f)
        assertFalse("a list that fits (max=0) never flings", f.fling(start = 0f, max = 0f))
    }

    @Test fun aDownOnAMovingListStopsTheFlingAndArmsNoPick() {
        val f = FlingScroller(ctx)
        steadyFlick(f)
        f.fling(start = 0f, max = 1000f)
        assertTrue("precondition: the fling is running", !f.isFinished)
        f.onDown()
        assertTrue("a DOWN halts the running fling", f.isFinished)
        assertTrue("…and arms 'this tap is not a pick'", f.stopArmed)
    }

    @Test fun aDownOnAStillListDoesNotArm() {
        val f = FlingScroller(ctx)
        f.onDown()
        assertFalse("a DOWN with no running fling does not arm stopArmed", f.stopArmed)
    }

    @Test fun onDownResetsTheVelocityWindow() {
        // A fresh gesture must not inherit the previous flick's samples (a single MOVE off DOWN is not a flick).
        val f = FlingScroller(ctx)
        steadyFlick(f)
        f.onDown()
        f.addSample(100, 90f) // only one sample this gesture
        assertEquals("velocity resets to 0 after onDown until ≥2 new samples", 0f, f.velocity(), 0f)
    }
}
