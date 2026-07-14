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

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.EmojiCatalog
import com.aegis.ime.layout.EmojiVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojiVariantsUiTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private fun view() = EmojiView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
    private val hand = EmojiCatalog.categories.first { it.id == "hand" }.emoji

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun bounds(root: ViewGroup, descendant: View): Rect = Rect(0, 0, descendant.width, descendant.height).also {
        root.offsetDescendantRectToMyCoords(descendant, it)
    }

    private fun EmojiView.send(action: Int, x: Float, y: Float, time: Long): Boolean =
        dispatchTouchEvent(MotionEvent.obtain(0, time, action, x, y, 0))

    private fun textViews(root: View): List<TextView> {
        val views = ArrayList<TextView>()
        fun collect(view: View) {
            if (view is TextView) views.add(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(root)
        return views
    }

    @Test fun long_press_opens_the_selector_only_for_a_variant_capable_cell() {
        val v = view()
        v.openCategoryForTest(2)
        val plain = hand.indexOf("👀")
        val variant = hand.indexOf("👋")
        assertTrue("fixtures present", plain >= 0 && variant >= 0)
        assertFalse("a plain cell's long-press opens nothing (falls through to a normal tap)", v.longPressCellForTest(plain))
        assertFalse(v.variantVisibleForTest())
        assertTrue("a variant cell's long-press opens the selector", v.longPressCellForTest(variant))
        assertTrue(v.variantVisibleForTest())
    }

    @Test fun a_plain_tap_still_commits_the_default_form() {
        val v = view()
        var committed = ""
        v.onEmoji = { committed = it }
        v.openCategoryForTest(2)
        v.tapCellForTest(hand.indexOf("🧑‍⚕️"))
        assertEquals("🧑‍⚕️", committed)
    }

    @Test fun a_programmatic_long_press_does_not_latch_pointer_ownership() {
        var committed = ""
        val v = view().apply {
            onEmoji = { committed = it }
            openCategoryForTest(2)
        }
        layout(v)
        assertTrue(v.longPressCellForTest(hand.indexOf("👋")))
        layout(v)
        val selected = v.variantSkinFormsForTest()[1]
        val cell = textViews(v.variantBackdropForTest()).single { it.text?.toString() == selected }
        val cellBounds = bounds(v, cell)
        val x = cellBounds.exactCenterX()
        val y = cellBounds.exactCenterY()
        assertTrue(v.send(MotionEvent.ACTION_DOWN, x, y, 0))
        assertTrue(cell.isPressed)
        val move = MotionEvent.obtain(0, 10, MotionEvent.ACTION_MOVE, x, y, 0)
        assertFalse(v.onInterceptTouchEvent(move))
        move.recycle()
        assertTrue(v.send(MotionEvent.ACTION_CANCEL, x, y, 20))
        assertTrue(v.tapVariantSkinForTest(1))
        assertEquals(selected, committed)
    }

    @Test fun skin_only_emoji_offers_default_plus_five_tones_and_commits_the_toned_form() {
        val v = view()
        var committed = ""
        v.onEmoji = { committed = it }
        v.openVariantsForTest("👋")
        assertTrue(v.variantVisibleForTest())
        assertEquals("no gender row for a genderless emoji", emptyList<String>(), v.variantGenderFormsForTest())
        val tones = v.variantSkinFormsForTest()
        assertEquals("default + 5 skin tones", 6, tones.size)
        assertEquals("default (untoned) shown first", "👋", tones[0])
        assertEquals(EmojiVariants.applyTone("👋", EmojiVariants.SKIN_TONES[2]), tones[3])
        v.tapVariantSkinForTest(3)
        assertEquals("tapping a swatch commits that toned form", tones[3], committed)
        assertFalse("the selector dismisses after a commit", v.variantVisibleForTest())
    }

    @Test fun gender_and_skin_axes_compose() {
        val v = view()
        var committed = ""
        v.onEmoji = { committed = it }
        v.openVariantsForTest("🧑‍⚕️")
        assertEquals(listOf("🧑‍⚕️", "👨‍⚕️", "👩‍⚕️"), v.variantGenderFormsForTest())
        assertEquals("skin row starts on the neutral form", "🧑‍⚕️", v.variantSkinFormsForTest()[0])
        v.tapVariantGenderForTest(2)
        val womanTones = v.variantSkinFormsForTest()
        assertEquals(6, womanTones.size)
        assertEquals("👩‍⚕️", womanTones[0])
        v.tapVariantSkinForTest(4)
        assertEquals(EmojiVariants.applyTone("👩‍⚕️", EmojiVariants.SKIN_TONES[3]), committed)
        assertFalse(v.variantVisibleForTest())
    }

    @Test fun gender_only_emoji_commits_the_gender_directly() {
        val v = view()
        var committed = ""
        v.onEmoji = { committed = it }
        v.openVariantsForTest("🧞")
        assertEquals(listOf("🧞", "🧞‍♂️", "🧞‍♀️"), v.variantGenderFormsForTest())
        assertEquals("no skin row for a tone-less role", emptyList<String>(), v.variantSkinFormsForTest())
        v.tapVariantGenderForTest(1)
        assertEquals("🧞‍♂️", committed)
        assertFalse(v.variantVisibleForTest())
    }

    @Test fun an_open_selector_owns_the_active_pointer_without_scrolling_or_dimming() {
        for (terminal in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            var committed = ""
            val v = view().apply {
                onEmoji = { committed = it }
                openCategoryForTest(2)
            }
            layout(v)
            val cell = requireNotNull(v.gridCellForTest(hand.indexOf("👋")))
            val cellBounds = bounds(v, cell)
            val x = cellBounds.exactCenterX()
            val y = cellBounds.exactCenterY()
            assertTrue(v.send(MotionEvent.ACTION_DOWN, x, y, 0))
            assertTrue(cell.performLongClick())
            assertTrue(v.variantVisibleForTest())
            val backdrop = v.variantBackdropForTest().background
            assertTrue(backdrop == null || backdrop is ColorDrawable && Color.alpha(backdrop.color) == 0)
            val scrollY = v.gridScrollYForTest()
            assertTrue(v.send(MotionEvent.ACTION_MOVE, x, y - 180f, 20))
            assertEquals(scrollY, v.gridScrollYForTest())
            assertTrue(v.send(terminal, x, y - 180f, 40))
            assertEquals(scrollY, v.gridScrollYForTest())
            assertTrue(v.variantVisibleForTest())
            val selected = v.variantSkinFormsForTest()[1]
            assertTrue(v.tapVariantSkinForTest(1))
            assertEquals(selected, committed)
        }
    }
}
