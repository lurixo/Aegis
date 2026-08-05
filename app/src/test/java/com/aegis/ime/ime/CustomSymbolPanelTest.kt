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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CustomSymbolPanelTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun dp(v: Int) = (v * density).toInt()

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    private fun laidOut(panel: CustomSymbolPanel, width: Int = dp(411), height: Int = dp(700)): CustomSymbolPanel {
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        return panel
    }

    private fun panel(
        added: List<String> = emptyList(),
        palette: List<String> = listOf("、", "，", "。"),
        colors: ImePalette = ImePalette.STATIC_LIGHT,
    ): CustomSymbolPanel {
        val backing = added.toMutableList()
        return CustomSymbolPanel(ctx).apply {
            current = { backing.toList() }
            onAdd = { if (it !in backing) backing.add(it) }
            onRemove = { backing.remove(it) }
            addPalette = palette
            applyPalette(colors)
        }
    }

    private fun tapMask(view: View): GradientDrawable =
        (view.foreground as RippleDrawable).findDrawableByLayerId(android.R.id.mask) as GradientDrawable

    @Test fun tapping_palette_adds_then_tapping_added_removes() {
        val backing = mutableListOf<String>()
        val p = CustomSymbolPanel(ctx).apply {
            current = { backing.toList() }
            onAdd = { if (it !in backing) backing.add(it) }
            onRemove = { backing.remove(it) }
        }
        p.refresh()
        val paletteChip = requireNotNull(p.paletteChipForTest("、"))
        assertTrue("palette mark 、 is clickable", paletteChip.performClick())
        assertEquals(listOf("、"), backing)
        p.refresh()
        val addedChip = requireNotNull(p.addedChipForTest("、"))
        assertEquals(
            "the added mark keeps a remove affordance",
            ctx.getString(R.string.csp_remove_symbol, "、"),
            addedChip.contentDescription,
        )
        assertTrue(addedChip.performClick())
        assertTrue("removed", backing.isEmpty())
    }

    @Test fun back_control_is_the_shared_icon_button_with_a_centred_title() {
        val p = laidOut(panel(added = listOf("、")))
        var back = 0
        p.onBack = { back++ }

        val button = p.backButtonForTest()
        assertTrue("the panel back control is the shared one", button is PanelBackButton)
        assertEquals("back hit width", dp(48), button.width)
        assertEquals("back hit height", dp(48), button.height)
        assertEquals("the shared control is announced as back", ctx.getString(R.string.clip_back), button.contentDescription)
        assertTrue("back keeps rounded tap feedback", tapMask(button).cornerRadius > 0f)
        assertTrue(button.performClick())
        assertEquals(1, back)

        val title = p.titleForTest()
        assertEquals("title uses the 16sp panel title scale", sp(16f).roundToInt(), title.textSize.roundToInt())
        assertEquals("title sits 8dp after the back control", dp(8), title.left - button.right)
        assertTrue(
            "title is vertically centred on the back control",
            abs((button.top + button.height / 2) - (title.top + title.height / 2)) <= 1,
        )
    }

    @Test fun section_titles_and_placeholder_use_the_secondary_medium_scale() {
        val colors = ImePalette.STATIC_LIGHT
        val p = laidOut(panel(colors = colors))
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        for (label in listOf(p.addedSectionLabelForTest(), p.paletteSectionLabelForTest())) {
            assertEquals("section title is 13sp", sp(13f).roundToInt(), label.textSize.roundToInt())
            assertEquals("section title weight", medium, label.typeface)
            assertEquals("section title colour", colors.keyLabelSecondary, label.currentTextColor)
        }
        assertEquals(ctx.getString(R.string.csp_section_added), p.addedSectionLabelForTest().text.toString())
        assertEquals(ctx.getString(R.string.csp_section_all_punctuation), p.paletteSectionLabelForTest().text.toString())

        val hint = p.addedEmptyHintForTest()
        assertEquals("placeholder is 13sp", sp(13f).roundToInt(), hint.textSize.roundToInt())
        assertEquals("placeholder colour", colors.keyLabelSecondary, hint.currentTextColor)
    }

    @Test fun the_added_section_falls_back_to_a_placeholder_row_when_empty() {
        val p = laidOut(panel())
        assertEquals(View.VISIBLE, p.addedEmptyHintForTest().visibility)
        assertEquals(ctx.getString(R.string.csp_added_empty), p.addedEmptyHintForTest().text.toString())
        assertEquals("the empty added flow takes no room", View.GONE, p.addedRowsForTest().visibility)
        assertTrue("placeholder is laid out", p.addedEmptyHintForTest().height > 0)

        requireNotNull(p.paletteChipForTest("、")).performClick()
        p.refresh()
        laidOut(p)
        assertEquals(View.GONE, p.addedEmptyHintForTest().visibility)
        assertEquals(View.VISIBLE, p.addedRowsForTest().visibility)
        assertNotNull(p.addedChipForTest("、"))
    }

    @Test fun added_chips_pair_the_symbol_with_a_secondary_remove_mark() {
        val p = laidOut(panel(added = listOf("、")))
        val chip = requireNotNull(p.addedChipForTest("、")) as ViewGroup
        val mark = requireNotNull(p.addedRemoveMarkForTest("、"))
        val symbol = chip.getChildAt(0) as TextView

        assertEquals("remove mark box width", dp(16), mark.width)
        assertEquals("remove mark box height", dp(16), mark.height)
        assertEquals("remove mark sits 8dp after the symbol", dp(8), mark.left - symbol.right)
        assertTrue(
            "remove mark is vertically centred on the symbol",
            abs((symbol.top + symbol.height / 2) - (mark.top + mark.height / 2)) <= 1,
        )
        assertEquals(
            "added capsules match the palette capsules",
            requireNotNull(p.paletteChipForTest("，")).height,
            chip.height,
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun the_remove_mark_is_drawn_in_the_secondary_colour_in_light_and_dark() {
        for (colors in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            assertFalse(
                "the secondary colour is not the error colour",
                colors.deletable == colors.keyLabelSecondary,
            )
            val p = laidOut(panel(added = listOf("、"), colors = colors))
            val mark = requireNotNull(p.addedRemoveMarkForTest("、"))
            val bitmap = Bitmap.createBitmap(mark.width, mark.height, Bitmap.Config.ARGB_8888)
            mark.draw(Canvas(bitmap))
            val centre = bitmap.getPixel(mark.width / 2, mark.height / 2)
            assertEquals("the cross draws ink at its centre", 0xFF, centre.ushr(24))
            assertEquals("the cross uses the secondary colour", colors.keyLabelSecondary, centre)
        }
    }

    @Test fun capsules_keep_forty_eight_dp_targets() {
        val p = laidOut(panel(added = listOf("、"), palette = listOf("，", "。")))
        val palette = requireNotNull(p.paletteChipForTest("，"))
        assertEquals("palette capsule height", dp(48), palette.height)
        assertTrue("palette capsule width", palette.width >= dp(48))
        val added = requireNotNull(p.addedChipForTest("、"))
        assertEquals("added capsule height", dp(48), added.height)
        assertTrue("added capsule width", added.width >= dp(48))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun long_symbols_widen_their_capsule_instead_of_clipping_it() {
        val long = "arcsin"
        val longPalette = "arctan"
        val p = laidOut(panel(added = listOf(long), palette = listOf(longPalette, "、")))
        val chip = requireNotNull(p.addedChipForTest(long)) as ViewGroup
        val symbol = chip.getChildAt(0) as TextView
        val mark = requireNotNull(p.addedRemoveMarkForTest(long))

        assertTrue("a long symbol widens its capsule past the minimum", chip.width > dp(72))
        assertEquals("the long symbol stays on one line", 1, symbol.lineCount)
        assertTrue("the symbol stays inside the capsule", symbol.left >= 0 && symbol.right <= chip.width)
        assertTrue("the remove mark stays inside the capsule", mark.right <= chip.width)
        assertEquals("the remove mark keeps its box", dp(16), mark.width)
        assertEquals("the remove mark keeps its 8dp gap", dp(8), mark.left - symbol.right)
        assertEquals("the capsule keeps the shared height", dp(48), chip.height)

        val palette = requireNotNull(p.paletteChipForTest(longPalette)) as TextView
        assertTrue("a long palette symbol widens too", palette.width > dp(56))
        assertEquals("the long palette symbol stays on one line", 1, palette.lineCount)

        val short = laidOut(panel(added = listOf("、"), palette = listOf("，")))
        assertEquals("short symbols keep the added minimum", dp(72), requireNotNull(short.addedChipForTest("、")).width)
        assertEquals("short symbols keep the palette minimum", dp(56), requireNotNull(short.paletteChipForTest("，")).width)

        val overlong = "arcsin".repeat(20)
        val overlongPalette = "arctan".repeat(20)
        val wide = laidOut(panel(added = listOf(overlong), palette = listOf(overlongPalette)))
        val rowWidth = dp(411) - dp(8) * 2
        val wideChip = requireNotNull(wide.addedChipForTest(overlong)) as ViewGroup
        assertTrue("an over-long symbol is clamped to the row width", wideChip.width <= rowWidth)
        assertEquals("a clamped symbol still stays on one line", 1, (wideChip.getChildAt(0) as TextView).lineCount)
        val widePalette = requireNotNull(wide.paletteChipForTest(overlongPalette)) as TextView
        assertTrue("an over-long palette symbol is clamped too", widePalette.width <= rowWidth)
        assertEquals("a clamped palette symbol stays on one line", 1, widePalette.lineCount)
    }

    @Test fun sections_use_the_eight_and_sixteen_dp_steps() {
        val p = laidOut(panel(added = listOf("、"), palette = listOf("，", "。")))
        val addedLabel = p.addedSectionLabelForTest()
        val addedRows = p.addedRowsForTest()
        val paletteLabel = p.paletteSectionLabelForTest()
        val paletteRows = p.paletteRowsForTest()

        assertEquals("first section starts 8dp under the header", dp(8), addedLabel.top)
        assertEquals("added title to content", dp(8), addedRows.top - addedLabel.bottom)
        assertEquals("section to section", dp(16), paletteLabel.top - addedRows.bottom)
        assertEquals("palette title to content", dp(8), paletteRows.top - paletteLabel.bottom)
    }

    @Test fun every_colour_comes_from_the_active_palette_in_light_and_dark() {
        for (colors in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val p = laidOut(panel(added = listOf("、"), palette = listOf("，"), colors = colors))
            assertEquals(colors.keyLabel, p.titleForTest().currentTextColor)
            assertEquals(colors.keyLabel, (p.backButtonForTest() as PanelBackButton).tint)
            assertEquals(colors.keyLabelSecondary, p.addedSectionLabelForTest().currentTextColor)
            assertEquals(colors.keyLabelSecondary, p.paletteSectionLabelForTest().currentTextColor)
            assertEquals(colors.keyLabelSecondary, p.addedEmptyHintForTest().currentTextColor)
            val symbol = (requireNotNull(p.addedChipForTest("、")) as ViewGroup).getChildAt(0) as TextView
            assertEquals(
                "added symbols are no longer painted with the error colour",
                colors.keyLabel,
                symbol.currentTextColor,
            )
            assertEquals(colors.keyLabel, (requireNotNull(p.paletteChipForTest("，")) as TextView).currentTextColor)
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun chinese_titles_drop_the_inline_chevron() {
        assertEquals("自定义标点", ctx.getString(R.string.csp_punctuation_title))
        assertEquals("自定义运算符", ctx.getString(R.string.csp_operators_title))
        assertEquals("已添加", ctx.getString(R.string.csp_section_added))
        assertEquals("从下方点击添加", ctx.getString(R.string.csp_added_empty))
        assertEquals("全部标点", ctx.getString(R.string.csp_section_all_punctuation))
        assertEquals("全部运算符", ctx.getString(R.string.csp_section_all_operators))
        assertNull(
            "no panel string keeps a text chevron",
            listOf(
                R.string.csp_punctuation_title,
                R.string.csp_operators_title,
                R.string.csp_section_added,
                R.string.csp_added_empty,
                R.string.csp_section_all_punctuation,
                R.string.csp_section_all_operators,
            ).firstOrNull { ctx.getString(it).contains("‹") },
        )
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun english_titles_drop_the_inline_chevron() {
        assertEquals("Custom punctuation", ctx.getString(R.string.csp_punctuation_title))
        assertEquals("Custom operators", ctx.getString(R.string.csp_operators_title))
        assertEquals("Added", ctx.getString(R.string.csp_section_added))
        assertEquals("Tap below to add", ctx.getString(R.string.csp_added_empty))
        assertEquals("All punctuation", ctx.getString(R.string.csp_section_all_punctuation))
        assertEquals("All operators", ctx.getString(R.string.csp_section_all_operators))
    }
}
