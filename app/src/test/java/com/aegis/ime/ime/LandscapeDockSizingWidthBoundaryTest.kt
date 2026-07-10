package com.aegis.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeDockSizingWidthBoundaryTest {

    private data class Boundary(
        val name: String,
        val density: Float,
        val preferredSurface: Int,
        val leftInset: Int,
        val rightInset: Int,
        val requiredSurface: Int,
        val floatingSurface: Int,
        val exactSlot: Int,
        val exactGutter: Int,
    )

    @Test fun exact_48dp_host_gutter_floats_but_one_pixel_less_falls_back_across_densities_and_insets() {
        val cases = listOf(
            Boundary(
                name = "mdpi/no insets/required width governs",
                density = 1f,
                preferredSurface = 200,
                leftInset = 0,
                rightInset = 0,
                requiredSurface = 288,
                floatingSurface = 288,
                exactSlot = 336,
                exactGutter = 48,
            ),
            Boundary(
                name = "hdpi/asymmetric side insets",
                density = 1.5f,
                preferredSurface = 300,
                leftInset = 13,
                rightInset = 17,
                requiredSurface = 443,
                floatingSurface = 443,
                exactSlot = 528,
                exactGutter = 72,
            ),
            Boundary(
                name = "xhdpi/no insets",
                density = 2f,
                preferredSurface = 400,
                leftInset = 0,
                rightInset = 0,
                requiredSurface = 576,
                floatingSurface = 576,
                exactSlot = 672,
                exactGutter = 96,
            ),
            Boundary(
                name = "xxxhdpi/large nav and cutout insets",
                density = 3f,
                preferredSurface = 600,
                leftInset = 45,
                rightInset = 60,
                requiredSurface = 912,
                floatingSurface = 912,
                exactSlot = 1101,
                exactGutter = 144,
            ),
            Boundary(
                name = "mdpi/preferred short-axis width governs",
                density = 1f,
                preferredSurface = 300,
                leftInset = 10,
                rightInset = 0,
                requiredSurface = 288,
                floatingSurface = 300,
                exactSlot = 358,
                exactGutter = 48,
            ),
        )

        for (case in cases) {
            val exact = LandscapeDockSizing.resolveWidth(
                landscape = true,
                slotWidth = case.exactSlot,
                preferredSurfaceWidth = case.preferredSurface,
                density = case.density,
                leftSystemInset = case.leftInset,
                rightSystemInset = case.rightInset,
            )
            assertTrue("${case.name}: an exact 48dp effective gutter is usable", exact.floating)
            assertEquals("${case.name}: surface", case.floatingSurface, exact.surfaceWidth)
            assertEquals("${case.name}: effective host gutter", case.exactGutter, exact.effectiveLeftGutter)
            assertEquals("${case.name}: minimum safe surface", case.requiredSurface, exact.requiredSurfaceWidth)

            val onePixelShortSlot = case.exactSlot - 1
            val short = LandscapeDockSizing.resolveWidth(
                landscape = true,
                slotWidth = onePixelShortSlot,
                preferredSurfaceWidth = case.preferredSurface,
                density = case.density,
                leftSystemInset = case.leftInset,
                rightSystemInset = case.rightInset,
            )
            assertFalse("${case.name}: a sub-48dp gutter must not be advertised as host space", short.floating)
            assertEquals("${case.name}: fallback consumes the whole real slot", onePixelShortSlot, short.surfaceWidth)
            assertEquals("${case.name}: fallback exposes no fake gutter", 0, short.effectiveLeftGutter)
            assertEquals("${case.name}: safe-width diagnostic remains stable", case.requiredSurface, short.requiredSurfaceWidth)
        }
    }

    @Test fun near_square_width_is_an_explicit_full_width_fallback() {
        val spec = LandscapeDockSizing.resolveWidth(
            landscape = true,
            slotWidth = 320,
            preferredSurfaceWidth = 200,
            density = 1f,
            leftSystemInset = 12,
            rightSystemInset = 16,
        )

        assertEquals("20dp ALPHA keys plus gaps and real side padding need a 300px surface", 300, spec.requiredSurfaceWidth)
        assertFalse("only 8px remain after the left inset, not a 48dp host gutter", spec.floating)
        assertEquals(320, spec.surfaceWidth)
        assertEquals("full-width fallback must never expose a synthetic pass-through strip", 0, spec.effectiveLeftGutter)
    }

    @Test fun portrait_and_zero_width_never_create_floating_geometry() {
        val portrait = LandscapeDockSizing.resolveWidth(
            landscape = false,
            slotWidth = 411,
            preferredSurfaceWidth = 320,
            density = 2f,
            leftSystemInset = 24,
            rightSystemInset = 24,
        )
        assertFalse(portrait.floating)
        assertEquals(411, portrait.surfaceWidth)
        assertEquals(0, portrait.effectiveLeftGutter)

        val empty = LandscapeDockSizing.resolveWidth(
            landscape = true,
            slotWidth = 0,
            preferredSurfaceWidth = 200,
            density = 1f,
            leftSystemInset = 0,
            rightSystemInset = 0,
        )
        assertFalse(empty.floating)
        assertEquals(0, empty.surfaceWidth)
        assertEquals(0, empty.effectiveLeftGutter)
    }
}
