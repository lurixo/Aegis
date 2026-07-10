package com.aegis.ime.ime

import android.content.res.Configuration
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aegis.ime.LandscapeImeWindowPolicy
import com.aegis.ime.layout.Key
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h200dp-land-mdpi")
class NarrowLandscapeMultiWindowFallbackTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test fun real_320_by_200_landscape_uses_clickable_full_width_alpha_and_nine_surfaces() {
        val config = context.resources.configuration
        assertEquals("the configuration must remain landscape", Configuration.ORIENTATION_LANDSCAPE, config.orientation)
        assertEquals("the test must not silently inherit the old w891dp fixture", 320, config.screenWidthDp)
        assertEquals(200, config.screenHeightDp)
        assertEquals("mdpi makes qualifier dp and measured px directly comparable", 1f, context.resources.displayMetrics.density, 0f)

        val view = InputView(context)
        val sideInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(12, 0, 0, 12))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 0, 16, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(view, sideInsets)

        val tapped = mutableListOf<Key>()
        view.onKey = tapped::add
        view.showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.EN), shifted = false, locked = false, lang = Lang.EN)
        layoutWithRealWindowCap(view)

        assertFullWidthFallback(view)
        assertEquals("left navigation inset remains real full-width body padding", 12, view.bodyLeftPaddingPxForTest())
        assertEquals("right cutout inset remains real full-width body padding", 16, view.bodyRightPaddingPxForTest())
        assertEquals(12, view.keyboardVisualLeftPx())
        assertEquals(WINDOW_WIDTH_PX - 16, view.keyboardVisualRightPx())
        assertEquals(WINDOW_WIDTH_PX - 12 - 16, view.keyboardVisualWidthPx())
        assertTrue(
            "full-width fallback preserves the declared 20dp minimum ALPHA key",
            view.keyboardMinimumKeyWidthPxForTest() >= MIN_KEY_WIDTH_DP,
        )

        val alphaKey = requireNotNull(view.keyboardLabelBoundsForTest("a"))
        val alphaEnter = requireNotNull(view.keyboardActionBoundsForTest(KeyAction.ENTER))
        assertWithinWindowAndSurface("ALPHA a", alphaKey, view)
        assertWithinWindowAndSurface("ALPHA Enter", alphaEnter, view)
        assertTrue("a tap must travel through InputView.dispatchTouchEvent", view.tapKeyboardLabelForTest("a"))
        assertTrue("Enter must remain root-dispatched at the bottom/right edge", view.tapKeyboardActionForTest(KeyAction.ENTER))
        assertEquals(2, tapped.size)
        assertEquals("a", tapped[0].label)
        assertEquals(KeyAction.ENTER, tapped[1].action)

        tapped.clear()
        view.showKeyboard(Layouts.nine(Lang.CN, Layouts.ninePunctuation()), shifted = false, locked = false, lang = Lang.CN)
        layoutWithRealWindowCap(view)

        assertFullWidthFallback(view)
        assertEquals(12, view.keyboardVisualLeftPx())
        assertEquals(WINDOW_WIDTH_PX - 16, view.keyboardVisualRightPx())
        assertTrue(
            "the narrow right rail of NINE also retains a usable >=20dp hit target",
            view.keyboardMinimumKeyWidthPxForTest() >= MIN_KEY_WIDTH_DP,
        )
        val nineKey = requireNotNull(view.keyboardLabelBoundsForTest("ABC"))
        val nineBottom = requireNotNull(view.keyboardLabelBoundsForTest("123"))
        val nineEnter = requireNotNull(view.keyboardActionBoundsForTest(KeyAction.ENTER))
        assertWithinWindowAndSurface("NINE ABC", nineKey, view)
        assertWithinWindowAndSurface("NINE 123", nineBottom, view)
        assertWithinWindowAndSurface("NINE Enter", nineEnter, view)
        assertTrue("T9 key must travel through the real root hierarchy", view.tapKeyboardLabelForTest("ABC"))
        assertTrue("the NINE last-row 123 key must remain root-dispatched", view.tapKeyboardLabelForTest("123"))
        assertTrue("the tall NINE Enter remains root-dispatched", view.tapKeyboardActionForTest(KeyAction.ENTER))
        assertEquals(3, tapped.size)
        assertEquals("ABC", tapped[0].label)
        assertEquals("2", tapped[0].output)
        assertEquals(KeyAction.SWITCH_NUMPAD, tapped[1].action)
        assertEquals(KeyAction.ENTER, tapped[2].action)

        view.showCandidates(listOf("你", "泥"), "ni", emptyList())
        layoutWithRealWindowCap(view)
        assertTrue(view.dockTouchableBoundsInWindow().contains(view.preeditSurfaceBoundsInWindow()))
        val normalTop = view.barTopInsetPx()
        val fallbackInsets = LandscapeImeWindowPolicy.resolve(
            compactLandscape = view.isCompactLandscapeDock(),
            normalTop = normalTop,
            windowBottom = view.measuredHeight,
            surfaceBounds = view.dockTouchableBoundsInWindow(),
        )
        assertEquals("full-width fallback keeps conventional content insets", normalTop, fallbackInsets.contentTop)
        assertEquals(normalTop, fallbackInsets.visibleTop)
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE, fallbackInsets.touchableInsets)
        assertNull("there is no synthetic region/gutter in fallback mode", fallbackInsets.touchableRegion)
    }

    @Test fun full_width_emergency_shrinks_horizontal_gaps_before_crossing_the_20dp_key_floor() {
        val view = InputView(context)
        ViewCompat.dispatchApplyWindowInsets(
            view,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(24, 0, 24, 0))
                .build(),
        )
        view.showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
        layoutWithRealWindowCap(view)

        assertFullWidthFallback(view)
        assertEquals(24, view.bodyLeftPaddingPxForTest())
        assertEquals(24, view.bodyRightPaddingPxForTest())
        assertEquals(272, view.keyboardVisualWidthPx())
        assertTrue(
            "a physically full fallback adapts the nominal 6dp gaps to retain 20dp ALPHA faces",
            view.keyboardMinimumKeyWidthPxForTest() >= MIN_KEY_WIDTH_DP,
        )
        assertTrue(view.tapKeyboardActionForTest(KeyAction.ENTER))
    }

    private fun layoutWithRealWindowCap(view: InputView) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WINDOW_WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(WINDOW_HEIGHT_PX, View.MeasureSpec.AT_MOST),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertEquals(WINDOW_WIDTH_PX, view.measuredWidth)
        assertEquals("natural content is taller, so the real AT_MOST cap must be consumed exactly", WINDOW_HEIGHT_PX, view.measuredHeight)
    }

    private fun assertFullWidthFallback(view: InputView) {
        assertFalse("near-square multi-window cannot safely float", view.isCompactLandscapeDock())
        assertEquals("no fake transparent host gutter", 0, view.dockSurfaceLeftPx())
        assertEquals(WINDOW_WIDTH_PX, view.dockSurfaceWidthPx())
        assertEquals(WINDOW_WIDTH_PX, view.dockSurfaceRightPx())
        assertEquals("preedit uses the same full fallback surface", 0, view.preeditVisualLeftPx())
        assertEquals(WINDOW_WIDTH_PX, view.preeditVisualRightPx())
        assertEquals(0, view.preeditVisualTopPx())
        assertTrue(view.preeditVisualBottomPx() <= view.measuredHeight)
        assertTrue(view.dockSurfaceTopPx() >= view.preeditVisualBottomPx())
        assertEquals("the opaque surface reaches, but never crosses, the capped root bottom", view.measuredHeight, view.dockSurfaceBottomPx())
        assertTrue(view.toolbarVisualBottomPx() <= view.measuredHeight)
        assertTrue(view.keyboardVisualBottomPx() <= view.measuredHeight)
        assertTrue(view.keyboardVisualTopPx() >= view.dockSurfaceTopPx())
    }

    private fun assertWithinWindowAndSurface(name: String, bounds: RectF, view: InputView) {
        assertTrue("$name has positive width", bounds.width() > 0f)
        assertTrue("$name has positive height", bounds.height() > 0f)
        assertTrue("$name left is inside the full fallback surface", bounds.left >= view.dockSurfaceLeftPx())
        assertTrue("$name top is inside the capped root", bounds.top >= view.dockSurfaceTopPx())
        assertTrue("$name right is inside the real window", bounds.right <= view.dockSurfaceRightPx())
        assertTrue("$name bottom is inside the real window", bounds.bottom <= view.measuredHeight)
    }

    private companion object {
        private const val WINDOW_WIDTH_PX = 320
        private const val WINDOW_HEIGHT_PX = 200
        private const val MIN_KEY_WIDTH_DP = 20f
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w359dp-h200dp-land-mdpi")
class Neighboring359LandscapeFallbackTest {

    @Test fun side_insets_leave_only_47dp_so_the_real_view_falls_back_full_width() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(359, context.resources.configuration.screenWidthDp)
        assertEquals(200, context.resources.configuration.screenHeightDp)
        val view = boundaryView(context, width = 359)

        assertFalse(view.isCompactLandscapeDock())
        assertEquals(0, view.dockSurfaceLeftPx())
        assertEquals(359, view.dockSurfaceRightPx())
        assertEquals(12, view.bodyLeftPaddingPxForTest())
        assertEquals(16, view.bodyRightPaddingPxForTest())
        assertTrue(view.keyboardMinimumKeyWidthPxForTest() >= 20f)
        assertTrue(view.tapKeyboardActionForTest(KeyAction.ENTER))

        val policy = LandscapeImeWindowPolicy.resolve(
            compactLandscape = view.isCompactLandscapeDock(),
            normalTop = view.barTopInsetPx(),
            windowBottom = view.height,
            surfaceBounds = view.dockTouchableBoundsInWindow(),
        )
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE, policy.touchableInsets)
        assertNull(policy.touchableRegion)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h200dp-land-mdpi")
class Neighboring360LandscapeFloatingTest {

    @Test fun side_insets_preserve_exactly_48dp_and_a_300dp_clickable_surface() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(360, context.resources.configuration.screenWidthDp)
        assertEquals(200, context.resources.configuration.screenHeightDp)
        val view = boundaryView(context, width = 360)

        assertTrue(view.isCompactLandscapeDock())
        assertEquals(60, view.dockSurfaceLeftPx())
        assertEquals(300, view.dockSurfaceWidthPx())
        assertEquals(360, view.dockSurfaceRightPx())
        assertEquals("the window-left inset lies wholly in the pass-through gutter", 4, view.bodyLeftPaddingPxForTest())
        assertEquals(16, view.bodyRightPaddingPxForTest())
        assertEquals("the governing ALPHA row lands exactly on its 20dp face floor", 20f, view.keyboardMinimumKeyWidthPxForTest(), 0.01f)
        assertTrue(view.tapKeyboardActionForTest(KeyAction.ENTER))

        val surface = view.dockTouchableBoundsInWindow()
        val policy = LandscapeImeWindowPolicy.resolve(
            compactLandscape = view.isCompactLandscapeDock(),
            normalTop = view.barTopInsetPx(),
            windowBottom = view.height,
            surfaceBounds = surface,
        )
        assertEquals(InputMethodService.Insets.TOUCHABLE_INSETS_REGION, policy.touchableInsets)
        assertEquals(surface, policy.touchableRegion)
    }
}

private fun boundaryView(context: Context, width: Int): InputView = InputView(context).also { view ->
    ViewCompat.dispatchApplyWindowInsets(
        view,
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(12, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, 0, 16, 0))
            .build(),
    )
    view.showKeyboard(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.AT_MOST),
    )
    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    assertEquals(200, view.measuredHeight)
    assertEquals(200, view.dockSurfaceBottomPx())
}
