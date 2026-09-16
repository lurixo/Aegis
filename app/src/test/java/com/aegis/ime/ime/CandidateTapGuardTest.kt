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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateTapGuardTest {

    private val old = listOf("就", "就是", "九")
    private val fresh = listOf("就是", "就是说", "九")

    @Test fun aTapWithoutAPressIsAccepted() {
        val g = CandidateTapGuard()
        g.show(fresh, pending = false, now = 1_000)
        assertTrue(g.accepts(1, now = 1_010))
    }

    @Test fun aListThatChangesUnderTheFingerRejectsTheWordThatReplacedIt() {
        val g = CandidateTapGuard()
        g.show(old, pending = true, now = 1_000)
        g.press(1_050)
        g.show(fresh, pending = false, now = 1_060)
        assertFalse(g.accepts(1, now = 1_100))
    }

    @Test fun aListThatChangesUnderTheFingerKeepsAWordThatStayedPut() {
        val g = CandidateTapGuard()
        g.show(old, pending = true, now = 1_000)
        g.press(1_050)
        g.show(fresh, pending = false, now = 1_060)
        assertTrue(g.accepts(2, now = 1_100))
    }

    @Test fun aSlowListLandingJustBeforeThePressRejectsTheWordThatReplacedIt() {
        val g = CandidateTapGuard()
        g.show(old, pending = false, now = 900)
        g.show(old, pending = true, now = 1_000)
        g.show(fresh, pending = false, now = 1_000 + CandidateTapGuard.STALE_VISIBLE_MILLIS)
        g.press(1_000 + CandidateTapGuard.STALE_VISIBLE_MILLIS + 40)
        assertFalse(g.accepts(0, now = 1_300))
        g.press(1_000 + CandidateTapGuard.STALE_VISIBLE_MILLIS + 50)
        assertTrue(g.accepts(2, now = 1_320))
    }

    @Test fun aQuickListNeverBlocksAnImmediateTap() {
        val g = CandidateTapGuard()
        g.show(old, pending = true, now = 1_000)
        g.show(fresh, pending = false, now = 1_000 + CandidateTapGuard.STALE_VISIBLE_MILLIS - 1)
        g.press(1_000 + CandidateTapGuard.STALE_VISIBLE_MILLIS + 10)
        assertTrue(g.accepts(0, now = 1_200))
    }

    @Test fun aPressWellAfterTheListSettledIsAccepted() {
        val g = CandidateTapGuard()
        g.show(old, pending = true, now = 1_000)
        g.show(fresh, pending = false, now = 1_500)
        g.press(1_500 + CandidateTapGuard.SETTLE_MILLIS)
        assertTrue(g.accepts(0, now = 1_800))
    }

    @Test fun aTapOnTheListStillOnScreenIsAccepted() {
        val g = CandidateTapGuard()
        g.show(old, pending = true, now = 1_000)
        g.press(1_300)
        assertTrue(g.accepts(1, now = 1_350))
    }

    @Test fun aStalePressDoesNotOutliveItsGesture() {
        val g = CandidateTapGuard()
        g.show(old, pending = false, now = 1_000)
        g.press(1_100)
        g.show(fresh, pending = false, now = 1_200)
        assertTrue(g.accepts(0, now = 1_100 + CandidateTapGuard.PRESS_MILLIS + 1))
    }
}
