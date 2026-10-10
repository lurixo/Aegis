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

import android.content.res.Configuration
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h291dp-land-xhdpi")
class LandscapeImeWindowPolicyTest {

    @Test fun service_never_requests_framework_fullscreen_extract() {

        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, service.resources.configuration.orientation)
        assertFalse(service.onEvaluateFullscreenMode())
    }

    @Test fun compact_landscape_preserves_host_bounds_and_copies_the_full_right_touch_envelope() {

        val surface = Rect(698, 93, 1280, 582)
        val spec = LandscapeImeWindowPolicy.resolve(
            compactLandscape = true,
            normalTop = 132,
            windowBottom = 582,
            surfaceBounds = surface,
        )

        assertEquals("bottom means no full-width resize/crop", 582, spec.contentTop)
        assertEquals("bottom means no full-width visible obstruction", 582, spec.visibleTop)
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_REGION, spec.touchableInsets)
        val region = requireNotNull(spec.touchableRegion)
        assertFalse("left game touch passes through", region.contains(300, 300))
        assertFalse("left pixel beside the preedit row passes through", region.contains(697, 110))
        assertTrue("composed preedit row is touchable above the opaque body", region.contains(900, 110))
        assertTrue("right keyboard remains touchable", region.contains(900, 300))
        assertEquals(surface, region)
        surface.setEmpty()
        assertEquals("policy must copy its caller-owned mutable Rect", Rect(698, 93, 1280, 582), region)
    }

    @Test fun portrait_or_full_width_fallback_keeps_conventional_top_insets() {
        val spec = LandscapeImeWindowPolicy.resolve(
            compactLandscape = false,
            normalTop = 404,
            windowBottom = 891,

            surfaceBounds = Rect(0, 378, 411, 891),
        )

        assertEquals(404, spec.contentTop)
        assertEquals(404, spec.visibleTop)
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE, spec.touchableInsets)
        assertNull(spec.touchableRegion)
    }

    @Test fun docked_surface_lets_the_host_run_behind_its_top_corners() {
        val spec = LandscapeImeWindowPolicy.resolve(
            compactLandscape = false,
            normalTop = 404,
            windowBottom = 891,
            surfaceBounds = Rect(0, 378, 411, 891),
            cornerOverlap = 21,
        )

        assertEquals("the host is resized only down to the end of the corner arc", 425, spec.contentTop)
        assertEquals("the surface still obscures the host from its own top", 404, spec.visibleTop)
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE, spec.touchableInsets)
        assertNull(spec.touchableRegion)
    }

    @Test fun docked_surface_with_a_preedit_tab_overlaps_the_host_without_moving_its_touch_region() {
        val window = Rect(0, 0, 411, 891)
        val tab = Rect(30, 380, 120, 404)
        val spec = LandscapeImeWindowPolicy.resolve(
            compactLandscape = false,
            normalTop = 404,
            windowBottom = 891,
            surfaceBounds = Rect(0, 378, 411, 891),
            windowBounds = window,
            preeditTab = tab,
            cornerOverlap = 21,
        )

        assertEquals(425, spec.contentTop)
        assertEquals(404, spec.visibleTop)
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_REGION, spec.touchableInsets)
        assertEquals(Rect(0, 404, 411, 891), spec.touchableRegion)
        assertEquals(tab, spec.touchableExtra)
    }

    @Test fun floating_surface_ignores_the_corner_overlap() {
        val surface = Rect(698, 93, 1280, 582)
        val spec = LandscapeImeWindowPolicy.resolve(
            compactLandscape = true,
            normalTop = 132,
            windowBottom = 582,
            surfaceBounds = surface,
            cornerOverlap = 21,
        )

        assertEquals(582, spec.contentTop)
        assertEquals(582, spec.visibleTop)
        assertEquals(surface, spec.touchableRegion)
    }

    @Test fun corner_overlap_never_pushes_content_past_the_window_bottom_or_above_the_surface() {
        val short = LandscapeImeWindowPolicy.resolve(
            compactLandscape = false,
            normalTop = 880,
            windowBottom = 891,
            surfaceBounds = Rect(0, 854, 411, 891),
            cornerOverlap = 21,
        )
        assertEquals(891, short.contentTop)
        assertEquals(880, short.visibleTop)

        val negative = LandscapeImeWindowPolicy.resolve(
            compactLandscape = false,
            normalTop = 404,
            windowBottom = 891,
            surfaceBounds = Rect(0, 378, 411, 891),
            cornerOverlap = -5,
        )
        assertEquals(404, negative.contentTop)
        assertEquals(404, negative.visibleTop)
    }

    @Test fun empty_surface_never_creates_an_empty_touch_region() {
        val spec = LandscapeImeWindowPolicy.resolve(
            compactLandscape = true,
            normalTop = 77,
            windowBottom = 582,
            surfaceBounds = Rect(),
        )
        assertEquals(77, spec.contentTop)
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE, spec.touchableInsets)
        assertNull(spec.touchableRegion)
    }
}
