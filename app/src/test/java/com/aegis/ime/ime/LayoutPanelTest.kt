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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.LayoutId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh")
class LayoutPanelTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val light = ImePalette.STATIC_LIGHT

    private class FakeHost : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private class Fixture(val controller: KeyboardController, val panel: LayoutPanelView, val input: InputView)

    private fun fixture(startEn: Boolean = false): Fixture {
        val controller = KeyboardController(FakeHost(), engine)
        val input = InputView(ctx)
        controller.attachView(input)
        controller.reset()
        if (startEn) controller.onKey(Key("", action = KeyAction.TOGGLE_LANG))
        val panel = LayoutPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        panel.onPick = { choice ->
            controller.applyLayoutChoice(choice)
            input.showPanel(null)
        }
        panel.onBack = { input.showPanel(null) }
        return Fixture(controller, panel, input)
    }

    private fun Fixture.open() {
        panel.setActiveChoice(controller.currentLayoutChoice())
        input.showPanel(panel)
    }

    private fun idleBar(widthDp: Int): CandidateView {
        val view = CandidateView(ctx)
        view.setContent(emptyList(), "")
        view.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        return view
    }

    @Test fun the_layout_and_edit_functions_have_the_final_toolbar_order() {
        assertEquals(
            listOf("BRAND", "EMOJI", "LAYOUT", "EDIT", "CLIPBOARD", "PHRASE"),
            BarFunction.entries.map { it.name },
        )
        val view = idleBar(360)
        assertEquals(
            listOf("BRAND", "EMOJI", "LAYOUT", "EDIT", "CLIPBOARD", "PHRASE"),
            view.toolbarFunctionsForTest().map { it.name },
        )
        assertEquals(view.toolbarFunctionsForTest().size + 1, view.toolbarControlBoundsForTest().size)
    }

    @Test fun idle_toolbar_layout_slot_renders_the_keyboard_icon() {
        val view = idleBar(320)
        val slot = view.toolbarControlBoundsForTest()[2]
        val s = 9f * density * view.toolbarIconScaleForTest(BarFunction.LAYOUT)
        val glyph = RectF(
            slot.centerX() - s * 0.70f,
            slot.centerY() - s * 0.700f,
            slot.centerX() + s * 0.70f,
            slot.centerY() + s * 0.700f,
        )
        assertTrue(slot.contains(glyph))
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val ink = RectF(glyph).apply { inset(-(0.9f * density + 1f), -(0.9f * density + 1f)) }
        var found = false
        for (y in slot.top.toInt() until slot.bottom.toInt()) {
            for (x in slot.left.toInt() until slot.right.toInt()) {
                if (bitmap.getPixel(x, y) != light.icon) continue
                found = true
                assertTrue(ink.contains(x.toFloat(), y.toFloat()))
            }
        }
        assertTrue(found)
    }

    @Test fun card_labels_use_the_exact_layout_names() {
        val panel = fixture().panel
        assertEquals("全拼9键", panel.cardViewForTest(LayoutChoice.CN_NINE).text.toString())
        assertEquals("全拼26键", panel.cardViewForTest(LayoutChoice.CN_ALPHA).text.toString())
        assertEquals("英文26键", panel.cardViewForTest(LayoutChoice.EN_ALPHA).text.toString())
    }

    @Test fun three_cards_carry_the_badge_digits_and_glyphs() {
        val panel = fixture().panel
        assertEquals("9", panel.badgeDigitsForTest(LayoutChoice.CN_NINE))
        assertEquals("26", panel.badgeDigitsForTest(LayoutChoice.CN_ALPHA))
        assertEquals("26", panel.badgeDigitsForTest(LayoutChoice.EN_ALPHA))
        assertEquals("拼", panel.iconCharForTest(LayoutChoice.CN_NINE))
        assertEquals("拼", panel.iconCharForTest(LayoutChoice.CN_ALPHA))
        assertEquals("EN", panel.iconCharForTest(LayoutChoice.EN_ALPHA))
    }

    @Test fun each_card_pick_from_a_cn_start_sets_lang_layout_and_closes_the_panel() {
        val expectations = listOf(
            Triple(LayoutChoice.CN_NINE, LayoutChoice.CN_NINE, LayoutId.NINE),
            Triple(LayoutChoice.CN_ALPHA, LayoutChoice.CN_ALPHA, LayoutId.ALPHA),
            Triple(LayoutChoice.EN_ALPHA, LayoutChoice.EN_ALPHA, LayoutId.ALPHA),
        )
        for ((pick, expectedChoice, expectedLayout) in expectations) {
            val f = fixture()
            assertEquals(LayoutChoice.CN_NINE, f.controller.currentLayoutChoice())
            f.open()
            assertTrue(f.input.isPanelShowing(f.panel))
            f.panel.cardViewForTest(pick).performClick()
            assertEquals(expectedChoice, f.controller.currentLayoutChoice())
            assertEquals(expectedLayout, f.controller.activeLayoutId())
            assertFalse(f.input.isPanelShowing(f.panel))
            assertFalse(f.input.panelShown)
        }
    }

    @Test fun each_card_pick_from_an_en_start_sets_lang_layout_and_closes_the_panel() {
        val expectations = listOf(
            Triple(LayoutChoice.CN_NINE, LayoutChoice.CN_NINE, LayoutId.NINE),
            Triple(LayoutChoice.CN_ALPHA, LayoutChoice.CN_ALPHA, LayoutId.ALPHA),
            Triple(LayoutChoice.EN_ALPHA, LayoutChoice.EN_ALPHA, LayoutId.ALPHA),
        )
        for ((pick, expectedChoice, expectedLayout) in expectations) {
            val f = fixture(startEn = true)
            assertEquals(LayoutChoice.EN_ALPHA, f.controller.currentLayoutChoice())
            f.open()
            assertTrue(f.input.isPanelShowing(f.panel))
            f.panel.cardViewForTest(pick).performClick()
            assertEquals(expectedChoice, f.controller.currentLayoutChoice())
            assertEquals(expectedLayout, f.controller.activeLayoutId())
            assertFalse(f.input.isPanelShowing(f.panel))
        }
    }

    @Test fun highlight_follows_the_controller_state_on_each_show() {
        val f = fixture()
        f.open()
        assertHighlighted(f.panel, LayoutChoice.CN_NINE)
        f.panel.cardViewForTest(LayoutChoice.EN_ALPHA).performClick()
        f.open()
        assertHighlighted(f.panel, LayoutChoice.EN_ALPHA)
        f.input.showPanel(null)
        f.controller.onKey(Key("", action = KeyAction.TOGGLE_LANG))
        f.controller.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        f.open()
        assertHighlighted(f.panel, LayoutChoice.CN_ALPHA)
    }

    private fun assertHighlighted(panel: LayoutPanelView, active: LayoutChoice) {
        for (choice in LayoutChoice.entries) {
            val card = panel.cardViewForTest(choice)
            if (choice == active) {
                assertEquals(light.accentBottom, card.currentTextColor)
                assertSame(Typeface.DEFAULT_BOLD, card.typeface)
                assertTrue(panel.cardActiveForTest(choice))
                assertEquals(light.accentBottom, panel.iconTintForTest(choice))
            } else {
                assertEquals(light.keyLabel, card.currentTextColor)
                assertSame(Typeface.DEFAULT, card.typeface)
                assertFalse(panel.cardActiveForTest(choice))
                assertEquals(light.keyLabel, panel.iconTintForTest(choice))
            }
        }
    }

    @Test fun the_active_card_paints_the_accent_green() {
        val panel = LayoutPanelView(ctx).apply {
            applyPalette(light)
            setActiveChoice(LayoutChoice.CN_ALPHA)
        }
        val w = (360 * density).toInt()
        val h = (240 * density).toInt()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val bitmap = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
        panel.draw(Canvas(bitmap))
        var accent = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == light.accentBottom) accent++
            }
        }
        assertTrue(accent > 0)
    }

    @Test fun the_card_icons_shrink_to_the_new_metrics() {
        val panel = fixture().panel
        val expected = (34 * density).toInt() + 2 * (6 * density).toInt()
        for (choice in LayoutChoice.entries) {
            val icon = panel.cardIconForTest(choice)
            assertEquals(expected, icon.intrinsicWidth)
            assertEquals(expected, icon.intrinsicHeight)
        }
    }

    @Test fun the_card_row_sits_at_the_clipboard_first_card_offset() {
        val panel = fixture().panel
        val w = (360 * density).toInt()
        val h = (240 * density).toInt()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        assertEquals((40 * density).toInt() + (18 * density).toInt(), panel.cardRowTopForTest())
    }

    @Config(sdk = [34], qualifiers = "zh-xxhdpi")
    @Test fun the_card_icon_ink_stays_centered_despite_the_badge() {
        val panel = LayoutPanelView(ctx).apply { applyPalette(light) }
        val icon = panel.cardIconForTest(LayoutChoice.CN_ALPHA)
        val w = icon.intrinsicWidth
        val h = icon.intrinsicHeight
        icon.setBounds(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        icon.draw(Canvas(bitmap))
        var minX = w
        var maxX = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 128) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
        }
        assertTrue(maxX >= 0)
        assertEquals(w / 2f, (minX + maxX) / 2f, 2f * density)
    }

    @Test fun back_and_predictive_back_close_the_panel_without_changing_the_layout() {
        val f = fixture()
        f.open()
        f.panel.titleButtonForTest().performClick()
        assertFalse(f.input.panelShown)
        assertEquals(LayoutChoice.CN_NINE, f.controller.currentLayoutChoice())
        assertEquals(LayoutId.NINE, f.controller.activeLayoutId())
        f.open()
        assertEquals("PANEL", f.input.backTargetKindForTest())
        assertTrue(f.input.closeTopOverlay())
        assertFalse(f.input.panelShown)
        assertEquals(LayoutChoice.CN_NINE, f.controller.currentLayoutChoice())
        assertEquals(LayoutId.NINE, f.controller.activeLayoutId())
    }
}
