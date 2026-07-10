package com.aegis.ime.ime

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aegis.ime.LandscapeImeWindowPolicy
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w853dp-h388dp-land-hdpi")
class LandscapeOverlayBoundsTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    @Test fun wide_landscape_keeps_left_host_pixels_visible_and_right_surface_opaque() {
        val iv = InputView(ctx).apply {
            applyPalette(ImePalette.STATIC_LIGHT)
            showCandidates(listOf("你", "泥"), "ni", emptyList())
        }
        layout(iv, 1280)
        val host = Color.rgb(21, 72, 91)
        val bitmap = Bitmap.createBitmap(iv.width, iv.height, Bitmap.Config.ARGB_8888).apply { eraseColor(host) }
        iv.draw(Canvas(bitmap))
        val alphaBitmap = Bitmap.createBitmap(iv.width, iv.height, Bitmap.Config.ARGB_8888)
        iv.draw(Canvas(alphaBitmap))

        val bodyY = iv.dockSurfaceTopPx() + 2
        val leftX = iv.dockSurfaceLeftPx() / 2
        val rightX = iv.dockSurfaceLeftPx() + 2
        assertTrue("a 1280px window has a substantial host-visible left gutter", iv.dockSurfaceLeftPx() > 500)
        assertEquals("transparent gutter must preserve the host pixel", host, bitmap.getPixel(leftX, bodyY))
        assertEquals(
            "right dock must retain the opaque anti-flicker floor",
            ImePalette.STATIC_LIGHT.keyboardBg,
            bitmap.getPixel(rightX, bodyY),
        )
        assertEquals(582, iv.dockSurfaceWidthPx())
        assertEquals(1280, iv.dockSurfaceRightPx())
        assertEquals("right surface reaches the IME window bottom", iv.height, iv.dockSurfaceBottomPx())
        assertRectIsColor(bitmap, 0, 0, iv.dockSurfaceLeftPx(), bitmap.height, host)
        assertRectExcludesColor(
            bitmap,
            iv.dockSurfaceLeftPx(),
            iv.dockSurfaceTopPx(),
            iv.dockSurfaceRightPx(),
            iv.dockSurfaceBottomPx(),
            host,
        )
        assertRectHasAlpha(alphaBitmap, 0, 0, iv.dockSurfaceLeftPx(), alphaBitmap.height, 0)
        assertRectHasAlpha(
            alphaBitmap,
            iv.dockSurfaceLeftPx(),
            iv.dockSurfaceTopPx(),
            iv.dockSurfaceRightPx(),
            iv.dockSurfaceBottomPx(),
            255,
        )

    }

    @Test fun side_and_bottom_insets_remain_inside_the_right_surface() {
        val iv = InputView(ctx)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(58, 0, 17, 24))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(9, 0, 9, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(iv, insets)
        layout(iv, 1280)

        assertEquals("screen-left cutout is wholly outside the right dock", dp(4), iv.bodyLeftPaddingPxForTest())
        assertEquals(17, iv.bodyRightPaddingPxForTest())
        assertEquals("surface bounds remain physically right-docked", 1280, iv.dockSurfaceRightPx())
        assertEquals("left content keeps only its normal local padding", iv.dockSurfaceLeftPx() + dp(4), iv.keyboardVisualLeftPx())
        assertEquals("right controls avoid a side navigation/cutout inset", 1280 - 17, iv.keyboardVisualRightPx())
        assertEquals(
            "real h388 cap preserves nav and only the height-budgeted residual raise",
            24 + iv.dockHeightSpecForTest()!!.bottomExtra,
            iv.bodyBottomPaddingPx(),
        )
        assertEquals("the natural keyboard leaves 6dp of the preferred 28dp raise", 24 + dp(6), iv.bodyBottomPaddingPx())
    }

    @Test fun narrow_full_width_landscape_still_honours_the_left_safe_inset() {
        val iv = InputView(ctx)
        layout(iv, 320)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(24, 0, 0, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(iv, insets)
        layout(iv, 320)

        assertFalse(iv.isCompactLandscapeDock())
        assertEquals(24, iv.bodyLeftPaddingPxForTest())
        assertEquals(24, iv.keyboardVisualLeftPx())
    }

    @Test fun dock_surface_bounds_use_attached_window_coordinates() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = InputView(controller.get())
            controller.get().setContentView(iv)
            layout(iv, 1280)
            val rootLocation = IntArray(2)
            iv.getLocationInWindow(rootLocation)
            val actual = iv.dockSurfaceBoundsInWindow()

            assertEquals(rootLocation[0] + iv.dockSurfaceLeftPx(), actual.left)
            assertEquals(rootLocation[1] + iv.dockSurfaceTopPx(), actual.top)
            assertEquals(rootLocation[0] + iv.dockSurfaceRightPx(), actual.right)
            assertEquals(rootLocation[1] + iv.dockSurfaceBottomPx(), actual.bottom)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun compact_touch_envelope_contains_composed_tab_and_body_controls_but_excludes_left_host() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val iv = InputView(controller.get()).apply {
                applyPalette(ImePalette.STATIC_LIGHT)
                showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.CN), false, false, Lang.CN)
                showCandidates(listOf("你", "泥"), "ni", emptyList())
                showEditBar(true)
            }
            controller.get().setContentView(iv)
            ViewCompat.dispatchApplyWindowInsets(
                iv,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(58, 0, 17, 24))
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(9, 0, 9, 0))
                    .build(),
            )
            layout(iv, 1280)

            val rootLocation = IntArray(2)
            iv.getLocationInWindow(rootLocation)
            val preedit = iv.preeditSurfaceBoundsInWindow()
            val body = iv.dockSurfaceBoundsInWindow()
            val envelope = iv.dockTouchableBoundsInWindow()
            val spec = LandscapeImeWindowPolicy.resolve(
                compactLandscape = iv.isCompactLandscapeDock(),
                normalTop = rootLocation[1] + iv.barTopInsetPx(),
                windowBottom = rootLocation[1] + iv.height,
                surfaceBounds = envelope,
            )
            val region = requireNotNull(spec.touchableRegion)

            assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_REGION, spec.touchableInsets)
            assertEquals("the stable envelope is the exact production region", envelope, region)
            assertEquals(rootLocation[0] + iv.preeditVisualLeftPx(), preedit.left)
            assertEquals(rootLocation[1] + iv.preeditVisualTopPx(), preedit.top)
            assertEquals(rootLocation[0] + iv.preeditVisualRightPx(), preedit.right)
            assertEquals(rootLocation[1] + iv.preeditVisualBottomPx(), preedit.bottom)
            assertTrue("the real composed preedit window bounds must be touchable: $preedit", region.contains(preedit))
            assertTrue("the whole opaque body must be touchable: $body", region.contains(body))
            assertEquals("preedit and body share the compact left edge", body.left, preedit.left)
            assertEquals("preedit and body share the compact right edge", body.right, preedit.right)
            assertEquals("the fixed preedit row sits directly above the body", body.top, preedit.bottom)
            assertTrue("the tab must extend the region above the old body-only top", region.top < body.top)
            assertFalse(
                "the adjacent host pixel must still pass through",
                region.contains(region.left - 1, preedit.centerY()),
            )

            val candidate = rootRect(
                rootLocation,
                iv.toolbarVisualLeftPx(),
                iv.toolbarVisualTopPx(),
                iv.toolbarVisualRightPx(),
                iv.toolbarVisualBottomPx(),
            )
            val edit = rootRect(
                rootLocation,
                iv.editBarVisualLeftPx(),
                iv.editBarVisualTopPx(),
                iv.editBarVisualRightPx(),
                iv.editBarVisualBottomPx(),
            )
            val key = Rect().also { requireNotNull(iv.keyboardLabelBoundsForTest("1")).roundOut(it) }.apply {
                offset(rootLocation[0], rootLocation[1])
            }
            val enter = Rect().also { requireNotNull(iv.keyboardActionBoundsForTest(KeyAction.ENTER)).roundOut(it) }.apply {
                offset(rootLocation[0], rootLocation[1])
            }
            for ((name, bounds) in listOf(
                "candidate" to candidate,
                "edit bar" to edit,
                "first key" to key,
                "Enter" to enter,
            )) {
                assertTrue("$name window bounds $bounds must be inside $region", region.contains(bounds))
            }
            assertEquals("screen-left nav/cutout remains outside the remote dock", dp(4), iv.bodyLeftPaddingPxForTest())
            assertEquals(17, iv.bodyRightPaddingPxForTest())
            assertEquals(24 + iv.dockHeightSpecForTest()!!.bottomExtra, iv.bodyBottomPaddingPx())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun layout(iv: InputView, widthPx: Int) {
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(388), View.MeasureSpec.AT_MOST),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun rootRect(rootLocation: IntArray, left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect(
            rootLocation[0] + left,
            rootLocation[1] + top,
            rootLocation[0] + right,
            rootLocation[1] + bottom,
        )

    private fun assertRectIsColor(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        expected: Int,
    ) {
        for (y in top until bottom) for (x in left until right) {
            val actual = bitmap.getPixel(x, y)
            if (actual != expected) throw AssertionError(
                "pixel ($x,$y) must remain host-visible: expected=${hex(expected)}, actual=${hex(actual)}",
            )
        }
    }

    private fun assertRectExcludesColor(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        forbidden: Int,
    ) {
        for (y in top until bottom) for (x in left until right) {
            if (bitmap.getPixel(x, y) == forbidden) {
                throw AssertionError("opaque dock has an unpainted host pixel at ($x,$y)")
            }
        }
    }

    private fun assertRectHasAlpha(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        expectedAlpha: Int,
    ) {
        for (y in top until bottom) for (x in left until right) {
            val alpha = Color.alpha(bitmap.getPixel(x, y))
            if (alpha != expectedAlpha) {
                throw AssertionError("pixel ($x,$y) alpha: expected=$expectedAlpha, actual=$alpha")
            }
        }
    }

    private fun hex(color: Int): String = "0x%08X".format(color)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h291dp-land-mdpi")
class RotationOverlayBoundsTest {

    @Test fun composed_touch_geometry_round_trips_through_portrait_visible_fallback() {
        val iv = InputView(RuntimeEnvironment.getApplication()).apply {
            showCandidates(listOf("你"), "ni", emptyList())
        }
        layout(iv, 640, 291)
        assertTrue(iv.isCompactLandscapeDock())
        assertEquals(640 - 291, iv.dockSurfaceLeftPx())
        val firstLandscape = resolveInsets(iv)
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_REGION, firstLandscape.touchableInsets)
        val firstRegion = requireNotNull(firstLandscape.touchableRegion)
        val firstPreedit = iv.preeditSurfaceBoundsInWindow()
        assertTrue(firstRegion.contains(firstPreedit))
        assertFalse(firstRegion.contains(firstRegion.left - 1, firstPreedit.centerY()))

        try {
            RuntimeEnvironment.setQualifiers("w291dp-h640dp-port-mdpi")
            layout(iv, 291, 640)
            assertFalse(iv.isCompactLandscapeDock())
            assertEquals(0, iv.dockSurfaceLeftPx())
            assertEquals(291, iv.dockSurfaceRightPx())
            val portrait = resolveInsets(iv)
            assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE, portrait.touchableInsets)
            assertNull("portrait must use normal visible touch semantics", portrait.touchableRegion)
            assertEquals(iv.barTopInsetPx(), portrait.visibleTop)
        } finally {
            RuntimeEnvironment.setQualifiers("w640dp-h291dp-land-mdpi")
        }

        layout(iv, 640, 291)
        assertTrue(iv.isCompactLandscapeDock())
        val restored = resolveInsets(iv)
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_REGION, restored.touchableInsets)
        val restoredRegion = requireNotNull(restored.touchableRegion)
        val restoredPreedit = iv.preeditSurfaceBoundsInWindow()
        assertTrue("restored live tab bounds must replace portrait geometry", restoredRegion.contains(restoredPreedit))
        assertEquals(iv.dockTouchableBoundsInWindow(), restoredRegion)
        assertFalse(restoredRegion.contains(restoredRegion.left - 1, restoredPreedit.centerY()))
    }

    private fun resolveInsets(iv: InputView) = LandscapeImeWindowPolicy.resolve(
        compactLandscape = iv.isCompactLandscapeDock(),
        normalTop = iv.barTopInsetPx(),
        windowBottom = iv.height,
        surfaceBounds = iv.dockTouchableBoundsInWindow(),
    )

    private fun layout(iv: InputView, widthPx: Int, heightPx: Int) {
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
class PortraitOverlayBoundsTest {

    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun portrait_surface_stays_full_width_and_opaque() {
        val iv = InputView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        iv.layout(0, 0, iv.measuredWidth, iv.measuredHeight)
        val bitmap = Bitmap.createBitmap(iv.width, iv.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        iv.draw(Canvas(bitmap))
        val alphaBitmap = Bitmap.createBitmap(iv.width, iv.height, Bitmap.Config.ARGB_8888)
        iv.draw(Canvas(alphaBitmap))

        assertEquals(0, iv.dockSurfaceLeftPx())
        assertEquals(411, iv.dockSurfaceWidthPx())
        assertEquals(411, iv.dockSurfaceRightPx())
        assertFalse(iv.isCompactLandscapeDock())
        val y = iv.dockSurfaceTopPx() + 2
        assertEquals(ImePalette.STATIC_LIGHT.keyboardBg, bitmap.getPixel(1, y))
        assertEquals(ImePalette.STATIC_LIGHT.keyboardBg, bitmap.getPixel(409, y))
        for (py in iv.dockSurfaceTopPx() until iv.dockSurfaceBottomPx()) for (px in 0 until bitmap.width) {
            assertTrue("portrait dock must not expose the host at ($px,$py)", bitmap.getPixel(px, py) != Color.MAGENTA)
            assertEquals("portrait dock must remain opaque at ($px,$py)", 255, Color.alpha(alphaBitmap.getPixel(px, py)))
        }
    }
}
