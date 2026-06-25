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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlingScrollerTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private fun steadyFlick(f: FlingScroller) {
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
        val f = FlingScroller(ctx)
        f.addSample(0, 180f); f.addSample(16, 148f); f.addSample(32, 116f)
        f.addSample(64, 116f)
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
        f.addSample(0, 100f); f.addSample(100, 98f)
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

    @Test fun aPredictedFinalOffsetMatchesAnUnclampedFlingAndLeavesTheScrollerIdle() {
        val f = FlingScroller(ctx)
        steadyFlick(f)
        val predicted = f.predictFinalOffset(start = 100f)
        assertTrue("the prediction looks ahead of the release point", predicted > 100f)
        assertTrue("predicting does not leave a fling running", f.isFinished)

        assertTrue("a fling still starts afterwards", f.fling(start = 100f, max = Float.MAX_VALUE))
        assertEquals("an unclamped fling lands where the prediction said", predicted, f.finalOffset(), 1f)
    }

    @Test fun aPredictedFinalOffsetOfASlowDragIsTheReleasePoint() {
        val f = FlingScroller(ctx)
        f.addSample(0, 100f); f.addSample(100, 98f)
        assertEquals("a drag too slow to fling predicts no travel", 250f, f.predictFinalOffset(start = 250f), 0f)
    }

    @Test fun onDownResetsTheVelocityWindow() {
        val f = FlingScroller(ctx)
        steadyFlick(f)
        f.onDown()
        f.addSample(100, 90f)
        assertEquals("velocity resets to 0 after onDown until ≥2 new samples", 0f, f.velocity(), 0f)
    }
}
