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

package com.aegis.ime

import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DockedImeVisibleInsetsTest {

    @Test fun docked_surface_reports_visible_insets_no_higher_than_content_insets() {
        val window = Rect(0, 0, 1080, 981)
        val spec = LandscapeImeWindowPolicy.resolve(
            compactLandscape = false,
            normalTop = 68,
            windowBottom = 981,
            surfaceBounds = Rect(11, 68, 1069, 981),
            windowBounds = window,
            cornerOverlap = 21,
        )

        assertEquals(89, spec.contentTop)
        assertEquals(89, spec.visibleTop)
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_REGION, spec.touchableInsets)
        assertEquals(Rect(0, 68, 1080, 981), spec.touchableRegion)
        assertNull(spec.touchableExtra)
    }

    @Test fun visible_top_never_sits_above_content_top_for_any_corner_overlap() {
        val window = Rect(0, 0, 1080, 981)
        val tab = Rect(30, 40, 200, 68)
        for (overlap in -5..1000) {
            for (preeditTab in listOf(null, tab)) {
                val spec = LandscapeImeWindowPolicy.resolve(
                    compactLandscape = false,
                    normalTop = 68,
                    windowBottom = 981,
                    surfaceBounds = Rect(11, 68, 1069, 981),
                    windowBounds = window,
                    preeditTab = preeditTab,
                    cornerOverlap = overlap,
                )
                assertTrue("overlap=$overlap tab=$preeditTab", spec.visibleTop >= spec.contentTop)
                assertTrue("overlap=$overlap tab=$preeditTab", spec.contentTop in 68..981)
                if (spec.touchableInsets == InputMethodService.Insets.TOUCHABLE_INSETS_REGION) {
                    assertEquals(Rect(0, 68, 1080, 981), spec.touchableRegion)
                } else {
                    assertEquals(68, spec.visibleTop)
                }
            }
        }
    }

    @Test fun corner_overlap_is_dropped_when_no_bounds_can_carry_the_touch_region() {
        val spec = LandscapeImeWindowPolicy.resolve(
            compactLandscape = false,
            normalTop = 68,
            windowBottom = 981,
            surfaceBounds = Rect(),
            windowBounds = Rect(),
            cornerOverlap = 21,
        )

        assertEquals(68, spec.contentTop)
        assertEquals(68, spec.visibleTop)
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE, spec.touchableInsets)
        assertNull(spec.touchableRegion)
    }
}
